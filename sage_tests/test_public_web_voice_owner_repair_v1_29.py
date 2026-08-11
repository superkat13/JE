#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build_129")
java = root / "app/src/main/java/com/pineapple/sage"
command = (java / "SageCommandEngine.java").read_text(encoding="utf-8")
host = (java / "SageHostInspectorActivity.java").read_text(encoding="utf-8")
inspector = (java / "SageHostInspector.java").read_text(encoding="utf-8")
owner = (java / "SageOwnerExperience.java").read_text(encoding="utf-8")
voice = (java / "SageVoiceService.java").read_text(encoding="utf-8")

checks = {
    "normal URL content ban removed": "I will not open onion or dark-web links." not in command,
    "normal URL launch retained": "looksLikeWebsite(target)" in command and "openUrl(normalizeUrl(target)" in command,
    "private host scope retained": "SageNetworkScanner.isPrivate" in inspector and "saved private-LAN snapshot" in inspector,
    "host UI public website handoff": "looksLikePublicWebsite(target)" in host and "PUBLIC WEB ROUTE" in host and "Intent.ACTION_VIEW" in host,
    "public site never inspected as host": "if(looksLikePublicWebsite(target)){openPublicWebsite(target);return;}" in host,
    "owner-aware candidate API": "recoverCandidate(Context context, ArrayList<String> choices, String selected)" in owner,
    "easter egg alternative preference": "SageEasterEggStore.find(context, choice)" in owner,
    "learned alias alternative preference": "savedAlias(context, normalized)" in owner,
    "cuss owner phrase preference": 'normalized.equals("you can cuss around me")' in owner,
    "voice uses owner-aware recovery": "recoverCandidate(SageVoiceService.this, finalChoices, candidate)" in voice,
    "existing state-machine path retained": ".finalTranscript(finalChoices.toString(), candidate, confidence" in voice,
    "existing confidence gate retained": "LOW_COMMAND_CONFIDENCE" in voice,
    "existing echo boundary retained": "ECHO_TEXT_WINDOW_MS" in voice and "speaker_echo" in voice,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + ": " + name)
if failed:
    raise SystemExit("public web / owner voice repair failures: " + "; ".join(failed))
print(f"All {len(checks)} public-web / owner-voice checks passed.")
