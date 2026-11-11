#!/usr/bin/env python
"""OCC environment lifecycle control: start/resume, pause and status.

Standard library plus the AWS CLI as subprocesses; no SDK dependency. The
desired mode lives in the deployment bucket's state/control.json, outside
EC2, so the desired state survives instance replacement and stop/start.

  pause        Record the paused intent, reconcile active remote work, stop
               the stack gracefully through SSM and stop the instance.
  start|resume Start the instance, wait for readiness and apply the latest
               eligible published release without rebuilding.
  status       Report desired/actual state, published/deployed pointers and
               any outstanding operation. Never prints secret values.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

POLL_INTERVAL_SECONDS = 5
SSM_TIMEOUT_SECONDS = 1800
# The poll ceiling must exceed the command's own timeout so a slow resume is
# never declared failed while the remote deploy still runs.
POLL_ATTEMPTS = (SSM_TIMEOUT_SECONDS // POLL_INTERVAL_SECONDS) + 60


class ControlError(Exception):
    """A lifecycle control contract violation."""


def aws(arguments: list[str], region: str, check: bool = True,
         capture: bool = True) -> subprocess.CompletedProcess:
    aws_executable = shutil.which("aws") or "aws"
    result = subprocess.run(
        [aws_executable, "--region", region, *arguments],
        capture_output=capture, text=True, encoding="utf-8", errors="replace")
    if check and result.returncode != 0:
        raise ControlError(f"aws {' '.join(arguments)} failed: {result.stderr.strip()}")
    return result


def s3_read(bucket: str, key: str, region: str) -> str | None:
    result = aws(["s3", "cp", f"s3://{bucket}/{key}", "-"], region, check=False)
    if result.returncode != 0 or not result.stdout.strip():
        return None
    return result.stdout


def read_control(bucket: str, region: str) -> dict:
    body = s3_read(bucket, "state/control.json", region)
    if body is None:
        return {"desiredMode": "running", "operationId": None, "phase": "idle",
                "result": "never-controlled"}
    try:
        return json.loads(body)
    except json.JSONDecodeError as error:
        raise ControlError(f"control state is not valid JSON: {error}") from error


def read_pointer(bucket: str, key: str, region: str) -> dict | None:
    body = s3_read(bucket, key, region)
    if body is None:
        return None
    try:
        return json.loads(body)
    except json.JSONDecodeError as error:
        raise ControlError(f"{key} is not valid JSON: {error}") from error


def write_control(bucket: str, region: str, control: dict) -> None:
    import tempfile

    payload = json.dumps(control, sort_keys=True)
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False,
                                     encoding="utf-8", newline="\n") as handle:
        handle.write(payload)
        path = handle.name
    try:
        aws(["s3", "cp", path, f"s3://{bucket}/state/control.json"], region)
    finally:
        Path(path).unlink(missing_ok=True)


def persist_intent(bucket: str, region: str, operation_id: str,
                   release_id: str, action: str) -> None:
    import tempfile

    intent = {"operationId": operation_id, "releaseId": release_id,
              "action": action, "phase": "dispatched"}
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False,
                                     encoding="utf-8", newline="\n") as handle:
        json.dump(intent, handle, sort_keys=True)
        path = handle.name
    # The intent is durable before SSM accepts the command, so runner loss
    # always leaves a record that blocks further mutations until reconciled.
    try:
        aws(["s3", "cp", path,
             f"s3://{bucket}/state/operations/{operation_id}.json"], region)
    finally:
        Path(path).unlink(missing_ok=True)


def clear_intent(bucket: str, region: str, operation_id: str) -> None:
    aws(["s3", "rm", f"s3://{bucket}/state/operations/{operation_id}.json"],
        region, check=False)


def instance_state(region: str, instance_id: str) -> str:
    result = aws(["ec2", "describe-instances", "--instance-ids", instance_id,
                  "--query", "Reservations[0].Instances[0].State.Name",
                  "--output", "text"], region)
    body = result.stdout.strip()
    # A real CLI returns the bare state text; the sandbox returns JSON.
    try:
        parsed = json.loads(body)
        return parsed.get("state", body)
    except json.JSONDecodeError:
        return body or "unknown"


def has_outstanding_operations(bucket: str, region: str) -> bool:
    result = aws(["s3", "ls", f"s3://{bucket}/state/operations/"], region, check=False)
    if result.returncode != 0:
        return False
    return bool(result.stdout.strip())


def dispatch_ssm(region: str, instance_id: str, commands: list[str],
                 operation_id: str, label: str) -> None:
    import json as json_module

    parameters = json_module.dumps({"commands": commands})
    # The comment prefix is shared with deploy_remote's recover lookup so a
    # lost resume can always be reconciled by operation id.
    comment = f"occ-operation:{operation_id}"
    result = aws(["ssm", "send-command",
                  "--instance-ids", instance_id,
                  "--document-name", "AWS-RunShellScript",
                  "--comment", comment,
                  "--timeout-seconds", str(SSM_TIMEOUT_SECONDS),
                  "--parameters", parameters], region, check=False)
    if result.returncode != 0:
        raise ControlError(f"SSM dispatch failed for {label}: {result.stderr.strip()}")
    try:
        command_id = json_module.loads(result.stdout)["Command"]["CommandId"]
    except (json_module.JSONDecodeError, KeyError, TypeError) as error:
        raise ControlError(f"SSM dispatch returned no command id for {label}: {error}") from error
    for _ in range(POLL_ATTEMPTS):
        invocation = aws(["ssm", "get-command-invocation",
                          "--command-id", command_id, "--instance-id", instance_id],
                         region, check=False)
        if invocation.returncode == 0:
            status = json_module.loads(invocation.stdout).get("Status", "")
            if status == "Success":
                return
            if status in ("Failed", "Cancelled", "TimedOut"):
                raise ControlError(f"{label} ended in status {status}")
        time.sleep(POLL_INTERVAL_SECONDS)
    raise ControlError(f"{label} did not reach a terminal status")


def reconcile_active_operations(bucket: str, region: str) -> None:
    if has_outstanding_operations(bucket, region):
        raise ControlError(
            "outstanding remote operations exist; reconcile them before a "
            "pause or start - ambiguous remote work blocks the mutation")


def do_pause(region: str, instance_id: str, bucket: str, operation_id: str) -> int:
    reconcile_active_operations(bucket, region)
    control = read_control(bucket, region)
    if control.get("desiredMode") == "paused":
        print("the environment is already paused; nothing to do")
        return 0
    # The paused intent is durable before any stop, so a partial pause is
    # always resumable and a stopped instance never wakes implicitly.
    control["desiredMode"] = "paused"
    control["operationId"] = operation_id
    control["phase"] = "pausing"
    write_control(bucket, region, control)

    state = instance_state(region, instance_id)
    if state in ("stopped", "stopping"):
        control["phase"] = "paused"
        control["result"] = "success"
        write_control(bucket, region, control)
        print(f"instance already {state}; paused intent recorded")
        return 0
    if state != "running":
        control["phase"] = "pause-blocked"
        write_control(bucket, region, control)
        raise ControlError(f"instance state is {state}; the pause intent is recorded "
                           "and the instance was not touched")
    try:
        dispatch_ssm(region, instance_id, [
            "set -eu",
            "export OCC_HOME=/opt/occ OCC_DATA_MOUNT=/var/lib/postgresql"
            " OCC_DEPLOYMENT_ENV=/opt/occ/env.rendered"
            " OCC_GATE_DIR=/opt/occ/runtime/nginx-gate",
            # Source the rendered deployment settings so Compose interpolation
            # sees the shell values; the runtime env file pins the images.
            "set -a",
            ". /opt/occ/env.rendered",
            "set +a",
            # Stop through the deployed release's own Compose files so the
            # project resolves its services; a bare `compose -p` without
            # files is an error and would leave the stack running.
            "if [ -f /opt/occ/state/active-release.json ]; then",
            "  release_id=$(sed -n 's/.*\"releaseId\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p'"
            " /opt/occ/state/active-release.json)",
            '  cd "/opt/occ/releases/$release_id/bundle"',
            "  if [ -f scripts/traffic-gate.sh ]; then",
            '    OCC_GATE_TOKEN=$(cat "$OCC_GATE_DIR/gate-token")',
            "    export OCC_GATE_TOKEN",
            "    sh scripts/traffic-gate.sh close",
            "  fi",
            "  docker compose -p occ-production --env-file /opt/occ/runtime/env"
            " --profile edge"
            " -f compose.yaml -f compose.images.yaml"
            " -f compose.cloudfront.yaml -f compose.host-data.yaml"
            " stop nginx ingestion disruption planning postgres",
            "fi",
        ], operation_id, "graceful-stack-stop")
        aws(["ec2", "stop-instances", "--instance-ids", instance_id], region)
        aws(["ec2", "wait", "instance-stopped", "--instance-ids", instance_id], region)
    except ControlError as error:
        # The paused intent is retained; the actual state is reported for a
        # retried pause. Never force-stop or terminate automatically.
        control["phase"] = "pause-failed"
        write_control(bucket, region, control)
        raise ControlError(f"pause did not complete: {error}; the paused intent is "
                           "retained - inspect the instance state and retry") from error
    control["phase"] = "paused"
    control["result"] = "success"
    write_control(bucket, region, control)
    print("environment paused: services stopped gracefully and the instance is stopped")
    return 0


def do_start(region: str, instance_id: str, bucket: str, operation_id: str) -> int:
    control = read_control(bucket, region)
    current_state = instance_state(region, instance_id)
    if control.get("desiredMode") == "running" and \
            control.get("result") == "success" and current_state == "running":
        print("the environment is already running; nothing to do")
        return 0
    # Reconcile outstanding remote work first: an accepted deploy/pause SSM
    # command can still be mutating production after its runner vanished.
    if has_outstanding_operations(bucket, region):
        raise ControlError(
            "outstanding remote operations exist; reconcile them before a "
            "start or resume - ambiguous remote work blocks the mutation")
    control["desiredMode"] = "running"
    control["operationId"] = operation_id
    control["phase"] = "starting"
    write_control(bucket, region, control)
    try:
        if instance_state(region, instance_id) != "running":
            aws(["ec2", "start-instances", "--instance-ids", instance_id], region)
            aws(["ec2", "wait", "instance-running", "--instance-ids", instance_id], region)
        published = read_pointer(bucket, "state/latest-published.json", region)
        if published is None or not published.get("releaseId"):
            raise ControlError("no published release exists; run the release pipeline "
                               "or a refresh before starting")
        release_id = published["releaseId"]
        persist_intent(bucket, region, operation_id, release_id, "resume-apply")
        dispatch_ssm(region, instance_id, [
            "set -eu",
            "export OCC_HOME=/opt/occ"
            " OCC_DATA_MOUNT=/var/lib/postgresql"
            " OCC_DEPLOYMENT_ENV=/opt/occ/env.rendered"
            " OCC_GATE_DIR=/opt/occ/runtime/nginx-gate"
            f" OCC_RELEASE_BUCKET={bucket}",
            f"test -f /var/lib/postgresql/.occ-data-marker",
            f"mkdir -p /opt/occ/releases/{release_id}",
            f"aws s3 cp --recursive s3://{bucket}/releases/{release_id}/"
            f" /opt/occ/releases/{release_id}/",
            "sh /opt/occ/releases/" + release_id +
            "/bundle/scripts/deploy.sh apply /opt/occ/releases/" + release_id,
        ], operation_id, "resume-apply")
    except ControlError as error:
        # A failed start retains the paused intent: the origin stays gated and
        # only an explicit retry brings the environment back.
        control["desiredMode"] = "paused"
        control["phase"] = "start-failed"
        write_control(bucket, region, control)
        raise ControlError(f"start did not complete: {error}; the paused intent is "
                           "retained and the origin stays gated") from error
    control["phase"] = "running"
    control["result"] = "success"
    write_control(bucket, region, control)
    clear_intent(bucket, region, operation_id)
    print(f"environment running: release {release_id} applied with its normal "
          "readiness and public checks")
    return 0


def do_status(region: str, instance_id: str, bucket: str, operation_id: str) -> int:
    control = read_control(bucket, region)
    state = instance_state(region, instance_id)
    published = read_pointer(bucket, "state/latest-published.json", region)
    deployed = read_pointer(bucket, "state/last-deployed.json", region)
    desired = control.get("desiredMode", "unknown")
    if desired == "running" and state != "running":
        actual = f"{state} (unavailable: desired mode is running)"
    else:
        actual = state
    print(f"desired mode: {desired}")
    print(f"instance state: {actual}")
    print(f"published release: {published.get('releaseId') if published else 'none'} "
          f"(revision {published.get('revision', '-')[:12] if published else '-'})")
    print(f"deployed release: {deployed.get('releaseId') if deployed else 'none'} "
          f"(revision {deployed.get('revision', '-')[:12] if deployed else '-'})")
    print(f"control phase: {control.get('phase', 'unknown')}, "
          f"result: {control.get('result', 'unknown')}")
    if has_outstanding_operations(bucket, region):
        print("outstanding operation: yes (reconcile before new mutations)")
    else:
        print("outstanding operation: none")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "resume", "pause", "status"])
    parser.add_argument("--region", required=True)
    parser.add_argument("--instance-id", required=True)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--operation-id", required=True)
    args = parser.parse_args(argv)
    try:
        if args.action == "pause":
            return do_pause(args.region, args.instance_id, args.bucket, args.operation_id)
        if args.action in ("start", "resume"):
            return do_start(args.region, args.instance_id, args.bucket, args.operation_id)
        return do_status(args.region, args.instance_id, args.bucket, args.operation_id)
    except ControlError as error:
        print(f"environment: error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
