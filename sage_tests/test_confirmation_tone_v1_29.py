#!/usr/bin/env python3
import pathlib, subprocess, sys, tempfile, unittest

ROOT = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else pathlib.Path("sage_build_129").resolve()
sys.argv[:] = sys.argv[:1]
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"

class ConfirmationToneTests(unittest.TestCase):
    def source(self, name): return (JAVA / name).read_text(encoding="utf-8")
    def test_pending_record_is_complete_and_persistent(self):
        policy=self.source("SageConfirmationLearning.java"); store=self.source("SageConfirmationStore.java")
        for token in ("turnId","generation","raw","normalized","alternatives","confidence","intent","entities","context","risk","createdAt","expiresAt"):
            self.assertIn(token,policy)
        self.assertIn('getSharedPreferences(PREFS,Context.MODE_PRIVATE)',store)
        self.assertIn('.commit()',store)
    def test_exact_once_answers_and_retry(self):
        src=self.source("SageConfirmationLearning.java")
        self.assertIn('"Did you say ‘"+clean(candidate)+"’?"',src)
        self.assertIn('"Okay—say it again."',src)
        self.assertIn('stale_confirmation_answer',src)
        self.assertIn('p.consumed=true;pending=null',src)
    def test_learning_lifecycle_and_protected_risks(self):
        src=self.source("SageConfirmationLearning.java")+self.source("SageConfirmationStore.java")
        for token in ('rejected_to_corrected','owner_confirmed','static boolean edit','static boolean delete','auth','red queen','permission','installer','destructive','authority'):
            self.assertIn(token,src.lower())
    def test_voice_integration_and_diagnostics(self):
        src=self.source("SageVoiceService.java")
        for token in ('askSpeechConfirmation','handleConfirmationAnswer','low_confidence','recognizer_generation=','alternatives=','dispatch_count=','learned_mapping=','SageConfirmationStore.apply'):
            self.assertIn(token,src)
        self.assertLess(src.index('handleConfirmationAnswer(decision.selectedCandidate'),src.index('handleCommand(decision.selectedCandidate)'))
    def test_tone_commands_persistence_and_brain_prompt(self):
        tone=self.source("SageTonePolicy.java"); engine=self.source("SageCommandEngine.java"); brain=self.source("SageBrainManager.java")
        for token in ('CLEAN','CASUAL','UNFILTERED','you can cuss around me','turn the sass up','tone it down','stop cussing','what is your language setting'):
            self.assertIn(token,tone)
        self.assertIn('putString("owner_tone", selected.name()).commit()',engine)
        self.assertIn('SageTonePolicy.brainInstruction(tone)',brain)
        self.assertIn('kind!=MessageClass.CONVERSATION',tone)
    def test_executable_policy_harness(self):
        with tempfile.TemporaryDirectory() as out:
            harness=pathlib.Path(__file__).with_name("SageConfirmationToneHarness.java")
            subprocess.run(["javac","-d",out,str(JAVA/"SageConfirmationLearning.java"),str(JAVA/"SageTonePolicy.java"),str(harness)],check=True)
            result=subprocess.run(["java","-cp",out,"com.pineapple.sage.SageConfirmationToneHarness"],check=True,text=True,capture_output=True)
            self.assertIn("12/12 passed",result.stdout)

if __name__ == "__main__": unittest.main(verbosity=2)
