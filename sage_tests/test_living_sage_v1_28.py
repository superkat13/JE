#!/usr/bin/env python3
from pathlib import Path
import sys


if len(sys.argv) != 2:
    raise SystemExit("usage: test_living_sage_v1_28.py <reconstructed-source>")

root = Path(sys.argv[1])
gradle = (root / "app/build.gradle.kts").read_text()
manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
strings = (root / "app/src/main/res/values/strings.xml").read_text()

checks = {
    "stable package": 'applicationId = "com.pineapple.sagecommander.stable"' in gradle,
    "version code 40": "versionCode = 40" in gradle,
    "version 1.28.0": 'versionName = "1.28.0"' in gradle,
    "launcher label 1.28.0": '<string name="app_name">Sage Commander 1.28.0</string>' in strings,
    "permanent release signing": (
        'getByName("release")' in gradle
        and 'signingConfig = signingConfigs.getByName("sagePermanentSigning")' in gradle
    ),
    "not test-only": 'android:testOnly="true"' not in manifest,
    "not debuggable": 'android:debuggable="true"' not in manifest,
    "no shared-user identity change": "android:sharedUserId=" not in manifest,
}

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'}: {name}")
if failed:
    raise SystemExit("Sage 1.28 production identity failures: " + ", ".join(failed))
