"""Publication contracts exercised without production credentials."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import yaml
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/cd'))
import pipeline


class PipelineTests(unittest.TestCase):
    def test_skopeo_login_and_transfer_use_runner_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(pipeline.os, 'getuid', return_value=1001, create=True), \
                 patch.object(pipeline.os, 'getgid', return_value=1002, create=True), \
                 patch.object(pipeline, 'run', return_value='password') as run, \
                 patch.object(pipeline.subprocess, 'run') as docker, \
                 patch.object(pipeline.os, 'chmod') as chmod:
                pipeline.login(root, 'registry.example', 'us-east-1')
                args = docker.call_args.args[0]
                self.assertEqual(args[args.index('--user') + 1], '1001:1002')
                self.assertNotIn('password', args)
                chmod.assert_called_once_with(root/'auth.json', 0o600)
                pipeline.skopeo(root, 'inspect', 'docker://registry.example/image')
                args = run.call_args.args
                self.assertEqual(args[args.index('--user') + 1], '1001:1002')

    def test_packaging_program_and_transfer_tool_invalidate_every_image_owner(self):
        for path in ('scripts/cd/pipeline.py','scripts/cd/skopeo-image.txt'):
            self.assertEqual(set(pipeline.release.ownership_for(path)), set(pipeline.release.COMPONENTS))

    def test_repository_mapping_keeps_namespace_and_normalizes_setup(self):
        env = {'OCC_'+c.upper().replace('-', '_')+'_REPOSITORY':
               '123.dkr.ecr.us-east-1.amazonaws.com/occ/'+c for c in pipeline.release.COMPONENTS}
        with patch.dict('os.environ', env):
            repos = pipeline.repositories()
        self.assertEqual(repos['db-setup'].split('/', 1)[1], 'occ/db-setup')

    def test_missing_reused_image_rebuilds_except_explicit_rescan(self):
        plan = {'buildComponents': [], 'deployRequired': False}
        previous = {'images': {c: 'registry/occ/'+c+'@sha256:'+'a'*64 for c in pipeline.release.COMPONENTS}}
        with patch.object(pipeline, 'available', return_value=False):
            value = pipeline.ensure_available(plan, previous, 'none', 'us-east-1')
            self.assertEqual(set(value['buildComponents']), set(pipeline.release.COMPONENTS))
            self.assertTrue(value['deployRequired'])
            with self.assertRaisesRegex(RuntimeError, 'rebuild'):
                pipeline.ensure_available(plan, previous, 'rescan', 'us-east-1')

    def test_inventory_excludes_manifest_and_anchors_every_other_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'script').write_text('content')
            (root/'manifest.json').write_text('not part of inventory')
            checksum = pipeline.inventory(root)
            self.assertEqual(len(checksum), 71)
            self.assertIn('script', (root/'checksums.txt').read_text())
            self.assertNotIn('manifest', (root/'checksums.txt').read_text())

    def test_publication_rejects_stale_and_same_sha_older_verification(self):
        current = {'revision': 'b'*40, 'verificationRun': 9}
        with patch.object(pipeline.release, 'is_ancestor', return_value=False):
            with self.assertRaisesRegex(RuntimeError, 'ancestry'):
                pipeline.check_publication(current, {'revision': 'a'*40, 'verificationRun': 10}, Path('.'))
        with self.assertRaisesRegex(RuntimeError, 'verification'):
            pipeline.check_publication(current, dict(current, verificationRun=8), Path('.'))

    def test_scan_reports_high_vulnerability_blocks_even_exit_zero_report(self):
        with self.assertRaisesRegex(RuntimeError, 'HIGH'):
            pipeline.validate_scan({'Results': [{'Vulnerabilities': [{'Severity': 'HIGH'}]}]})
        pipeline.validate_scan({'Results': [{'Vulnerabilities': [{'Severity': 'LOW'}]}]})

    def test_workflow_queues_and_pr_role_boundary(self):
        cd = yaml.safe_load((ROOT/'.github/workflows/cd.yml').read_text())
        self.assertNotIn('concurrency', cd)
        self.assertNotIn('concurrency', cd['jobs']['release'])
        self.assertNotIn('concurrency', cd['jobs']['scan'])
        for job,group in (('publish','occ-production-publication'), ('deploy','occ-production-mutation')):
            self.assertEqual(cd['jobs'][job]['concurrency'],
                             {'group':group,'cancel-in-progress':False,'queue':'max'})
        for component in pipeline.release.COMPONENTS:
            self.assertIn('OCC_'+component.upper().replace('-','_')+'_REPOSITORY', cd['env'])
        controls = yaml.safe_load((ROOT/'.github/workflows/environment-control.yml').read_text())
        self.assertNotIn('concurrency', controls)
        self.assertNotIn('concurrency', controls['jobs']['status'])
        self.assertEqual(controls['jobs']['control']['concurrency']['queue'], 'max')
        ci = yaml.safe_load((ROOT/'.github/workflows/ci.yml').read_text())
        steps = ci['jobs']['package-images']['steps']
        role = next(s for s in steps if 'configure-aws-credentials@' in s.get('uses',''))
        self.assertIn("github.ref == 'refs/heads/main'", role['if'])
        self.assertIn("github.event_name != 'pull_request'", role['if'])
        self.assertNotIn('secrets', ci['jobs']['package-images'])
        self.assertIn('OCC_VERIFY_ROLE_ARN', str(role))
        self.assertNotIn('OCC_PUBLISH_ROLE_ARN', str(ci))

    def test_candidate_provenance_and_complete_set_are_required(self):
        candidate = dict(revision='a'*40, runId='10', trusted=True,
                         components={c:{} for c in pipeline.release.COMPONENTS})
        pipeline.validate_candidate(candidate, 'a'*40, 10)
        for changes in ({'trusted':False}, {'revision':'b'*40}, {'runId':'9'}, {'components':{}}):
            with self.assertRaises(RuntimeError):
                pipeline.validate_candidate(dict(candidate, **changes), 'a'*40, 10)


if __name__ == '__main__':
    unittest.main()
