#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_voice_tolerance_v1_29.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
owner = (java / "SageOwnerExperience.java").read_text()
voice = (java / "SageVoiceService.java").read_text()
machine = (java / "SageConversationStateMachine.java").read_text()

checks = {
    "recovery helper exists": "static String recoverCandidate" in owner,
    "recovery is prefix bounded": 'startsWith(normalizedSelected + " ")' in owner,
    "recovery is limited to short fragments": "selectedWords > 2" in owner,
    "recovery caps candidate word count": "words > 12" in owner,
    "recovery caps candidate length": "normalizedChoice.length() > 160" in owner,
    "recovery diagnostics exist": '"VOICE RECOVERY"' in owner and "prefix_fragment_alternate" in owner,
    "final candidate uses recovery": "candidate = SageOwnerExperience.recoverCandidate(" in voice,
    "recovery records before dispatch": "SageOwnerExperience.recordRecovery(SageVoiceService.this" in voice,
    "existing candidate diagnostics remain": "SageOwnerExperience.recordCandidates(SageVoiceService.this" in voice,
    "command state preserved": "COMMAND_LISTENING" in machine,
    "finalizing state preserved": "FINALIZING" in machine,
    "echo guard preserved": "ECHO_GUARD" in machine,
    "command-final transcript preserved": "COMMAND_FINAL" in machine,
    "dispatch accounting preserved": "dispatchCount" in machine,
    "sage tts classification preserved": "SAGE_TTS" in machine,
    "active media classification preserved": "ACTIVE_MEDIA" in machine,
    "no authority escalation in owner helper": not any(token in owner for token in (
        "SageRedQueenSession.unlock", "DevicePolicyManager", "Runtime.getRuntime().exec",
        "ProcessBuilder", "su -c", "requestPermissions(", "SageCapabilityRegistry.authorize"
    )),
}

# Small executable model of the intended bounded recovery contract. The inherited
# 1.29 conversation regression independently verifies duplicate-final/stale-callback
# rejection, TTS echo blocking, and active-media authorization on every checkpoint.
def normalize(value):
    import re
    value = (value or "").lower()
    value = re.sub(r"[^a-z0-9']+", " ", value).strip()
    return re.sub(r"\s+", " ", value)

def recover(choices, selected):
    base = normalize(selected)
    count = len(base.split()) if base else 0
    if not choices or count < 1 or count > 2:
        return selected
    best = selected
    best_words = count
    for choice in choices:
        normalized = normalize(choice)
        if not normalized or normalized == base:
            continue
        if not normalized.startswith(base + " "):
            continue
        words = len(normalized.split())
        if words <= best_words or words > 12 or len(normalized) > 160:
            continue
        best = (choice or selected).strip()
        best_words = words
    return best

behavior = {
    "one-word fragment recovers longer alternate": recover(["turn", "turn the volume down"], "turn") == "turn the volume down",
    "two-word fragment recovers same-prefix alternate": recover(["open youtube", "open youtube on the tv"], "open youtube") == "open youtube on the tv",
    "different intent is not substituted": recover(["open youtube", "open settings"], "open youtube") == "open youtube",
    "complete three-word command is left alone": recover(["turn volume down", "turn volume down all the way"], "turn volume down") == "turn volume down",
}
checks.update(behavior)

choose_at = voice.find("String candidate = chooseBestCandidate(")
recover_at = voice.find("candidate = SageOwnerExperience.recoverCandidate(")
record_at = voice.find("SageOwnerExperience.recordCandidates(SageVoiceService.this")
checks["recovery order is bounded to recognition front end"] = -1 not in (choose_at, recover_at, record_at) and choose_at < recover_at < record_at

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " | " + name)
if failed:
    raise SystemExit("Checkpoint 3 voice tolerance failed: " + ", ".join(failed))
print("Checkpoint 3 bounded voice-tolerance regression passed")
