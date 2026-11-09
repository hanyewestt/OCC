"""Exercise the real setup entrypoint with command-boundary fakes."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SH = shutil.which('sh') or 'C:/Program Files/Git/bin/bash.exe'

class SetupTests(unittest.TestCase):
    def run_setup(self, failure=''):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            log = folder / 'calls'
            for command in ('psql', 'flyway'):
                script = folder / command
                script.write_text('#!/bin/sh\n'
                    + ('echo bootstrap >> "$CALL_LOG"\n[ "$FAIL_AT" != bootstrap ]\n' if command == 'psql' else
                       'test -z "${POSTGRES_PASSWORD-}" || exit 99\n'
                       'echo "$FLYWAY_DEFAULT_SCHEMA:$FLYWAY_USER" >> "$CALL_LOG"\n'
                       '[ "$FAIL_AT" != "$FLYWAY_DEFAULT_SCHEMA" ]\n'), newline='\n')
                script.chmod(0o755)
            env = dict(os.environ, PATH=f'{folder}{os.pathsep}{os.environ["PATH"]}',
                       CALL_LOG=log.as_posix(), FAIL_AT=failure,
                       POSTGRES_PASSWORD='admin', POSTGRES_USER='admin', POSTGRES_DB='occ')
            for service in ('INGESTION', 'DISRUPTION', 'PLANNING'):
                env[f'OCC_{service}_DB_MIGRATOR_USERNAME'] = service.lower() + '_migrator'
                env[f'OCC_{service}_DB_MIGRATOR_PASSWORD'] = 'synthetic'
                env[f'OCC_{service}_DB_USERNAME'] = service.lower()
            result = subprocess.run([SH, str(ROOT / 'infra/db-setup.sh')], env=env, capture_output=True, text=True)
            return result, log.read_text().splitlines() if log.exists() else []

    def test_all_schemas_use_their_own_migrator_after_bootstrap(self):
        result, calls = self.run_setup()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, ['bootstrap', 'ingestion:ingestion_migrator',
                                 'disruption:disruption_migrator', 'planning:planning_migrator'])

    def test_bootstrap_failure_prevents_any_migration(self):
        result, calls = self.run_setup('bootstrap')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, ['bootstrap'])

    def test_migration_failure_prevents_later_migrations(self):
        result, calls = self.run_setup('disruption')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, ['bootstrap', 'ingestion:ingestion_migrator', 'disruption:disruption_migrator'])

if __name__ == '__main__':
    unittest.main()
