#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build_131")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"
GRADLE = ROOT / "app/build.gradle.kts"
STRINGS = ROOT / "app/src/main/res/values/strings.xml"
MAIN = JAVA / "MainActivity.java"
HEALTH = JAVA / "SageBrainHealth.java"
VOICE = JAVA / "SageVoiceService.java"
STATE = JAVA / "SageConversationStateMachine.java"
AUTONOMY = JAVA / "SageAutonomyActivity.java"

for path in (GRADLE, STRINGS, MAIN, HEALTH, VOICE, STATE, AUTONOMY):
    if not path.is_file():
        raise SystemExit(f"missing reconstructed file: {path}")

gradle = GRADLE.read_text(encoding="utf-8")
strings = STRINGS.read_text(encoding="utf-8")
main = MAIN.read_text(encoding="utf-8")
health = HEALTH.read_text(encoding="utf-8")
voice = VOICE.read_text(encoding="utf-8")
state = STATE.read_text(encoding="utf-8")
autonomy = AUTONOMY.read_text(encoding="utf-8")

checks = [
    ('permanent package', 'applicationId = "com.pineapple.sagecommander.stable"' in gradle),
    ('1.31 versionCode', 'versionCode = 44' in gradle),
    ('1.31 versionName', 'versionName = "1.31.0"' in gradle),
    ('1.31 visible label', '<string name="app_name">Sage Commander 1.31.0</string>' in strings),
    ('presence field exists', 'brainPresenceText' in main),
    ('presence strip uses Brain health source', 'SageBrainHealth.presence(this)' in main),
    ('presence refresh piggybacks Brain refresh', 'refreshBrainPresence();' in main),
    ('presence strip opens existing Brain test', 'startActivity(new Intent(this, SageBrainTestActivity.class))' in main),
    ('presence is not another button', 'makeButton("Brain presence' not in main),
    ('local ready state visible', 'Ready locally' in health),
    ('local thinking state visible', 'Thinking locally' in health),
    ('Dell thinking state visible', 'Thinking on Dell' in health),
    ('fallback state visible', 'Fallback used' in health),
    ('timeout state visible', 'Timed out' in health),
    ('cancelled state visible', 'Cancelled' in health),
    ('error state visible', 'Needs attention' in health),
    ('last successful response visible', 'last reply ready' in health),
    ('existing health indicator retained', 'static String indicator(Context c)' in health),
    ('existing snapshot telemetry retained', 'static String snapshot(Context c)' in health),
    ('conversation state machine retained', 'COMMAND_LISTENING' in state and 'ECHO_GUARD' in state),
    ('voice service retained', 'class SageVoiceService' in voice),
    ('Red Queen autonomy boundary retained', 'SageRedQueenSession.isUnlocked(this)' in autonomy),
    ('Forge compatibility retained', 'requireForgeAutonomyReady' in autonomy),
]

failed = []
for label, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + label)
    if not ok:
        failed.append(label)

patch = Path('sage_patches/brain_presence_v1_31.py').read_text(encoding='utf-8')
for forbidden in ('Runtime.getRuntime().exec', 'ProcessBuilder', 'java.lang.Process', 'su -c', 'adb shell'):
    ok = forbidden not in patch
    print(('PASS' if ok else 'FAIL') + ' | no execution primitive: ' + forbidden)
    if not ok:
        failed.append('execution primitive ' + forbidden)

if failed:
    raise SystemExit('Sage 1.31 Brain presence regression failed: ' + ', '.join(failed))
print('Sage 1.31 compact Brain presence regression passed')
