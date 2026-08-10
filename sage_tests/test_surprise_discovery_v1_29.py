#!/usr/bin/env python3
import pathlib, subprocess, sys, tempfile, unittest

ROOT=pathlib.Path(sys.argv[1]).resolve() if len(sys.argv)>1 else pathlib.Path("sage_build_129").resolve()
sys.argv[:]=sys.argv[:1]
JAVA=ROOT/"app/src/main/java/com/pineapple/sage"

class SurpriseDiscoveryTests(unittest.TestCase):
    def source(self,name):return (JAVA/name).read_text(encoding="utf-8")
    def test_all_commands_and_real_engine_route(self):
        policy=self.source("SageSurprisePolicy.java");engine=self.source("SageCommandEngine.java")
        for phrase in ("surprise me","surprise me on youtube","something weird","cure my boredom","rabbit hole","another one","stop"):
            self.assertIn(phrase,policy.lower())
        self.assertIn("SageSurpriseManager.execute(context,raw)",engine)
        self.assertLess(engine.index("SageSurpriseManager.execute"),engine.index("executeSemanticSlice(raw, lower)"))
    def test_context_seed_recent_and_single_open(self):
        manager=self.source("SageSurpriseManager.java");policy=self.source("SageSurprisePolicy.java")
        for token in ('session_seed','injectedSeed','last_provider','last_topic','last_title','last_uri','recent','Another'):
            self.assertIn(token.lower(),(manager+policy).lower())
        self.assertNotIn("showNumberOverlay",manager)
        self.assertIn("media.control(\"next\")",manager)
        self.assertIn("internalProvider(provider)",manager)
        self.assertGreaterEqual(manager.count("launchYouTubeSearch"),3)
    def test_youtube_pending_selection_and_honest_routes(self):
        manager=self.source("SageSurpriseManager.java")
        for token in ('pending_semantic_selection','accessibility_fallback','unsupported','youtube.com/results?search_query=','selectSurpriseVideo','I’ll open one verified result when it appears'):
            self.assertIn(token,manager)
        self.assertNotIn("opened successfully",manager.lower())
    def test_accessibility_filters_and_revalidates(self):
        access=self.source("SageAccessibilityService.java");policy=self.source("SageSurprisePolicy.java")
        for token in ('sponsored','advertisement','navigation','isVisibleToUser','isEnabled','semanticIdentityMatches','SageSurprisePolicy.revalidate','immediate_revalidation_failed','clickNodeOrTap'):
            self.assertIn(token,access+policy)
        self.assertLess(access.index("SageSurprisePolicy.revalidate(selected,currentCandidate)"),access.index("boolean opened=service.clickNodeOrTap"))
        self.assertIn("internal_sage_window",access)
        self.assertIn("controlText",policy)
    def test_stop_and_no_duplicate_loop(self):
        manager=self.source("SageSurpriseManager.java");media=self.source("SageMediaSessionBridge.java")
        for token in ('putBoolean("pending",false)','putBoolean("opening",false)','cancelSurprise','stopPlayback','ACTION_STOP','ACTION_PAUSE'):
            self.assertIn(token,manager+media)
        self.assertIn('duplicate_dispatch=false',self.source("SageCommandEngine.java"))
    def test_standard_privacy_boundary(self):
        manager=self.source("SageSurpriseManager.java");policy=self.source("SageSurprisePolicy.java")
        self.assertIn('sage_surprise_standard_v1',manager)
        self.assertIn('red_queen_queried=false',manager)
        self.assertNotIn('SageRedQueenVault',manager)
        self.assertNotIn('sage_red_queen',manager)
        self.assertIn('standardSafe',policy)
    def test_visible_create_card_controls(self):
        main=self.source("MainActivity.java");access=self.source("SageAccessibilityService.java")
        for label in ('"Create"','"Surprise Me"','"YouTube"','"Weird"','"Rabbit Hole"','"Another"','"Stop"','Last surprise selection and route'):
            self.assertIn(label,main)
        self.assertEqual(main.count('addSurpriseButton('),7)
        self.assertIn('showSurpriseStopControl',access)
        self.assertIn('Stop surprise discovery and media',access)
    def test_executable_policy_harness(self):
        with tempfile.TemporaryDirectory() as out:
            harness=pathlib.Path(__file__).with_name("SageSurpriseHarness.java")
            subprocess.run(["javac","-d",out,str(JAVA/"SageSurprisePolicy.java"),str(harness)],check=True)
            result=subprocess.run(["java","-cp",out,"com.pineapple.sage.SageSurpriseHarness"],check=True,text=True,capture_output=True)
            self.assertIn("16/16 passed",result.stdout)

if __name__=="__main__":unittest.main(verbosity=2)
