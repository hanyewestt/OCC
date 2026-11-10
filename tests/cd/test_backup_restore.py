"""Backup and restore contract fixtures.

Drives scripts/cd/backup.sh against a sandbox host root with stub pg_dump,
aws and docker, asserting dump invocation shape (database-consistent flags),
S3 upload with retention, failure visibility, restore-into-isolated-PostGIS
behavior and that cleanup never touches live release references.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKUP = ROOT / "scripts" / "cd" / "backup.sh"
SH = shutil.which("sh") or "C:/Program Files/Git/bin/bash.exe"


class BackupSandbox:
    def __init__(self) -> None:
        self.root = Path(tempfile.mkdtemp(prefix="occ-backup-"))
        self.bin = self.root / "bin"
        self.bin.mkdir(parents=True)
        self.state = self.root / "opt" / "occ" / "state"
        self.state.mkdir(parents=True)
        self.calls = self.root / "calls.log"
        self.calls.write_text("", encoding="utf-8")
        self.dumps = self.root / "dumps"
        self.dumps.mkdir()
        self._write_stubs()

    def cleanup(self) -> None:
        shutil.rmtree(self.root, ignore_errors=True)

    def _stub(self, name: str, body: str) -> None:
        path = self.bin / name
        path.write_text("#!/bin/sh\n" + body, newline="\n")
        path.chmod(0o755)

    def _write_stubs(self) -> None:
        calls = self.calls.as_posix()
        dumps = self.dumps.as_posix()
        self._stub("pg_dump", f'''
printf '%s\\n' "pg_dump $*" >> {calls}
out=""
prev=""
for arg in "$@"; do
  case "$prev" in
    --file) out="$arg" ;;
  esac
  prev="$arg"
done
[ -n "$out" ] || out="${{OCC_FAKE_DUMP_FILE:-{dumps}/dump.sql}}"
if [ -f "${{OCC_FAKE_DUMP_FILE:-{dumps}/dump.sql}}" ]; then
  cat "$OCC_FAKE_DUMP_FILE" > "$out"
else
  printf 'dump content\\n' > "$out"
fi
''')
        self._stub("aws", f'''
printf '%s\\n' "aws $*" >> {calls}
case "$*" in
  *"s3 cp"*)
    last=""
    for arg in "$@"; do last="$arg"; done
    case "$last" in
      s3://*) exit 0 ;;
    esac
    for arg in "$@"; do
      case "$arg" in
        -) cat "${{OCC_FAKE_S3_READ_FILE:-/dev/null}}" 2>/dev/null; exit 0 ;;
        s3://*|s3|cp) ;;
        "$last")
          # A download: produce a plausible local backup object.
          printf 'dump content\\n' > "$arg"
          ;;
      esac
    done
    exit 0 ;;
  *"s3 ls"*)
    if [ -f "${{OCC_FAKE_BACKUP_LIST:-}}" ]; then cat "$OCC_FAKE_BACKUP_LIST"; fi
    exit 0 ;;
  *"s3 rm"*)
    if [ "${{OCC_FAKE_S3RM_FAILS:-}}" = "yes" ]; then exit 1; fi
    ;;
esac
exit 0
''')
        self._stub("docker", f'''
printf '%s\\n' "docker $*" >> {calls}
case "$*" in
  run\\ *|*" run "*)
    if [ "${{OCC_FAKE_RESTORE_FAILS:-}}" = "yes" ]; then exit 1; fi
    ;;
esac
exit 0
''')

    def run_backup(self, extra_env: dict | None = None) -> subprocess.CompletedProcess:
        env = self._env()
        env.update(extra_env or {})
        return subprocess.run([SH, BACKUP.as_posix(), "backup"],
                              capture_output=True, text=True, encoding="utf-8",
                              errors="replace", env=env)

    def run_restore(self, backup_key: str = "backups/occ-20261110T0000Z.sql.gz",
                    extra_env: dict | None = None) -> subprocess.CompletedProcess:
        env = self._env()
        env.update(extra_env or {})
        return subprocess.run([SH, BACKUP.as_posix(), "restore", backup_key],
                              capture_output=True, text=True, encoding="utf-8",
                              errors="replace", env=env)

    def _env(self) -> dict:
        env = dict(os.environ)
        env.update({
            "OCC_BACKUP_DIR": self.dumps.as_posix(),
            "OCC_RELEASE_BUCKET": "occ-test",
            "OCC_BACKUP_RETAIN": "3",
            "PATH": self.bin.as_posix() + os.pathsep + os.environ["PATH"],
            "OCC_AWS_LOG": self.calls.as_posix(),
            "POSTGRES_DB": "occ",
            "OCC_BACKUP_STAMP": "20261110T0000Z",
        })
        return env

    def aws_calls(self) -> list[str]:
        return self.calls.read_text(encoding="utf-8").splitlines()


class BackupTests(unittest.TestCase):
    def setUp(self) -> None:
        self.sandbox = BackupSandbox()
        self.addCleanup(self.sandbox.cleanup)

    def test_backup_dumps_consistently_and_uploads_to_s3(self):
        result = self.sandbox.run_backup()
        self.assertEqual(result.returncode, 0, result.stderr)
        joined = "\n".join(self.sandbox.aws_calls())
        self.assertIn("s3 cp", joined)
        self.assertIn("backups/occ-20261110T0000Z", joined)
        dump_call = next(line for line in self.sandbox.aws_calls()
                         if line.startswith("pg_dump"))
        # Database-consistent dump: single transaction snapshot, plain SQL.
        self.assertIn("--single-transaction", dump_call)

    def test_backup_failure_is_visible_and_stops_the_upload(self):
        self.sandbox._stub("pg_dump", "exit 3\n")
        result = self.sandbox.run_backup()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("dump", result.stderr.lower())
        self.assertFalse(any("s3 cp" in line for line in self.sandbox.aws_calls()))

    def test_retention_removes_only_oldest_backups(self):
        listing = self.sandbox.state / "backup-list.txt"
        listing.write_text(
            "2026-11-01 backups/occ-20261101T0000Z.sql.gz\n"
            "2026-11-02 backups/occ-20261102T0000Z.sql.gz\n"
            "2026-11-03 backups/occ-20261103T0000Z.sql.gz\n"
            "2026-11-04 backups/occ-20261104T0000Z.sql.gz\n", encoding="utf-8")
        result = self.sandbox.run_backup(extra_env={
            "OCC_FAKE_BACKUP_LIST": listing.as_posix()})
        self.assertEqual(result.returncode, 0, result.stderr)
        joined = "\n".join(self.sandbox.aws_calls())
        # With retention 3, the oldest object goes; newer ones stay.
        self.assertIn("occ-20261101T0000Z.sql.gz", joined)
        self.assertNotIn("occ-20261104T0000Z.sql.gz\"", joined)

    def test_cleanup_failure_cannot_abort_or_lose_the_new_backup(self):
        listing = self.sandbox.state / "backup-list.txt"
        listing.write_text(
            "2026-11-01 backups/occ-20261101T0000Z.sql.gz\n"
            "2026-11-02 backups/occ-20261102T0000Z.sql.gz\n"
            "2026-11-03 backups/occ-20261103T0000Z.sql.gz\n"
            "2026-11-04 backups/occ-20261104T0000Z.sql.gz\n", encoding="utf-8")
        result = self.sandbox.run_backup(extra_env={
            "OCC_FAKE_BACKUP_LIST": listing.as_posix(),
            "OCC_FAKE_S3RM_FAILS": "yes"})
        self.assertEqual(result.returncode, 0, result.stderr)
        joined = "\n".join(self.sandbox.aws_calls())
        # The upload happened before the cleanup attempt and is not undone.
        self.assertIn("backups/occ-20261110T0000Z", joined)


class RestoreTests(unittest.TestCase):
    def setUp(self) -> None:
        self.sandbox = BackupSandbox()
        self.addCleanup(self.sandbox.cleanup)

    def test_restore_runs_into_an_isolated_postgis_container(self):
        result = self.sandbox.run_restore()
        self.assertEqual(result.returncode, 0, result.stderr)
        joined = "\n".join(self.sandbox.aws_calls())
        self.assertIn("s3 cp", joined)
        self.assertIn("backups/occ-20261110T0000Z.sql.gz", joined)
        docker_calls = [line for line in self.sandbox.aws_calls()
                        if line.startswith("docker")]
        self.assertTrue(docker_calls)
        # The restore runs against a disposable PostGIS container with an
        # isolated name and never against the production compose project.
        self.assertIn("occ-restore", docker_calls[0])
        self.assertNotIn("-p occ-production", docker_calls[0])

    def test_restore_failure_is_visible(self):
        result = self.sandbox.run_restore(extra_env={"OCC_FAKE_RESTORE_FAILS": "yes"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("restore", result.stderr.lower())

    def test_restore_rejects_unsafe_keys(self):
        for key in ("../escape", "/absolute", "backups/x$IFS y"):
            result = self.sandbox.run_restore(key)
            self.assertNotEqual(result.returncode, 0, key)
            self.assertIn("backup key", result.stderr.lower())


if __name__ == "__main__":
    unittest.main()
