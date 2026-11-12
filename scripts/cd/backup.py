#!/usr/bin/env python3
"""Consistent database/role backups and network-isolated restore drills.

Role passwords are deliberately excluded. Restored runtime credentials must
be rotated/reinjected before a restored database is used by applications.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import time
import uuid

POSTGIS_IMAGE = 'postgis/postgis:18-3.6@sha256:7e00e8c3539fdd43f513b98806c8204714dcd09dea683c259e333d7690317119'
KEY_PATTERN = re.compile(r'backups/occ-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}\.tar\.gz')


def command(args, *, output=None, input_file=None):
    return subprocess.run(args, check=True, stdout=output or subprocess.PIPE,
                          stdin=input_file, stderr=subprocess.PIPE).stdout


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1024*1024), b''):
            value.update(chunk)
    return value.hexdigest()


def stale_keys(objects, retain):
    if retain < 1:
        raise ValueError('OCC_BACKUP_RETAIN must be positive')
    keys = sorted(o['Key'] for o in objects if KEY_PATTERN.fullmatch(o['Key']))
    return keys[:-retain]


def overdue(status, now):
    return now - status.get('lastSuccess', 0) >= 86400


def write_status(directory, value):
    path = directory/'status.json'
    with (directory/'status.json.new').open('w') as handle:
        json.dump(value, handle, sort_keys=True)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(directory/'status.json.new', path)


def aws(*args):
    return json.loads(command(['aws', *args]).decode() or '{}')


def backup(directory, bucket, project, database, username, retain, only_overdue=False):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    status = json.loads((directory/'status.json').read_text()) if (directory/'status.json').exists() else {}
    now = int(time.time())
    if only_overdue and not overdue(status, now):
        print('backup: last successful backup is less than 24 hours old')
        return
    status.update(lastAttempt=now, result='running')
    write_status(directory, status)
    uploaded = False
    archive = None
    try:
        containers = command(['docker', 'ps', '--quiet', '--filter', 'label=com.docker.compose.project='+project,
                              '--filter', 'label=com.docker.compose.service=postgres']).decode().split()
        if len(containers) != 1:
            raise RuntimeError('expected exactly one running production postgres container')
        container = containers[0]
        name = datetime.now(timezone.utc).strftime('occ-%Y%m%dT%H%M%SZ-')+uuid.uuid4().hex[:12]+'.tar.gz'
        archive = directory/name
        with tempfile.TemporaryDirectory(prefix='dump-', dir=directory) as temporary:
            root = Path(temporary)
            for filename, arguments in (
                ('roles.sql', ['pg_dumpall', '--roles-only', '--no-role-passwords', '--username', username]),
                ('database.dump', ['pg_dump', '--format=custom', '--dbname', database, '--username', username])):
                with (root/filename).open('wb') as handle:
                    # pg_dump uses one consistent snapshot by default; the
                    # unsupported pg_restore --single-transaction flag is absent.
                    command(['docker', 'exec', container, *arguments], output=handle)
            metadata = dict(schemaVersion=1, database=database,
                            checksums={f: digest(root/f) for f in ('roles.sql', 'database.dump')})
            (root/'metadata.json').write_text(json.dumps(metadata))
            with tarfile.open(archive, 'w:gz') as handle:
                for filename in ('roles.sql', 'database.dump', 'metadata.json'):
                    handle.add(root/filename, arcname=filename)
        os.chmod(archive, 0o600)
        key = 'backups/'+name
        aws('s3api', 'put-object', '--bucket', bucket, '--key', key, '--body', str(archive),
            '--if-none-match', '*', '--checksum-algorithm', 'SHA256')
        uploaded = True
        status.update(lastSuccess=now, key=key, checksum=digest(archive), result='success')
        write_status(directory, status)
        # AWS CLI paginates this API into a complete Contents array by default.
        objects = aws('s3api', 'list-objects-v2', '--bucket', bucket, '--prefix', 'backups/').get('Contents', [])
        for stale in stale_keys(objects, retain):
            aws('s3api', 'delete-object', '--bucket', bucket, '--key', stale)
        local = sorted(p for p in directory.glob('occ-*.tar.gz') if KEY_PATTERN.fullmatch('backups/'+p.name))
        for path in local[:-retain]:
            path.unlink()
        print('backup: uploaded '+key)
    except BaseException:
        if not uploaded and archive is not None:
            archive.unlink(missing_ok=True)
        status['result'] = 'retention-failed' if uploaded else 'failed'
        write_status(directory, status)
        raise


def restore(directory, bucket, key):
    if not KEY_PATTERN.fullmatch(key):
        raise ValueError('unsafe backup key; expected backups/occ-<timestamp>-<id>.tar.gz')
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    # Unique owned names, server on network none, no production mounts/ports.
    name = 'occ-restore-'+uuid.uuid4().hex
    bootstrap = 'occ_restore_'+uuid.uuid4().hex
    created = False
    with tempfile.TemporaryDirectory(prefix='restore-', dir=directory) as temporary:
        root = Path(temporary)
        aws('s3api', 'get-object', '--bucket', bucket, '--key', key,
            '--checksum-mode', 'ENABLED', str(root/'backup.tar.gz'))
        with tarfile.open(root/'backup.tar.gz') as archive:
            members = archive.getmembers()
            if len(members) != 3 or {m.name for m in members} != {'roles.sql', 'database.dump', 'metadata.json'} or any(not m.isfile() for m in members):
                raise RuntimeError('invalid backup inventory')
            for member in members:
                with (root/member.name).open('wb') as output:
                    import shutil
                    shutil.copyfileobj(archive.extractfile(member), output)
        metadata = json.loads((root/'metadata.json').read_text())
        for filename in ('roles.sql', 'database.dump'):
            if digest(root/filename) != metadata['checksums'][filename]:
                raise RuntimeError('backup checksum mismatch')
        try:
            container = command(['docker', 'run', '--detach', '--network', 'none', '--name', name,
                                 '-e', 'POSTGRES_USER='+bootstrap, '-e', 'POSTGRES_DB=postgres',
                                 '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', POSTGIS_IMAGE]).decode().strip()
            created = True
            for attempt in range(60):
                try:
                    command(['docker', 'exec', container, 'pg_isready', '-h', '127.0.0.1', '-U', bootstrap, '-d', 'postgres'])
                    break
                except subprocess.CalledProcessError:
                    time.sleep(1)
            else:
                raise RuntimeError('isolated restore server did not become ready')
            with (root/'roles.sql').open('rb') as handle:
                command(['docker', 'exec', '-i', container, 'psql', '-U', bootstrap, '-d', 'postgres',
                         '--set', 'ON_ERROR_STOP=1'], input_file=handle)
            # PostGIS's default DB already has tiger/topology. Restore into
            # template0 so the archive creates its own extensions and schemas.
            command(['docker', 'exec', container, 'createdb', '-U', bootstrap, '--template=template0', 'occ_restore_target'])
            with (root/'database.dump').open('rb') as handle:
                command(['docker', 'exec', '-i', container, 'pg_restore', '-U', bootstrap,
                         '--dbname', 'occ_restore_target', '--exit-on-error', '--single-transaction'], input_file=handle)
            result = command(['docker', 'exec', container, 'psql', '-U', bootstrap, '-d', 'occ_restore_target',
                              '--set', 'ON_ERROR_STOP=1', '-Atc',
                              "SELECT extname FROM pg_extension ORDER BY extname"]).decode().strip()
            print('backup: isolated restore completed; extensions: '+result.replace('\n', ', '))
        finally:
            if created:
                command(['docker', 'rm', '--force', container])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('backup', 'overdue', 'restore'))
    parser.add_argument('key', nargs='?')
    args = parser.parse_args()
    os.umask(0o077)
    directory = Path(os.getenv('OCC_BACKUP_DIR', '/opt/occ/backups'))
    bucket = os.environ['OCC_RELEASE_BUCKET']
    try:
        if args.action == 'restore':
            restore(directory, bucket, args.key or '')
        else:
            retain = int(os.getenv('OCC_BACKUP_RETAIN', '7'))
            stale_keys([], retain)
            backup(directory, bucket, os.getenv('OCC_PROJECT', 'occ-production'),
                   os.environ['POSTGRES_DB'], os.getenv('POSTGRES_USER', 'occ_admin'), retain, args.action == 'overdue')
    except (RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        # subprocess stderr may contain SQL/connection details; keep it off logs.
        parser.exit(1, 'backup: '+(str(error) if not isinstance(error, subprocess.CalledProcessError) else 'external command failed; inspect local service health')+'\n')


if __name__ == '__main__':
    main()
