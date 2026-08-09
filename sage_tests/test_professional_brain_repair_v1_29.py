#!/usr/bin/env python3
import pathlib,subprocess,sys,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
BUILD=pathlib.Path(sys.argv[1]) if len(sys.argv)>1 and pathlib.Path(sys.argv[1]).is_dir() else None
if BUILD is None:
  BUILD=pathlib.Path(tempfile.mkdtemp(prefix="sage-professional-brain-"))
  subprocess.run([str(ROOT/"sage_tools/reconstruct_v1_29.sh"),str(BUILD)],cwd=ROOT,check=True,stdout=subprocess.DEVNULL)
sys.argv=sys.argv[:1]
class ProfessionalBrain(unittest.TestCase):
  def read(self,p):return (BUILD/p).read_text()
  def test_executable_prompt_and_race_policy(self):
    with tempfile.TemporaryDirectory() as out:
      subprocess.run(["javac","-d",out,str(BUILD/"app/src/main/java/com/pineapple/sage/SageBrainRequestPolicy.java"),
                      str(ROOT/"sage_tests/SageProfessionalBrainHarness.java")],check=True)
      value=subprocess.run(["java","-cp",out,"com.pineapple.sage.SageProfessionalBrainHarness"],text=True,capture_output=True,check=True)
      self.assertIn("15/15 passed",value.stdout)
  def test_real_exact_native_route(self):
    manager=self.read("app/src/main/java/com/pineapple/sage/SageBrainManager.java")
    policy=self.read("app/src/main/java/com/pineapple/sage/SageBrainRequestPolicy.java")
    self.assertIn("PromptMode.EXACT_LITERAL",manager);self.assertIn("nativeGenerate(request.requestId",manager)
    self.assertIn("literalMatches",manager);self.assertNotIn("return Reply.answer(prompt.expectedLiteral)",manager)
    self.assertIn("Output only the requested literal",policy)
  def test_total_budget_and_modes(self):
    policy=self.read("app/src/main/java/com/pineapple/sage/SageBrainRequestPolicy.java")
    for token in ("EXACT_LITERAL","CONCISE_ANSWER","ACTION_SELECTION","CONVERSATIONAL",
                  "formattedCharacters","TEMPLATE_RESERVE_CHARS","boundedLine","outputBudget"):
      self.assertIn(token,policy)
  def test_atomic_voice_manager_timeout(self):
    voice=self.read("app/src/main/java/com/pineapple/sage/SageVoiceService.java")
    manager=self.read("app/src/main/java/com/pineapple/sage/SageBrainManager.java")
    self.assertLess(voice.index("timeoutCurrentRequest(managerRequestId"),voice.index("brainInProgress = false;",voice.index("private void brainTimedOut")))
    self.assertIn("requestGeneration,new SageBrainManager.ReplyCallback",voice)
    self.assertIn("Terminal.TIMED_OUT",manager);self.assertIn("Terminal.CANCELLED",manager)
  def test_native_stages_and_generation_only_telemetry(self):
    native=self.read("app/src/main/cpp/sage_brain.cpp")
    for stage in ("prompt_tokenization","prompt_prefill","sampling","first_token","generation","cancellation","complete"):
      self.assertIn(stage,native)
    self.assertIn("generation_finished - generation_only_start",native)
    self.assertIn("g_last_prompt_tokens_per_second",native)
    self.assertIn("std::max(1, std::min(24",native)
  def test_snapshot_reset_and_wake_throttle(self):
    health=self.read("app/src/main/java/com/pineapple/sage/SageBrainHealth.java")
    diagnostics=self.read("app/src/main/java/com/pineapple/sage/SageDiagnostics.java")
    self.assertIn('getSharedPreferences(SNAPSHOT',health);self.assertIn(".edit().clear()",health)
    for field in ("formatted_token_count","prefill_duration_ms","first_token_latency_ms",
                  "generation_only_duration_ms","generated_tokens","terminal_outcome","exact_error"):
      self.assertIn(field,health)
    self.assertIn("aggregatedWakeMisses",diagnostics);self.assertIn("5000L",diagnostics)
    self.assertIn("lastBrainSnapshot",diagnostics)
  def test_repair_metadata_and_reproduction(self):
    repair=self.read("app/src/main/java/com/pineapple/sage/SageRepairManager.java")
    gradle=self.read("app/build.gradle.kts")
    self.assertIn("BuildConfig.SOURCE_BRANCH",repair);self.assertIn("BuildConfig.SOURCE_COMMIT",repair)
    self.assertIn("lastFailedCommand",repair);self.assertIn("brain_snapshot",repair)
    self.assertNotIn("agent/sage-1-27-unified-20260801",repair)
    self.assertIn("agent/sage-1-29-recovery-20260801",gradle);self.assertIn("GITHUB_SHA",gradle)
  def test_deterministic_commands_and_continuity(self):
    voice=self.read("app/src/main/java/com/pineapple/sage/SageVoiceService.java")
    gradle=self.read("app/build.gradle.kts")
    route=voice.index("SageCommandEngine.Result result =")
    self.assertLess(voice.index("commandEngine.execute",route),voice.index("beginBrainRequest",route))
    for value in ('applicationId = "com.pineapple.sagecommander.stable"',"versionCode = 41",
                  'versionName = "1.29.0"',"sagePermanentSigning"):self.assertIn(value,gradle)
  def test_q4_is_optional_not_q8_replacement(self):
    manager=self.read("app/src/main/java/com/pineapple/sage/SageBrainManager.java")
    self.assertIn("Optional verified Q4_K_M performance alternative",manager)
    self.assertIn("installed Q8 model is not considered corrupt",manager)
if __name__=="__main__":unittest.main(verbosity=2)
