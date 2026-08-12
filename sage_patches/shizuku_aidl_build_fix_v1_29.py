#!/usr/bin/env python3
"""Enable Android AIDL generation for Sage's typed Shizuku UserService.

AGP 9 does not generate the typed Binder interface unless the app module explicitly
enables AIDL. This patch keeps the interface typed and avoids replacing it with a
generic command channel.
"""
from pathlib import Path
import sys


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: shizuku_aidl_build_fix_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    gradle = root / "app/build.gradle.kts"
    aidl = root / "app/src/main/aidl/com/pineapple/sage/ISageShizukuPower.aidl"
    if not gradle.is_file():
        raise SystemExit("Shizuku AIDL fix missing app/build.gradle.kts")
    if not aidl.is_file():
        raise SystemExit("Shizuku AIDL fix missing typed interface")

    text = gradle.read_text(encoding="utf-8")
    if "aidl = true" not in text:
        marker = "android {\n"
        if marker not in text:
            raise SystemExit("Shizuku AIDL fix could not find android block")
        text = text.replace(marker, "android {\n    buildFeatures {\n        aidl = true\n    }\n", 1)
        gradle.write_text(text, encoding="utf-8")

    if "aidl = true" not in gradle.read_text(encoding="utf-8"):
        raise SystemExit("Shizuku AIDL generation was not enabled")
    print("Enabled AGP AIDL generation for typed Shizuku UserService")


if __name__ == "__main__":
    main()
