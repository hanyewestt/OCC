#!/usr/bin/env python3
"""Run-bound candidate packaging and append-only, conditional publication.

The candidate plan is frozen before integration tests. CD never replans or
substitutes a newly published baseline after those tests have run.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release
import state


def run(*args, capture=False):
    return subprocess.run(list(map(str, args)), check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def save(path, value):
    Path(path).write_text(json.dumps(value, sort_keys=True, indent=2)+'\n', encoding='utf-8')


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def repositories():
    result = {c: os.environ['OCC_'+c.upper().replace('-', '_')+'_REPOSITORY']
              for c in release.COMPONENTS}
    for c, repo in result.items():
        release.validate_digest(c, repo+'@sha256:'+'0'*64)
    return result


def available(reference, region):
    registry, name = reference.split('/', 1)
    name, digest = name.split('@')
    response = state.aws(['ecr', 'batch-get-image', '--registry-id', registry.split('.')[0],
                          '--repository-name', name, '--image-ids', 'imageDigest='+digest], region)
    failures = response.get('failures', [])
    if any(f.get('failureCode') != 'ImageNotFound' for f in failures):
        raise RuntimeError('ECR image lookup failed; do not treat denied access as a cache miss')
    return any(i['imageId']['imageDigest'] == digest for i in response.get('images', []))


def ensure_available(plan, previous, refresh, region):
    value = dict(plan, buildComponents=list(plan['buildComponents']))
    for component in release.COMPONENTS:
        if component in value['buildComponents']:
            continue
        reference = (previous or {}).get('images', {}).get(component)
        if not reference or not available(reference, region):
            if refresh == 'rescan':
                raise RuntimeError('rescan digest unavailable; explicitly request rebuild')
            value['buildComponents'].append(component)
            value['deployRequired'] = True
    value['buildComponents'].sort()
    return value


def plan_candidate(directory, revision, run_id, refresh, bucket, region, trusted):
    previous = None
    if trusted:
        record = state.read_json(bucket, 'state/latest-published.json', region)['value']
        if record:
            previous = state.verified_manifest(bucket, record, region)
    if trusted and previous:
        plan = release.build_plan(Path('.'), revision, None, previous,
                                  refresh != 'none', rebuild=refresh == 'rebuild')
        plan = ensure_available(plan, previous, refresh, region)
    else:
        base = release.git(Path('.'), 'rev-list', '--max-parents=0', revision).splitlines()[0]
        plan = release.build_plan(Path('.'), revision, base, None, True, rebuild=True)
    value = dict(plan=plan, previous=previous, revision=revision, runId=str(run_id),
                 refreshMode=refresh, trusted=trusted, components={})
    save(directory/'candidate.json', value)
    return value


def skopeo(directory, *arguments):
    # The image is pinned by repository digest in skopeo-image.txt. No host
    # installation or plaintext registry password in command arguments.
    image = Path('scripts/cd/skopeo-image.txt').read_text().strip()
    return run('docker', 'run', '--rm', '--user', f'{os.getuid()}:{os.getgid()}', '--network', 'host',
               '-v', str(directory.resolve())+':/work', image, *arguments, capture=True)


def login(directory, registry, region):
    password = run('aws', '--region', region, 'ecr', 'get-login-password', capture=True)
    image = Path('scripts/cd/skopeo-image.txt').read_text().strip()
    subprocess.run(['docker', 'run', '--rm', '-i', '--user', f'{os.getuid()}:{os.getgid()}', '--network', 'host',
                    '-v', str(directory.resolve())+':/work', image,
                    'login', '--authfile', '/work/auth.json', '--username', 'AWS',
                    '--password-stdin', registry], input=password, text=True, check=True,
                   stdout=subprocess.DEVNULL)
    os.chmod(directory/'auth.json', 0o600)


def archive_identity(path):
    with tarfile.open(path) as archive:
        def read(name):
            member = archive.getmember(name)
            if not member.isfile():
                raise RuntimeError('invalid OCI archive entry')
            return archive.extractfile(member).read()
        index = json.loads(read('index.json'))
        if len(index['manifests']) != 1:
            raise RuntimeError('candidate must contain one linux/amd64 manifest')
        descriptor = index['manifests'][0]
        digest = descriptor['digest']
        raw = read('blobs/'+digest.replace(':', '/'))
        if 'sha256:'+hashlib.sha256(raw).hexdigest() != digest:
            raise RuntimeError('OCI manifest checksum mismatch')
        manifest = json.loads(raw)
        config_digest = manifest['config']['digest']
        config_raw = read('blobs/'+config_digest.replace(':', '/'))
        if 'sha256:'+hashlib.sha256(config_raw).hexdigest() != config_digest:
            raise RuntimeError('OCI config checksum mismatch')
        config = json.loads(config_raw)
        if config.get('os') != 'linux' or config.get('architecture') != 'amd64':
            raise RuntimeError('candidate platform must be linux/amd64')
    return dict(digest=digest, config=config_digest,
                archiveChecksum='sha256:'+hashlib.sha256(path.read_bytes()).hexdigest())


def daemon_config(directory, tag):
    # Containerd-backed Docker can expose the manifest as .Id. Inspect the
    # actual config bytes exported by the test daemon instead of assuming its ID format.
    path = directory/'daemon-check.tar'
    try:
        run('docker', 'image', 'save', '--output', path, tag)
        with tarfile.open(path) as archive:
            manifest = json.load(archive.extractfile('manifest.json'))
            config = archive.extractfile(manifest[0]['Config']).read()
        return 'sha256:'+hashlib.sha256(config).hexdigest()
    finally:
        path.unlink(missing_ok=True)


def package(directory):
    candidate = load(directory/'candidate.json')
    plan, previous = candidate['plan'], candidate['previous']
    if previous:
        login(directory, next(iter(previous['images'].values())).split('/')[0], os.environ['OCC_REGION'])
    try:
        for component in release.COMPONENTS:
            archive = directory/f'occ-image-{component}.oci.tar'
            source = None
            if component in plan['buildComponents']:
                if component in ('ingestion', 'disruption', 'planning'):
                    dockerfile = 'infra/java-runtime.Dockerfile'
                    context = directory/f'context-{component}'
                    port = dict(ingestion=8001, disruption=8002, planning=8003)[component]
                    extra = ['--build-arg', f'OCC_SERVICE_INTERNAL_PORT={port}']
                elif component == 'web':
                    dockerfile, context, extra = 'infra/web.Dockerfile', directory/'context-web', []
                else:
                    dockerfile, context, extra = 'infra/flyway.Dockerfile', Path('.'), []
                cache = directory/f'cache-{component}'
                args = ['docker', 'buildx', 'build', '--pull', '--platform', 'linux/amd64',
                        '--provenance=false', '--sbom=false', '--file', dockerfile,
                        '--output', 'type=oci,dest='+str(archive),
                        '--cache-to', f'type=local,dest={cache}.new,mode=max']
                if cache.exists():
                    args += ['--cache-from', 'type=local,src='+str(cache)]
                run(*args, *extra, context)
                if cache.exists():
                    shutil.rmtree(cache)
                Path(str(cache)+'.new').rename(cache)
            else:
                source = previous['images'][component]
                skopeo(directory, 'copy', '--authfile', '/work/auth.json', '--preserve-digests',
                       'docker://'+source, 'oci-archive:/work/'+archive.name)
            identity = archive_identity(archive)
            if source and source.split('@')[1] != identity['digest']:
                raise RuntimeError('reused OCI digest changed during transfer')
            # Docker test archives are derived from the same config/layers. CD
            # publishes the OCI archive; docker-save conversion never defines its digest.
            skopeo(directory, 'copy', 'oci-archive:/work/'+archive.name,
                   f'docker-archive:/work/occ-image-{component}.tar:occ-{component}:ci')
            run('docker', 'load', '--input', directory/f'occ-image-{component}.tar')
            actual = run('docker', 'image', 'inspect', '--format', '{{.Id}}', f'occ-{component}:ci', capture=True).strip()
            if daemon_config(directory, f'occ-{component}:ci') != identity['config']:
                raise RuntimeError('test daemon loaded a different image config')
            (directory/f'occ-image-{component}.id').write_text(actual+'\n')
            candidate['components'][component] = dict(identity, built=component in plan['buildComponents'], source=source)
        save(directory/'candidate.json', candidate)
    finally:
        (directory/'auth.json').unlink(missing_ok=True)


def validate_candidate(candidate, revision, run_id):
    if candidate.get('revision') != revision or candidate.get('runId') != str(run_id) or not candidate.get('trusted'):
        raise RuntimeError('candidate run/SHA/trust provenance mismatch')
    if set(candidate['components']) != set(release.COMPONENTS):
        raise RuntimeError('candidate must contain all five tested images')


def prepare(directory, revision, run_id, release_id, region):
    candidate = load(directory/'candidate.json')
    validate_candidate(candidate, revision, run_id)
    if not candidate['plan']['deployRequired']:
        return None
    repos = repositories()
    login(directory, next(iter(repos.values())).split('/')[0], region)
    images = {}
    try:
        for component, metadata in candidate['components'].items():
            path = directory/f'occ-image-{component}.oci.tar'
            if archive_identity(path) != {k: metadata[k] for k in ('digest', 'config', 'archiveChecksum')}:
                raise RuntimeError('candidate archive differs from tested metadata')
            if metadata['built']:
                skopeo(directory, 'copy', '--authfile', '/work/auth.json', '--preserve-digests',
                       'oci-archive:/work/'+path.name, 'docker://'+repos[component]+':'+release_id)
                reference = repos[component]+'@'+metadata['digest']
            else:
                reference = metadata['source']
            if not available(reference, region):
                raise RuntimeError('final manifest digest unavailable in ECR')
            images[component] = reference
    finally:
        (directory/'auth.json').unlink(missing_ok=True)
    manifest = release.build_manifest(candidate['plan'], candidate['previous'], images, release_id, 'unknown', None)
    if manifest['images'] != images:
        raise RuntimeError('manifest substituted an untested image')
    manifest['verificationRun'] = int(run_id)
    manifest['candidateMetadata'] = candidate['components']
    save(directory/'manifest.json', manifest)
    return manifest


def inventory(root):
    rows = []
    for path in sorted(root.rglob('*')):
        if path.is_file() and path.name not in ('manifest.json', 'checksums.txt'):
            rows.append(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+path.relative_to(root).as_posix()+'\n')
    raw = ''.join(rows).encode()
    (root/'checksums.txt').write_bytes(raw)
    return 'sha256:'+hashlib.sha256(raw).hexdigest()


def validate_scan(report):
    for result in report.get('Results', []):
        for item in result.get('Vulnerabilities', []) or []:
            if item.get('Severity') in ('HIGH', 'CRITICAL'):
                raise RuntimeError(item['Severity']+' vulnerability blocks publication')


def check_publication(current, candidate, repo):
    if current:
        if current['revision'] == candidate['revision']:
            if int(candidate['verificationRun']) <= int(current.get('verificationRun', 0)):
                raise RuntimeError('verification run is not newer than published run')
        elif not release.is_ancestor(repo, current['revision'], candidate['revision']):
            raise RuntimeError('publication ancestry would regress or diverge')
    if not release.is_ancestor(repo, candidate['revision'], 'origin/main'):
        raise RuntimeError('candidate is outside current main ancestry')


def publish(directory, bucket, region):
    manifest = load(directory/'manifest.json')
    release.validate_manifest(manifest)
    # Scan/SBOM evidence is required for every exact final digest.
    for component in release.COMPONENTS:
        report = load(directory/f'scan-{component}.json')
        validate_scan(report)
        if report.get('ArtifactName') != manifest['images'][component]:
            raise RuntimeError('scan subject differs from manifest digest')
        sbom = load(directory/f'sbom-{component}.json')
        if sbom.get('metadata', {}).get('component', {}).get('name') != manifest['images'][component]:
            raise RuntimeError('SBOM subject differs from manifest digest')
    current = state.read_json(bucket, 'state/latest-published.json', region)
    check_publication(current['value'], manifest, Path('.'))
    bundle = directory/'release-bundle'
    bundle.mkdir(exist_ok=True)
    files = {'compose.yaml': 'compose.yaml', 'infra/compose.images.yaml': 'compose.images.yaml',
             'infra/compose.cloudfront.yaml': 'compose.cloudfront.yaml',
             'infra/compose.host-data.yaml': 'compose.host-data.yaml',
             'infra/nginx/cloudfront.conf.template': 'infra/nginx/cloudfront.conf.template',
             'scripts/cd/deploy.sh': 'scripts/deploy.sh', 'scripts/cd/traffic-gate.sh': 'scripts/traffic-gate.sh',
             'scripts/cd/backup.sh': 'scripts/backup.sh', 'scripts/cd/backup.py': 'scripts/backup.py'}
    for source, target in files.items():
        destination = bundle/target
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    for component in release.COMPONENTS:
        for kind in ('scan', 'sbom'):
            shutil.copyfile(directory/f'{kind}-{component}.json', bundle/f'{kind}-{component}.json')
    manifest['bundleInventoryChecksum'] = inventory(bundle)
    raw = (json.dumps(manifest, sort_keys=True, indent=2)+'\n').encode()
    (bundle/'manifest.json').write_bytes(raw)
    prefix = 'releases/'+manifest['releaseId']
    for path in sorted(bundle.rglob('*')):
        if path.is_file():
            state.write_immutable(bucket, prefix+'/bundle/'+path.relative_to(bundle).as_posix(), path.read_bytes(), region)
    metadata = state.write_immutable(bucket, prefix+'.json', raw, region)
    reference = {k: manifest[k] for k in ('releaseId', 'revision')}
    reference.update(releaseKey=prefix+'.json', version=metadata['versionId'], checksum=metadata['checksum'])
    # Full manifest fields support planning; pinned reference fields support apply.
    state.compare_and_set_json(bucket, 'state/latest-published.json', dict(manifest, **reference), current['etag'], region)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('plan', 'package', 'prepare', 'publish'))
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--revision', default=os.getenv('GITHUB_SHA'))
    parser.add_argument('--run-id', default=os.getenv('GITHUB_RUN_ID'))
    parser.add_argument('--refresh', choices=('none', 'rescan', 'rebuild'), default='none')
    parser.add_argument('--trusted', action='store_true')
    parser.add_argument('--release-id')
    parser.add_argument('--region', default=os.getenv('OCC_REGION', 'us-east-1'))
    parser.add_argument('--bucket', default=os.getenv('OCC_RELEASE_BUCKET'))
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    if args.action == 'plan':
        plan_candidate(args.directory, args.revision, args.run_id, args.refresh, args.bucket, args.region, args.trusted)
    elif args.action == 'package':
        package(args.directory)
    elif args.action == 'prepare':
        prepare(args.directory, args.revision, args.run_id, args.release_id, args.region)
    else:
        publish(args.directory, args.bucket, args.region)


if __name__ == '__main__':
    main()
