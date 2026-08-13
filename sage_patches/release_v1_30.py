#!/usr/bin/env python3
"""Promote reconstructed Sage to Commander 1.30.0 / versionCode 42."""
from pathlib import Path
import re
import sys


def replace_regex_once(path: Path, pattern: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(updated, encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: release_v1_30.py <reconstructed-source>")
    root = Path(sys.argv[1])
    gradle = root / "app/build.gradle.kts"
    if not gradle.is_file():
        raise SystemExit("Sage 1.30 release requires reconstructed app/build.gradle.kts")

    replace_regex_once(gradle, r'versionCode\s*=\s*41\b', 'versionCode = 42', "versionCode 42")
    replace_regex_once(gradle, r'versionName\s*=\s*"1\.29\.0"', 'versionName = "1.30.0"', "versionName 1.30.0")

    text = gradle.read_text(encoding="utf-8")
    if 'applicationId = "com.pineapple.sagecommander.stable"' not in text:
        raise SystemExit("Sage 1.30 would break permanent package identity")
    if 'versionCode = 42' not in text or 'versionName = "1.30.0"' not in text:
        raise SystemExit("Sage 1.30 release identity did not stick")
    print("Promoted Sage Commander to 1.30.0 (42) with permanent package identity preserved")


if __name__ == "__main__":
    main()
