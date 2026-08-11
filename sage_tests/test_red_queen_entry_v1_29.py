#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_red_queen_entry_v1_29.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
voice = (java / "SageVoiceService.java").read_text()
activity = (java / "SageRedQueenActivity.java").read_text()
session = (java / "SageRedQueenSession.java").read_text()

checks = {
    "spoken trigger recognized": 'value.equals("red queen mode")' in voice and 'value.equals("sage glitch")' in voice,
    "spoken trigger opens workspace": "openRedQueenWorkspace" in voice and "SageRedQueenActivity.class" in voice,
    "saved media response is preserved": "if (voiceResponse != null)" in voice and "playVoiceResponse(voiceResponse)" in voice,
    "text fallback exists": "Off with the training wheels" in voice,
    "existing owner session reused": "if (SageRedQueenSession.isUnlocked(this))" in activity and "showWorkspace();" in activity,
    "locked entry still authenticates": "showLocked();" in activity and "authenticate();" in activity,
    "device lock boundary preserved": "isDeviceLocked()" in session,
    "auth rate limit preserved": "canAttempt" in session and "recordFailure" in session,
    "process-local session preserved": "private static volatile long unlockedUntilMs" in session,
    "no automatic unlock from voice": "SageRedQueenSession.unlock" not in voice,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " | " + name)
if failed:
    raise SystemExit("Checkpoint 6 Red Queen entry failed: " + ", ".join(failed))
print("Checkpoint 6 spoken Red Queen entry regression passed")
