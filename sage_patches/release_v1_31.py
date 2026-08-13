#!/usr/bin/env python3
from pathlib import Path
import re
import sys


def sub_once(path, pattern, replacement, label):
    text = path.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(updated, encoding="utf-8")


def text_once(path, old, new, label):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: release_v1_31.py <reconstructed-source>")
    root = Path(sys.argv[1])
    gradle = root / "app/build.gradle.kts"
    strings = root / "app/src/main/res/values/strings.xml"
    sub_once(gradle, r'versionCode\s*=\s*43\b', 'versionCode = 44', "version code")
    sub_once(gradle, r'versionName\s*=\s*"1\.30\.1"', 'versionName = "1.31.0"', "version name")
    text_once(strings,
              '<string name="app_name">Sage Commander 1.30.1</string>',
              '<string name="app_name">Sage Commander 1.31.0</string>',
              "app label")
    current = gradle.read_text(encoding="utf-8")
    if 'applicationId = "com.pineapple.sagecommander.stable"' not in current:
        raise SystemExit("package name changed unexpectedly")
    print("Promoted Sage Commander to 1.31.0 (44) with visible label updated")


if __name__ == "__main__":
    main()
