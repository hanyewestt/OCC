"""Host deployment and recovery contract fixtures.

Drives scripts/cd/deploy.sh against a sandbox host root with stub docker,
curl, sha256sum-verified bundles and a fake S3 writer, asserting the mutation
order, the aggregate setup gate, state advancement and every recovery path.
"""
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEPLOY = ROOT / "scripts" / "cd" / "deploy.sh"
GATE = ROOT / "scripts" / "cd" / "traffic-gate.sh"
SH = shutil.which("sh") or "C:/Program Files/Git/bin/bash.exe"

REVISION_A = "aa" * 20
REVISION_B = "bb" * 20
ECR = "123456789012.dkr.ecr.us-east-1.amazonaws.com/occ"


def digest(component: str, seed: str = "11") -> str:
    return f"{ECR}/{component}@sha256:{seed * 32}"


class DeploySandbox:
    """A fake host root with controllable docker, curl and S3 behavior."""

    def __init__(self) -> None:
        self.root = Path(tempfile.mkdtemp(prefix="occ-deploy-"))
        self.add_cleanup = None
        self.home = self.root / "opt" / "occ"
        self.state = self.home / "state"
        self.releases = self.home / "releases"
        self.runtime = self.home / "runtime"
        self.bin = self.root / "bin"
        for directory in (self.state, self.releases, self.runtime, self.bin):
            directory.mkdir(parents=True)
        (self.root / "data" / "postgresql").mkdir(parents=True)
        (self.root / "data" / "postgresql" / ".occ-data-marker").write_text("durable")
        self.calls = self.root / "calls.log"
        self.calls.write_text("", encoding="utf-8")
        self.docker_env: dict[str, str] = {}
        self._write_stubs()

    def cleanup(self) -> None:
        import shutil

        for child in self.bin.iterdir():
            child.chmod(child.stat().st_mode | stat.S_IEXEC)
        shutil.rmtree(self.root, ignore_errors=True)

    def _stub(self, name: str, body: str) -> None:
        path = self.bin / name
        path.write_text("#!/bin/sh\n" + body, newline="\n")
        path.chmod(0o755)

    def _write_stubs(self) -> None:
        calls = self.calls.as_posix()
        self._stub("docker", f'''
printf '%s\\n' "docker $*" >> {calls}
case " $* " in
  *" compose "*)
    case "$*" in
      *"--no-build"*) : ;;
      *" up "*|*" build "*) echo "compose mutation without --no-build refused" >&2; exit 64 ;;
    esac
    case "$*" in
      *" down --volumes"*) echo "down --volumes refused" >&2; exit 64 ;;
    esac
    case "$*" in
      *" run "*db-setup*)
        if [ -n "${{OCC_FAKE_DB_SETUP_EXIT:-}}" ]; then exit "$OCC_FAKE_DB_SETUP_EXIT"; fi
        exit 0 ;;
      *" ps "*q*) echo "container-id" ;;
      *" exec "*)
        case "$*" in
          *ingestion*|*disruption*|*planning*|*postgres*)
            if [ "${{OCC_FAKE_EXEC_FAIL:-}}" = "yes" ]; then exit 1; fi
            exit 0 ;;
        esac
        exit 0 ;;
    esac
    exit 0 ;;
esac
exit 0
''')
        self._stub("curl", f'''
printf '%s\\n' "curl $*" >> {calls}
case "$*" in
  *_occ_gate_readiness*) code="${{OCC_FAKE_GATE_READINESS_CODE:-204}}" ;;
  *_occ_edge_health*) code="${{OCC_FAKE_EDGE_HEALTH_CODE:-503}}" ;;
  *) code="${{OCC_FAKE_CURL_CODE:-200}}" ;;
esac
if [ "${{OCC_CURL_PHASE:-}}" = "public" ] && [ -n "${{OCC_FAKE_PUBLIC_SMOKE_CODE:-}}" ]; then
    code="$OCC_FAKE_PUBLIC_SMOKE_CODE"
fi
echo "$code"
''')
        self._stub("df", f'''
printf '%s\\n' "df $*" >> {calls}
echo "Filesystem 1024-blocks Used Available Capacity Mounted"
echo "stub 1024 512 ${{OCC_FAKE_DF_FREE_MB:-10240}} 5% /data"
''')
        self._stub("aws", f'''
printf '%s\\n' "aws $*" >> {calls}
case "$*" in
  *"s3 cp"*)
    if [ "${{OCC_FAKE_S3_FAIL:-}}" = "yes" ]; then exit 1; fi
    ;;
esac
exit 0
''')

    def read_calls(self) -> list[str]:
        return [line for line in self.calls.read_text(encoding="utf-8").splitlines() if line]

    def write_release(self, release_id: str, revision: str,
                      compatibility: str = "unknown", evidence: dict | None = None,
                      baseline: str | None = None) -> Path:
        directory = self.releases / release_id
        (directory / "bundle").mkdir(parents=True, exist_ok=True)
        manifest = {
            "schemaVersion": 2,
            "releaseId": release_id,
            "revision": revision,
            "baselineRevision": baseline,
            "images": {component: digest(component, seed="ab")
                       for component in ("ingestion", "disruption", "planning", "db-setup", "web")},
            "inputHashes": {component: f"hash-{release_id}-{component}"
                            for component in ("ingestion", "disruption", "planning", "db-setup", "web")},
            "builtComponents": [],
            "dbSetupInputHash": f"hash-{release_id}-db-setup",
            "migrationCompatibility": compatibility,
        }
        if evidence is not None:
            manifest["compatibilityEvidence"] = evidence
        (directory / "bundle" / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        (directory / "bundle" / "compose.yaml").write_text("services: {}\n", encoding="utf-8")
        (directory / "bundle" / "compose.images.yaml").write_text("services: {}\n", encoding="utf-8")
        (directory / "bundle" / "compose.cloudfront.yaml").write_text("services: {}\n", encoding="utf-8")
        checksums = []
        import hashlib
        for name in ("manifest.json", "compose.yaml", "compose.images.yaml", "compose.cloudfront.yaml"):
            blob = (directory / "bundle" / name).read_bytes()
            checksums.append(f"{hashlib.sha256(blob).hexdigest()}  {name}")
        (directory / "bundle" / "checksums.txt").write_text(
            "\n".join(checksums) + "\n", encoding="utf-8", newline="\n")
        return directory

    def reseal(self, release: Path) -> None:
        """Recompute checksums after intentionally editing a bundle."""
        import hashlib
        directory = release / "bundle"
        checksums = []
        for name in ("manifest.json", "compose.yaml", "compose.images.yaml", "compose.cloudfront.yaml"):
            blob = (directory / name).read_bytes()
            checksums.append(f"{hashlib.sha256(blob).hexdigest()}  {name}")
        (directory / "checksums.txt").write_text(
            "\n".join(checksums) + "\n", encoding="utf-8", newline="\n")

    @staticmethod
    def _to_msys(entry: str) -> str:
        entry = entry.strip()
        match = re.match(r"^([A-Za-z]):[/\\](.*)$", entry)
        if match:
            return "/" + match.group(1).lower() + "/" + match.group(2).replace("\\", "/")
        return entry

    def _msys_env_path(self) -> str:
        # Git Bash rebuilds its PATH with MSYS defaults first; an explicit
        # colon-separated MSYS-form PATH puts the stubs ahead of real tools.
        entries = [self._to_msys(self.bin.as_posix())]
        for entry in os.environ.get("PATH", "").split(os.pathsep):
            converted = self._to_msys(entry)
            if converted:
                entries.append(converted)
        return ":".join(entries)

    def _shell_env(self) -> dict:
        """Environment for sh subprocesses with the stub bin forced first.

        Git Bash prepends /mingw64/bin and /usr/bin to any inherited PATH, so
        the real curl/df would shadow the stubs; BASH_ENV re-exports the PATH
        inside the script's shell instead.
        """
        env = dict(os.environ)
        env["PATH"] = self._msys_env_path()
        prepend = self.bin / "occ-path-prepend.sh"
        prepend.write_text(
            f'export PATH="{self._to_msys(self.bin.as_posix())}:$PATH"\n',
            encoding="utf-8", newline="\n")
        env["BASH_ENV"] = prepend.as_posix()
        return env

    def deploy(self, release: Path, action: str = "apply") -> subprocess.CompletedProcess:
        env = self._shell_env()
        env.update({
            "OCC_HOME": self.home.as_posix(),
            "OCC_DATA_MOUNT": (self.root / "data" / "postgresql").as_posix(),
            "OCC_DEPLOYMENT_ENV": (self.runtime / "deployment.env").as_posix(),
            "OCC_MIN_FREE_MB": "1",
            "OCC_RELEASE_BUCKET": "occ-test-releases",
            "OCC_GATE_TOKEN": "synthetic-gate-token",
            "OCC_GATE_DIR": (self.runtime / "nginx-gate").as_posix(),
        })
        env.update(self.docker_env)
        return subprocess.run(
            [SH, DEPLOY.as_posix(), action, release.as_posix()],
            capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)

    def gate_log(self) -> str:
        path = self.runtime / "nginx-gate" / "gate.log"
        return path.read_text(encoding="utf-8") if path.exists() else ""

    def docker_calls(self) -> list[str]:
        return [line for line in self.read_calls() if line.startswith("docker")]

    def write_deployment_env(self) -> None:
        self.runtime.mkdir(parents=True, exist_ok=True)
        gate_dir = self.runtime / "nginx-gate"
        gate_dir.mkdir(parents=True, exist_ok=True)
        (gate_dir / "gate-token").write_text("synthetic-gate-token", encoding="utf-8")
        (self.runtime / "deployment.env").write_text(
            "POSTGRES_PASSWORD=synthetic\nPOSTGRES_USER=occ_admin\nPOSTGRES_DB=occ\n",
            encoding="utf-8")


class DeployTestBase(unittest.TestCase):
    def setUp(self) -> None:
        self.sandbox = DeploySandbox()
        self.addCleanup(self.sandbox.cleanup)
        self.sandbox.write_deployment_env()

    def assert_no_forbidden_mutations(self) -> None:
        joined = "\n".join(self.sandbox.read_calls())
        self.assertNotIn("compose build", joined)
        self.assertNotIn("down --volumes", joined)


class ApplyTests(DeployTestBase):
    def test_first_install_runs_aggregate_setup_with_gate_closed(self):
        release = self.sandbox.write_release("rel-a-1", REVISION_A)
        result = self.sandbox.deploy(release)
        self.assertEqual(result.returncode, 0, result.stderr)
        joined = "\n".join(self.sandbox.read_calls())
        # The gate closes before any writer stops and the setup runs once.
        self.assertIn("gate close", self.sandbox.gate_log())
        self.assertIn("run --rm --no-deps db-setup", joined)
        self.assertIn("gate open", self.sandbox.gate_log())
        self.assert_no_forbidden_mutations()
        active = json.loads((self.sandbox.state / "active-release.json").read_text(encoding="utf-8"))
        self.assertEqual(active["releaseId"], "rel-a-1")
        applied = json.loads((self.sandbox.state / "applied-setup.json").read_text(encoding="utf-8"))
        self.assertEqual(applied["dbSetupInputHash"], "hash-rel-a-1-db-setup")
        self.assertTrue(applied["fingerprint"])

    def test_unchanged_setup_skips_setup_and_writer_shutdown(self):
        first = self.sandbox.write_release("rel-a-1", REVISION_A)
        self.assertEqual(self.sandbox.deploy(first).returncode, 0)
        calls_before = len(self.sandbox.read_calls())
        gate_before = self.sandbox.gate_log()
        # A web-only release: same db-setup input hash, different web image.
        second = self.sandbox.write_release("rel-b-1", REVISION_B)
        manifest = json.loads((second / "bundle" / "manifest.json").read_text(encoding="utf-8"))
        manifest["inputHashes"]["db-setup"] = "hash-rel-a-1-db-setup"
        manifest["dbSetupInputHash"] = "hash-rel-a-1-db-setup"
        (second / "bundle" / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        self.sandbox.reseal(second)
        result = self.sandbox.deploy(second)
        self.assertEqual(result.returncode, 0, result.stderr)
        joined = "\n".join(self.sandbox.read_calls()[calls_before:])
        self.assertNotIn("run --rm --no-deps db-setup", joined)
        self.assertEqual(self.sandbox.gate_log(), gate_before)
        self.assertNotIn(" stop nginx ingestion", joined)
        active = json.loads((self.sandbox.state / "active-release.json").read_text(encoding="utf-8"))
        self.assertEqual(active["releaseId"], "rel-b-1")

    def test_changed_setup_closes_gate_and_stops_writers(self):
        first = self.sandbox.write_release("rel-a-1", REVISION_A)
        self.assertEqual(self.sandbox.deploy(first).returncode, 0)
        second = self.sandbox.write_release("rel-b-1", REVISION_B)
        result = self.sandbox.deploy(second)
        self.assertEqual(result.returncode, 0, result.stderr)
        joined = "\n".join(self.sandbox.read_calls())
        self.assertIn("gate close", self.sandbox.gate_log())
        self.assertIn(" stop nginx ingestion disruption planning", joined)
        self.assertIn("run --rm --no-deps db-setup", joined)

    def test_corrupt_bundle_is_refused_before_any_mutation(self):
        release = self.sandbox.write_release("rel-a-1", REVISION_A)
        (release / "bundle" / "compose.yaml").write_text("tampered\n", encoding="utf-8")
        result = self.sandbox.deploy(release)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.sandbox.docker_calls(), [])
        self.assertFalse((self.sandbox.state / "active-release.json").exists())

    def test_bad_digest_is_refused(self):
        release = self.sandbox.write_release("rel-a-1", REVISION_A)
        manifest = json.loads((release / "bundle" / "manifest.json").read_text(encoding="utf-8"))
        manifest["images"]["web"] = "occ-web:latest"
        (release / "bundle" / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        self.sandbox.reseal(release)
        result = self.sandbox.deploy(release)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("digest", result.stderr.lower())
        self.assertEqual(self.sandbox.docker_calls(), [])

    def test_path_traversal_release_id_is_refused(self):
        release = self.sandbox.write_release("../escape", REVISION_A)
        result = self.sandbox.deploy(release)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("releases", result.stderr.lower())
        self.assertEqual(self.sandbox.docker_calls(), [])

    def test_missing_durable_mount_blocks_start(self):
        release = self.sandbox.write_release("rel-a-1", REVISION_A)
        (self.sandbox.root / "data" / "postgresql" / ".occ-data-marker").unlink()
        result = self.sandbox.deploy(release)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("mount", result.stderr.lower())
        self.assertEqual(self.sandbox.docker_calls(), [])

    def test_disk_full_blocks_mutation(self):
        release = self.sandbox.write_release("rel-a-1", REVISION_A)
        self.sandbox.docker_env["OCC_FAKE_DF_FREE_MB"] = "0"
        result = self.sandbox.deploy(release)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("space", result.stderr.lower())
        self.assertEqual(self.sandbox.docker_calls(), [])

    def test_setup_failure_in_second_schema_keeps_gate_closed_and_state_stale(self):
        release = self.sandbox.write_release("rel-a-1", REVISION_A)
        self.sandbox.docker_env["OCC_FAKE_DB_SETUP_EXIT"] = "2"
        result = self.sandbox.deploy(release)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.sandbox.state / "active-release.json").exists())
        self.assertFalse((self.sandbox.state / "applied-setup.json").exists())
        self.assertIn("gate close", self.sandbox.gate_log())
        self.assertNotIn("gate open", self.sandbox.gate_log())
        self.assert_no_forbidden_mutations()

    def test_unhealthy_readiness_blocks_state_advance_and_reopens_nothing(self):
        release = self.sandbox.write_release("rel-a-1", REVISION_A)
        self.sandbox.docker_env["OCC_FAKE_EXEC_FAIL"] = "yes"
        result = self.sandbox.deploy(release)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.sandbox.state / "active-release.json").exists())
        self.assertIn("gate close", self.sandbox.gate_log())
        self.assertNotIn("gate open", self.sandbox.gate_log())

    def test_failed_public_smoke_does_not_advance_state(self):
        release = self.sandbox.write_release("rel-a-1", REVISION_A)
        self.sandbox.docker_env["OCC_FAKE_PUBLIC_SMOKE_CODE"] = "500"
        result = self.sandbox.deploy(release)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.sandbox.state / "active-release.json").exists())

    def test_pointer_write_failure_is_recoverable_without_repeating_setup(self):
        release = self.sandbox.write_release("rel-a-1", REVISION_A)
        self.sandbox.docker_env["OCC_FAKE_S3_FAIL"] = "yes"
        result = self.sandbox.deploy(release)
        self.assertNotEqual(result.returncode, 0)
        # The host advanced its own state but the remote pointer write failed.
        active = json.loads((self.sandbox.state / "active-release.json").read_text(encoding="utf-8"))
        self.assertEqual(active["releaseId"], "rel-a-1")
        self.assertTrue((self.sandbox.state / "pointer-pending.json").exists())
        # The retry reconciles only the pointer: no setup, no up, no gate churn.
        self.sandbox.docker_env["OCC_FAKE_S3_FAIL"] = ""
        calls_before = len(self.sandbox.read_calls())
        gate_before = self.sandbox.gate_log()
        retry = self.sandbox.deploy(release)
        self.assertEqual(retry.returncode, 0, retry.stderr)
        new_calls = "\n".join(self.sandbox.read_calls()[calls_before:])
        self.assertNotIn("run --rm --no-deps db-setup", new_calls)
        self.assertNotIn("up -d", new_calls)
        self.assertEqual(self.sandbox.gate_log(), gate_before)
        self.assertIn("s3 cp", new_calls)
        self.assertFalse((self.sandbox.state / "pointer-pending.json").exists())

    def test_runner_loss_leaves_ambiguous_state_blocked(self):
        release = self.sandbox.write_release("rel-a-1", REVISION_A)
        operation = self.sandbox.state / "outstanding-operation.json"
        operation.parent.mkdir(parents=True, exist_ok=True)
        operation.write_text(json.dumps({
            "operationId": "apply-rel-a-1-lost", "phase": "ssm-dispatched",
            "commandId": None}), encoding="utf-8")
        result = self.sandbox.deploy(release)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("outstanding", result.stderr.lower())
        self.assertEqual(self.sandbox.docker_calls(), [])

    def test_concurrent_apply_is_locked_out(self):
        release = self.sandbox.write_release("rel-a-1", REVISION_A)
        lock = self.sandbox.state / "deploy.lock"
        holder = subprocess.Popen(
            [SH, "-c",
             f'mkdir "{lock.as_posix()}" && echo $$ > "{lock.as_posix()}/owner" && sleep 30'],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(holder.kill)
        try:
            result = self.sandbox.deploy(release)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("lock", result.stderr.lower())
        finally:
            holder.kill()


class RollbackTests(DeployTestBase):
    def deploy_a(self) -> Path:
        release = self.sandbox.write_release("rel-a-1", REVISION_A)
        result = self.sandbox.deploy(release)
        self.assertEqual(result.returncode, 0, result.stderr)
        return release

    def test_compatible_rollback_restores_previous_images_without_setup(self):
        self.deploy_a()
        second = self.sandbox.write_release(
            "rel-b-1", REVISION_B, compatibility="backward-compatible",
            evidence={"rollbackReleaseId": "rel-a-1",
                      "setupFingerprint": "hash-rel-b-1-db-setup",
                      "migrations": []},
            baseline=REVISION_A)
        self.assertEqual(self.sandbox.deploy(second).returncode, 0)
        applied = json.loads((self.sandbox.state / "applied-setup.json").read_text(encoding="utf-8"))
        first = self.sandbox.write_release(
            "rel-a-1", REVISION_A, compatibility="backward-compatible",
            evidence={"rollbackReleaseId": "rel-b-1",
                      "setupFingerprint": applied["fingerprint"],
                      "migrations": []})
        calls_before = len(self.sandbox.read_calls())
        result = self.sandbox.deploy(first, action="rollback")
        self.assertEqual(result.returncode, 0, result.stderr)
        new_calls = "\n".join(self.sandbox.read_calls()[calls_before:])
        self.assertNotIn("db-setup", new_calls)
        self.assertNotIn("down --volumes", new_calls)
        active = json.loads((self.sandbox.state / "active-release.json").read_text(encoding="utf-8"))
        self.assertEqual(active["releaseId"], "rel-a-1")

    def test_rollback_without_previous_release_is_refused(self):
        release = self.sandbox.write_release("rel-a-1", REVISION_A)
        result = self.sandbox.deploy(release, action="rollback")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("previous", result.stderr.lower())
        self.assertEqual(self.sandbox.docker_calls(), [])

    def test_incompatible_rollback_is_refused(self):
        self.deploy_a()
        first = self.sandbox.write_release("rel-a-1", REVISION_A)
        calls_before = len(self.sandbox.read_calls())
        result = self.sandbox.deploy(first, action="rollback")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("compatib", result.stderr.lower())
        joined = "\n".join(self.sandbox.read_calls()[calls_before:])
        self.assertNotIn("db-setup", joined)
        self.assertNotIn("stop nginx", joined)


if __name__ == "__main__":
    unittest.main()
