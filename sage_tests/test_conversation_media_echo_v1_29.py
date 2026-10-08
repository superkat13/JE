#!/usr/bin/env python3
"""Executable regressions for the Sage 1.29 conversation/media/echo slice."""

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build_129")
REPO = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"
paths = {
    "state": JAVA / "SageConversationStateMachine.java",
    "voice": JAVA / "SageVoiceService.java",
    "diagnostics": JAVA / "SageDiagnostics.java",
    "media": JAVA / "SageMediaSessionBridge.java",
    "lifecycle": JAVA / "SageMediaCaptureLifecycle.java",
    "commands": JAVA / "SageCommandEngine.java",
    "main": JAVA / "MainActivity.java",
}
source = {name: path.read_text(encoding="utf-8") for name, path in paths.items()}

checks = {
    "stable package and version": 'applicationId = "com.pineapple.sagecommander.stable"' in
        (ROOT / "app/build.gradle.kts").read_text() and
        'versionName = "1.29.0"' in (ROOT / "app/build.gradle.kts").read_text() and
        "versionCode = 41" in (ROOT / "app/build.gradle.kts").read_text(),
    "unique turn and generation": "turnSequence" in source["state"] and
        "activeGeneration" in source["state"],
    "partial display only": "COMMAND_PARTIAL" in source["state"] and
        '"partial_transcript_ui_only"' in source["state"] and '"ui_only"' in source["state"],
    "one accepted final latch": "completed" in source["state"] and
        '"turn_already_completed"' in source["state"],
    "stale generation rejected": '"stale_recognizer_generation"' in source["state"],
    "normalized duplicate rejected": '"duplicate_transcript_window"' in source["state"],
    "strong wake and debounce": "startsWithWakePhrase" in source["voice"] and
        "WAKE_DEBOUNCE_MS" in source["voice"] and '"wake_debounce"' in source["state"],
    "tts fingerprint recorded": "lastSpokenForEcho" in source["voice"] and
        "lastSpokenFinishedAtMs" in source["voice"],
    "tts echo rejected": "fingerprintsMatch" in source["state"] and
        '"speaker_echo"' in source["state"],
    "all audio classifications": all(value in source["state"] for value in (
        "OWNER_SPEECH", "SAGE_TTS", "ACTIVE_MEDIA", "DUPLICATE", "UNKNOWN_AUDIO")),
    "media authorization boundary": '"media_without_authorized_wake"' in source["state"] and
        "captureAuthorizedByWakeOrPushToTalk" in source["voice"],
    "media pause or duck": "pauseOrDuckForAuthorizedCapture" in source["media"] and
        "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK" in source["media"],
    "media restoration": "restoreAfterCapture" in source["media"] and
        '"listening_ended_or_cancelled"' in source["media"],
    "push to talk remains": "ACTION_LISTEN_NOW" in source["voice"],
    "typed input remains": "ACTION_TYPED_COMMAND" in source["voice"] and
        "typedTranscript" in source["voice"] and "textToSpeech.stop()" in source["voice"],
    "natural follow-up context": "CONVERSATION_WINDOW_MS" in source["voice"] and
        "beginConversationListening" in source["voice"],
    "memory save atomic": "SageMemoryStore.SaveResult.SAVED" in source["commands"] and
        '"I saved that in my memory."' in source["commands"],
    "diagnostic transcript type": "transcript_type=" in source["diagnostics"],
    "diagnostic normalization": "normalized=" in source["diagnostics"],
    "diagnostic reason": "rejection=" in source["diagnostics"],
    "diagnostic wake and media": "wake_state=" in source["diagnostics"] and
        "media_state=" in source["diagnostics"],
    "diagnostic echo source": "echo_classification=" in source["diagnostics"],
    "diagnostic dispatch count": "dispatch_count=" in source["diagnostics"],
    "diagnostic latency": "latency_ms=" in source["diagnostics"],
}

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(("PASS" if passed else "FAIL") + ": " + name)
if failed:
    raise SystemExit("Sage 1.29 conversation failures: " + "; ".join(failed))

java = shutil.which("java")
javac = shutil.which("javac")
if not java or not javac:
    raise SystemExit("JDK required for executable conversation regression")
with tempfile.TemporaryDirectory(prefix="sage-conversation-129-") as target:
    subprocess.run([javac, "-d", target, str(paths["state"]), str(paths["lifecycle"]),
                    str(REPO / "sage_tests/SageConversationMediaEchoHarness.java")], check=True)
    subprocess.run([java, "-cp", target,
                    "com.pineapple.sage.SageConversationMediaEchoHarness"], check=True)

print(f"All {len(checks)} Sage 1.29 conversation/media/echo source checks passed.")
