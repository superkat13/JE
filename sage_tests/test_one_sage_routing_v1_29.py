#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_one_sage_routing_v1_29.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
main = (java / "MainActivity.java").read_text()
store = (java / "SageWakeProfileStore.java").read_text()
voice = (java / "SageVoiceService.java").read_text()
gradle = (root / "app/build.gradle.kts").read_text()

checks = {
    "normal profile label is Sage": '"Sage",\n            "Red Queen phrase",\n            "Run a command"' in main,
    "new profile UI hides Brain mode": "SageWakeProfileStore.MODE_BRAIN," not in main,
    "new profile UI does not label Sage Brain": '"Sage Brain"' not in main,
    "help says specialists are internal": "Sage chooses Brain and other specialists internally." in main,
    "normal wake delegates specialist selection": "Sage decides whether the request belongs to Brain, device control, Forge, creative tools, or another installed specialist." in main,
    "red queen remains wake choice": "SageWakeProfileStore.MODE_RED_QUEEN" in main,
    "command shortcut remains wake choice": "SageWakeProfileStore.MODE_COMMAND" in main,
    "legacy Brain constant preserved": 'MODE_BRAIN = "brain"' in store,
    "legacy Brain profile decode preserved": "normalizeMode" in store and "MODE_BRAIN.equals(mode)" in store,
    "legacy Brain runtime preserved": "SageWakeProfileStore.MODE_BRAIN.equals(profile.mode)" in voice and "forceBrainForNextCommand = true;" in voice,
    "red queen runtime preserved": "SageWakeProfileStore.MODE_RED_QUEEN.equals(profile.mode)" in voice,
    "legacy Brain label is compatibility-only": 'return "Sage (legacy direct-Brain shortcut)";' in store,
    "package identity preserved": 'applicationId = "com.pineapple.sagecommander.stable"' in gradle,
    "version identity preserved": 'versionCode = 41' in gradle and 'versionName = "1.29.0"' in gradle,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " | " + name)
if failed:
    raise SystemExit("Checkpoint 1 one-Sage routing failed: " + ", ".join(failed))
print("Checkpoint 1 one-Sage routing regression passed")
