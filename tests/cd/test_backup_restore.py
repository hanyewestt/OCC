"""Strict external-command backup/restore boundaries; real PostGIS in host_integration.py."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[2]/'scripts/cd'))
import backup


class BackupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.calls = []
        self.objects = {}

    def command(self, args, *, output=None, input_file=None):
        self.calls.append(args)
        if args[:2] == ['docker', 'ps']:
            return b'production-postgres'
        if args[:3] == ['docker', 'exec', 'production-postgres']:
            if args[3] not in ('pg_dump', 'pg_dumpall'):
                raise AssertionError(args)
            output.write(b'consistent database fixture')
            return None
        if args[:2] == ['docker', 'run']:
            return b'owned-container'
        if args[:2] == ['docker', 'exec']:
            if not any(a in args for a in ('pg_isready', 'psql', 'createdb', 'pg_restore')):
                raise AssertionError(args)
            return b'postgis'
        if args == ['docker', 'rm', '--force', 'owned-container']:
            return b''
        raise AssertionError(args)

    def aws(self, *args):
        self.calls.append(list(args))
        if args[:2] == ('s3api', 'put-object'):
            self.objects[args[args.index('--key')+1]] = Path(args[args.index('--body')+1]).read_bytes()
            return {'ETag': 'test'}
        if args[:2] == ('s3api', 'get-object'):
            Path(args[-1]).write_bytes(self.objects[args[args.index('--key')+1]])
            return {}
        if args[:2] == ('s3api', 'list-objects-v2'):
            return {'Contents': [{'Key': k} for k in self.objects]}
        if args[:2] == ('s3api', 'delete-object'):
            del self.objects[args[args.index('--key')+1]]
            return {}
        raise AssertionError(args)

    def make_backup(self):
        with patch.object(backup, 'command', side_effect=self.command), patch.object(backup, 'aws', side_effect=self.aws):
            backup.backup(self.root, 'bucket', 'production', 'occ', 'occ_admin', 3)
        return next(iter(self.objects))

    def test_snapshot_dump_roles_without_passwords_and_immutable_upload(self):
        self.make_backup()
        calls = str(self.calls)
        self.assertIn('--format=custom', calls)
        self.assertNotIn('--single-transaction', calls)
        self.assertIn('--no-role-passwords', calls)
        self.assertIn('--if-none-match', calls)
        self.assertEqual(json.loads((self.root/'status.json').read_text())['result'], 'success')

    def test_dump_failure_never_uploads_partial_backup_and_is_visible(self):
        with patch.object(backup, 'command', side_effect=subprocess.CalledProcessError(1, ['docker'])), patch.object(backup, 'aws') as aws:
            with self.assertRaises(subprocess.CalledProcessError):
                backup.backup(self.root, 'bucket', 'production', 'occ', 'occ_admin', 3)
        aws.assert_not_called()
        self.assertEqual(json.loads((self.root/'status.json').read_text())['result'], 'failed')

    def test_restore_uses_empty_template_and_roles_without_production_mounts(self):
        key = self.make_backup()
        self.calls.clear()
        with patch.object(backup, 'command', side_effect=self.command), patch.object(backup, 'aws', side_effect=self.aws):
            backup.restore(self.root, 'bucket', key)
        calls = str(self.calls)
        self.assertIn('--template=template0', calls)
        self.assertIn('pg_restore', calls)
        self.assertIn("'--network', 'none'", calls)
        self.assertNotIn('production-postgres', calls)
        self.assertFalse(any(p.name.startswith('restore-') for p in self.root.iterdir()))

    def test_failed_container_creation_never_removes_an_existing_container(self):
        key = self.make_backup()
        self.calls.clear()
        def fail(args, **kwargs):
            self.calls.append(args)
            raise subprocess.CalledProcessError(1, args)
        with patch.object(backup, 'command', side_effect=fail), patch.object(backup, 'aws', side_effect=self.aws):
            with self.assertRaises(subprocess.CalledProcessError):
                backup.restore(self.root, 'bucket', key)
        self.assertFalse(any(a[:2] == ['docker', 'rm'] for a in self.calls))
        self.assertFalse(any(p.name.startswith('restore-') for p in self.root.iterdir()))

    def test_retention_failure_preserves_uploaded_backup_and_last_success(self):
        def fail(*args):
            if args[:2] == ('s3api', 'list-objects-v2'):
                raise RuntimeError('retention denied')
            return self.aws(*args)
        with patch.object(backup, 'command', side_effect=self.command), patch.object(backup, 'aws', side_effect=fail):
            with self.assertRaisesRegex(RuntimeError, 'denied'):
                backup.backup(self.root, 'bucket', 'production', 'occ', 'occ_admin', 3)
        self.assertEqual(len(self.objects), 1)
        status=json.loads((self.root/'status.json').read_text())
        self.assertTrue(status['lastSuccess'])
        self.assertEqual(status['result'], 'retention-failed')

    def test_restore_rejects_unsafe_keys_before_external_calls(self):
        with patch.object(backup, 'aws') as aws:
            for key in ('../escape', '/absolute', 'backups/x$IFS y'):
                with self.assertRaisesRegex(ValueError, 'backup key'):
                    backup.restore(self.root, 'bucket', key)
        aws.assert_not_called()


if __name__ == '__main__':
    unittest.main()
