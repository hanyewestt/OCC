"""Strict external state/SSM boundary fixtures; no production behavior is mocked."""
import hashlib
import json
import subprocess
import state


class CloudFixture:
    def __init__(self):
        self.objects = {}
        self.serial = 0
        self.calls = []
        self.commands = []
        self.instance = "running"
        self.online = True
        self.dispatch_fails = False
        self.stop_fails = False
        self.command_status = "Success"

    def read(self, bucket, key, region, **kwargs):
        value, etag, version = self.objects.get(key, (None, None, None))
        return {"value": json.loads(json.dumps(value)), "etag": etag, "versionId": version,
                "checksum": "sha256:" + hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()}

    def put(self, bucket, key, value, expected_etag, region):
        if self.objects.get(key, (None, None, None))[1] != expected_etag:
            raise state.ConflictError("PreconditionFailed", "stale fixture writer")
        self.serial += 1
        self.objects[key] = (json.loads(json.dumps(value)), f'"etag-{self.serial}"', f"version-{self.serial}")
        return {"etag": f'"etag-{self.serial}"', "versionId": f"version-{self.serial}"}

    def seed(self, key, value):
        return self.put("fixture", key, value, self.objects.get(key, (None, None, None))[1], "us-east-1")

    def publish(self, release_id="rel-b", revision="b" * 40):
        components = ["ingestion", "disruption", "planning", "db-setup", "web"]
        manifest = {"schemaVersion": 2, "releaseId": release_id, "revision": revision,
                    "images": {c: f"123456789012.dkr.ecr.us-east-1.amazonaws.com/occ/{c}@sha256:" + "a" * 64 for c in components},
                    "inputHashes": {c: "a" * 64 for c in components}, "builtComponents": [],
                    "dbSetupInputHash": "a" * 64, "migrationCompatibility": "unknown"}
        key = f"releases/{release_id}.json"
        metadata = self.seed(key, manifest)
        reference = {"releaseId": release_id, "revision": revision, "releaseKey": key,
                     "version": metadata["versionId"], "checksum": self.read("", key, "")["checksum"]}
        self.seed("state/latest-published.json", dict(manifest, **reference))
        return reference

    def aws(self, args, region, check=True, capture=True):
        self.calls.append(list(args))
        command = args[:2]
        output = {}
        if command == ["ec2", "describe-instances"]:
            return subprocess.CompletedProcess(args, 0, self.instance, "")
        if command == ["ec2", "start-instances"]:
            self.instance = "running"
        elif command == ["ec2", "stop-instances"]:
            if self.stop_fails:
                raise RuntimeError("stop failed")
            self.instance = "stopped"
        elif command == ["ec2", "wait"]:
            pass
        elif command == ["ssm", "describe-instance-information"]:
            output = {"InstanceInformationList": [{"InstanceId": "i-test", "PingStatus": "Online" if self.online else "ConnectionLost"}]}
        elif command == ["ssm", "send-command"]:
            if self.dispatch_fails:
                raise RuntimeError("ambiguous SSM dispatch failure")
            operation = self.read("", "state/active-operation.json", "")["value"]
            self.commands.append(json.loads(args[args.index("--parameters") + 1])["commands"])
            receipt = {"operationId": operation["operationId"], "action": operation["action"],
                       "outcome": "success" if self.command_status == "Success" else "failed",
                       "phase": "verified", "gateClosed": operation["action"] == "pause" or self.command_status != "Success",
                       "reference": operation.get("reference"), "setupFingerprint": "verified-fp"}
            if operation.get("reference"):
                receipt["releaseId"] = operation["reference"]["releaseId"]
            self.seed(f'state/receipts/{operation["operationId"]}.json', receipt)
            output = {"Command": {"CommandId": f"command-{len(self.commands)}"}}
        elif command == ["ssm", "get-command-invocation"]:
            output = {"Status": self.command_status}
        elif command == ["ssm", "list-commands"]:
            output = {"Commands": []}
        else:
            raise AssertionError(f"unexpected external AWS request: {command}")
        return subprocess.CompletedProcess(args, 0, json.dumps(output), "")
