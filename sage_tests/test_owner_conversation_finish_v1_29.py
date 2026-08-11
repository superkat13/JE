#!/usr/bin/env python3
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else pathlib.Path("sage_build_129").resolve()
sys.argv[:] = sys.argv[:1]
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"


class OwnerConversationFinishTests(unittest.TestCase):
    def source(self, name):
        return (JAVA / name).read_text(encoding="utf-8")

    def test_uncertain_final_is_held_before_dispatch(self):
        machine = self.source("SageConversationStateMachine.java")
        service = self.source("SageVoiceService.java")
        self.assertIn("AWAITING_CONFIRMATION", machine)
        self.assertIn('"confirmation_required"', machine)
        self.assertIn("boolean requireOwnerConfirmation", machine)
        self.assertIn('"confirmation_required".equals(decision.rejectionReason)', service)
        self.assertLess(service.index('"confirmation_required".equals(decision.rejectionReason)'),
                        service.index("else if (decision.executable)"))
        self.assertNotIn('} else if ("low_confidence".equals(rejection)', service)

    def test_confirmation_answers_do_not_recursively_confirm(self):
        service = self.source("SageVoiceService.java")
        self.assertIn("boolean answeringConfirmation", service)
        self.assertIn("&& !answeringConfirmation", service)
        self.assertIn("dispatch_count=0", service)
        self.assertIn("save_count=1", service)

    def test_less_robotic_preset_is_real_and_preview_does_not_overwrite_it(self):
        voice = self.source("SageVoiceSettingsActivity.java")
        self.assertIn("Fix robotic voice — preview & save", voice)
        self.assertIn('preset("LESS_ROBOTIC", Locale.UK, 0.88f, 0.96f)', voice)
        self.assertIn('"Rate %.2f  •  Pitch %.2f"', voice)
        preview = voice.split("private void preview()", 1)[1].split("private Voice selectedVoice", 1)[0]
        self.assertIn("tts.setSpeechRate(rate())", preview)
        self.assertIn("tts.setPitch(pitch())", preview)
        self.assertNotIn('save("CUSTOM"', preview)

    def test_unfiltered_mode_has_immediate_owner_visible_feedback(self):
        tone = self.source("SageTonePolicy.java")
        self.assertIn("Hell yes—I can cuss with you.", tone)
        self.assertIn("Sass is on.", tone)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK unavailable in local scratch")
    def test_android_free_state_machine_harness(self):
        with tempfile.TemporaryDirectory() as out:
            harness = pathlib.Path(__file__).with_name("SageOwnerConversationFinishHarness.java")
            subprocess.run(["javac", "-d", out,
                            str(JAVA / "SageConversationStateMachine.java"), str(harness)], check=True)
            result = subprocess.run(["java", "-cp", out,
                                     "com.pineapple.sage.SageOwnerConversationFinishHarness"],
                                    check=True, text=True, capture_output=True)
            self.assertIn("8/8 passed", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
