#!/usr/bin/env python3
import pathlib
import sys
import unittest


ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "sage_build_129")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"


class ExternalActionLifecycleTests(unittest.TestCase):
    def source(self, name):
        return (JAVA / name).read_text(encoding="utf-8")

    def test_external_actions_close_turn_without_foreground_package_race(self):
        engine = self.source("SageCommandEngine.java")
        for token in (
            '"external_ui"', "Result.external", "Result.quietExternal",
            'type=semantic_tap source_package=', 'type=ordinal_tap source_package=',
            'return Result.quietExternal("Tapped " + target + ".")',
            'return Result.quietExternal(successMessage)',
        ):
            self.assertIn(token, engine)
        tap_start = engine.index("private Result tapText")
        tap_end = engine.index("private Result typeText", tap_start)
        self.assertNotIn("isMediaAppForeground", engine[tap_start:tap_end])

    def test_service_records_typed_boundary_before_follow_up_logic(self):
        service = self.source("SageVoiceService.java")
        self.assertIn('"ACTION BOUNDARY"', service)
        self.assertIn('"kind=" + result.actionBoundary', service)
        self.assertIn('"media".equals(result.actionBoundary)', service)
        self.assertLess(service.index("if (result.freshWakeAfterAction)"),
                        service.index("if (commandEngine.isAwaitingFollowUp())"))

    def test_self_repair_detects_the_exact_physical_trace(self):
        repair = self.source("SageRepairManager.java")
        policy = self.source("SageExternalActionPolicy.java")
        for token in (
            "SageExternalActionPolicy.hasLifecycleViolation(events)",
            "external_action_lifecycle_violation",
            "unauthorized conversation-listening reopen",
            '"modify_sage_source"', '"add_regression_tests"',
        ):
            self.assertIn(token, repair)
        for token in (
            '"detail=tapped "', '"detail=going home."',
            'lower.indexOf("conversation open", outcome)',
            'lower.indexOf("action boundary", outcome)',
        ):
            self.assertIn(token, policy)

    def test_jvm_regression_covers_bad_good_and_conversation_traces(self):
        test = (ROOT / "app/src/test/java/com/pineapple/sage/"
                "SageExternalActionPolicyTest.java").read_text(encoding="utf-8")
        self.assertIn("detectsPhysicalTabletClickVideoReopenTrace", test)
        self.assertIn("acceptsFreshWakeBoundaryBeforeAnyLaterConversation", test)
        self.assertIn("ignoresOrdinaryConversationalFollowUp", test)
        self.assertIn('testImplementation("junit:junit:4.13.2")',
                      (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"))


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ExternalActionLifecycleTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
