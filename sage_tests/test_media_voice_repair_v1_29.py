#!/usr/bin/env python3
import pathlib
import sys
import unittest


ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "sage_build_129")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"


class MediaVoiceRepairTests(unittest.TestCase):
    def source(self, name):
        return (JAVA / name).read_text(encoding="utf-8")

    def test_media_action_closes_natural_conversation(self):
        engine = self.source("SageCommandEngine.java")
        service = self.source("SageVoiceService.java")
        for token in (
            "freshWakeAfterAction", "Result.media", "Result.quietMedia",
            "closeConversationWindow();", "commandEngine.cancelFollowUp();",
            '"ACTION BOUNDARY"', "fresh_wake_required=true",
        ):
            self.assertIn(token, engine + service)
        self.assertLess(service.index("if (result.freshWakeAfterAction)"),
                        service.index("if (commandEngine.isAwaitingFollowUp())"))

    def test_media_foreground_blocks_automatic_reopen(self):
        access = self.source("SageAccessibilityService.java")
        service = self.source("SageVoiceService.java")
        self.assertIn("activePackageName()", access)
        self.assertIn("isMediaAppForeground()", access)
        self.assertIn('value.contains("youtube")', access)
        self.assertIn("|| SageAccessibilityService.isMediaAppForeground()", service)
        self.assertIn("authorized_wake_required", service)

    def test_media_session_uses_playing_controller_and_audio_fallback(self):
        bridge = self.source("SageMediaSessionBridge.java")
        self.assertIn("preferredController(controllers)", bridge)
        self.assertIn("isPlayingState(code) || musicActive", bridge)
        self.assertIn('"MEDIA SNAPSHOT"', bridge)
        self.assertIn("after_media_action_1200ms", self.source("SageVoiceService.java"))

    def test_youtube_and_semantic_video_actions_are_media_boundaries(self):
        engine = self.source("SageCommandEngine.java")
        self.assertGreaterEqual(engine.count("Result.media(\"Opening YouTube."), 2)
        self.assertIn("asMediaBoundary(openUrl(url, \"Searching YouTube", engine)
        self.assertIn('Result.quietExternal("Tapped " + target + ".")', engine)
        self.assertIn('Result.quietMedia("Playing.")', engine)

    def test_voice_studio_is_real_and_manifest_registered(self):
        activity = self.source("SageVoiceSettingsActivity.java")
        profile = self.source("SageVoiceProfile.java")
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        main = self.source("MainActivity.java")
        for token in (
            "Preview selected voice", "Save for Sage", "Natural preset",
            "Cheeky British preset", "Install or manage Android voices",
            "TextToSpeech", "UtteranceProgressListener",
        ):
            self.assertIn(token, activity)
        for token in (
            "voice.getQuality()", "isNetworkConnectionRequired()", "getDefaultEngine()",
            "setSpeechRate", "setPitch", "bestVoice", "storedDiagnostic",
        ):
            self.assertIn(token, profile)
        self.assertIn(".SageVoiceSettingsActivity", manifest)
        self.assertIn("new Intent(this, SageVoiceSettingsActivity.class)", main)

    def test_voice_profile_is_applied_and_exported(self):
        service = self.source("SageVoiceService.java")
        repair = self.source("SageRepairManager.java")
        self.assertGreaterEqual(service.count("SageVoiceProfile.apply(this, textToSpeech)"), 3)
        self.assertIn('"TTS PROFILE"', service)
        self.assertIn('packet.put("tts_profile"', repair)
        self.assertIn("## Voice output", repair)
        self.assertIn("Media and external-action boundary diagnostics are present.", repair)


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(MediaVoiceRepairTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
