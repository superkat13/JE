#!/usr/bin/env python3
"""Advance the recovered Sage 1.27 source to the production 1.28 identity.

This patch is intentionally small and deterministic. Functional 1.28 slices are
applied after it so an early, correctly signed recovery APK can be produced.
"""

from pathlib import Path
import re
import sys


def replace_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"expected exactly one {label}, found {count}")
    return updated


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: living_sage_v1_28.py <reconstructed-source>")

    root = Path(sys.argv[1])
    gradle_path = root / "app/build.gradle.kts"
    manifest_path = root / "app/src/main/AndroidManifest.xml"
    strings_path = root / "app/src/main/res/values/strings.xml"
    gradle = gradle_path.read_text()
    manifest = manifest_path.read_text()
    strings = strings_path.read_text()

    gradle = replace_once(
        gradle,
        r"versionCode\s*=\s*39\b",
        "versionCode = 40",
        "Sage 1.27 versionCode",
    )
    gradle = replace_once(
        gradle,
        r'versionName\s*=\s*"1\.27\.0"',
        'versionName = "1.28.0"',
        "Sage 1.27 versionName",
    )
    strings = replace_once(
        strings,
        r"<string name=\"app_name\">Sage Commander 1\.25\.0</string>",
        '<string name="app_name">Sage Commander 1.28.0</string>',
        "stale launcher label",
    )

    required_gradle = (
        'applicationId = "com.pineapple.sagecommander.stable"',
        'versionCode = 40',
        'versionName = "1.28.0"',
        'signingConfig = signingConfigs.getByName("sagePermanentSigning")',
    )
    for marker in required_gradle:
        if marker not in gradle:
            raise SystemExit(f"release continuity marker missing: {marker}")

    forbidden_manifest = (
        'android:testOnly="true"',
        'android:debuggable="true"',
        'android:sharedUserId=',
    )
    for marker in forbidden_manifest:
        if marker in manifest:
            raise SystemExit(f"production manifest contains forbidden marker: {marker}")

    gradle_path.write_text(gradle)
    strings_path.write_text(strings)
    print("Applied Sage Commander 1.28.0 production identity")


if __name__ == "__main__":
    main()
