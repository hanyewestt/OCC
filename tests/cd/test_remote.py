"""Remote deployment driver and pipeline contract fixtures.

Covers scripts/cd/deploy_remote.py against a stubbed AWS CLI (operation
intent, SSM dispatch, eventual consistency, runner-loss recovery, terminal
statuses, pointer CAS and paused deferral) and the workflow YAML contracts
that isolate production deployment to trusted main-branch runs.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REMOTE = ROOT / "scripts" / "cd" / "deploy_remote.py"
SH = shutil.which("sh") or "C:/Program Files/Git/bin/bash.exe"

VERIFY_YML = ROOT / ".github" / "workflows" / "verify.yml"
CD_YML = ROOT / ".github" / "workflows" / "cd.yml"
CI_YML = ROOT / ".github" / "workflows" / "ci.yml"


class AwsSandbox:
    def __init__(self) -> None:
        self.root = Path(tempfile.mkdtemp(prefix="occ-remote-"))
        self.bin = self.root / "bin"
        self.bin.mkdir(parents=True)
        self.state = self.root / "state"
        self.state.mkdir()
        self.calls = self.root / "aws-calls.log"
        self.calls.write_text("", encoding="utf-8")
        self._write_stub()

    def cleanup(self) -> None:
        shutil.rmtree(self.root, ignore_errors=True)

    def _write_stub(self) -> None:
        calls = self.calls.as_posix()
        script = f'''#!/bin/sh
printf '%s\\n' "aws $*" >> {calls}
# Identify the first two non-flag arguments as the AWS subcommand so that
# parameter payloads containing command-like text cannot misroute dispatch.
sub1=""
sub2=""
prev=""
for arg in "$@"; do
  case "$prev" in
    --region|--profile|--output|--endpoint-url) prev="$arg"; continue ;;
  esac
  case "$arg" in
    --*) ;;
    *)
      if [ -z "$sub1" ]; then sub1="$arg"
      elif [ -z "$sub2" ]; then sub2="$arg"
      fi ;;
  esac
  prev="$arg"
done
case "$sub1 $sub2" in
  "s3 cp")
    # A write has the s3:// destination last and is recorded only; a read
    # resolves by its s3:// source path inside the sandbox state.
    last=""
    for arg in "$@"; do last="$arg"; done
    case "$last" in
      s3://*) exit 0 ;;
    esac
    for arg in "$@"; do
      case "$arg" in
        s3://*state/control.json)
          cat "${{OCC_FAKE_STATE_DIR:-}}/control.json" 2>/dev/null; exit 0 ;;
        s3://*state/last-deployed.json)
          if [ -f "${{OCC_FAKE_STATE_DIR:-}}/last-deployed.json" ]; then
            cat "${{OCC_FAKE_STATE_DIR:-}}/last-deployed.json"; exit 0
          fi
          exit 1 ;;
        -) if [ "${{OCC_FAKE_S3_READ_BODY:-}}" = "none" ]; then exit 1; fi
           cat "${{OCC_FAKE_S3_READ_FILE:-/dev/null}}" 2>/dev/null; exit 0 ;;
        s3://*) ;;
      esac
    done
    exit 0 ;;
  "ssm send-command")
    if [ "${{OCC_FAKE_SEND_LOST:-}}" = "yes" ]; then
      printf '{{}}'
    else
      printf '{{"Command":{{"CommandId":"%s"}}}}' "${{OCC_FAKE_COMMAND_ID:-c-1234}}"
    fi
    exit 0 ;;
  "ssm list-commands")
    if [ -n "${{OCC_FAKE_LIST_FILE:-}}" ]; then cat "$OCC_FAKE_LIST_FILE"; fi
    exit 0 ;;
  "ssm get-command-invocation")
    seq_file="${{OCC_FAKE_STATUS_FILE:-}}"
    if [ -n "$seq_file" ]; then
      line=$(( $(cat "$seq_file") + 1 ))
      echo "$line" > "$seq_file"
      status=$(sed -n "${{line}}p" "${{seq_file}}.statuses" 2>/dev/null)
      if [ -z "$status" ]; then status=$(tail -n 1 "${{seq_file}}.statuses"); fi
      case "$status" in
        InvocationDoesNotExist) printf 'error: InvocationDoesNotExist' >&2; exit 1 ;;
        *) printf '{{"Status":"%s"}}' "$status" ;;
      esac
    else
      printf '{{"Status":"%s"}}' "${{OCC_FAKE_STATUS:-Success}}"
    fi
    exit 0 ;;
esac
exit 0
'''
        stub = self.bin / "aws-stub.sh"
        stub.write_text(script, newline="\n")
        stub.chmod(0o755)
        # Windows Python resolves PATHEXT entries; the .cmd shim delegates to
        # the shell stub so both the shim and the logic stay in one place.
        shim = self.bin / "aws.cmd"
        shim.write_text(
            '@"C:\\Program Files\\Git\\bin\\bash.exe" "%~dp0aws-stub.sh" %*\n',
            encoding="utf-8", newline="\n")

    def remote(self, *args: str, extra_env: dict | None = None) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        # A Windows-form PATH with the stub bin first: the Python child
        # resolves the aws.cmd shim through PATHEXT.
        env["PATH"] = self.bin.as_posix() + os.pathsep + os.environ.get("PATH", "")
        env["OCC_AWS_LOG"] = self.calls.as_posix()
        env.update(extra_env or {})
        return subprocess.run(
            [sys.executable, REMOTE.as_posix(), *args],
            capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)

    @staticmethod
    def _msys(entry: str) -> str:
        entry = entry.strip()
        match = re.match(r"^([A-Za-z]):[/\\](.*)$", entry)
        if match:
            return "/" + match.group(1).lower() + "/" + match.group(2).replace("\\", "/")
        return entry

    def aws_calls(self) -> list[str]:
        return self.calls.read_text(encoding="utf-8").splitlines()

    def ssm_calls(self) -> list[str]:
        return [line for line in self.aws_calls() if " ssm " in line]

    def write_control(self, mode: str) -> None:
        (self.state / "control.json").write_text(
            json.dumps({"desiredMode": mode}), encoding="utf-8")


class DeployRemoteTests(unittest.TestCase):
    def setUp(self) -> None:
        self.sandbox = AwsSandbox()
        self.addCleanup(self.sandbox.cleanup)
        # The published manifest supplies the revision the deployed-pointer
        # CAS validates; the dummy aws stub serves it through the read file.
        self.published = self.sandbox.state / "published.json"
        self.published.write_text(json.dumps({
            "releaseId": "rel-1", "revision": "a" * 40, "sequence": 6,
        }), encoding="utf-8")

    def deploy(self, extra_env: dict | None = None) -> subprocess.CompletedProcess:
        merged = {"OCC_FAKE_STATE_DIR": self.sandbox.state.as_posix(),
                  "OCC_FAKE_S3_READ_FILE": self.published.as_posix()}
        merged.update(extra_env or {})
        return self.sandbox.remote(
            "deploy", "--region", "us-east-1", "--instance-id", "i-123",
            "--bucket", "occ-test", "--release-id", "rel-1",
            "--operation-id", "op-1", "--sequence", "7",
            extra_env=merged)

    def test_paused_mode_defers_without_remote_mutation(self):
        self.sandbox.write_control("paused")
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("deferred", result.stdout.lower())
        self.assertEqual(self.sandbox.ssm_calls(), [])
        joined = "\n".join(self.sandbox.aws_calls())
        self.assertNotIn("operations/op-1.json", joined)

    def test_running_mode_persists_intent_before_send_command(self):
        self.sandbox.write_control("running")
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.sandbox.aws_calls()
        intent_position = min(
            (position for position, line in enumerate(calls)
             if "operations/op-1.json" in line and " s3 cp " in line),
            default=-1)
        send_position = min(
            (position for position, line in enumerate(calls)
             if " ssm send-command " in line),
            default=-1)
        self.assertGreaterEqual(send_position, 0)
        self.assertLess(intent_position, send_position)
        joined = "\n".join(calls)
        self.assertIn("occ-operation:op-1", joined)

    def test_terminal_success_advances_pointer_with_sequence(self):
        self.sandbox.write_control("running")
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        joined = "\n".join(self.sandbox.aws_calls())
        self.assertIn("last-deployed.json", joined)
        # The revision comes from the deployed release's own manifest.
        self.assertIn("releases/rel-1.json", joined)
        self.assertIn('"sequence": 7', result.stdout)

    def test_host_advanced_pointer_is_advanced_idempotently(self):
        self.sandbox.write_control("running")
        revision = "a" * 40
        # The host already recorded this exact release after its own verified
        # checks; the driver must add the sequence, not reject the identical
        # pointer as an overwrite.
        (self.sandbox.state / "last-deployed.json").write_text(json.dumps({
            "releaseId": "rel-1", "revision": revision,
            "releaseKey": "releases/rel-1", "version": "-", "checksum": "-",
            "deployedAt": "2026-11-10T00:00:00Z"}), encoding="utf-8")
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('"sequence": 7', result.stdout)

    def test_failed_invocation_fails_the_job(self):
        self.sandbox.write_control("running")
        result = self.deploy(extra_env=self._status_env(["Failed"]))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Failed", result.stderr + result.stdout)

    def test_eventual_consistency_is_retried_until_terminal(self):
        self.sandbox.write_control("running")
        result = self.deploy(extra_env=self._status_env(
            ["InvocationDoesNotExist", "InvocationDoesNotExist", "Success"]))
        self.assertEqual(result.returncode, 0, result.stderr)

    @staticmethod
    def _status_env(statuses: list[str]) -> dict:
        counter = Path(tempfile.mkstemp(suffix=".counter")[1])
        statuses_file = Path(str(counter) + ".statuses")
        statuses_file.write_text("\n".join(statuses) + "\n", encoding="utf-8", newline="\n")
        counter.write_text("0", encoding="utf-8")
        return {"OCC_FAKE_STATUS_FILE": counter.as_posix()}

    def test_runner_loss_recovers_the_accepted_invocation(self):
        self.sandbox.write_control("running")
        listing = self.sandbox.state / "commands.json"
        listing.write_text(json.dumps({
            "Commands": [{"CommandId": "c-recovered", "Comment": "occ-operation:op-1",
                          "Status": "Success"}]}), encoding="utf-8")
        result = self.sandbox.remote(
            "recover", "--region", "us-east-1", "--instance-id", "i-123",
            "--bucket", "occ-test", "--operation-id", "op-1", "--sequence", "7",
            extra_env={"OCC_FAKE_LIST_FILE": listing.as_posix(),
                       "OCC_FAKE_S3_READ_FILE": self.published.as_posix()})
        self.assertEqual(result.returncode, 0, result.stderr)
        joined = "\n".join(self.sandbox.aws_calls())
        self.assertIn("ssm list-commands", joined)
        self.assertIn("occ-operation:op-1", joined)

    def test_ambiguous_outstanding_commands_block(self):
        self.sandbox.write_control("running")
        listing = self.sandbox.state / "commands.json"
        listing.write_text(json.dumps({
            "Commands": [{"CommandId": "c-1", "Comment": "occ-operation:op-1",
                          "Status": "Pending"},
                         {"CommandId": "c-2", "Comment": "occ-operation:op-1",
                          "Status": "Pending"}]}), encoding="utf-8")
        result = self.sandbox.remote(
            "recover", "--region", "us-east-1", "--instance-id", "i-123",
            "--bucket", "occ-test", "--operation-id", "op-1", "--sequence", "7",
            extra_env={"OCC_FAKE_LIST_FILE": listing.as_posix()})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ambiguous", result.stderr.lower())


class PipelineContractTests(unittest.TestCase):
    def test_verify_yml_runs_on_main_and_invokes_reusable_verification(self):
        text = VERIFY_YML.read_text(encoding="utf-8")
        self.assertIn("branches: [main]", text)
        self.assertIn("uses: ./.github/workflows/ci.yml", text)

    def test_ci_yml_is_reusable_without_a_main_push_trigger(self):
        text = CI_YML.read_text(encoding="utf-8")
        self.assertIn("workflow_call:", text)
        self.assertNotRegex(text, r"on:\s*\n\s*push:")

    def test_cd_yml_runs_only_after_main_verification_completes(self):
        text = CD_YML.read_text(encoding="utf-8")
        self.assertIn("workflow_run:", text)
        self.assertIn("Main verification", text)
        self.assertNotIn("pull_request:", text)
        self.assertNotIn("pull_request_target", text)

    def test_production_jobs_use_the_environment_and_oidc_roles(self):
        text = CD_YML.read_text(encoding="utf-8")
        self.assertIn("environment: production", text)
        self.assertIn("OCC_PUBLISH_ROLE_ARN", text)
        self.assertIn("OCC_DEPLOY_ROLE_ARN", text)
        self.assertNotIn("pull_request_target", text)

    def test_mutation_jobs_serialize_without_cancellation(self):
        text = CD_YML.read_text(encoding="utf-8")
        self.assertIn("cancel-in-progress: false", text)

    def test_publish_verifies_ecr_digests_before_the_pointer(self):
        text = CD_YML.read_text(encoding="utf-8")
        describe_position = text.find("ecr describe-images")
        pointer_position = text.rfind("state/latest-published.json")
        self.assertGreater(describe_position, 0)
        # The digest check happens in the release job; the published record
        # is advanced afterwards in the publish job.
        self.assertGreater(text.find("Advance the published manifest"), describe_position)
        self.assertGreater(pointer_position, describe_position)

    def test_docs_record_the_required_check_migration(self):
        testing = (ROOT / "docs" / "TESTING.md").read_text(encoding="utf-8")
        self.assertIn("Main verification", testing)
        self.assertIn("branch protection", testing.lower())

    def test_actions_read_permission_granted_for_artifact_downloads(self):
        for path in (CI_YML, CD_YML,
                     ROOT / ".github" / "workflows" / "system-verification.yml"):
            text = path.read_text(encoding="utf-8")
            self.assertRegex(text, r"actions:\s*read", str(path.name))

    def test_publish_job_advances_the_full_manifest_not_a_bare_pointer(self):
        text = CD_YML.read_text(encoding="utf-8")
        manifest_position = text.find("new-manifest.json")
        self.assertGreater(manifest_position, 0)
        # The full manifest (with inputHashes and images) is built before the
        # published record is written, and the candidate record is that
        # manifest rather than a pointer-only shape.
        self.assertGreater(manifest_position, text.find("release.py manifest"))
        self.assertIn("published-manifest.json", text)
        self.assertGreater(text.find("published-manifest.json"),
                           text.find("release.py manifest"))

    def test_first_release_does_not_pass_an_empty_previous_record(self):
        text = CD_YML.read_text(encoding="utf-8")
        manifest_position = text.find("scripts/cd/release.py manifest")
        self.assertGreater(manifest_position, 0)
        window = text[max(0, manifest_position - 800):manifest_position]
        # The download fallback is `{}` when nothing has been published yet;
        # passing that as --previous makes build_manifest reject the first
        # release for having no revision.
        self.assertIn("previous_args", window)
        self.assertIn('get("revision")', window)

    def test_bootstrap_renders_the_env_path_the_remote_commands_source(self):
        bootstrap = (ROOT / "scripts" / "cd" / "bootstrap-host.sh").read_text(
            encoding="utf-8")
        self.assertIn('"$ENV_DIR/env.rendered"', bootstrap)
        # Rendered lines are shell-sourceable `export KEY='value'`; Compose's
        # --env-file needs the prefix stripped, which deploy.sh must do.
        self.assertIn("export {key}=", bootstrap)
        deploy = (ROOT / "scripts" / "cd" / "deploy.sh").read_text(encoding="utf-8")
        self.assertIn("s/^export", deploy)
        for name in ("deploy_remote.py", "environment.py"):
            text = (ROOT / "scripts" / "cd" / name).read_text(encoding="utf-8")
            self.assertIn("OCC_DEPLOYMENT_ENV=/opt/occ/env.rendered", text, name)

    def test_release_bundle_carries_the_cloudfront_server_block(self):
        text = CD_YML.read_text(encoding="utf-8")
        # The web image bakes only the shared routes include; the overlay
        # mounts the CloudFront server block from the bundle root.
        self.assertIn("infra/nginx/cloudfront.conf.template", text)
        self.assertIn("release-bundle/infra/nginx", text)
        # The retained-volume bind mount ships with the bundle too.
        self.assertIn("infra/compose.host-data.yaml", text)

    def test_release_bundle_uploads_under_the_bundle_prefix(self):
        text = CD_YML.read_text(encoding="utf-8")
        # The host runs <release>/bundle/scripts/deploy.sh and validates
        # <release>/bundle/manifest.json.
        self.assertIn("releases/$release_id/bundle/", text)

    def test_first_release_plan_uses_a_conservative_root_commit_base(self):
        text = CD_YML.read_text(encoding="utf-8")
        plan_position = text.find('scripts/cd/release.py "${args[@]}"')
        self.assertGreater(plan_position, 0)
        window = text[max(0, plan_position - 900):plan_position]
        # Without a published record the planner still needs a baseline.
        self.assertIn("--base", window)
        self.assertIn("rev-list --max-parents=0", window)

    def test_recover_subcommand_is_documented(self):
        deployment = (ROOT / "docs" / "DEPLOYMENT.md").read_text(encoding="utf-8")
        self.assertIn("recover", deployment.lower())


if __name__ == "__main__":
    unittest.main()
