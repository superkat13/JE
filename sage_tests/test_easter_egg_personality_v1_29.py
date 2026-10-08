#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_easter_egg_personality_v1_29.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
main = (java / "MainActivity.java").read_text()
voice = (java / "SageVoiceService.java").read_text()
store = (java / "SageEasterEggStore.java").read_text()
gradle = (root / "app/build.gradle.kts").read_text()

checks = {
    "store exists": "class SageEasterEggStore" in store,
    "persistent shared preference": 'KEY = "easter_egg_replies_v1"' in store,
    "exact text reply preserved": "public final String response" in store,
    "red queen phrase reserved": 'phrase.equals("red queen mode")' in store,
    "store grants no executable dispatch": all(token not in store for token in (
        "Runtime.getRuntime", "ProcessBuilder", "startActivity", "SageRedQueenSession",
        "SageForge", "PackageInstaller", "AccessibilityService"
    )),
    "normal Sage UI present": 'easterEggTitle.setText("Sage Easter eggs")' in main,
    "phrase editor present": 'easterEggPhrase.setHint(' in main,
    "reply editor present": 'easterEggReply.setHint("Sage\'s exact reply")' in main,
    "save action present": 'makeButton("Save Easter egg")' in main,
    "remove action present": 'makeButton("Remove typed Easter egg")' in main,
    "voice exact-match dispatch present": "SageEasterEggStore.find(this, cleaned)" in voice,
    "reply uses normal broadcast": 'broadcastLine("Sage", easterEgg.response);' in voice,
    "reply uses existing voice": "speak(easterEgg.response);" in voice,
    "reply cannot fall through to command": "speak(easterEgg.response);\n            return;" in voice,
    "diagnostic event present": '"EASTER EGG"' in voice,
    "red queen runtime still present": "red queen mode" in voice.lower(),
    "package preserved": 'applicationId = "com.pineapple.sagecommander.stable"' in gradle,
    "version preserved": 'versionCode = 41' in gradle and 'versionName = "1.29.0"' in gradle,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " | " + name)
if failed:
    raise SystemExit("Checkpoint 2 Easter egg personality failed: " + ", ".join(failed))
print("Checkpoint 2 Easter egg personality regression passed")
