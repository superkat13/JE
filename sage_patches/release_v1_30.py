#!/usr/bin/env python3
from pathlib import Path
import re
import sys


def replace_regex_once(path, pattern, replacement, label):
    text = path.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, replacement, text, count=1)
    if count != 1:
        raise SystemExit(label + ": expected one match, found " + str(count))
    path.write_text(updated, encoding="utf-8")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: release_v1_30.py <reconstructed-source>")
    root = Path(sys.argv[1])
    gradle = root / "app/build.gradle.kts"
    strings = root / "app/src/main/res/values/strings.xml"
    replace_regex_once(gradle, r'versionCode\s*=\s*41\b', 'versionCode = 42', "version code")
    replace_regex_once(gradle, r'versionName\s*=\s*"1\.29\.0"', 'versionName = "1.30.0"', "version name")
    text = strings.read_text(encoding="utf-8")
    old = '<string name="app_name">Sage Commander 1.29.0</string>'
    new = '<string name="app_name">Sage Commander 1.30.0</string>'
    if text.count(old) != 1:
        raise SystemExit("app label: expected one match")
    strings.write_text(text.replace(old, new, 1), encoding="utf-8")
    current = gradle.read_text(encoding="utf-8")
    if 'applicationId = "com.pineapple.sagecommander.stable"' not in current:
        raise SystemExit("package name changed unexpectedly")
    print("Promoted Sage Commander to 1.30.0 (42) and updated the visible app label")


if __name__ == "__main__":
    main()
