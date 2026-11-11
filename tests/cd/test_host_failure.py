"""Trusted terminal-failure fallback never reapplies an uncertain database."""
from contextlib import nullcontext
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/cd'))
import host_failure


class HostFailureTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home=Path(self.temp.name)
        self.state=self.home/'state'
        self.state.mkdir()
        self.operation={'operationId':'op-1','action':'apply','reference':{
            'releaseId':'rel-1','revision':'a'*40,'releaseKey':'releases/rel-1.json',
            'version':'v1','checksum':'sha256:'+'a'*64}}
        self.calls=[]
        self.setup_running=False
        self.addCleanup(patch.stopall)
        patch.dict(os.environ,{'OCC_HOME':str(self.home)}).start()
        patch.object(host_failure,'host_lock',return_value=nullcontext()).start()
        patch.object(host_failure.subprocess,'run',side_effect=self.docker).start()

    def docker(self,args,**kwargs):
        self.calls.append(args)
        if args[:2]==['docker','ps']:
            service=args[-1].split('=')[-1]
            output='setup-container' if service=='db-setup' and self.setup_running else '' if service=='db-setup' else service+'-container'
            return subprocess.CompletedProcess(args,0,output,'')
        if args[:2]==['docker','stop']:
            self.assertNotIn('postgres-container',args)
            return subprocess.CompletedProcess(args,0,'','')
        raise AssertionError(args)

    def test_pre_apply_failure_closes_traffic_and_can_end_failed_ownership(self):
        gate=self.home/'runtime/nginx-gate/traffic-gate.inc'
        gate.parent.mkdir(parents=True)
        gate.write_text('set $occ_traffic_gate "open";\nset $occ_gate_token "synthetic";\n')
        receipt=host_failure.record_failure(self.operation,pre_apply=True)
        self.assertEqual(receipt['outcome'],'failed')
        self.assertTrue(receipt['gateClosed'])
        self.assertFalse((self.state/'setup-recovery-required.json').exists())
        self.assertEqual(json.loads((self.state/'receipts/op-1.json').read_text())['reference'],self.operation['reference'])
        self.assertFalse(any(args[:2]==['docker','compose'] for args in self.calls))
        self.assertNotIn('build',str(self.calls))
        self.assertIn('set $occ_traffic_gate "closed";',gate.read_text())
        self.assertIn('set $occ_gate_token "synthetic";',gate.read_text())

    def test_unknown_database_outcome_is_marked_before_ownership_can_end(self):
        (self.state/'outstanding-operation.json').write_text(json.dumps(self.operation))
        receipt=host_failure.record_failure(self.operation,pre_apply=False)
        self.assertTrue(receipt['setupRecoveryRequired'])
        self.assertTrue((self.state/'setup-recovery-required.json').exists())
        self.assertFalse((self.state/'outstanding-operation.json').exists())
        self.assertFalse((self.state/'last-deployed.json').exists())

    def test_existing_verified_receipt_is_preserved_without_stopping_verified_app(self):
        receipts=self.state/'receipts'
        receipts.mkdir()
        value={'operationId':'op-1','action':'apply','outcome':'success','phase':'verified'}
        (receipts/'op-1.json').write_text(json.dumps(value))
        self.assertEqual(host_failure.record_failure(self.operation,pre_apply=False),value)
        self.assertEqual(self.calls,[])

    def test_another_host_operation_is_never_cleared(self):
        (self.state/'outstanding-operation.json').write_text('{"operationId":"foreign"}')
        with self.assertRaisesRegex(RuntimeError,'another'):
            host_failure.record_failure(self.operation,pre_apply=False)
        self.assertEqual(self.calls,[])

    def test_running_setup_is_not_misclassified_as_terminal_database_work(self):
        self.setup_running=True
        with self.assertRaisesRegex(RuntimeError,'setup'):
            host_failure.record_failure(self.operation,pre_apply=False)
        self.assertFalse((self.state/'receipts/op-1.json').exists())


if __name__=='__main__': unittest.main()
