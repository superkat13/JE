#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build_131")
java = root / "app/src/main/java/com/pineapple/sage"
profile = (java / "SageVoiceProfile.java").read_text(encoding="utf-8")
activity = (java / "SageVoiceSettingsActivity.java").read_text(encoding="utf-8")
service = (java / "SageVoiceService.java").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")

checks = {
    "package": 'applicationId = "com.pineapple.sagecommander.stable"' in gradle,
    "version": 'versionCode = 44' in gradle and 'versionName = "1.31.0"' in gradle,
    "recommendation": 'recommendedVoice(TextToSpeech tts)' in profile,
    "uk preference": 'Locale.UK' in profile,
    "offline ranking": 'score += 5000' in profile,
    "quality ranking": 'voice.getQuality() * 10 - voice.getLatency()' in profile,
    "saved selection": 'findVoice(tts.getVoices(), requested)' in profile,
    "rate default": 'getFloat(RATE, 0.90f)' in profile,
    "pitch default": 'getFloat(PITCH, 0.98f)' in profile,
    "natural action": 'Make Sage sound natural' in activity,
    "preview": 'Preview selected voice' in activity,
    "save": 'Save this voice for Sage' in activity,
    "sliders": 'Speech rate' in activity and 'Pitch' in activity,
    "recommended selection": 'SageVoiceProfile.recommendedVoice(tts)' in activity,
    "runtime apply": 'SageVoiceProfile.apply(this, textToSpeech)' in service,
    "old repair removed": 'Fix robotic voice' not in activity,
    "old natural removed": 'button("Natural preset")' not in activity,
    "old british removed": 'button("Cheeky British preset")' not in activity,
    "old clear removed": 'button("Clear and steady preset")' not in activity,
}

failed = []
for label, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " | " + label)
    if not ok:
        failed.append(label)

preview = activity.split('private void preview()', 1)[1].split('private Voice selectedVoice()', 1)[0]
if 'save("CUSTOM"' in preview:
    failed.append("preview persisted selection")

if failed:
    raise SystemExit("Sage 1.31 natural voice regression failed: " + ", ".join(failed))
print("Sage 1.31 natural voice regression passed")
