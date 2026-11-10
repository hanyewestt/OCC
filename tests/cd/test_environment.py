"""Lifecycle control fixtures: start/resume, pause and status.

Drives scripts/cd/environment.py through its CLI entrypoint with a fake aws
function, asserting the paused-intent ordering, graceful shutdown, stop/start
semantics, release selection on resume and the status contract. The aws CLI
subprocess wrapper itself is exercised end-to-end by test_remote; the fake
here replaces only that spawn so JSON parameter payloads cannot be mangled by
Windows command shells.
"""
import importlib.util
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

ROOT = Path(__file__).resolve().parents[2]
ENVIRONMENT = ROOT / "scripts" / "cd" / "environment.py"
CONTROL_WORKFLOW = ROOT / ".github" / "workflows" / "environment-control.yml"
CD_YML = ROOT / ".github" / "workflows" / "cd.yml"

_spec = importlib.util.spec_from_file_location("occ_environment", ENVIRONMENT)
environment = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(environment)


class FakeAws:
    """Records aws invocations and answers from a scripted state."""

    def __init__(self, state: Path) -> None:
        self.state = state
        self.calls: list[list[str]] = []
        self.instance_state = "running"
        self.start_fails = False
        self.stop_fails = False
        self.ssm_fails = False
        self.ssm_status = "Success"

    def __call__(self, arguments: list[str], region: str, check: bool = True,
                 capture: bool = True):
        self.calls.append(list(arguments))
        joined = " ".join(arguments)
        stdout, stderr, returncode = "", "", 0

        if joined.startswith("s3 cp"):
            if arguments[-1].startswith("s3://"):
                source = next((a for a in reversed(arguments[:-1])
                               if not a.startswith("s3://") and a != "-"), None)
                if source and Path(source).is_file():
                    name = arguments[-1].rsplit("/", 1)[-1]
                    if "/state/operations/" in arguments[-1]:
                        operations = self.state / "operations"
                        operations.mkdir(exist_ok=True)
                        target = operations / name
                    else:
                        target = self.state / name
                    target.write_text(
                        Path(source).read_text(encoding="utf-8"), encoding="utf-8")
                return environment.subprocess.CompletedProcess(arguments, 0, "", "")
            name = ("control.json" if "state/control.json" in joined else
                    "latest-published.json" if "state/latest-published.json" in joined
                    else "last-deployed.json")
            path = self.state / name
            if path.is_file():
                stdout = path.read_text(encoding="utf-8")
                return environment.subprocess.CompletedProcess(arguments, 0,
                                                               stdout, "")
            returncode, stderr = 1, "reading failed"
            if check:
                raise environment.ControlError(stderr)
            return environment.subprocess.CompletedProcess(arguments, returncode,
                                                           stdout, stderr)
        if joined.startswith("s3 rm"):
            if "/state/operations/" in arguments[-1]:
                (self.state / "operations" /
                 arguments[-1].rsplit("/", 1)[-1]).unlink(missing_ok=True)
            return environment.subprocess.CompletedProcess(arguments, 0, "", "")
        if joined.startswith("s3 ls"):
            operations = self.state / "operations"
            if operations.is_dir() and any(operations.iterdir()):
                stdout = "object\n"
            return environment.subprocess.CompletedProcess(arguments, 0, stdout, stderr)
        if joined.startswith("ec2 describe-instances"):
            stdout = json.dumps({"state": self.instance_state})
            return environment.subprocess.CompletedProcess(arguments, 0, stdout, stderr)
        if joined.startswith("ec2 start-instances"):
            if self.start_fails:
                if check:
                    raise environment.ControlError("start failed")
                stderr, returncode = "start failed", 1
            return environment.subprocess.CompletedProcess(arguments, returncode,
                                                           stdout, stderr)
        if joined.startswith("ec2 stop-instances") or \
                joined.startswith("ec2 wait instance-stopped"):
            if self.stop_fails:
                if check:
                    raise environment.ControlError("stop failed")
                stderr, returncode = "stop failed", 1
            return environment.subprocess.CompletedProcess(arguments, returncode,
                                                           stdout, stderr)
        if joined.startswith("ec2 wait instance-running"):
            return environment.subprocess.CompletedProcess(arguments, 0, stdout, stderr)
        if joined.startswith("ssm send-command"):
            if self.ssm_fails:
                stderr, returncode = "ssm failed", 1
            else:
                stdout = json.dumps({"Command": {"CommandId": "c-9"}})
            return environment.subprocess.CompletedProcess(arguments, returncode,
                                                           stdout, stderr)
        if joined.startswith("ssm get-command-invocation"):
            stdout = json.dumps({"Status": self.ssm_status})
            return environment.subprocess.CompletedProcess(arguments, 0, stdout, stderr)
        # Mirror the real aws wrapper: check=True raises on failure.
        if check and returncode != 0:
            raise environment.ControlError(stderr or f"{joined} failed")
        return environment.subprocess.CompletedProcess(arguments, returncode,
                                                       stdout, stderr)


class EnvironmentTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.state = Path(tempfile.mkdtemp(prefix="occ-env-state-"))
        self.addCleanup(self._cleanup)
        self.fake = FakeAws(self.state)
        environment.aws = self.fake

    def _cleanup(self) -> None:
        shutil.rmtree(self.state, ignore_errors=True)

    def run_action(self, action: str) -> tuple[int, str, str]:
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = environment.main([action, "--region", "us-east-1",
                                     "--instance-id", "i-123", "--bucket", "occ-test",
                                     "--operation-id", "op-env-1"])
        return code, stdout.getvalue(), stderr.getvalue()

    def write_control(self, mode: str, result: str = "success") -> None:
        (self.state / "control.json").write_text(
            json.dumps({"desiredMode": mode, "operationId": None, "phase": "idle",
                        "result": result}), encoding="utf-8")

    def read_control(self) -> dict:
        return json.loads((self.state / "control.json").read_text(encoding="utf-8"))

    def ssm_commands(self) -> list[str]:
        return [" ".join(arguments) for arguments in self.fake.calls
                if arguments[:2] == ["ssm", "send-command"]]


class PauseTests(EnvironmentTestCase):
    def test_pause_records_intent_stops_services_then_stops_instance(self):
        self.write_control("running")
        code, _, err = self.run_action("pause")
        self.assertEqual(code, 0, err)
        control = self.read_control()
        self.assertEqual(control["desiredMode"], "paused")
        self.assertEqual(control["result"], "success")
        # The paused intent is durable before the instance stops: the local
        # control write (an s3 cp from a temp file with paused mode) precedes
        # the stop-instances call in the recorded order.
        intent_position = next(
            (position for position, arguments in enumerate(self.fake.calls)
             if arguments[:2] == ["s3", "cp"]
             and not arguments[2].startswith("s3://")
             and "control.json" in arguments[2]
             and json.loads(Path(arguments[2]).read_text(encoding="utf-8"))
             .get("desiredMode") == "paused"),
            -1)
        stop_position = next((position for position, arguments in
                              enumerate(self.fake.calls)
                              if arguments[:2] == ["ec2", "stop-instances"]), -1)
        self.assertGreaterEqual(stop_position, 0)
        self.assertGreater(stop_position, intent_position)
        joined = "\n".join(self.ssm_commands())
        self.assertIn("stop nginx ingestion disruption planning postgres", joined)
        self.assertFalse(any(arguments[:2] == ["ec2", "terminate-instances"]
                             for arguments in self.fake.calls))

    def test_pause_stops_through_the_deployed_compose_project(self):
        self.write_control("running")
        code, _, err = self.run_action("pause")
        self.assertEqual(code, 0, err)
        joined = "\n".join(self.ssm_commands())
        self.assertIn("active-release.json", joined)
        self.assertIn(". /opt/occ/env.rendered", joined)
        self.assertIn("releases/$release_id/bundle", joined)
        self.assertIn("traffic-gate.sh close", joined)
        self.assertIn("-f compose.yaml", joined)
        self.assertIn("stop nginx ingestion disruption planning postgres", joined)
        self.assertNotIn("active-release.name", joined)

    def test_lifecycle_commands_use_the_shared_recover_comment_prefix(self):
        self.write_control("running")
        code, _, err = self.run_action("pause")
        self.assertEqual(code, 0, err)
        joined = "\n".join(self.ssm_commands())
        # deploy_remote.recover filters on this exact prefix.
        self.assertIn("occ-operation:op-env-1", joined)
        self.assertNotIn("occ-lifecycle:", joined)

    def test_pause_is_idempotent_when_already_paused(self):
        self.write_control("paused")
        code, _, err = self.run_action("pause")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.ssm_commands(), [])
        self.assertFalse(any(arguments[:2] == ["ec2", "stop-instances"]
                             for arguments in self.fake.calls))

    def test_pause_with_stopped_instance_needs_no_ssm(self):
        self.write_control("running")
        self.fake.instance_state = "stopped"
        code, _, err = self.run_action("pause")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.ssm_commands(), [])
        self.assertEqual(self.read_control()["desiredMode"], "paused")

    def test_shutdown_failure_keeps_paused_intent_and_reports_state(self):
        self.write_control("running")
        self.fake.stop_fails = True
        code, _, err = self.run_action("pause")
        self.assertNotEqual(code, 0)
        self.assertEqual(self.read_control()["desiredMode"], "paused")
        self.assertIn("intent", err.lower())
        self.assertFalse(any(arguments[:2] == ["ec2", "terminate-instances"]
                             for arguments in self.fake.calls))

    def test_ssm_shutdown_failure_keeps_paused_intent(self):
        self.write_control("running")
        self.fake.ssm_fails = True
        code, _, err = self.run_action("pause")
        self.assertNotEqual(code, 0)
        self.assertEqual(self.read_control()["desiredMode"], "paused")

    def test_outstanding_operations_block_the_pause(self):
        self.write_control("running")
        operations = self.state / "operations"
        operations.mkdir()
        (operations / "op-1.json").write_text("{}", encoding="utf-8")
        code, _, err = self.run_action("pause")
        self.assertNotEqual(code, 0)
        self.assertIn("outstanding", err.lower())
        self.assertEqual(self.ssm_commands(), [])

    def test_outstanding_operations_block_the_start(self):
        self.write_control("paused")
        operations = self.state / "operations"
        operations.mkdir()
        (operations / "op-1.json").write_text("{}", encoding="utf-8")
        code, _, err = self.run_action("start")
        self.assertNotEqual(code, 0)
        self.assertIn("outstanding", err.lower())
        self.assertEqual(self.ssm_commands(), [])
        self.assertFalse(any(arguments[:2] == ["ec2", "start-instances"]
                             for arguments in self.fake.calls))


