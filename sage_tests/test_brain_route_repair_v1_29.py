#!/usr/bin/env python3
import pathlib, subprocess, sys, tempfile, unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
BUILD=pathlib.Path(sys.argv[1]) if len(sys.argv)>1 and pathlib.Path(sys.argv[1]).is_dir() else None
if BUILD is None:
    BUILD=pathlib.Path(tempfile.mkdtemp(prefix="sage-brain-route-src-"))
    subprocess.run([str(ROOT/"sage_tools/reconstruct_v1_29.sh"),str(BUILD)],cwd=ROOT,check=True,
                   stdout=subprocess.DEVNULL)
sys.argv=sys.argv[:1]

class BrainRouteRepair(unittest.TestCase):
    def text(self,relative): return (BUILD/relative).read_text()
    def test_request_identity_java_and_jni(self):
        manager=self.text("app/src/main/java/com/pineapple/sage/SageBrainManager.java")
        native=self.text("app/src/main/cpp/sage_brain.cpp")
        self.assertIn("request.requestId",manager); self.assertIn("jlong request_id",native)
        self.assertIn("g_active_request_id",native)
    def test_terminal_success_and_watchdog_are_guarded(self):
        policy=self.text("app/src/main/java/com/pineapple/sage/SageBrainRequestPolicy.java")
        voice=self.text("app/src/main/java/com/pineapple/sage/SageVoiceService.java")
        self.assertIn("terminal == Terminal.ACTIVE",policy)
        self.assertIn("watchdogActive = false",policy)
        self.assertIn("if (!brainManager.cancelCurrentRequest()) return;",voice)
    def test_health_is_request_scoped_and_success_ready(self):
        health=self.text("app/src/main/java/com/pineapple/sage/SageBrainHealth.java")
        manager=self.text("app/src/main/java/com/pineapple/sage/SageBrainManager.java")
        self.assertIn("isCurrent(context,requestId,generationId)",health)
        self.assertIn('state=State.READY; status="Sage Brain ready"; lastError=""',manager)
    def test_prompt_is_compact_relevant_and_diagnostic(self):
        manager=self.text("app/src/main/java/com/pineapple/sage/SageBrainManager.java")
        self.assertIn("SageBrainRequestPolicy.compact",manager)
        for value in ("budget=","truncated=","context=","memories="): self.assertIn(value,manager)
    def test_repair_filename_uses_installed_version(self):
        repair=self.text("app/src/main/java/com/pineapple/sage/SageRepairManager.java")
        self.assertIn('"Sage-" + installedVersion + "-repair-bundle.zip"',repair)
        self.assertNotIn("Sage-1.27.0-repair-bundle.zip",repair)
    def test_package_version_and_signer_continuity(self):
        gradle=self.text("app/build.gradle.kts")
        self.assertIn('applicationId = "com.pineapple.sagecommander.stable"',gradle)
        self.assertIn("versionCode = 41",gradle)
        self.assertIn('versionName = "1.29.0"',gradle)
        self.assertIn("sagePermanentSigning",gradle)
    def test_executable_policy_harness(self):
        with tempfile.TemporaryDirectory() as out:
            subprocess.run(["javac","-d",out,
                str(BUILD/"app/src/main/java/com/pineapple/sage/SageBrainRequestPolicy.java"),
                str(ROOT/"sage_tests/SageBrainRouteRepairHarness.java")],check=True)
            result=subprocess.run(["java","-cp",out,"com.pineapple.sage.SageBrainRouteRepairHarness"],
                                  text=True,capture_output=True,check=True)
            self.assertIn("12/12 passed",result.stdout)

if __name__=="__main__": unittest.main(verbosity=2)
