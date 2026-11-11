#!/usr/bin/env python3
"""Explicit pause/resume/status over the shared durable production protocol."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import shlex
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import state
import deploy_remote as remote

aws = remote.aws
ControlError = remote.RemoteError


def read_control(bucket, region):
    return state.read_json(bucket, "state/control.json", region)


def write_control(bucket, region, value, etag):
    return state.compare_and_set_json(bucket, "state/control.json", value, etag, region)["etag"]


def instance_state(region, instance_id):
    result = aws(["ec2", "describe-instances", "--instance-ids", instance_id,
                  "--query", "Reservations[0].Instances[0].State.Name", "--output", "text"], region)
    return result.stdout.strip()


def reconcile_active_operations(bucket, region, instance_id):
    operation = state.read_json(bucket, remote.ACTIVE_KEY, region)["value"]
    if operation and operation.get("phase") != "idle":
        if operation.get("instanceId") != instance_id:
            raise ControlError("outstanding work targets another instance; inspect it explicitly")
        remote.reconcile_operation(region, instance_id, bucket, operation)


def wait_online(region, instance_id, attempts=120):
    for _ in range(attempts):
        result = aws(["ssm", "describe-instance-information", "--filters",
                      f"Key=InstanceIds,Values={instance_id}"], region)
        if any(i.get("InstanceId") == instance_id and i.get("PingStatus") == "Online"
               for i in json.loads(result.stdout).get("InstanceInformationList", [])):
            return
        time.sleep(5)
    raise ControlError("SSM is not Online; instance running alone does not prove readiness")


def host_ready(region, instance_id):
    """Read-only proof for a running no-op; boot closes the gate independently of S3."""
    wait_online(region, instance_id)
    commands = ['set -eu', '. /opt/occ/env.rendered',
        '''for service in ingestion disruption planning; do
case "$service" in ingestion) port=${OCC_INGESTION_INTERNAL_PORT:-8001};; disruption) port=${OCC_DISRUPTION_INTERNAL_PORT:-8002};; planning) port=${OCC_PLANNING_INTERNAL_PORT:-8003};; esac
ids=$(docker ps --quiet --filter label=com.docker.compose.project=occ-production --filter "label=com.docker.compose.service=$service")
set -- $ids
[ "$#" = 1 ]
docker exec "$1" sh -c "curl --fail --silent --max-time 5 http://127.0.0.1:$port/actuator/health/readiness" >/dev/null
done''',
        '''url=${OCC_PUBLIC_URL:-${OCC_ORIGIN_PUBLIC_URL:?missing public URL}}
for check in _occ_edge_health:204 :200 frontend-config:200 login:200; do
path=${check%:*}; expected=${check##*:}
status=$(curl --connect-timeout 5 --max-time 10 --silent --output /dev/null --write-out '%{http_code}' "$url/$path")
[ "$status" = "$expected" ]
done''']
    result = aws(['ssm', 'send-command', '--instance-ids', instance_id,
                  '--document-name', 'AWS-RunShellScript', '--comment', 'occ-readiness',
                  '--timeout-seconds', '120', '--parameters',
                  json.dumps({'commands':commands,'executionTimeout':['120']})], region)
    command_id = json.loads(result.stdout)['Command']['CommandId']
    return remote.wait_terminal(region, instance_id, command_id) == 'Success'


def pause_commands(bucket, operation_id, region):
    receipt_code = '''import json,os,pathlib,sys
operation=sys.argv[1]; path=pathlib.Path('/opt/occ/state/receipts')/(operation+'.json')
path.parent.mkdir(parents=True,exist_ok=True)
value=dict(operationId=operation,action='pause',outcome='success',phase='verified',gateClosed=True)
with open(str(path)+'.new','w') as handle:
    json.dump(value,handle,sort_keys=True); handle.flush(); os.fsync(handle.fileno())
os.replace(str(path)+'.new',path)
'''
    return ["set -eu", f"export AWS_DEFAULT_REGION={shlex.quote(region)}",
        "mkdir -p /opt/occ/state /opt/occ/runtime/nginx-gate",
        "exec 9>/opt/occ/state/deploy.lock", "flock -n 9",
        'token=$(cat /opt/occ/runtime/nginx-gate/gate-token)',
        '''printf 'set $occ_traffic_gate "closed";\\nset $occ_gate_token "%s";\\n' "$token" > /opt/occ/runtime/nginx-gate/traffic-gate.inc.new''',
        "chown 101:101 /opt/occ/runtime/nginx-gate/traffic-gate.inc.new",
        "chmod 0640 /opt/occ/runtime/nginx-gate/traffic-gate.inc.new",
        "mv /opt/occ/runtime/nginx-gate/traffic-gate.inc.new /opt/occ/runtime/nginx-gate/traffic-gate.inc",
        # Stop the edge first, database last; only the stable project's known services.
        'for service in nginx ingestion disruption planning postgres; do ids=$(docker ps --quiet --filter label=com.docker.compose.project=occ-production --filter "label=com.docker.compose.service=$service"); [ -z "$ids" ] || docker stop --time 60 $ids >/dev/null; done',
        "python3 -c " + shlex.quote(receipt_code) + " " + shlex.quote(operation_id),
        f"aws s3api put-object --bucket {shlex.quote(bucket)} --key state/receipts/{shlex.quote(operation_id)}.json --body /opt/occ/state/receipts/{shlex.quote(operation_id)}.json --if-none-match '*' >/dev/null"]


def do_pause(region, instance_id, bucket, operation_id):
    reconcile_active_operations(bucket, region, instance_id)
    current = read_control(bucket, region)
    control = current["value"] or {}
    actual = instance_state(region, instance_id)
    if control.get("phase") == "paused" and control.get("result") == "success" and actual == "stopped":
        print("environment is already stopped and paused")
        return 0
    operation = remote.begin_operation(region, instance_id, bucket, operation_id, "pause")
    value = {"desiredMode": "paused", "phase": "pausing", "operationId": operation_id, "result": None}
    etag = write_control(bucket, region, value, current["etag"])
    try:
        if actual in ("stopped", "stopping"):
            if actual == "stopping":
                aws(["ec2", "wait", "instance-stopped", "--instance-ids", instance_id], region)
            remote.finish_operation(bucket, region, operation, "success")
        else:
            if actual == "pending":
                aws(["ec2", "wait", "instance-running", "--instance-ids", instance_id], region)
            elif actual != "running":
                raise ControlError(f"cannot pause instance in {actual}; paused intent retained")
            wait_online(region, instance_id)
            remote.dispatch_operation(region, instance_id, bucket, operation,
                                      pause_commands(bucket, operation_id, region))
    except (RuntimeError, state.StateError) as error:
        latest = read_control(bucket, region)
        if (latest["value"] or {}).get("operationId") == operation_id:
            write_control(bucket, region, dict(value, phase="pause-failed", result="failed"), latest["etag"])
        raise ControlError(f"pause did not complete; paused intent retained: {error}") from error
    write_control(bucket, region, dict(value, phase="paused", result="success"), etag)
    print("environment paused: services stopped gracefully and EC2 stopped")
    return 0


def do_start(region, instance_id, bucket, operation_id):
    reconcile_active_operations(bucket, region, instance_id)
    current = read_control(bucket, region)
    control = current["value"] or {}
    reference = remote.selected_release(bucket, region)
    deployed = state.read_json(bucket, "state/last-deployed.json", region)["value"]
    actual = instance_state(region, instance_id)
    if control.get("desiredMode") == "running" and control.get("phase") == "running" and control.get("result") == "success" and actual == "running" and deployed and all(deployed.get(k) == v for k,v in reference.items()) and host_ready(region, instance_id):
        print("environment already runs the latest verified release")
        return 0
    operation = remote.begin_operation(region, instance_id, bucket, operation_id, "apply", reference)
    operation = dict(operation, requestedAction="resume")
    remote._save_operation(bucket, region, operation)
    value = {"desiredMode": "paused", "phase": "starting", "operationId": operation_id, "result": None}
    etag = write_control(bucket, region, value, current["etag"])
    try:
        if actual == "stopping":
            aws(["ec2", "wait", "instance-stopped", "--instance-ids", instance_id], region)
            actual = "stopped"
        if actual == "stopped":
            aws(["ec2", "start-instances", "--instance-ids", instance_id], region)
        elif actual not in ("running", "pending"):
            raise ControlError(f"cannot resume instance in {actual}")
        aws(["ec2", "wait", "instance-running", "--instance-ids", instance_id], region)
        aws(["ec2", "wait", "instance-status-ok", "--instance-ids", instance_id], region)
        wait_online(region, instance_id)
        reference = remote.selected_release(bucket, region)
        operation = dict(operation, reference=reference)
        remote._save_operation(bucket, region, operation)
        remote.dispatch_operation(region, instance_id, bucket, operation,
                                  remote.remote_command(bucket, reference, operation_id, region, check_backup=True))
    except (RuntimeError, state.StateError) as error:
        latest = read_control(bucket, region)
        if (latest["value"] or {}).get("operationId") == operation_id:
            write_control(bucket, region, dict(value, phase="start-failed", result="failed"), latest["etag"])
        # Uncertain accepted work remains owned; never force-stop a potentially migrating DB.
        active = state.read_json(bucket, remote.ACTIVE_KEY, region)["value"]
        if not active or active.get("phase") == "idle":
            try:
                do_pause(region, instance_id, bucket, operation_id + "-cleanup")
            except (RuntimeError, state.StateError):
                pass
        raise ControlError(f"resume failed; paused intent/recovery state retained: {error}") from error
    write_control(bucket, region, dict(value, desiredMode="running", phase="running", result="success"), etag)
    print(f'environment running: verified release {reference["releaseId"]}, no rebuild')
    return 0


def do_status(region, instance_id, bucket, operation_id):
    control = read_control(bucket, region)["value"] or {"desiredMode": "paused", "phase": "uninitialized"}
    print(f'desired mode: {control.get("desiredMode")}')
    print(f'instance state: {instance_state(region, instance_id)}')
    for label,key in (("published", "state/latest-published.json"), ("deployed", "state/last-deployed.json")):
        value = state.read_json(bucket, key, region)["value"] or {}
        print(f'{label} release: {value.get("releaseId", "none")} revision: {value.get("revision", "none")}')
    active = state.read_json(bucket, remote.ACTIVE_KEY, region)["value"] or {}
    print(f'control phase: {control.get("phase")}, result: {control.get("result")}')
    print(f'active operation: {active.get("operationId", "none")} phase: {active.get("phase", "idle")}')
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("start", "resume", "pause", "status"))
    for name in ("region", "instance-id", "bucket", "operation-id"):
        parser.add_argument("--"+name, required=True)
    args = parser.parse_args(argv)
    try:
        fn = do_pause if args.action == "pause" else do_status if args.action == "status" else do_start
        return fn(args.region, args.instance_id, args.bucket, args.operation_id)
    except (RuntimeError, state.StateError) as error:
        print(f"environment: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
