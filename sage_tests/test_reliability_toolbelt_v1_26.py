#!/usr/bin/env python3
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
REPO = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"

files = {
    "gradle": ROOT / "app/build.gradle.kts",
    "manifest": ROOT / "app/src/main/AndroidManifest.xml",
    "state": JAVA / "SageConversationStateMachine.java",
    "voice": JAVA / "SageVoiceService.java",
    "brain": JAVA / "SageBrainManager.java",
    "brain_health": JAVA / "SageBrainHealth.java",
    "brain_test": JAVA / "SageBrainTestActivity.java",
    "native": ROOT / "app/src/main/cpp/sage_brain.cpp",
    "accessibility": JAVA / "SageAccessibilityService.java",
    "commands": JAVA / "SageCommandEngine.java",
    "toolbelt": JAVA / "SageToolbeltActivity.java",
}
source = {name: path.read_text() for name, path in files.items()}

checks = {
    "stable package": 'applicationId = "com.pineapple.sagecommander.stable"' in source["gradle"],
    "version code 39": "versionCode = 39" in source["gradle"],
    "version 1.27.0": 'versionName = "1.27.0"' in source["gradle"],
    "release signing": 'getByName("release")' in source["gradle"] and "sagePermanentSigning" in source["gradle"],
    "ten lifecycle states": all(value in source["state"] for value in (
        "IDLE_WAKE", "WAKE_ACCEPTED", "COMMAND_LISTENING", "FINALIZING",
        "DISPATCHING", "SPEAKING", "ECHO_GUARD", "CONVERSATION_LISTENING",
        "CLOSED", "ERROR")),
    "turn id": "activeTurnId" in source["state"] and "turnSequence" in source["state"],
    "recognizer generation": "activeGeneration" in source["state"],
    "partial UI only": '"partial_transcript_ui_only"' in source["state"],
    "duplicate final latch": "completed" in source["state"] and '"turn_already_completed"' in source["state"],
    "normalized dedup": '"duplicate_transcript_window"' in source["state"],
    "wake debounce": '"wake_debounce"' in source["state"],
    "bounded retry": "retryUsed" in source["state"] and "retryIncomplete" in source["state"],
    "media trust boundary": '"media_without_authorized_wake"' in source["state"],
    "tts fingerprint": "fingerprintsMatch" in source["state"] and '"speaker_echo"' in source["state"],
    "source classification": "DEVICE_MEDIA" in source["state"] and "SAGE_TTS" in source["state"],
    "active media detection": "isPlaybackActive" in source["voice"],
    "media capture restore": "pauseOrDuckForAuthorizedCapture" in source["voice"] and "restoreAfterCapture" in source["voice"],
    "echo counter": "echoRejected" in source["voice"],
    "push to talk authorization": "captureAuthorizedByWakeOrPushToTalk" in source["voice"],
    "brain load coalescing": "loadScheduled" in source["brain"] and "pendingLoadCallbacks" in source["brain"],
    "native cancellation": "nativeCancelGeneration" in source["brain"] and "g_cancel_requested" in source["native"],
    "native first token metric": "nativeLastFirstTokenLatencyMs" in source["brain"] and "g_last_first_token_latency_ms" in source["native"],
    "native token count": "nativeLastGeneratedTokenCount" in source["brain"] and "g_last_generated_token_count" in source["native"],
    "native generation duration": "nativeLastGenerationDurationMs" in source["brain"] and "g_last_generation_duration_ms" in source["native"],
    "zero text error": "The local model generated no text" in source["native"],
    "load stage": '"load"' in source["brain_health"],
    "generation stage": '"generation"' in source["brain_health"],
    "model name report": "Model name:" in source["brain_health"],
    "model file report": "Model file:" in source["brain_health"],
    "quantization report": "Quantization:" in source["brain_health"],
    "hash report": "Verification hash:" in source["brain_health"],
    "ram report": "Available RAM:" in source["brain_health"],
    "route report": "Active route:" in source["brain_health"],
    "semantic targeting": "semanticIdentityMatches" in source["accessibility"],
    "clickable ancestor": "ancestorText" in source["accessibility"],
    "stable target drawer": "showTargetDrawer" in source["accessibility"],
    "coarse grid": "showGrid" in source["accessibility"],
    "refined grid": "refineGrid" in source["accessibility"],
    "single number container": '"single_container markers="' in source["accessibility"],
    "target revalidation": "resolveCurrentTarget" in source["accessibility"],
    "toolbelt package inspector": "Package Inspector" in source["toolbelt"],
    "toolbelt file hasher": "File Hasher" in source["toolbelt"],
    "toolbelt media inspector": "Media Inspector" in source["toolbelt"],
}

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(("PASS" if passed else "FAIL") + ": " + name)
if failed:
    raise SystemExit("Sage 1.26 reliability failures: " + "; ".join(failed))

java = shutil.which("java") or "/usr/lib/jvm/java-17-openjdk-amd64/bin/java"
javac = shutil.which("javac")
if Path(java).is_file() or shutil.which("java"):
    with tempfile.TemporaryDirectory(prefix="sage-state-machine-") as target:
        compiler = [javac] if javac else [
            java, "-m", "jdk.compiler/com.sun.tools.javac.Main"
        ]
        subprocess.run(compiler + ["-d", target,
            str(files["state"]),
            str(REPO / "sage_tests/SageConversationStateMachineHarness.java"),
        ], check=True)
        subprocess.run([
            java, "-cp", target,
            "com.pineapple.sage.SageConversationStateMachineHarness",
        ], check=True)
else:
    print("SKIP: Java runtime unavailable; GitHub Actions runs the executable harness after JDK setup")

print(f"All {len(checks)} Sage 1.26 source checks passed.")
