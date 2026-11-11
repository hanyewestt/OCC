import importlib.util
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]/'scripts/cd'))
import backup


class BackupPolicyTests(unittest.TestCase):
    def test_retention_uses_full_keys_and_never_deletes_unrelated_objects(self):
        objects = [{'Key': f'backups/occ-2026110{i}T000000Z-aaaaaaaaaaaa.tar.gz'} for i in range(1, 5)]
        objects += [{'Key': 'backups/notes.txt'}, {'Key': 'releases/occ-20200101T000000Z.tar.gz'}]
        self.assertEqual(backup.stale_keys(objects, 3), ['backups/occ-20261101T000000Z-aaaaaaaaaaaa.tar.gz'])
        with self.assertRaises(ValueError):
            backup.stale_keys(objects, 0)

    def test_overdue_uses_success_time_not_failed_attempt(self):
        self.assertTrue(backup.overdue({'lastSuccess': 100, 'lastAttempt': 99999}, 90000))
        self.assertFalse(backup.overdue({'lastSuccess': 89900}, 90000))


if __name__ == '__main__':
    unittest.main()
