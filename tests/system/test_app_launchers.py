"""Run both real launchers against fake Docker/npm executables; no live resources."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASH = 'C:/Program Files/Git/bin/bash.exe' if os.name == 'nt' else shutil.which('bash')
POWERSHELL = shutil.which('pwsh') or shutil.which('powershell')

FAKE = '''import json, os, sys
from pathlib import Path
args = sys.argv[2:]
with open(os.environ['CALL_LOG'], 'a') as stream:
    stream.write(json.dumps([sys.argv[1], *args]) + '\\n')
if 'config' in args and '--format' in args:
    print('{"name":"launcher-test"}' if args[-1] == 'json' else 'name: launcher-test')
if os.environ.get('FAIL_AT') == 'setup' and 'run' in args and 'db-setup' in args:
    sys.exit(23)
if os.environ.get('FAIL_AT') == 'postgres' and 'up' in args and args[-1] == 'postgres':
    sys.exit(24)
'''

class LauncherTests(unittest.TestCase):
    def launch(self, shell, action, fail='', confirm=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            (root / 'frontend').mkdir()
            (root / '.env').write_text('')
            for file in ('app.sh', 'app.ps1'):
                shutil.copyfile(ROOT / 'scripts' / file, root / 'scripts' / file)
            binary = root / 'bin'
            binary.mkdir()
            fake = binary / 'fake.py'
            fake.write_text(FAKE)
            for name in ('docker', 'npm'):
                shim = binary / name
                shim.write_text(f'#!/bin/sh\nexec "{Path(sys.executable).as_posix()}" "{fake.as_posix()}" {name} "$@"\n', newline='\n')
                shim.chmod(0o755)
                (binary / f'{name}.cmd').write_text(f'@"{sys.executable}" "{fake}" {name} %*\n')
            log = root / 'calls.jsonl'
            env = dict(os.environ, PATH=f'{binary}{os.pathsep}{os.environ["PATH"]}',
                       CALL_LOG=log.as_posix(), FAIL_AT=fail)
            if shell == 'bash':
                command = [BASH, str(root / 'scripts/app.sh'), action, '--project', 'launcher-test']
                if confirm and action in ('reset', 'clear'):
                    command += ['--yes']
            else:
                command = [POWERSHELL, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                           str(root / 'scripts/app.ps1'), action, '-ProjectName', 'launcher-test']
                if confirm and action in ('reset', 'clear'):
                    command += ['-Yes']
            result = subprocess.run(command, env=env, input='cancel\n', text=True,
                                    capture_output=True, timeout=60)
            calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
            # Strip Docker's global Compose options to compare the actual operations.
            operations = []
            for call in calls:
                if call[:2] == ['docker', 'compose']:
                    args = call[2:]
                    for verb in ('config', 'build', 'up', 'run', 'start', 'stop', 'down'):
                        if verb in args:
                            operations.append(args[args.index(verb):])
                            break
            return result, calls, [op for op in operations if op[0] != 'config']

    def shells(self):
        return ['bash'] + (['powershell'] if POWERSHELL else [])

    def test_up_and_reset_run_setup_before_starting_apps(self):
        for shell in self.shells():
            for action in ('up', 'reset'):
                with self.subTest(shell=shell, action=action):
                    result, calls, ops = self.launch(shell, action)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(ops[-3:-1], [['up', '-d', '--wait', 'postgres'],
                                                 ['run', '--rm', '--no-deps', 'db-setup']])
                    self.assertEqual(ops[-1][0], 'up')
                    self.assertNotIn('postgres', ops[-1])
                    self.assertIn(['npm', 'ci'], calls)
                    self.assertIn(['npm', 'run', 'build'], calls)
                    self.assertTrue(any(op[0] == 'build' for op in ops))

    def test_setup_or_postgres_failure_blocks_application_start(self):
        for shell in self.shells():
            for action in ('up', 'reset'):
                for failure in ('setup', 'postgres'):
                    with self.subTest(shell=shell, action=action, failure=failure):
                        result, _, ops = self.launch(shell, action, failure)
                        self.assertNotEqual(result.returncode, 0)
                        self.assertEqual(ops[-1][0], 'run' if failure == 'setup' else 'up')
                        self.assertIn('db-setup' if failure == 'setup' else 'postgres', ops[-1])

    def test_other_actions_do_not_rerun_setup(self):
        expected = {'start': ['start', '--wait'], 'stop': ['stop'],
                    'start-pgadmin': ['up', '-d', '--wait', 'pgadmin'],
                    'stop-pgadmin': ['stop', 'pgadmin'],
                    'clear': ['down', '--volumes', '--remove-orphans', '--rmi', 'all']}
        for shell in self.shells():
            for action, operation in expected.items():
                with self.subTest(shell=shell, action=action):
                    result, calls, ops = self.launch(shell, action)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(ops, [operation])
                    self.assertFalse(any(call[0] == 'npm' for call in calls))
                    if action == 'clear':
                        down = next(call for call in calls if 'down' in call)
                        self.assertIn('setup', down, 'clear must remove the setup image too')

    def test_cancelled_destructive_actions_do_not_mutate_resources(self):
        for shell in self.shells():
            for action in ('reset', 'clear'):
                with self.subTest(shell=shell, action=action):
                    result, _, ops = self.launch(shell, action, confirm=False)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(ops, [])

if __name__ == '__main__':
    unittest.main()