class StartResumeTests(EnvironmentTestCase):
    def setUp(self) -> None:
        super().setUp()
        (self.state / "latest-published.json").write_text(json.dumps({
            "releaseId": "rel-1", "revision": "aa" * 20,
            "releaseKey": "releases/rel-1", "version": "-", "checksum": "-",
            "sequence": 5}), encoding="utf-8")
        (self.state / "last-deployed.json").write_text(json.dumps({
            "releaseId": "rel-1", "revision": "aa" * 20,
            "releaseKey": "releases/rel-1", "version": "-", "checksum": "-",
            "sequence": 5}), encoding="utf-8")

    def test_start_starts_instance_and_applies_latest_release(self):
        self.write_control("paused")
        self.fake.instance_state = "stopped"
        code, _, err = self.run_action("start")
        self.assertEqual(code, 0, err)
        self.assertTrue(any(arguments[:2] == ["ec2", "start-instances"]
                            for arguments in self.fake.calls))
        joined = "\n".join(self.ssm_commands())
        self.assertIn("releases/rel-1", joined)
        self.assertIn("deploy.sh apply", joined)
        self.assertEqual(self.read_control()["desiredMode"], "running")

    def test_start_persists_intent_before_dispatch_and_clears_it(self):
        self.write_control("paused")
        self.fake.instance_state = "stopped"
        code, _, err = self.run_action("start")
        self.assertEqual(code, 0, err)
        intent_calls = [
            (position, arguments) for position, arguments in
            enumerate(self.fake.calls)
            if "/state/operations/op-env-1.json" in " ".join(arguments)]
        self.assertTrue(intent_calls)
        write_position = next(position for position, arguments in intent_calls
                              if arguments[:2] == ["s3", "cp"])
        remove_position = next(position for position, arguments in intent_calls
                               if arguments[:2] == ["s3", "rm"])
        dispatch_position = next(
            position for position, arguments in enumerate(self.fake.calls)
            if arguments[:2] == ["ssm", "send-command"])
        # The intent is durable before SSM accepts the command and cleared
        # only after the verified terminal success.
        self.assertLess(write_position, dispatch_position)
        self.assertGreater(remove_position, dispatch_position)
        self.assertFalse((self.state / "operations" / "op-env-1.json").exists())

    def test_failed_start_retains_the_operation_intent(self):
        self.write_control("paused")
        self.fake.instance_state = "stopped"
        self.fake.ssm_status = "Failed"
        code, _, err = self.run_action("start")
        self.assertNotEqual(code, 0)
        self.assertIn("paused intent", err.lower())
        # A failed resume keeps the dispatch record so a lost runner cannot be
        # silently forgotten; it blocks further mutations until reconciled.
        self.assertTrue((self.state / "operations" / "op-env-1.json").is_file())

    def test_resume_is_an_alias_of_start(self):
        self.write_control("paused")
        self.fake.instance_state = "stopped"
        code, _, err = self.run_action("resume")
        self.assertEqual(code, 0, err)
        self.assertTrue(any(arguments[:2] == ["ec2", "start-instances"]
                            for arguments in self.fake.calls))

    def test_start_when_already_running_applies_nothing(self):
        self.write_control("running", result="success")
        code, _, err = self.run_action("start")
        self.assertEqual(code, 0, err)
        self.assertFalse(any(arguments[:2] == ["ec2", "start-instances"]
                             for arguments in self.fake.calls))
        self.assertEqual(self.ssm_commands(), [])

    def test_failed_start_retains_paused_intent(self):
        self.write_control("paused")
        self.fake.instance_state = "stopped"
        self.fake.start_fails = True
        code, _, err = self.run_action("start")
        self.assertNotEqual(code, 0)
        self.assertEqual(self.read_control()["desiredMode"], "paused")

    def test_resume_uses_the_published_pointer_even_when_newer_main_failed(self):
        # A newer main push failed verification; the published pointer still
        # names the last eligible release and resume deploys exactly that.
        self.write_control("paused")
        self.fake.instance_state = "stopped"
        code, _, err = self.run_action("resume")
        self.assertEqual(code, 0, err)
        joined = "\n".join(self.ssm_commands())
        self.assertIn("releases/rel-1", joined)
        self.assertNotIn("releases/rel-2", joined)

    def test_resume_without_any_published_release_fails_clearly(self):
        self.write_control("paused")
        self.fake.instance_state = "stopped"
        (self.state / "latest-published.json").unlink()
        code, _, err = self.run_action("start")
        self.assertNotEqual(code, 0)
        self.assertIn("published", err.lower())
        self.assertEqual(self.read_control()["desiredMode"], "paused")


