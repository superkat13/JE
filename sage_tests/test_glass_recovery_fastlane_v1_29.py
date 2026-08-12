#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build_129")
java = root / "app/src/main/java/com/pineapple/sage"
voice = (java / "SageVoiceService.java").read_text(encoding="utf-8")
command = (java / "SageCommandEngine.java").read_text(encoding="utf-8")
fast_path_file = java / "SageFastPath.java"
assert fast_path_file.is_file(), "SageFastPath.java missing"
fast = fast_path_file.read_text(encoding="utf-8")

# Deterministic arithmetic must run inside the existing command engine before open-ended Brain fallback.
needle = "String fastAnswer = SageFastPath.answer(raw);"
assert needle in command
assert command.index(needle) < command.index("SageSurpriseManager.Outcome surprise")
assert '"FAST PATH"' in command
assert "brain_bypassed=true" in command

# Keep the fast path deliberately bounded and local. No network, shell, files, Android authority, or Brain calls.
for forbidden in ("Http", "Socket", "Runtime.getRuntime", "ProcessBuilder", "SageBrain", "Shizuku", "File("):
    assert forbidden not in fast, f"fast path acquired forbidden dependency: {forbidden}"
for required in ("BigDecimal", 'case "+"', 'case "-"', 'case "*"', 'case "/"', "PERCENT_OF"):
    assert required in fast, f"fast path missing {required}"
assert "divide by zero" in fast.lower()

# Android command recognizer errors 1/2 get exactly one bounded retry while preserving the already-authorized turn.
for required in (
    "MAX_COMMAND_NETWORK_RETRIES = 1",
    "COMMAND_NETWORK_RETRY_MS = 650L",
    "SpeechRecognizer.ERROR_NETWORK",
    "SpeechRecognizer.ERROR_NETWORK_TIMEOUT",
    '"COMMAND STT RECOVERY"',
    "authorization_preserved=true partial_executed=false",
    "captureAuthorizedByWakeOrPushToTalk = authorizedCapture",
    "startCommandRecognition(COMMAND_NETWORK_RETRY_MS, true)",
):
    assert required in voice, f"missing command STT recovery guard: {required}"

# Successful and terminal paths must reset the retry budget; partial text is never dispatched from onError.
assert voice.count("commandNetworkRetries = 0;") >= 2
error_start = voice.index("public void onError(int error)")
result_start = voice.index("public void onResults(Bundle results)", error_start)
error_block = voice[error_start:result_start]
assert "handleRecognizedCommand(partialCandidate" not in error_block
assert "dispatch" not in error_block.lower() or "partial_executed=false" in error_block

# This checkpoint is additive around the stabilized state machine, not a state-machine rewrite.
machine = (java / "SageConversationStateMachine.java").read_text(encoding="utf-8")
for state in ("COMMAND_LISTENING", "FINALIZING", "DISPATCHING", "SPEAKING", "ECHO_GUARD"):
    assert state in machine, f"stabilized state missing: {state}"

print("Glass-recovery fast lane and bounded command-STT recovery checks passed")
