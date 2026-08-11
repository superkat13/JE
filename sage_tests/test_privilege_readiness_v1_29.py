#!/usr/bin/env python3
from pathlib import Path
import sys


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: test_privilege_readiness_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    readiness = (java / "SagePrivilegeReadiness.java").read_text()
    activity = (java / "SagePrivilegeReadinessActivity.java").read_text()
    redqueen = (java / "SageRedQueenActivity.java").read_text()
    manifest = (root / "app/src/main/AndroidManifest.xml").read_text()

    require("ROOT: NOT PROVEN" in readiness, "Checkpoint 8 must never infer root from hints")
    require("BOOTLOADER UNLOCK: UNKNOWN FROM ORDINARY APP AUTHORITY" in readiness,
            "Checkpoint 8 must keep bootloader unlock unknown without external evidence")
    require("VERIFIED BOOT STATE: REQUIRES ADB/BOOTLOADER EVIDENCE" in readiness,
            "Checkpoint 8 must require real external verified-boot evidence")
    require("SageDeviceAuthority.isDeviceOwner(context)" in readiness,
            "Checkpoint 8 must preserve real Android authority reporting")
    require("SageForgeStore.isPaired(context)" in readiness,
            "Checkpoint 8 must preserve Forge status")
    require("SagePrivilegeReadinessActivity.class" in redqueen,
            "Red Queen must expose the read-only readiness screen")
    require(".SagePrivilegeReadinessActivity" in manifest,
            "Privilege readiness activity must be private in the manifest")
    require('android:exported="false"' in manifest,
            "Privilege readiness activity must not be exported")
    require("root_proven=false" in activity,
            "Diagnostics must explicitly avoid claiming root")

    combined = readiness + activity
    forbidden = (
        "Runtime.getRuntime().exec", "ProcessBuilder", "Os.execv", "Os.execve",
        'new String[]{"su"', 'new String[] {"su"', "su -c", "fastboot flashing unlock",
        "fastboot oem unlock", "reboot bootloader", "wipe data", "factory reset",
        "dd if=", "magisk --install-module"
    )
    for token in forbidden:
        require(token not in combined, f"Checkpoint 8 must remain read-only; found {token!r}")

    require("adb shell getprop ro.boot.flash.locked" in readiness,
            "Checkpoint 8 should provide owner-run flash-lock evidence command")
    require("adb shell getprop ro.boot.verifiedbootstate" in readiness,
            "Checkpoint 8 should provide owner-run verified-boot evidence command")
    print("Checkpoint 8 privilege readiness regression passed")


if __name__ == "__main__":
    main()