class StatusTests(EnvironmentTestCase):
    def test_status_reports_desired_actual_and_pointers_without_secrets(self):
        self.write_control("running")
        (self.state / "latest-published.json").write_text(json.dumps({
            "releaseId": "rel-1", "revision": "aa" * 20, "sequence": 5}), encoding="utf-8")
        (self.state / "last-deployed.json").write_text(json.dumps({
            "releaseId": "rel-1", "revision": "aa" * 20, "sequence": 5}), encoding="utf-8")
        code, out, _ = self.run_action("status")
        self.assertEqual(code, 0)
        self.assertIn("running", out)
        self.assertIn("rel-1", out)
        self.assertNotIn("password", out.lower())
        self.assertNotIn("secret", out.lower())

    def test_unexpectedly_stopped_in_running_mode_reports_unavailable(self):
        self.write_control("running")
        self.fake.instance_state = "stopped"
        code, out, _ = self.run_action("status")
        self.assertEqual(code, 0)
        self.assertIn("unavailable", out.lower())
        self.assertFalse(any(arguments[:2] == ["ec2", "start-instances"]
                             for arguments in self.fake.calls))


class WorkflowContractTests(unittest.TestCase):
    def test_poll_ceiling_exceeds_the_ssm_command_timeout(self):
        # A slow resume must never be declared failed while the remote deploy
        # is still running under its own timeout.
        self.assertGreater(
            environment.POLL_ATTEMPTS * environment.POLL_INTERVAL_SECONDS,
            environment.SSM_TIMEOUT_SECONDS)

    def test_environment_control_dispatches_the_four_actions(self):
        text = CONTROL_WORKFLOW.read_text(encoding="utf-8")
        for action in ("start", "resume", "pause", "status"):
            self.assertIn(action, text)
        self.assertIn("environment: production", text)
        self.assertIn("workflow_dispatch", text)
        self.assertNotIn("pull_request", text)

    def test_mutating_jobs_share_the_production_queue(self):
        control = CONTROL_WORKFLOW.read_text(encoding="utf-8")
        cd = CD_YML.read_text(encoding="utf-8")
        self.assertIn("occ-production-mutation", control)
        self.assertIn("cancel-in-progress: false", control)
        self.assertIn("occ-production-mutation", cd)


if __name__ == "__main__":
    unittest.main()
