"""Accepted remote commands remain owned until receipt and pointer reconciliation."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/cd"))
import state


class OperationTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("operation_driver", ROOT / "scripts/cd/deploy_remote.py")
        self.remote = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.remote)
        self.objects = {}
        self.serial = 0
        self.addCleanup(patch.stopall)
        patch.object(state, "read_json", side_effect=self.read).start()
        patch.object(state, "compare_and_set_json", side_effect=self.write).start()
        patch.object(self.remote.time, "sleep").start()
        self.reference = {"releaseId": "rel-1", "revision": "a" * 40,
                          "releaseKey": "releases/rel-1.json", "version": "v1",
                          "checksum": "sha256:" + "a" * 64}

    def read(self, bucket, key, region, **kwargs):
        value, etag = self.objects.get(key, (None, None))
        return {"value": value, "etag": etag, "versionId": "v1", "checksum": "sha256:" + "a" * 64}

    def write(self, bucket, key, value, expected_etag, region):
        current = self.objects.get(key, (None, None))[1]
        if current != expected_etag:
            raise state.ConflictError("PreconditionFailed", "competing writer")
        self.serial += 1
        self.objects[key] = (dict(value), str(self.serial))
        return {"etag": str(self.serial), "versionId": f"v{self.serial}"}

    def test_claim_is_conditional_and_contains_durable_dispatch_identity(self):
        operation = self.remote.begin_operation("us-east-1", "i-test", "bucket", "op-1", "apply", self.reference)
        actual = self.read("bucket", "state/active-operation.json", "us-east-1")["value"]
        self.assertEqual(actual["operationId"], "op-1")
        self.assertEqual(actual["phase"], "claimed")
        self.assertEqual(actual["instanceId"], "i-test")
        self.assertEqual(operation["reference"], self.reference)

    def test_active_dispatch_blocks_a_second_mutation_when_acceptance_is_ambiguous(self):
        self.objects["state/active-operation.json"] = ({"operationId": "old", "instanceId": "i-test",
            "action": "pause", "phase": "dispatching", "startedAt": "2026-11-11T00:00:00Z"}, "e1")
        with patch.object(self.remote, "lookup_invocation", return_value=None):
            with self.assertRaises(self.remote.RemoteError):
                self.remote.begin_operation("us-east-1", "i-test", "bucket", "new", "apply", self.reference)
        self.assertEqual(self.objects["state/active-operation.json"][0]["operationId"], "old")

    def test_recovery_matches_comment_and_target_without_unsupported_comment_filter(self):
        def aws(args, region, **kwargs):
            self.assertNotIn("key=Comment", " ".join(args))
            return subprocess.CompletedProcess(args, 0, json.dumps({"Commands": [
                {"CommandId": "foreign", "Comment": "occ-operation:op-1", "InstanceIds": ["i-other"], "Status": "Success"},
                {"CommandId": "accepted", "Comment": "occ-operation:op-1", "InstanceIds": ["i-test"], "Status": "Success"}]}), "")
        with patch.object(self.remote, "aws", side_effect=aws):
            found = self.remote.lookup_invocation("us-east-1", "i-test", "op-1")
        self.assertEqual(found["CommandId"], "accepted")

    def test_receipt_with_wrong_release_cannot_advance_pointer(self):
        operation = {"operationId": "op-1", "action": "apply", "instanceId": "i-test",
                     "phase": "dispatched", "commandId": "c-1", "reference": self.reference}
        self.objects["state/active-operation.json"] = (operation, "a")
        self.objects["state/receipts/op-1.json"] = ({"operationId": "op-1", "outcome": "success",
            "releaseId": "other", "reference": dict(self.reference, releaseId="other")}, "r")
        with patch.object(self.remote, "wait_terminal", return_value="Success"):
            with self.assertRaises(self.remote.RemoteError):
                self.remote.reconcile_operation("us-east-1", "i-test", "bucket", operation)
        self.assertNotIn("state/last-deployed.json", self.objects)

    def completed_operation(self, outcome="success", gate_closed=False):
        operation = self.remote.begin_operation("us-east-1", "i-test", "bucket", "op-1", "apply", self.reference)
        operation = dict(operation, phase="dispatched", commandId="c-1")
        self.remote._save_operation("bucket", "us-east-1", operation)
        self.objects["state/receipts/op-1.json"] = ({"operationId": "op-1", "action": "apply",
            "outcome": outcome, "releaseId": "rel-1", "reference": self.reference,
            "gateClosed": gate_closed, "setupFingerprint": "applied-fp"}, "r")
        return operation

    def test_success_receipt_advances_real_reference_and_is_recoverable_idempotently(self):
        operation = self.completed_operation()
        with patch.object(self.remote, "wait_terminal", return_value="Success"):
            self.remote.reconcile_operation("us-east-1", "i-test", "bucket", operation)
        pointer = self.objects["state/last-deployed.json"][0]
        self.assertEqual(pointer["releaseKey"], "releases/rel-1.json")
        self.assertEqual(pointer["version"], "v1")
        self.assertEqual(pointer["sequence"], 1)
        self.assertEqual(self.remote.run_recover("us-east-1", "i-test", "bucket", "op-1", 0), 0)

    def test_failed_remote_work_needs_closed_gate_and_remains_failed(self):
        operation = self.completed_operation("failed", False)
        with patch.object(self.remote, "wait_terminal", return_value="Failed"):
            with self.assertRaises(self.remote.RemoteError):
                self.remote.reconcile_operation("us-east-1", "i-test", "bucket", operation)
        self.assertEqual(self.objects["state/active-operation.json"][0]["phase"], "dispatched")
        receipt = self.objects["state/receipts/op-1.json"][0]
        self.objects["state/receipts/op-1.json"] = (dict(receipt, gateClosed=True), "r2")
        with patch.object(self.remote, "wait_terminal", return_value="Failed"):
            with self.assertRaises(self.remote.RemoteError):
                self.remote.reconcile_operation("us-east-1", "i-test", "bucket", operation)
        self.assertNotIn("state/last-deployed.json", self.objects)
        self.assertEqual(self.objects["state/active-operation.json"][0]["result"], "failed")

    def test_other_pointer_writer_cannot_be_overwritten_after_host_success(self):
        operation = self.completed_operation()
        self.objects["state/last-deployed.json"] = ({"operationId": "other", "sequence": 9}, "new")
        with patch.object(self.remote, "wait_terminal", return_value="Success"):
            with self.assertRaises(self.remote.RemoteError):
                self.remote.reconcile_operation("us-east-1", "i-test", "bucket", operation)
        self.assertEqual(self.objects["state/last-deployed.json"][0]["sequence"], 9)

    def test_missing_remote_receipt_is_retrieved_read_only_before_pointer_advance(self):
        operation = self.completed_operation()
        receipt = self.objects.pop("state/receipts/op-1.json")[0]
        commands = []
        def aws(args, region, **kwargs):
            if args[:2] == ["ssm", "send-command"]:
                commands.extend(json.loads(args[args.index("--parameters") + 1])["commands"])
                return subprocess.CompletedProcess(args, 0, '{"Command":{"CommandId":"receipt-read"}}', "")
            return subprocess.CompletedProcess(args, 0, json.dumps({"StandardOutputContent": json.dumps(receipt)}), "")
        with patch.object(self.remote, "aws", side_effect=aws), patch.object(self.remote, "wait_terminal", return_value="Success"):
            self.remote.reconcile_operation("us-east-1", "i-test", "bucket", operation)
        self.assertIn("cat /opt/occ/state/receipts/op-1.json", "\n".join(commands))
        self.assertNotIn("deploy.sh", "\n".join(commands))
        self.assertEqual(self.objects["state/last-deployed.json"][0]["releaseId"], "rel-1")

    def test_terminal_missing_receipt_quiesces_and_archives_failure_without_pointer_advance(self):
        operation=self.completed_operation()
        self.objects.pop('state/receipts/op-1.json')
        receipt=dict(operationId='op-1',action='apply',reference=self.reference,
                     releaseId='rel-1',outcome='failed',phase='failed',gateClosed=True,setupRecoveryRequired=True)
        commands=[]
        def aws(args,region,**kwargs):
            if args[:2]==['ssm','send-command']:
                parameters=json.loads(args[args.index('--parameters')+1])
                self.assertEqual(parameters['executionTimeout'],['600'])
                commands.extend(parameters['commands'])
                value={'Command':{'CommandId':'failure-inspection'}}
            else:
                value={'StandardOutputContent':json.dumps(receipt)}
            return subprocess.CompletedProcess(args,0,json.dumps(value),'')
        with patch.object(self.remote,'aws',side_effect=aws), patch.object(self.remote,'wait_terminal',side_effect=['Failed','Success']):
            with self.assertRaisesRegex(self.remote.RemoteError,'failed'):
                self.remote.reconcile_operation('us-east-1','i-test','bucket',operation)
        self.assertIn('setup-recovery-required.json','\n'.join(commands))
        self.assertNotIn('state/last-deployed.json',self.objects)
        self.assertEqual(self.objects['state/active-operation.json'][0]['result'],'failed')

    def test_failed_receipt_probe_can_retry_instead_of_caching_a_dead_command(self):
        operation=self.completed_operation()
        operation=dict(operation,receiptCommandId='failed-inspection')
        self.objects['state/active-operation.json']=(operation,'active-etag')
        with patch.object(self.remote,'wait_terminal',return_value='Failed'):
            with self.assertRaisesRegex(self.remote.RemoteError,'unavailable'):
                self.remote.recover_receipt('us-east-1','i-test','bucket',operation)
        active=self.objects['state/active-operation.json'][0]
        self.assertNotIn('receiptCommandId',active)
        self.assertEqual(active['operationId'],'op-1')


if __name__ == "__main__":
    unittest.main()
