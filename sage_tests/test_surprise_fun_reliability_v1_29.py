#!/usr/bin/env python3
import pathlib
import sys
import unittest


ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "sage_build_129")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"


class SurpriseFunReliabilityTests(unittest.TestCase):
    def source(self, name):
        return (JAVA / name).read_text(encoding="utf-8")

    def test_browser_handoff_tracks_exact_resolved_package(self):
        manager = self.source("SageSurpriseManager.java")
        for token in (
            "ComponentName resolved=intent.resolveActivity",
            'putString("pending_package",expectedPackage)',
            "pendingProviderMatches(expected,pkg)",
            "selectSurpriseVideoForProvider",
            '"unexpected_provider"',
        ):
            self.assertIn(token, manager + self.source("SageAccessibilityService.java"))

    def test_active_youtube_is_not_treated_as_generic_page(self):
        manager = self.source("SageSurpriseManager.java")
        self.assertIn('activeProvider.toLowerCase(Locale.US).contains("youtube")', manager)
        youtube = manager.index('if(provider.equals("youtube"))')
        generic = manager.index("selectSurpriseCurrent", youtube)
        self.assertLess(youtube, generic)

    def test_visible_another_and_stop_controls_are_real(self):
        access = self.source("SageAccessibilityService.java")
        for token in (
            'another.setText("Another")',
            'another.setOnClickListener(v->SageSurpriseManager.execute(service,"another one"))',
            'stop.setText("Stop")',
            'stop.setOnClickListener(v->SageSurpriseManager.execute(service,"stop"))',
            "visible_another=true visible_stop=true",
        ):
            self.assertIn(token, access)

    def test_controlled_rotation_timeout_and_no_global_callback_wipe(self):
        manager = self.source("SageSurpriseManager.java")
        access = self.source("SageAccessibilityService.java")
        for token in (
            "SEED_STEP", "selection_counter", "Math.floorMod",
            "pending_attempts", "expirePending", "selection_timeout",
        ):
            self.assertIn(token, manager)
        self.assertNotIn("removeCallbacksAndMessages(null)", access)

    def test_natural_fun_aliases_and_player_controls_are_filtered(self):
        policy = self.source("SageSurprisePolicy.java")
        for phrase in (
            "one more", "something else", "surprise me again",
            "stop surprising me", "that is enough", "i m fucking bored",
            'v.equals("play")', 'v.equals("pause")', 'v.equals("subscribe")',
        ):
            self.assertIn(phrase, policy)


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(SurpriseFunReliabilityTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
