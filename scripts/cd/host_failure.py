"""Trusted fallback for terminal SSM failures, including pre-bundle failures."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import subprocess


@contextmanager
def host_lock(path):
    import fcntl
    with path.open('a') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def atomic_json(path, value):
    atomic_write(path, json.dumps(value, sort_keys=True).encode())


def atomic_write(path, content, owner=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.new')
    with temporary.open('wb') as handle:
        handle.write(content)
        os.chmod(temporary, owner.st_mode & 0o777 if owner else 0o600)
        if owner and os.name == 'posix':
            os.chown(temporary, owner.st_uid, owner.st_gid)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)
    if os.name == 'posix':
        descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def record_failure(operation, pre_apply=False):
    identity = operation['operationId']
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,99}', identity):
        raise ValueError('unsafe operation ID')
    root = Path(os.getenv('OCC_HOME', '/opt/occ'))/'state'
    root.mkdir(parents=True, exist_ok=True)
    receipt_path = root/'receipts'/(identity+'.json')
    with host_lock(root/'deploy.lock'):
        if receipt_path.exists():
            return json.loads(receipt_path.read_text())
        outstanding = root/'outstanding-operation.json'
        if outstanding.exists() and json.loads(outstanding.read_text()).get('operationId') != identity:
            raise RuntimeError('another host operation requires inspection')
        def containers(service):
            return subprocess.run(['docker','ps','--quiet','--filter',
                'label=com.docker.compose.project='+os.getenv('OCC_PROJECT','occ-production'),
                '--filter','label=com.docker.compose.service='+service],
                check=True,capture_output=True,text=True).stdout.split()
        if containers('db-setup'):
            raise RuntimeError('setup container is still running; retain ownership')
        gate = root.parent/'runtime/nginx-gate/traffic-gate.inc'
        if gate.exists():
            content = re.sub(r'(set\s+\$occ_traffic_gate\s+)"[^"]*";',r'\1"closed";',gate.read_text())
            atomic_write(gate,content.encode(),gate.stat())
        # Stop the edge and writers; PostgreSQL and its retained data stay intact.
        for service in ('nginx','ingestion','disruption','planning'):
            ids = containers(service)
            if ids:
                subprocess.run(['docker','stop','--time','60',*ids],check=True,capture_output=True,text=True)
        marker = root/'setup-recovery-required.json'
        if not pre_apply and operation['action'] in ('apply','rollback'):
            atomic_json(marker,dict(operationId=identity,reason='terminal failure without receipt; database outcome requires inspection'))
        reference = operation.get('reference')
        receipt = dict(operationId=identity,action=operation['action'],outcome='failed',
                       phase='failed',gateClosed=True,setupRecoveryRequired=marker.exists())
        if reference:
            receipt.update(reference=reference,releaseId=reference['releaseId'])
            atomic_json(root/'failed-release.json',reference)
        atomic_json(receipt_path,receipt)
        if outstanding.exists():
            outstanding.unlink()
        return receipt


if __name__ == '__main__':
    import sys
    print(json.dumps(record_failure(json.loads(sys.argv[1]), pre_apply=sys.argv[2]=='true')))
