#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_monster_mode_v1_29.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
monster = (java / "SageMonsterMode.java").read_text()
session = (java / "SageRedQueenSession.java").read_text()
redqueen = (java / "SageRedQueenActivity.java").read_text()
voice = (java / "SageVoiceService.java").read_text()

checks = {
    "monster class exists": "final class SageMonsterMode" in monster,
    "monster requires active Red Queen": "&& SageRedQueenSession.isUnlocked(context)" in monster,
    "monster enable requires owner": "if (enabled && !SageRedQueenSession.isUnlocked(context)) return false;" in monster,
    "owner session is 60 minutes": "private static final long SESSION_MS = 60L * 60L * 1000L;" in session,
    "workspace timer is 60 minutes": "private static final long INACTIVITY_MS = 60L * 60L * 1000L;" in redqueen,
    "background switch no longer locks": 'SageRedQueenSession.lock(this, "app_backgrounded")' not in redqueen,
    "workspace destroy no longer locks": 'SageRedQueenSession.lock(this, "workspace_closed")' not in redqueen,
    "explicit owner control visible": "Monster Mode: ON" in redqueen and "Monster Mode: OFF" in redqueen,
    "minimum command window is layered": "SageMonsterMode.commandMinimumMillis(this" in voice,
    "complete silence window is layered": "SageMonsterMode.completeSilenceMillis(this" in voice,
    "possible silence window is layered": "SageMonsterMode.possibleSilenceMillis(this" in voice,
    "alternate candidates are diagnosed": 'SageMonsterMode.recordCandidates(this, "final", combinedChoices, candidate);' in voice,
    "selector diagnostics are additive": 'SageDiagnostics.appendEvent(this, "VOICE SELECTION"' in voice,
    "state machine command listening preserved": "COMMAND_LISTENING" in voice,
    "state machine finalizing preserved": "FINALIZING" in voice,
    "state machine echo guard preserved": "ECHO_GUARD" in voice,
    "package identity preserved": 'applicationId = "com.pineapple.sagecommander.stable"' in (root / "app/build.gradle.kts").read_text(),
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " | " + name)
if failed:
    raise SystemExit("Monster Mode regression failed: " + ", ".join(failed))
print("Monster Mode additive regression passed")
