#!/usr/bin/env python3
"""Serialize production operations, validate immutable releases and reconcile SSM."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import state
import release

OPERATION_COMMENT_PREFIX = "occ-operation:"
ACTIVE_KEY = "state/active-operation.json"
POLL_ATTEMPTS = 1500
POLL_INTERVAL_SECONDS = 5
TERMINAL = {"Success", "Failed", "Cancelled", "TimedOut", "Undeliverable", "Terminated", "InvalidPlatform", "AccessDenied"}


class RemoteError(RuntimeError):
    pass


def aws(arguments: list[str], region: str, check: bool = True, capture: bool = True):
    result = subprocess.run([shutil.which("aws") or "aws", "--region", region, *arguments],
                            capture_output=capture, text=True, encoding="utf-8", errors="replace")
    if check and result.returncode:
        raise RemoteError(f"AWS {arguments[0]} {arguments[1]} failed: {result.stderr.strip()}")
    return result


def read_control_mode(bucket: str, region: str) -> str:
    control = state.read_json(bucket, "state/control.json", region)["value"]
    mode = (control or {}).get("desiredMode", "running")
    if mode not in ("running", "paused"):
        raise RemoteError("invalid desiredMode in control state")
    return mode


def lookup_invocation(region: str, instance_id: str, operation_id: str,
                      started_at: str | None = None) -> dict | None:
    for attempt in range(6):
        arguments = ["ssm", "list-commands"]
        if started_at:
            arguments += ["--filters", f"key=InvokedAfter,value={started_at}"]
        matches = []
        token = None
        while True:
            result = aws(arguments + (["--next-token", token] if token else []), region)
            page = json.loads(result.stdout)
            for command in page.get("Commands", []):
                if command.get("Comment") != OPERATION_COMMENT_PREFIX + operation_id:
                    continue
                targets = command.get("InstanceIds", []) + [v for t in command.get("Targets", [])
                          if t.get("Key") == "InstanceIds" for v in t.get("Values", [])]
                if instance_id in targets:
                    matches.append(command)
            token = page.get("NextToken")
            if not token:
                break
        if len(matches) > 1:
            raise RemoteError("ambiguous accepted commands; do not dispatch another mutation")
        if matches:
            return matches[0]
        time.sleep(2 * (attempt + 1))
    return None


def wait_terminal(region: str, instance_id: str, command_id: str,
                  attempts: int = POLL_ATTEMPTS) -> str:
    for _ in range(attempts):
        result = aws(["ssm", "get-command-invocation", "--command-id", command_id,
                      "--instance-id", instance_id], region, check=False)
        if result.returncode == 0:
            status = json.loads(result.stdout).get("Status")
            if status in TERMINAL:
                return status
        elif "InvocationDoesNotExist" not in result.stderr:
            raise RemoteError("SSM invocation polling failed; operation ownership retained")
        time.sleep(POLL_INTERVAL_SECONDS)
    raise RemoteError("SSM has no verified terminal result; operation ownership retained")


def _save_operation(bucket: str, region: str, operation: dict) -> None:
    current = state.read_json(bucket, ACTIVE_KEY, region)
    if (current["value"] or {}).get("operationId") != operation["operationId"]:
        raise RemoteError("production operation ownership changed; reconcile before continuing")
    state.compare_and_set_json(bucket, ACTIVE_KEY, operation, current["etag"], region)
    key = f'state/operations/{operation["operationId"]}.json'
    audit = state.read_json(bucket, key, region)
    state.compare_and_set_json(bucket, key, operation, audit["etag"], region)


def finish_operation(bucket: str, region: str, operation: dict, outcome: str) -> None:
    operation = dict(operation, phase="complete", result=outcome)
    _save_operation(bucket, region, operation)
    current = state.read_json(bucket, ACTIVE_KEY, region)
    if current["value"].get("operationId") != operation["operationId"]:
        raise RemoteError("operation ownership changed before completion")
    state.compare_and_set_json(bucket, ACTIVE_KEY,
        {"phase": "idle", "previousOperationId": operation["operationId"], "result": outcome},
        current["etag"], region)


def begin_operation(region: str, instance_id: str, bucket: str, operation_id: str,
                    action: str, reference: dict | None = None, sequence: int = 0) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", operation_id):
        raise RemoteError("unsafe or overlong operation ID")
    if reference:
        state.validate_reference(reference)
    audit = state.read_json(bucket, f"state/operations/{operation_id}.json", region)["value"]
    if audit and audit.get("phase") == "complete":
        if audit.get("result") != "success" or audit.get("action") != action or audit.get("reference") != reference:
            raise RemoteError("completed operation cannot be reused for different/failed work; use a fresh ID")
        if reference:
            deployed = state.read_json(bucket, "state/last-deployed.json", region)["value"] or {}
            if any(deployed.get(k) != v for k, v in reference.items()):
                raise RemoteError("historical operation cannot be replayed after the deployed release changed")
        return audit
    current = state.read_json(bucket, ACTIVE_KEY, region)
    prior = current["value"]
    if prior and prior.get("phase") != "idle":
        if prior.get("instanceId") != instance_id:
            raise RemoteError("an operation owns another instance; explicit reconciliation required")
        if prior.get("operationId") == operation_id:
            if prior.get("action") != action or prior.get("reference") != reference:
                raise RemoteError("operation ID already belongs to different work")
            return prior
        reconcile_operation(region, instance_id, bucket, prior)
        current = state.read_json(bucket, ACTIVE_KEY, region)
    deployed = state.read_json(bucket, "state/last-deployed.json", region)
    operation = {"operationId": operation_id, "instanceId": instance_id, "action": action,
                 "reference": reference, "phase": "claimed", "workflowSequence": sequence,
                 "baselineEtag": deployed["etag"],
                 "startedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
    state.compare_and_set_json(bucket, ACTIVE_KEY, operation, current["etag"], region)
    _save_operation(bucket, region, operation)
    return operation


def remote_command(bucket: str, reference: dict, operation_id: str, region: str,
                   action: str = "apply") -> list[str]:
    state.validate_reference(reference)
    # This trusted driver verifies downloaded bytes BEFORE executing bundled code.
    verifier = '''import hashlib,json,pathlib,subprocess,sys
bucket,ref,root=sys.argv[1],json.loads(sys.argv[2]),pathlib.Path(sys.argv[3])
bundle=root/'bundle'; bundle.mkdir(parents=True,exist_ok=True)
def get(key,path,version=None):
    args=['aws','s3api','get-object','--bucket',bucket,'--key',key]
    if version: args += ['--version-id',version]
    subprocess.run(args+[str(path)],check=True,stdout=subprocess.DEVNULL)
get(ref['releaseKey'],bundle/'manifest.json',ref['version'])
raw=(bundle/'manifest.json').read_bytes()
if 'sha256:'+hashlib.sha256(raw).hexdigest()!=ref['checksum']: raise SystemExit('manifest checksum mismatch')
manifest=json.loads(raw)
if manifest.get('releaseId')!=ref['releaseId'] or manifest.get('revision')!=ref['revision']: raise SystemExit('manifest identity mismatch')
prefix='releases/'+ref['releaseId']+'/bundle/'
get(prefix+'checksums.txt',bundle/'checksums.txt')
if 'sha256:'+hashlib.sha256((bundle/'checksums.txt').read_bytes()).hexdigest()!=manifest.get('bundleInventoryChecksum'): raise SystemExit('untrusted bundle inventory')
seen=set()
for line in (bundle/'checksums.txt').read_text().splitlines():
    digest,name=line.split('  ',1)
    path=bundle/name
    if name in seen or name in ('manifest.json','checksums.txt') or not path.resolve().is_relative_to(bundle.resolve()): raise SystemExit('unsafe inventory')
    seen.add(name); path.parent.mkdir(parents=True,exist_ok=True); get(prefix+name,path)
    if hashlib.sha256(path.read_bytes()).hexdigest()!=digest: raise SystemExit('bundle checksum mismatch')
(root/'reference.json').write_text(json.dumps(ref))
'''
    root = "/opt/occ/releases/" + reference["releaseId"]
    return ["set -eu",
        "export OCC_HOME=/opt/occ OCC_DATA_MOUNT=/var/lib/postgresql"
        " OCC_DEPLOYMENT_ENV=/opt/occ/env.rendered OCC_GATE_DIR=/opt/occ/runtime/nginx-gate"
        f" OCC_RELEASE_BUCKET={shlex.quote(bucket)} AWS_DEFAULT_REGION={shlex.quote(region)}"
        f" OCC_OPERATION_ID={shlex.quote(operation_id)} OCC_RELEASE_REFERENCE_FILE={shlex.quote(root + '/reference.json')}",
        "python3 -c " + shlex.quote(verifier) + " " + shlex.quote(bucket) + " " +
        shlex.quote(json.dumps(reference)) + " " + shlex.quote(root),
        f"sh {shlex.quote(root + '/bundle/scripts/deploy.sh')} {action} {shlex.quote(root)}"]


def dispatch_operation(region: str, instance_id: str, bucket: str, operation: dict,
                       commands: list[str]) -> dict:
    if operation["phase"] == "complete":
        return state.read_json(bucket, f'state/receipts/{operation["operationId"]}.json', region)["value"]
    if operation["phase"] != "claimed":
        return reconcile_operation(region, instance_id, bucket, operation)
    operation = dict(operation, phase="dispatching")
    _save_operation(bucket, region, operation)
    result = aws(["ssm", "send-command", "--instance-ids", instance_id,
                  "--document-name", "AWS-RunShellScript", "--comment",
                  OPERATION_COMMENT_PREFIX + operation["operationId"], "--timeout-seconds", "3600",
                  "--parameters", json.dumps({"commands": commands, "executionTimeout": ["3600"]})], region)
    try:
        command_id = json.loads(result.stdout)["Command"]["CommandId"]
    except (ValueError, KeyError, TypeError):
        command_id = None
    operation = dict(operation, phase="dispatched", commandId=command_id)
    _save_operation(bucket, region, operation)
    return reconcile_operation(region, instance_id, bucket, operation)


def recover_receipt(region: str, instance_id: str, bucket: str, operation: dict) -> dict:
    operation_id = operation["operationId"]
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", operation_id):
        raise RemoteError("unsafe receipt operation ID")
    command_id = operation.get("receiptCommandId")
    if not command_id:
        commands = ["set -eu", f"cat /opt/occ/state/receipts/{operation_id}.json"]
        result = aws(["ssm", "send-command", "--instance-ids", instance_id,
                      "--document-name", "AWS-RunShellScript", "--comment", "occ-receipt:" + operation_id,
                      "--parameters", json.dumps({"commands": commands, "executionTimeout": ["60"]})], region)
        try:
            command_id = json.loads(result.stdout)["Command"]["CommandId"]
        except (ValueError, KeyError, TypeError) as error:
            raise RemoteError("receipt read was accepted ambiguously; retry only the read, never apply") from error
        operation = dict(operation, receiptCommandId=command_id)
        _save_operation(bucket, region, operation)
    if wait_terminal(region, instance_id, command_id) != "Success":
        raise RemoteError("host receipt is unavailable; inspect the host before releasing ownership")
    result = aws(["ssm", "get-command-invocation", "--command-id", command_id,
                  "--instance-id", instance_id], region)
    try:
        receipt = json.loads(json.loads(result.stdout)["StandardOutputContent"])
    except (ValueError, KeyError, TypeError) as error:
        raise RemoteError("host receipt was missing/truncated; ownership retained") from error
    key = f"state/receipts/{operation_id}.json"
    try:
        state.compare_and_set_json(bucket, key, receipt, None, region)
    except state.ConflictError:
        receipt = state.read_json(bucket, key, region)["value"]
    return receipt


def reconcile_operation(region: str, instance_id: str, bucket: str, operation: dict) -> dict:
    if operation.get("phase") == "claimed":
        # SendCommand is preceded by a durable dispatching transition.
        finish_operation(bucket, region, operation, "not-dispatched")
        return {"outcome": "not-dispatched"}
    command_id = operation.get("commandId")
    if not command_id:
        found = lookup_invocation(region, instance_id, operation["operationId"], operation.get("startedAt"))
        if not found:
            raise RemoteError("ambiguous command acceptance; keep operation ownership and recover explicitly")
        command_id = found["CommandId"]
        operation = dict(operation, commandId=command_id)
        _save_operation(bucket, region, operation)
    terminal = wait_terminal(region, instance_id, command_id)
    receipt = state.read_json(bucket, f'state/receipts/{operation["operationId"]}.json', region)["value"]
    if not receipt:
        receipt = recover_receipt(region, instance_id, bucket, operation)
    if receipt.get("operationId") != operation["operationId"] or receipt.get("action") != operation["action"]:
        raise RemoteError("host receipt does not belong to this operation")
    reference = operation.get("reference")
    if reference and (receipt.get("reference") != reference or receipt.get("releaseId") != reference["releaseId"]):
        raise RemoteError("host receipt release does not match the selected immutable reference")
    if receipt.get("outcome") != "success" or (terminal != "Success" and receipt.get("phase") != "verified"):
        if receipt.get("gateClosed") is not True:
            raise RemoteError("failed remote work has no proven closed gate; recovery required")
        finish_operation(bucket, region, operation, "failed")
        raise RemoteError("remote operation failed; host remains gated")
    if reference:
        current = state.read_json(bucket, "state/last-deployed.json", region)
        if (current["value"] or {}).get("operationId") != operation["operationId"]:
            if current["etag"] != operation.get("baselineEtag"):
                raise RemoteError("deployed pointer changed while host applied; retain receipt for reconciliation")
            pointer = dict(reference, operationId=operation["operationId"],
                           sequence=(current["value"] or {}).get("sequence", 0) + 1,
                           setupFingerprint=receipt.get("setupFingerprint"),
                           deployedAt=datetime.now(timezone.utc).isoformat())
            if current["value"]:
                pointer["previousReference"] = {key: current["value"].get(key) for key in release.POINTER_FIELDS}
            if operation["action"] == "rollback":
                pointer["rollbackOf"] = (current["value"] or {}).get("releaseId")
            state.compare_and_set_json(bucket, "state/last-deployed.json", pointer, current["etag"], region)
    if operation["action"] == "pause":
        actual = aws(["ec2", "describe-instances", "--instance-ids", instance_id,
                      "--query", "Reservations[0].Instances[0].State.Name", "--output", "text"], region).stdout.strip()
        if actual != "stopped":
            operation = dict(operation, phase="stopping")
            _save_operation(bucket, region, operation)
            if actual != "stopping":
                aws(["ec2", "stop-instances", "--instance-ids", instance_id], region)
            aws(["ec2", "wait", "instance-stopped", "--instance-ids", instance_id], region)
    finish_operation(bucket, region, operation, "success")
    return receipt


def selected_release(bucket: str, region: str) -> dict:
    published = state.read_json(bucket, "state/latest-published.json", region)["value"]
    if not published:
        raise RemoteError("no eligible published release")
    reference = {key: published.get(key) for key in release.POINTER_FIELDS}
    state.validate_reference(reference)
    manifest = state.verified_manifest(bucket, reference, region)
    release.validate_manifest(manifest)
    repo = Path(__file__).resolve().parents[2]
    if not release.is_ancestor(repo, reference["revision"], "origin/main"):
        raise RemoteError("published revision is not a verified current main ancestor; refresh ancestry for review")
    deployed = state.read_json(bucket, "state/last-deployed.json", region)["value"]
    if deployed and deployed["revision"] != reference["revision"] and not release.is_ancestor(repo, deployed["revision"], reference["revision"]):
        raise RemoteError("selected release would regress/diverge from the deployed revision")
    return reference


def run_deploy(region: str, instance_id: str, bucket: str, release_id: str,
               operation_id: str, sequence: int) -> int:
    if read_control_mode(bucket, region) == "paused":
        print("deployment deferred: desired mode is paused; instance was not started or applied")
        return 0
    reference = selected_release(bucket, region)
    operation = begin_operation(region, instance_id, bucket, operation_id, "apply", reference, sequence)
    if read_control_mode(bucket, region) == "paused":
        if operation["phase"] == "claimed":
            finish_operation(bucket, region, operation, "deferred-paused")
            return 0
        reconcile_operation(region, instance_id, bucket, operation)
        return 0
    if operation["phase"] == "claimed":
        try:
            latest = selected_release(bucket, region)
        except (RemoteError, state.StateError, release.ContractError):
            finish_operation(bucket, region, operation, "rejected-before-dispatch")
            raise
        if latest != reference:
            reference = latest
            operation = dict(operation, reference=reference)
            _save_operation(bucket, region, operation)
    dispatch_operation(region, instance_id, bucket, operation,
                       remote_command(bucket, reference, operation_id, region))
    print(f'release {reference["releaseId"]} deployed and verified')
    return 0


def run_recover(region: str, instance_id: str, bucket: str, operation_id: str, sequence: int) -> int:
    operation = state.read_json(bucket, ACTIVE_KEY, region)["value"]
    if operation and operation.get("phase") == "idle" and operation.get("previousOperationId") == operation_id:
        if operation.get("result") != "success":
            raise RemoteError("the prior operation completed unsuccessfully; its failed result is retained")
        print(f"operation {operation_id} was already reconciled successfully")
        return 0
    if not operation or operation.get("operationId") != operation_id or operation.get("instanceId") != instance_id:
        raise RemoteError("no matching active operation to reconcile")
    reconcile_operation(region, instance_id, bucket, operation)
    print(f"operation {operation_id} reconciled")
    return 0


def run_rollback(region: str, instance_id: str, bucket: str, release_id: str,
                 operation_id: str, sequence: int) -> int:
    current = state.read_json(bucket, "state/last-deployed.json", region)["value"]
    if not current:
        raise RemoteError("no verified deployed baseline exists for rollback")
    reference = {key: current.get(key) for key in release.POINTER_FIELDS}
    if release_id and release_id != reference["releaseId"]:
        reference = current.get("previousReference")
        if not reference or reference.get("releaseId") != release_id:
            raise RemoteError("rollback must target the pinned current recovery baseline or previous release")
    release.validate_manifest(state.verified_manifest(bucket, reference, region))
    operation = begin_operation(region, instance_id, bucket, operation_id, "rollback", reference, sequence)
    dispatch_operation(region, instance_id, bucket, operation,
                       remote_command(bucket, reference, operation_id, region, "rollback"))
    print(f'rollback recovery verified: {reference["releaseId"]}; original failed deploy remains failed')
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("deploy", "recover", "rollback"))
    for name in ("region", "instance-id", "bucket", "operation-id"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--release-id", default="")
    parser.add_argument("--sequence", type=int, default=0)
    args = parser.parse_args(argv)
    try:
        if args.command == "deploy":
            return run_deploy(args.region, args.instance_id, args.bucket, args.release_id, args.operation_id, args.sequence)
        if args.command == "rollback":
            return run_rollback(args.region, args.instance_id, args.bucket, args.release_id, args.operation_id, args.sequence)
        return run_recover(args.region, args.instance_id, args.bucket, args.operation_id, args.sequence)
    except (RemoteError, state.StateError, release.ContractError) as error:
        print(f"deploy-remote: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
