"""Exercise state serialization and conditional S3 request boundaries."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class StateTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("occ_state", ROOT / "scripts/cd/state.py")
        self.state = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.state)
        self.objects = {}
        self.generation = 0
        self.calls = []
        self.state.aws = self.fake_s3

    def fake_s3(self, args, region):
        self.calls.append(args)
        key = args[args.index("--key") + 1]
        current = self.objects.get(key)
        if args[1] == "get-object":
            if current is None:
                raise self.state.StateError("NoSuchKey", "missing fixture key")
            if "--version-id" in args and args[args.index("--version-id") + 1] != current["VersionId"]:
                raise self.state.StateError("NoSuchVersion", "wrong immutable version")
            Path(args[-1]).write_bytes(current["body"])
            return {k: v for k, v in current.items() if k != "body"}
        if args[1] == "put-object":
            if "--if-none-match" in args:
                self.assertEqual(args[args.index("--if-none-match") + 1], "*")
                if current:
                    raise self.state.ConflictError("PreconditionFailed", "exists")
            elif "--if-match" in args:
                if not current or args[args.index("--if-match") + 1] != current["ETag"]:
                    raise self.state.ConflictError("PreconditionFailed", "stale ETag")
            else:
                self.fail("production attempted an unconditional state write")
            self.generation += 1
            current = {"ETag": f'"opaque-{self.generation}"', "VersionId": f"v{self.generation}",
                       "body": Path(args[args.index("--body") + 1]).read_bytes()}
            self.objects[key] = current
            return {k: v for k, v in current.items() if k != "body"}
        self.fail(f"unexpected S3 operation: {args[:2]}")

    def test_conditional_create_update_and_stale_writer(self):
        first = self.state.compare_and_set_json("bucket", "state/control.json", {"desiredMode": "paused"}, None, "us-east-1")
        actual = self.state.read_json("bucket", "state/control.json", "us-east-1")
        self.assertEqual(actual["value"], {"desiredMode": "paused"})
        self.assertEqual(actual["versionId"], "v1")
        self.state.compare_and_set_json("bucket", "state/control.json", {"desiredMode": "running"}, first["etag"], "us-east-1")
        with self.assertRaises(self.state.ConflictError):
            self.state.compare_and_set_json("bucket", "state/control.json", {"desiredMode": "paused"}, first["etag"], "us-east-1")

    def test_access_denial_is_not_a_missing_or_default_running_state(self):
        def denied(args, region):
            raise self.state.StateError("AccessDenied", "fixture denied")
        self.state.aws = denied
        with self.assertRaises(self.state.StateError):
            self.state.read_json("bucket", "state/control.json", "us-east-1")

    def test_pinned_version_and_checksum_are_verified_before_use(self):
        import hashlib
        body = json.dumps({"releaseId": "rel-1", "revision": "a" * 40}).encode()
        self.objects["releases/rel-1.json"] = {"body": body, "ETag": '"opaque"', "VersionId": "real-version"}
        reference = {"releaseId": "rel-1", "revision": "a" * 40,
                     "releaseKey": "releases/rel-1.json", "version": "real-version",
                     "checksum": "sha256:" + hashlib.sha256(body).hexdigest()}
        self.assertEqual(self.state.verified_manifest("bucket", reference, "us-east-1")["releaseId"], "rel-1")
        reference["checksum"] = "sha256:" + "0" * 64
        with self.assertRaises(self.state.StateError):
            self.state.verified_manifest("bucket", reference, "us-east-1")
        reference["version"] = "-"
        with self.assertRaises(self.state.StateError):
            self.state.verified_manifest("bucket", reference, "us-east-1")

    def test_a_missing_key_is_explicitly_absent(self):
        self.assertIsNone(self.state.read_json("bucket", "missing", "us-east-1")["value"])

    def test_initial_control_pauses_once_without_overwriting_running_intent(self):
        self.state.initialize_control("bucket", "us-east-1")
        record = self.state.read_json("bucket", "state/control.json", "us-east-1")
        self.assertEqual(record["value"]["desiredMode"], "paused")
        self.state.compare_and_set_json("bucket", "state/control.json", {"desiredMode":"running"}, record["etag"], "us-east-1")
        self.state.initialize_control("bucket", "us-east-1")
        self.assertEqual(self.state.read_json("bucket", "state/control.json", "us-east-1")["value"]["desiredMode"], "running")


if __name__ == "__main__":
    unittest.main()
