"""Remote CLI/selection boundaries and workflow production contracts."""
import json
import re
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/cd"))
import state
import deploy_remote as remote
from cloud_fixtures import CloudFixture
VERIFY_YML = ROOT / ".github/workflows/verify.yml"
CD_YML = ROOT / ".github/workflows/cd.yml"
CI_YML = ROOT / ".github/workflows/ci.yml"


class DeployRemoteTests(unittest.TestCase):
    def setUp(self):
        self.cloud = CloudFixture()
        self.addCleanup(patch.stopall)
        patch.object(state, "read_json", side_effect=self.cloud.read).start()
        patch.object(state, "compare_and_set_json", side_effect=self.cloud.put).start()
        patch.object(remote, "aws", side_effect=self.cloud.aws).start()
        patch.object(remote.release, "is_ancestor", side_effect=lambda repo,a,b: b == "origin/main" or a <= b).start()
        patch.object(remote.time, "sleep").start()
        self.reference = self.cloud.publish()
        self.cloud.seed("state/control.json", {"desiredMode": "running"})

    def test_absent_control_defers_until_explicit_resume(self):
        self.cloud.objects.pop("state/control.json")
        self.assertEqual(remote.run_deploy("us-east-1", "i-test", "bucket", "rel-b", "deploy-1", 7), 0)
        self.assertEqual(self.cloud.commands, [])

    def test_paused_mode_defers_without_remote_mutation(self):
        self.cloud.seed("state/control.json", {"desiredMode":"paused"})
        self.assertEqual(remote.run_deploy("us-east-1","i-test","bucket","stale-hint","deploy-1",7),0)
        self.assertEqual(self.cloud.commands, [])

    def test_stale_caller_selects_latest_eligible_release(self):
        self.assertEqual(remote.run_deploy("us-east-1","i-test","bucket","old-a","deploy-1",7),0)
        pointer = self.cloud.read("", "state/last-deployed.json", "")["value"]
        self.assertEqual(pointer["releaseId"], "rel-b")
        self.assertEqual(pointer["version"], self.reference["version"])
        self.assertEqual(pointer["sequence"], 1)
        self.assertIn("rel-b", str(self.cloud.commands))
        self.assertNotIn("old-a", str(self.cloud.commands))

    def test_completed_operation_retry_does_not_dispatch_another_apply(self):
        remote.run_deploy("us-east-1", "i-test", "bucket", "rel-b", "deploy-1", 7)
        remote.run_deploy("us-east-1", "i-test", "bucket", "rel-b", "deploy-1", 7)
        self.assertEqual(len(self.cloud.commands), 1)

    def test_older_published_revision_cannot_replace_newer_deployed(self):
        self.cloud.seed("state/last-deployed.json", dict(self.reference, revision="c"*40, releaseId="newer-c"))
        with self.assertRaises(remote.RemoteError):
            remote.run_deploy("us-east-1","i-test","bucket","rel-b","deploy-1",100)
        self.assertEqual(self.cloud.commands, [])

    def test_ambiguous_existing_operation_blocks_new_deploy(self):
        self.cloud.seed(remote.ACTIVE_KEY, {"operationId":"lost", "instanceId":"i-test", "action":"apply", "phase":"dispatching"})
        with self.assertRaises(remote.RemoteError):
            remote.run_deploy("us-east-1","i-test","bucket","rel-b","deploy-1",7)
        self.assertEqual(self.cloud.commands, [])

    def test_failed_remote_operation_stays_red_and_does_not_advance_pointer(self):
        self.cloud.command_status = "Failed"
        self.assertEqual(remote.main(["deploy","--region","us-east-1","--instance-id","i-test","--bucket","bucket","--operation-id","deploy-1"]),1)
        self.assertIsNone(self.cloud.read("", "state/last-deployed.json", "")["value"])
        self.assertEqual(self.cloud.read("", remote.ACTIVE_KEY, "")["value"]["result"], "failed")

    def test_download_verifier_pins_manifest_version_before_bundled_execution(self):
        commands = remote.remote_command("bucket", self.reference, "op-1", "us-east-1")
        verifier = next(i for i,c in enumerate(commands) if c.startswith("python3 -c"))
        apply = next(i for i,c in enumerate(commands) if c.startswith("sh "))
        self.assertLess(verifier, apply)
        self.assertIn("--version-id", commands[verifier])
        self.assertIn("bundleInventoryChecksum", commands[verifier])


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
        import yaml
        jobs = yaml.safe_load(CD_YML.read_text())["jobs"]
        self.assertEqual(jobs["scan"]["needs"], "release")
        self.assertEqual(set(jobs["publish"]["needs"]), {"release", "scan"})
        self.assertEqual(jobs["deploy"]["needs"], "publish")
        pipeline = (ROOT/"scripts/cd/pipeline.py").read_text()
        self.assertIn("if not available(reference, region)", pipeline)
        self.assertIn("state.compare_and_set_json", pipeline)

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
        pipeline = (ROOT/"scripts/cd/pipeline.py").read_text()
        self.assertIn("dict(manifest, **reference)", pipeline)
        self.assertIn("state.verified_manifest", pipeline)

    def test_first_release_does_not_pass_an_empty_previous_record(self):
        pipeline = (ROOT/"scripts/cd/pipeline.py").read_text()
        self.assertIn("previous = None", pipeline)
        self.assertNotIn("except state.StateError", pipeline)

    def test_bootstrap_renders_the_env_path_the_remote_commands_source(self):
        bootstrap = (ROOT / "scripts" / "cd" / "bootstrap-host.sh").read_text(
            encoding="utf-8")
        self.assertIn('"$ENV_DIR/env.rendered"', bootstrap)
        # Secrets remain shell-sourceable values; only image pins enter dotenv.
        self.assertIn("export {key}=", bootstrap)
        deploy = (ROOT / "scripts" / "cd" / "deploy.sh").read_text(encoding="utf-8")
        self.assertIn('. "${OCC_DEPLOYMENT_ENV:', deploy)
        self.assertIn("Only image pins need", deploy)
        for name in ("deploy_remote.py",):
            text = (ROOT / "scripts" / "cd" / name).read_text(encoding="utf-8")
            self.assertIn("OCC_DEPLOYMENT_ENV=/opt/occ/env.rendered", text, name)

    def test_release_bundle_carries_the_cloudfront_server_block(self):
        pipeline = (ROOT/"scripts/cd/pipeline.py").read_text()
        self.assertIn("infra/nginx/cloudfront.conf.template", pipeline)
        self.assertIn("infra/compose.host-data.yaml", pipeline)

    def test_release_bundle_uploads_under_the_bundle_prefix(self):
        pipeline = (ROOT/"scripts/cd/pipeline.py").read_text()
        self.assertIn("prefix+'/bundle/'", pipeline)
        self.assertIn("state.write_immutable", pipeline)

    def test_first_release_plan_uses_a_conservative_root_commit_base(self):
        pipeline = (ROOT/"scripts/cd/pipeline.py").read_text()
        self.assertIn("'--max-parents=0'", pipeline)
        self.assertIn("None, True, rebuild=True", pipeline)

    def test_recover_subcommand_is_documented(self):
        deployment = (ROOT / "docs" / "DEPLOYMENT.md").read_text(encoding="utf-8")
        self.assertIn("recover", deployment.lower())


if __name__ == "__main__":
    unittest.main()
