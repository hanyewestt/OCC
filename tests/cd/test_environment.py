"""Lifecycle transitions using the real shared operation/conditional-state code."""
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/cd"))
import state
import deploy_remote as remote
from cloud_fixtures import CloudFixture


class EnvironmentTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("environment_under_test", ROOT / "scripts/cd/environment.py")
        self.env = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.env)
        self.cloud = CloudFixture()
        self.addCleanup(patch.stopall)
        patch.object(state, "read_json", side_effect=self.cloud.read).start()
        patch.object(state, "compare_and_set_json", side_effect=self.cloud.put).start()
        patch.object(remote, "aws", side_effect=self.cloud.aws).start()
        patch.object(self.env, "aws", side_effect=self.cloud.aws).start()
        patch.object(remote.release, "is_ancestor", side_effect=lambda repo, a, b: b == "origin/main" or a <= b).start()
        patch.object(remote.time, "sleep").start()
        self.reference = self.cloud.publish()

    def control(self):
        return self.cloud.read("", "state/control.json", "")["value"]

    def test_retry_failed_pause_stops_the_still_running_instance(self):
        self.cloud.seed("state/control.json", {"desiredMode": "paused", "phase": "pause-failed", "result": "failed"})
        self.assertEqual(self.env.do_pause("us-east-1", "i-test", "bucket", "pause-1"), 0)
        self.assertEqual(self.cloud.instance, "stopped")
        self.assertEqual(self.control()["phase"], "paused")
        self.assertEqual(len(self.cloud.commands), 1)

    def test_stopped_pause_is_idempotent_without_ssm(self):
        self.cloud.instance = "stopped"
        self.assertEqual(self.env.do_pause("us-east-1", "i-test", "bucket", "pause-1"), 0)
        self.assertEqual(self.env.do_pause("us-east-1", "i-test", "bucket", "pause-2"), 0)
        self.assertEqual(self.cloud.commands, [])

    def test_pause_dispatch_has_recoverable_durable_ownership(self):
        self.cloud.dispatch_fails = True
        with self.assertRaises(Exception):
            self.env.do_pause("us-east-1", "i-test", "bucket", "pause-1")
        active = self.cloud.read("", "state/active-operation.json", "")["value"]
        self.assertEqual(active["action"], "pause")
        self.assertEqual(active["phase"], "dispatching")
        self.assertEqual(self.control()["desiredMode"], "paused")
        self.assertEqual(self.cloud.instance, "running")

    def test_resume_waits_for_ec2_and_ssm_then_applies_without_rebuild(self):
        self.cloud.instance = "stopped"
        self.cloud.seed("state/control.json", {"desiredMode": "paused", "phase": "paused", "result": "success"})
        self.assertEqual(self.env.do_start("us-east-1", "i-test", "bucket", "start-1"), 0)
        calls = self.cloud.calls
        online = next(i for i,a in enumerate(calls) if a[:2] == ["ssm", "describe-instance-information"])
        dispatch = next(i for i,a in enumerate(calls) if a[:2] == ["ssm", "send-command"])
        self.assertLess(online, dispatch)
        self.assertTrue(any(a[:3] == ["ec2", "wait", "instance-status-ok"] for a in calls))
        self.assertNotIn("docker build", str(self.cloud.commands))
        self.assertIn("OCC_CHECK_OVERDUE_BACKUP=true", str(self.cloud.commands))
        self.assertEqual(self.control()["phase"], "running")
        self.assertEqual(self.cloud.read("", "state/last-deployed.json", "")["value"]["releaseId"], "rel-b")

    def test_stale_success_during_starting_does_not_skip_apply(self):
        self.cloud.seed("state/control.json", {"desiredMode": "running", "phase": "starting", "result": "success"})
        self.assertEqual(self.env.do_start("us-east-1", "i-test", "bucket", "start-1"), 0)
        self.assertEqual(len(self.cloud.commands), 1)

    def test_a_genuine_running_noop_checks_the_deployed_release(self):
        self.cloud.seed("state/control.json", {"desiredMode": "running", "phase": "running", "result": "success"})
        self.cloud.seed("state/last-deployed.json", self.reference)
        self.assertEqual(self.env.do_start("us-east-1", "i-test", "bucket", "start-1"), 0)
        self.assertEqual(self.cloud.commands, [])

    def test_no_release_does_not_wake_the_instance(self):
        self.cloud.objects.pop("state/latest-published.json")
        self.cloud.instance = "stopped"
        with self.assertRaises(Exception):
            self.env.do_start("us-east-1", "i-test", "bucket", "start-1")
        self.assertEqual(self.cloud.instance, "stopped")

    def test_ambiguous_old_work_blocks_resume_and_keeps_paused_intent(self):
        self.cloud.seed("state/control.json", {"desiredMode": "paused", "phase": "pause-failed", "result": "failed"})
        self.cloud.seed("state/active-operation.json", {"operationId": "lost", "action": "pause", "instanceId": "i-test", "phase": "dispatching"})
        with self.assertRaises(Exception):
            self.env.do_start("us-east-1", "i-test", "bucket", "start-1")
        self.assertEqual(self.cloud.commands, [])
        self.assertEqual(self.control()["desiredMode"], "paused")


if __name__ == "__main__":
    unittest.main()
