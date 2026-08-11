#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_monster_mode_v1_29.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
owner = (java / "SageOwnerExperience.java").read_text()
session = (java / "SageRedQueenSession.java").read_text()
redqueen = (java / "SageRedQueenActivity.java").read_text()
voice = (java / "SageVoiceService.java").read_text()
state_machine = (java / "SageConversationStateMachine.java").read_text()

checks = {
    "one Sage owner experience exists": "final class SageOwnerExperience" in owner,
    "owner experience grants no authority": "grant no Android permission" in owner and "Red Queen authority" in owner,
    "no Monster Mode class remains": not (java / "SageMonsterMode.java").exists(),
    "no Monster Mode button remains": "Monster Mode: ON" not in redqueen and "Monster Mode: OFF" not in redqueen,
    "owner session is 60 minutes": "private static final long SESSION_MS = 60L * 60L * 1000L;" in session,
    "workspace timer is 60 minutes": "private static final long INACTIVITY_MS = 60L * 60L * 1000L;" in redqueen,
    "background switch no longer locks": 'SageRedQueenSession.lock(this, "app_backgrounded")' not in redqueen,
    "workspace destroy no longer locks": 'SageRedQueenSession.lock(this, "workspace_closed")' not in redqueen,
    "minimum command window is normal Sage": "SageOwnerExperience.commandMinimumMillis(" in voice,
    "complete silence window is normal Sage": "SageOwnerExperience.completeSilenceMillis(" in voice,
    "possible silence window is normal Sage": "SageOwnerExperience.possibleSilenceMillis(" in voice,
    "alternate candidates are diagnosed": 'SageOwnerExperience.recordCandidates(this, "final"' in voice,
    "state machine command listening preserved": "COMMAND_LISTENING" in state_machine,
    "state machine finalizing preserved": "FINALIZING" in state_machine,
    "state machine echo guard preserved": "ECHO_GUARD" in state_machine,
    "package identity preserved": 'applicationId = "com.pineapple.sagecommander.stable"' in (root / "app/build.gradle.kts").read_text(),
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " | " + name)
if failed:
    raise SystemExit("One Sage owner-experience regression failed: " + ", ".join(failed))
print("One Sage owner-experience additive regression passed")
