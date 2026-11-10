#!/usr/bin/env python
"""Drive the remote OCC host deployment through SSM Run Command.

Standard library only; every AWS call goes through the AWS CLI as a
subprocess. Exit 0 means a verified terminal remote success (or an explicitly
deferred paused deployment) - anything ambiguous fails.

  deploy   Read the desired mode, persist the operation intent, dispatch the
           deploy command and wait for its terminal status.
  recover  Look up an already-accepted invocation by its operation comment
           after runner loss and reconcile it to a terminal state.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

OPERATION_COMMENT_PREFIX = "occ-operation:"
POLL_ATTEMPTS = 120
POLL_INTERVAL_SECONDS = 5


class RemoteError(Exception):
    """A remote deployment contract violation."""


def aws(arguments: list[str], region: str, check: bool = True,
         capture: bool = True) -> subprocess.CompletedProcess:
    aws_executable = shutil.which("aws") or "aws"
    result = subprocess.run(
        [aws_executable, "--region", region, *arguments],
        capture_output=capture, text=True, encoding="utf-8", errors="replace")
    if check and result.returncode != 0:
        raise RemoteError(f"aws {' '.join(arguments)} failed: {result.stderr.strip()}")
    return result


def read_control_mode(bucket: str, region: str) -> str:
    result = aws(["s3", "cp", f"s3://{bucket}/state/control.json", "-"], region, check=False)
    if result.returncode != 0 or not result.stdout.strip():
        # Missing control state means the default mode: running.
        return "running"
    try:
        return json.loads(result.stdout).get("desiredMode", "running")
    except json.JSONDecodeError as error:
        raise RemoteError(f"control state is not valid JSON: {error}") from error


def remote_command(bucket: str, release_id: str) -> list[str]:
    return [
        "set -eu",
        "export OCC_HOME=/opt/occ"
        " OCC_DATA_MOUNT=/var/lib/postgresql"
        " OCC_DEPLOYMENT_ENV=/opt/occ/env.rendered"
        " OCC_GATE_DIR=/opt/occ/runtime/nginx-gate"
        f" OCC_RELEASE_BUCKET={bucket}",
        f"mkdir -p /opt/occ/releases/{release_id}",
        f"aws s3 cp --recursive s3://{bucket}/releases/{release_id}/"
        f" /opt/occ/releases/{release_id}/",
        "sh /opt/occ/releases/" + release_id +
        "/bundle/scripts/deploy.sh apply /opt/occ/releases/" + release_id,
    ]


def send_command(region: str, instance_id: str, bucket: str, release_id: str,
                 operation_id: str) -> str | None:
    parameters = json.dumps({"commands": remote_command(bucket, release_id)})
    result = aws([
        "ssm", "send-command",
        "--instance-ids", instance_id,
        "--document-name", "AWS-RunShellScript",
        "--comment", f"{OPERATION_COMMENT_PREFIX}{operation_id}",
        "--timeout-seconds", "3600",
        "--parameters", parameters,
    ], region, check=False)
    if result.returncode != 0:
        raise RemoteError(f"send-command failed: {result.stderr.strip()}")
    try:
        command_id = json.loads(result.stdout)["Command"]["CommandId"]
    except (json.JSONDecodeError, KeyError, TypeError):
        return None
    return command_id or None


def lookup_invocation(region: str, instance_id: str, operation_id: str) -> dict | None:
    """Find the accepted invocation by its operation comment.

    Bounded retries absorb SSM's eventual consistency; more than one
    outstanding command for the operation is ambiguous and blocks.
    """
    pending: list[dict] = []
    terminal: list[dict] = []
    for attempt in range(6):
        result = aws([
            "ssm", "list-commands",
            "--filter", f"key=Comment,value={OPERATION_COMMENT_PREFIX}{operation_id}",
        ], region, check=False)
        if result.returncode == 0 and result.stdout.strip():
            try:
                commands = json.loads(result.stdout).get("Commands", [])
            except json.JSONDecodeError:
                commands = []
            pending = [c for c in commands
                       if c.get("Status") in ("Pending", "InProgress", "Delayed")]
            terminal = [c for c in commands
                        if c.get("Status") in ("Success", "Failed", "Cancelled", "TimedOut")]
            if len(pending) == 1 or terminal:
                break
        time.sleep(2 * (attempt + 1))
    if len(pending) > 1:
        raise RemoteError(
            "ambiguous outstanding commands for the operation; "
            "reconcile before any new deploy or pause")
    if pending:
        return {"CommandId": pending[0]["CommandId"], "Status": pending[0]["Status"]}
    if terminal:
        return {"CommandId": terminal[0]["CommandId"], "Status": terminal[0]["Status"]}
    return None


def wait_terminal(region: str, instance_id: str, command_id: str,
                  attempts: int = POLL_ATTEMPTS) -> str:
    """Poll the invocation to a terminal status with bounded retries.

    Initial eventual-consistency errors (InvocationDoesNotExist) retry; any
    non-success terminal status fails.
    """
    last_error = ""
    for attempt in range(attempts):
        result = aws([
            "ssm", "get-command-invocation",
            "--command-id", command_id, "--instance-id", instance_id,
        ], region, check=False)
        if result.returncode == 0:
            status = json.loads(result.stdout).get("Status", "")
            if status == "Success":
                return status
            if status in ("Failed", "Cancelled", "TimedOut"):
                raise RemoteError(f"remote deployment ended in status {status}")
        else:
            last_error = (result.stderr or "").strip()
            if "InvocationDoesNotExist" not in last_error:
                raise RemoteError(f"invocation lookup failed: {last_error}")
        time.sleep(POLL_INTERVAL_SECONDS)
    raise RemoteError(f"remote deployment did not reach a terminal status; "
                      f"last lookup error: {last_error or 'timeout'}")


def advance_pointer(bucket: str, region: str, release_id: str, sequence: int) -> None:
    """CAS the last-deployed pointer; an older or equal sequence is refused."""
    import importlib.util

    release_script = Path(__file__).resolve().parent / "release.py"
    spec = importlib.util.spec_from_file_location("occ_release", release_script)
    release = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(release)

    candidate = {"releaseId": release_id, "sequence": sequence,
                 "releaseKey": f"releases/{release_id}",
                 "version": "-", "checksum": "-"}
    result = aws(["s3", "cp", f"s3://{bucket}/state/last-deployed.json", "-"],
                 region, check=False)
    if result.returncode == 0 and result.stdout.strip():
        try:
            current = json.loads(result.stdout)
        except json.JSONDecodeError as error:
            raise RemoteError(f"remote last-deployed pointer is not valid JSON: {error}") from error
        release.reject_older_overwrite(current, candidate)
    pointer = Path(__file__).resolve().parent / ".last-deployed.json"
    pointer.write_text(json.dumps(candidate), encoding="utf-8")
    aws(["s3", "cp", pointer.as_posix(), f"s3://{bucket}/state/last-deployed.json"], region)
    pointer.unlink(missing_ok=True)
    print("last-deployed pointer advanced:", json.dumps(candidate, sort_keys=True))


def clear_intent(bucket: str, region: str, operation_id: str) -> None:
    aws(["s3", "rm", f"s3://{bucket}/state/operations/{operation_id}.json"], region, check=False)


def persist_intent(bucket: str, region: str, operation_id: str, release_id: str) -> None:
    intent = Path(__file__).resolve().parent / ".operation-intent.json"
    intent.write_text(json.dumps({
        "operationId": operation_id, "releaseId": release_id, "phase": "dispatched",
    }, sort_keys=True), encoding="utf-8")
    # The intent is durable before SendCommand so a runner loss after AWS
    # accepts the command can always be reconciled by operation id.
    aws(["s3", "cp", intent.as_posix(),
         f"s3://{bucket}/state/operations/{operation_id}.json"], region)
    intent.unlink(missing_ok=True)


def run_deploy(region: str, instance_id: str, bucket: str, release_id: str,
               operation_id: str, sequence: int) -> int:
    mode = read_control_mode(bucket, region)
    if mode == "paused":
        print(f"deployment deferred: the environment is paused; release {release_id} "
              f"is published and eligible for the next resume")
        return 0
    if mode != "running":
        raise RemoteError(f"unknown desiredMode in control state: {mode}")
    persist_intent(bucket, region, operation_id, release_id)
    command_id = send_command(region, instance_id, bucket, release_id, operation_id)
    if command_id is None:
        # AWS may have accepted the command before the runner could persist
        # the id: recover the accepted invocation by its operation comment.
        recovered = lookup_invocation(region, instance_id, operation_id)
        if recovered is None:
            clear_intent(bucket, region, operation_id)
            raise RemoteError("send-command returned no command id and no accepted "
                              "invocation was found; refusing to resubmit blindly")
        command_id = recovered["CommandId"]
    try:
        wait_terminal(region, instance_id, command_id)
    except RemoteError:
        raise
    advance_pointer(bucket, region, release_id, sequence)
    clear_intent(bucket, region, operation_id)
    print(f"release {release_id} deployed and verified on the host")
    return 0


def run_recover(region: str, instance_id: str, bucket: str, operation_id: str,
                sequence: int) -> int:
    recovered = lookup_invocation(region, instance_id, operation_id)
    if recovered is None:
        raise RemoteError("no accepted invocation found for the operation; "
                          "nothing to reconcile")
    command_id = recovered["CommandId"]
    if recovered["Status"] in ("Pending", "InProgress", "Delayed"):
        wait_terminal(region, instance_id, command_id)
    else:
        status = recovered["Status"]
        if status != "Success":
            raise RemoteError(f"recovered invocation ended in status {status}")
    advance_pointer(bucket, region, json.loads(
        (aws(["s3", "cp", f"s3://{bucket}/state/operations/{operation_id}.json", "-"],
             region)).stdout or "{}").get("releaseId", ""), sequence)
    clear_intent(bucket, region, operation_id)
    print(f"operation {operation_id} reconciled to a verified terminal success")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    deploy_parser = subparsers.add_parser("deploy")
    deploy_parser.add_argument("--region", required=True)
    deploy_parser.add_argument("--instance-id", required=True)
    deploy_parser.add_argument("--bucket", required=True)
    deploy_parser.add_argument("--release-id", required=True)
    deploy_parser.add_argument("--operation-id", required=True)
    deploy_parser.add_argument("--sequence", type=int, required=True)

    recover_parser = subparsers.add_parser("recover")
    recover_parser.add_argument("--region", required=True)
    recover_parser.add_argument("--instance-id", required=True)
    recover_parser.add_argument("--bucket", required=True)
    recover_parser.add_argument("--operation-id", required=True)
    recover_parser.add_argument("--sequence", type=int, required=True)

    args = parser.parse_args(argv)
    try:
        if args.command == "deploy":
            return run_deploy(args.region, args.instance_id, args.bucket,
                              args.release_id, args.operation_id, args.sequence)
        return run_recover(args.region, args.instance_id, args.bucket,
                           args.operation_id, args.sequence)
    except RemoteError as error:
        print(f"deploy-remote: error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
