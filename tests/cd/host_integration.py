#!/usr/bin/env python3
"""Opt-in real Docker checks. Selected checks fail if Docker is unavailable.

Uses unique disposable names, network-isolated PostgreSQL, synthetic data and
an in-memory S3 boundary. Never reads deployment secrets or production data.
EC2/systemd/CloudFront acceptance requires the separately reviewed live stack.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import tarfile
import uuid
from unittest.mock import patch
from contextlib import nullcontext

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'scripts/cd'))
import backup
import pipeline
import host_failure


def run(*args, **kwargs):
    result = subprocess.run(args, capture_output=True, **kwargs)
    if result.returncode:
        print(result.stderr.decode(errors='replace'), file=sys.stderr)
        result.check_returncode()
    return result.stdout


def check_backup():
    suffix = uuid.uuid4().hex[:12]
    project = 'occ-backup-proof-'+suffix
    container = None
    objects = {}
    with tempfile.TemporaryDirectory(prefix='occ-backup-proof-') as directory:
        root = Path(directory)
        def s3(*args):
            key = args[args.index('--key')+1] if '--key' in args else None
            if args[:2] == ('s3api', 'put-object'):
                if key in objects:
                    raise RuntimeError('immutable object collision')
                objects[key] = Path(args[args.index('--body')+1]).read_bytes()
                return {'ETag':'test'}
            if args[:2] == ('s3api', 'get-object'):
                Path(args[-1]).write_bytes(objects[key])
                return {}
            if args[:2] == ('s3api', 'list-objects-v2'):
                return {'Contents':[{'Key':k} for k in objects]}
            if args[:2] == ('s3api', 'delete-object'):
                del objects[key]
                return {}
            raise AssertionError(args)
        try:
            container = run('docker', 'run', '--detach', '--name', project, '--network', 'none',
                            '--label', 'com.docker.compose.project='+project,
                            '--label', 'com.docker.compose.service=postgres',
                            '-e', 'POSTGRES_USER=occ_admin', '-e', 'POSTGRES_DB=occ',
                            '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', backup.POSTGIS_IMAGE).decode().strip()
            for attempt in range(60):
                result = subprocess.run(['docker','exec',container,'pg_isready','-h','127.0.0.1','-U','occ_admin','-d','occ'],capture_output=True)
                if result.returncode == 0:
                    break
                import time
                time.sleep(1)
            else:
                raise RuntimeError('source PostGIS did not become ready')
            sql = '''CREATE ROLE proof_runtime LOGIN; CREATE ROLE proof_migrator LOGIN;
CREATE SCHEMA proof AUTHORIZATION proof_migrator;
SET ROLE proof_migrator;
CREATE TABLE proof.rows(id int PRIMARY KEY, point geometry(Point,4326));
INSERT INTO proof.rows VALUES(42,ST_SetSRID(ST_Point(103.8,1.3),4326));
CREATE TABLE proof.flyway_schema_history(version text PRIMARY KEY, success boolean NOT NULL);
INSERT INTO proof.flyway_schema_history VALUES('1',true);
GRANT USAGE ON SCHEMA proof TO proof_runtime;
GRANT SELECT ON proof.rows TO proof_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA proof GRANT SELECT ON TABLES TO proof_runtime;
RESET ROLE;'''
            run('docker','exec','-i',container,'psql','-U','occ_admin','-d','occ','--set','ON_ERROR_STOP=1',input=sql.encode())
            actual_command = backup.command
            checked = []
            def inspect_restore(args, **kwargs):
                result = actual_command(args, **kwargs)
                if 'pg_restore' in args:
                    target = args[args.index('-i')+1]
                    user = args[args.index('-U')+1]
                    query = "SET ROLE proof_runtime; SELECT id,ST_AsText(point) FROM proof.rows; RESET ROLE; SELECT tableowner FROM pg_tables WHERE schemaname='proof' AND tablename='rows'; SELECT defaclrole::regrole FROM pg_default_acl WHERE defaclrole='proof_migrator'::regrole; SELECT version,success FROM proof.flyway_schema_history; SELECT has_table_privilege('proof_runtime','proof.rows','INSERT'),has_schema_privilege('proof_migrator','proof','CREATE'); SET ROLE proof_migrator; BEGIN; INSERT INTO proof.rows VALUES(43,ST_Point(0,0)); ROLLBACK;"
                    evidence = actual_command(['docker','exec',target,'psql','-U',user,'-d','occ_restore_target','-Atc',query]).decode()
                    if '42|POINT(103.8 1.3)' not in evidence or evidence.count('proof_migrator') != 2:
                        raise RuntimeError('restored data/ownership/grants/default ACLs differ: '+evidence)
                    if '1|t' not in evidence or 'f|t' not in evidence:
                        raise RuntimeError('restored migration history or least privilege differs: '+evidence)
                    checked.append(evidence)
                return result
            with patch.object(backup, 'aws', side_effect=s3):
                backup.backup(root, 'isolated-fixture', project, 'occ', 'occ_admin', 3)
                with patch.object(backup, 'command', side_effect=inspect_restore):
                    backup.restore(root, 'isolated-fixture', next(iter(objects)))
            if len(checked) != 1:
                raise RuntimeError('restore verification was not executed')
            print('PASS real PostGIS snapshot, roles, ownership, grants, default ACLs, data and isolated cleanup')
            # A valid inventory containing an invalid pg_dump payload must fail
            # inside the real restore client and still remove its owned server.
            key=next(iter(objects))
            with tarfile.open(fileobj=io.BytesIO(objects[key]),mode='r:gz') as archive:
                content={member.name:archive.extractfile(member).read() for member in archive.getmembers()}
            content['database.dump']=b'invalid synthetic custom dump'
            metadata=json.loads(content['metadata.json'])
            metadata['checksums']['database.dump']=hashlib.sha256(content['database.dump']).hexdigest()
            content['metadata.json']=json.dumps(metadata).encode()
            output=io.BytesIO()
            with tarfile.open(fileobj=output,mode='w:gz') as archive:
                for name,data in content.items():
                    member=tarfile.TarInfo(name)
                    member.size=len(data)
                    archive.addfile(member,io.BytesIO(data))
            objects[key]=output.getvalue()
            created=[]
            def track_restore(args,**kwargs):
                result=actual_command(args,**kwargs)
                if args[:2]==['docker','run']:
                    created.append(result.decode().strip())
                return result
            with patch.object(backup,'aws',side_effect=s3), patch.object(backup,'command',side_effect=track_restore):
                try:
                    backup.restore(root,'isolated-fixture',key)
                except subprocess.CalledProcessError:
                    pass
                else:
                    raise RuntimeError('invalid restore unexpectedly succeeded')
            if len(created)!=1:
                raise RuntimeError('failure cleanup did not exercise an owned restore server')
            if subprocess.run(['docker','inspect',created[0]],capture_output=True).returncode==0:
                raise RuntimeError('failed restore leaked its owned server')
            if any(p.name.startswith('restore-') for p in root.iterdir()):
                raise RuntimeError('failed restore leaked temporary dump files')
            print('PASS real pg_restore failure removes only its owned server and temporary files')
        finally:
            if container:
                run('docker','rm','--force',container)


def check_oci():
    builder = 'occ-oci-proof-'+uuid.uuid4().hex[:12]
    created = False
    with tempfile.TemporaryDirectory(prefix='occ-oci-proof-') as directory:
        root = Path(directory)
        (root/'Dockerfile').write_text('FROM scratch\nCOPY payload /payload\n')
        (root/'payload').write_text('synthetic candidate proof\n')
        try:
            run('docker','buildx','create','--name',builder,'--driver','docker-container')
            created = True
            run('docker','buildx','build','--builder',builder,'--platform','linux/amd64','--provenance=false','--sbom=false',
                '--output','type=oci,dest='+str(root/'image.tar'),root)
            identity = pipeline.archive_identity(root/'image.tar')
            pipeline.skopeo(root,'copy','--preserve-digests','oci-archive:/work/image.tar','oci-archive:/work/copied.tar')
            copied = pipeline.archive_identity(root/'copied.tar')
            if copied['digest'] != identity['digest'] or copied['config'] != identity['config']:
                raise RuntimeError('OCI transfer changed tested identity')
            tag = 'occ-oci-proof:'+builder
            pipeline.skopeo(root,'copy','oci-archive:/work/image.tar','docker-archive:/work/test.tar:'+tag)
            run('docker','load','--input',root/'test.tar')
            try:
                config = pipeline.daemon_config(root, tag)
                if config != identity['config']:
                    import tarfile
                    with tarfile.open(root/'image.tar') as archive:
                        original = archive.extractfile('blobs/'+identity['config'].replace(':','/')).read()
                    with tarfile.open(root/'test.tar') as archive:
                        manifest = json.load(archive.extractfile('manifest.json'))
                        converted = archive.extractfile(manifest[0]['Config']).read()
                    raise RuntimeError('Docker import config mismatch: '+repr(dict(actual=config, expected=identity['config'], original=original, converted=converted)))
            finally:
                run('docker','image','rm',tag)
            print('PASS real Buildx OCI archive, preserve-digests transfer and Docker test config identity')
        finally:
            if created:
                run('docker','buildx','rm',builder)


def check_failure():
    # Exercise the Linux kernel lock separately from the Docker boundary.
    lock_code = '''import pathlib,tempfile,sys
sys.path.insert(0,'/code')
import host_failure
with tempfile.TemporaryDirectory() as directory:
    path=pathlib.Path(directory)/'lock'
    with host_failure.host_lock(path):
        try:
            with host_failure.host_lock(path): raise RuntimeError('second lock was admitted')
        except BlockingIOError: pass
    with host_failure.host_lock(path): pass
print('PASS Linux flock excludes overlap and releases ownership')
'''
    result=run('docker','run','--rm','--network','none','-v',str(ROOT/'scripts/cd')+':/code:ro',
               'python:3.13-slim','python','-c',lock_code)
    print(result.decode().strip())
    project='occ-failure-proof-'+uuid.uuid4().hex[:12]
    containers={}
    with tempfile.TemporaryDirectory(prefix='occ-failure-proof-') as temporary:
        root=Path(temporary)
        gate=root/'runtime/nginx-gate/traffic-gate.inc'
        gate.parent.mkdir(parents=True)
        gate.write_text('set $occ_traffic_gate "open";\nset $occ_gate_token "synthetic";\n')
        try:
            for service in ('nginx','ingestion','disruption','planning','postgres'):
                image_arguments = ['-e','POSTGRES_USER=occ_admin','-e','POSTGRES_DB=occ',
                                   '-e','POSTGRES_HOST_AUTH_METHOD=trust',backup.POSTGIS_IMAGE] if service=='postgres' else [
                    '--entrypoint','nginx',
                    'nginx:1.30.6-alpine-slim@sha256:f65ed258206d1eb6c4eb6141111715a2cf94ac0a4ec9675fc2da186f7aadb163',
                    '-g','daemon off;']
                containers[service]=run('docker','run','--detach','--network','none',
                    '--label','com.docker.compose.project='+project,
                    '--label','com.docker.compose.service='+service,
                    *image_arguments).decode().strip()
            operation=dict(operationId='failure-proof',action='apply',reference=dict(releaseId='synthetic'))
            # The host driver is Windows here; real fcntl exclusion was proven above.
            with patch.dict(os.environ,dict(OCC_HOME=str(root),OCC_PROJECT=project)), \
                 patch.object(host_failure,'host_lock',return_value=nullcontext()):
                receipt=host_failure.record_failure(operation,pre_apply=False)
            if not receipt['gateClosed'] or not receipt['setupRecoveryRequired']:
                raise RuntimeError('uncertain database outcome was not guarded')
            for service,container in containers.items():
                running=run('docker','inspect','--format','{{.State.Running}}',container).decode().strip()
                if running != ('true' if service=='postgres' else 'false'):
                    raise RuntimeError('incorrect quiesce state for '+service)
            if '"closed"' not in gate.read_text():
                raise RuntimeError('stored gate was not closed')
            print('PASS real Docker recovery stops edge/writers, preserves PostgreSQL and records guarded failure')
        finally:
            for container in containers.values():
                run('docker','rm','--force',container)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('suite',choices=('backup','oci','failure','all'))
    args=parser.parse_args()
    run('docker','info')
    os.chdir(ROOT)
    if args.suite in ('backup','all'):
        check_backup()
    if args.suite in ('oci','all'):
        check_oci()
    if args.suite in ('failure','all'):
        check_failure()


if __name__ == '__main__':
    main()
