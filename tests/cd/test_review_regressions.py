"""Regressions from the independent whole-branch CD review."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import yaml

ROOT = Path(__file__).resolve().parents[2]


class RoleBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.resources = yaml.load((ROOT/'infra/aws/ec2-cd.yaml').read_text(), Loader=yaml.BaseLoader)['Resources']

    def statements(self, role):
        return [s for p in self.resources[role]['Properties']['Policies'] for s in p['PolicyDocument']['Statement']]

    def test_host_writes_only_receipts_and_backups_not_control_or_pointers(self):
        resources = [s['Resource'] for s in self.statements('OccHostRole') if 's3:PutObject' in s.get('Action', [])]
        self.assertEqual(set(resources), {'${OccReleaseBucket.Arn}/state/receipts/*', '${OccReleaseBucket.Arn}/backups/*'})

    def test_publisher_cannot_read_database_backups(self):
        resources = [r for s in self.statements('OccPublishRole') if 's3:GetObject' in s.get('Action', [])
                     for r in ([s['Resource']] if isinstance(s['Resource'],str) else s['Resource'])]
        self.assertNotIn('${OccReleaseBucket.Arn}/*', resources)
        self.assertIn('${OccReleaseBucket.Arn}/releases/*', resources)
        self.assertIn('${OccReleaseBucket.Arn}/state/latest-published.json', resources)

    def test_publisher_can_distinguish_first_missing_pointer_from_access_denial(self):
        self.assertTrue(any('s3:ListBucket' in s.get('Action',[]) for s in self.statements('OccPublishRole')))

    def test_repository_actions_are_scoped_and_only_ecr_token_is_wildcard(self):
        for role in ('OccHostRole','OccPublishRole','OccVerifyRole'):
            for statement in self.statements(role):
                actions = statement.get('Action', [])
                if isinstance(actions,str): actions=[actions]
                for action in actions:
                    if action.startswith('ecr:') and action != 'ecr:GetAuthorizationToken':
                        self.assertNotEqual(statement['Resource'], '*', (role,action))


class EgressReviewTests(unittest.TestCase):
    def setUp(self):
        spec=importlib.util.spec_from_file_location('egress_review', ROOT/'scripts/cd/egress-prefix-list.py')
        self.egress=importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.egress)

    def test_host_plan_excludes_hosted_runner_and_enterprise_importer_ranges(self):
        with tempfile.TemporaryDirectory() as directory:
            source=Path(directory)/'source.json'
            source.write_text(json.dumps({'github':{'api':['192.0.2.0/24'],
                'actions':['198.51.100.0/24'],'github_enterprise_importer':['203.0.113.0/24']}}))
            ranges,_=self.egress.collect(str(source),[],[])
            self.assertEqual(ranges,['192.0.2.0/24'])

    def test_waits_for_complete_state_and_uses_completed_version(self):
        responses=[{'PrefixLists':[{'PrefixListId':'pl-test','State':'modify-in-progress','Version':4}]},
                   {'PrefixLists':[{'PrefixListId':'pl-test','State':'modify-complete','Version':5}]}]
        with patch.object(self.egress,'aws',side_effect=responses), patch.object(self.egress.time,'sleep'):
            self.assertEqual(self.egress.wait_ready('us-east-1','pl-test')['Version'],5)


class RecoveryWorkflowTests(unittest.TestCase):
    def test_recover_exposes_the_original_operation_id_instead_of_generating_one(self):
        workflow=yaml.load((ROOT/'.github/workflows/environment-control.yml').read_text(),Loader=yaml.BaseLoader)
        self.assertIn('recovery-operation-id',workflow['on']['workflow_dispatch']['inputs'])
        step=next(s for s in workflow['jobs']['control']['steps'] if s.get('name')=='Run the lifecycle action')
        self.assertIn('RECOVERY_OPERATION_ID',step['env'])
        self.assertIn('operation="$RECOVERY_OPERATION_ID"',step['run'])


if __name__=='__main__': unittest.main()
