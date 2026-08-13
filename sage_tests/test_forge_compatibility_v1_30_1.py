#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build_1301")
java = root / "app/src/main/java/com/pineapple/sage"
checks = {
    root / "app/build.gradle.kts": [
        'applicationId = "com.pineapple.sagecommander.stable"',
        'versionCode = 43',
        'versionName = "1.30.1"',
    ],
    root / "app/src/main/res/values/strings.xml": [
        '<string name="app_name">Sage Commander 1.30.1</string>',
    ],
    java / "SageForgeClient.java": [
        'void health(Callback callback)',
        '"GET","/v1/health"',
        'void tools(Callback callback)',
        'authenticated("GET","/v1/tools"',
    ],
    java / "SageAutonomyActivity.java": [
        'requireForgeAutonomyReady',
        'developer.autonomy_dispatch',
        'developer.autonomy_result',
        'Compatibility proven. Queuing Sage',
        'Update and restart Sage Forge 0.3.1 or newer',
        'SageRedQueenSession.isUnlocked(this)',
    ],
    java / "SageForgeActivity.java": [
        'Check Forge autonomy compatibility',
        'autonomy transport READY',
        'Update and restart Sage Forge 0.3.1 or newer',
    ],
}
for path, tokens in checks.items():
    if not path.is_file():
        raise SystemExit(f"missing reconstructed file: {path}")
    text = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            raise SystemExit(f"missing {token!r} in {path}")
print("Sage 1.30.1 Forge compatibility regression checks passed")
