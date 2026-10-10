from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from sage_forge.self_repair_loop import SelfRepairAgent


class StubModel:
    def __init__(self, replacement=None, bad=False):
        self.replacement = replacement
        self.bad = bad
        self.calls = 0

    def generate(self, prompt):
        self.calls += 1
        if self.bad:
            return 'not json'
        current_sha = prompt.split('SHA256: ', 1)[1].split('\n', 1)[0]
        return json.dumps({
            'source_sha256': current_sha,
            'replacement_code': self.replacement,
            'diagnosis': 'Fixed incorrect answer.',
        })


class RepairTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root/'sage_forge'/'tests').mkdir(parents=True)
        self.target = self.root/'sage_forge'/'sample.py'
        self.target.write_text('def answer():\n    return 41\n', encoding='utf-8')
        (self.root/'sage_forge'/'tests'/'test_sample.py').write_text(
            'import unittest\nfrom sage_forge.sample import answer\n'
            'class Sample(unittest.TestCase):\n'
            '    def test_answer(self):\n'
            '        self.assertEqual(answer(), 42)\n', encoding='utf-8'
        )
        (self.root/'sage_forge'/'__init__.py').write_text('',encoding='utf-8')
        self.git('init','-q')
        self.git('config','user.name','Sage Test')
        self.git('config','user.email','sage@example.invalid')
        self.git('add','.')
        self.git('commit','-qm','baseline')

    def git(self,*args):
        subprocess.run(['git','-C',str(self.root),*args],check=True,capture_output=True)

    def test_repair_candidate_verified_without_mutating_original(self):
        model = StubModel('def answer():\n    return 42\n')
        result = SelfRepairAgent(model,self.root,'sage_forge/sample.py',timeout_seconds=10).run()
        self.assertEqual(result.state,'verified_candidate',result.after_log)
        self.assertEqual(result.attempts,1)
        self.assertIn('+    return 42',result.patch)
        self.assertIn('return 41',self.target.read_text())
        self.assertEqual(model.calls,1)

    def test_already_healthy_does_not_ask_model(self):
        self.target.write_text('def answer():\n    return 42\n',encoding='utf-8')
        self.git('add','.')
        self.git('commit','-qm','fixed baseline')
        model=StubModel('def answer():\n    return 99\n')
        result=SelfRepairAgent(model,self.root,'sage_forge/sample.py').run()
        self.assertEqual(result.state,'healthy')
        self.assertEqual(model.calls,0)

    def test_broken_proposal_is_rejected(self):
        model=StubModel(bad=True)
        result=SelfRepairAgent(model,self.root,'sage_forge/sample.py').run()
        self.assertEqual(result.state,'blocked')
        self.assertEqual(model.calls,1)
        self.assertEqual(self.target.read_text(),'def answer():\n    return 41\n')

    def test_failed_verify_stops_at_budget(self):
        model=StubModel('def answer():\n    return 40\n')
        result=SelfRepairAgent(model,self.root,'sage_forge/sample.py',max_retries=3).run()
        self.assertEqual(result.state,'blocked')
        self.assertLessEqual(model.calls,3)
        self.assertEqual(self.target.read_text(),'def answer():\n    return 41\n')

    def test_reject_path_escape_and_dirty_tree(self):
        with self.assertRaises(ValueError):
            SelfRepairAgent(StubModel(),self.root,'../private.py')
        self.target.write_text('def answer(): return 0\n')
        with self.assertRaisesRegex(ValueError,'clean'):
            SelfRepairAgent(StubModel(),self.root,'sage_forge/sample.py').run()


if __name__=='__main__':
    unittest.main()
