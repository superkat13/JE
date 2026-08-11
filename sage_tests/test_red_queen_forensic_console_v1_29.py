#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_red_queen_forensic_console_v1_29.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
forensics = (java / "SageRedQueenForensics.java").read_text(encoding="utf-8")
activity = (java / "SageRedQueenForensicActivity.java").read_text(encoding="utf-8")
redqueen = (java / "SageRedQueenActivity.java").read_text(encoding="utf-8")
manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")

checks = {
    "forensic sweep exists": "LOCAL FORENSIC SWEEP" in forensics,
    "live build evidence": "Build.FINGERPRINT" in forensics,
    "live authority evidence": "SageDeviceAuthority.isDeviceOwner(context)" in forensics,
    "live Forge evidence": "SageForgeStore.isPaired(context)" in forensics,
    "network interface inventory": "NetworkInterface.getNetworkInterfaces" in forensics,
    "installed package inventory": "getInstalledPackages" in forensics,
    "read-only root hints": '"/system/bin/su"' in forensics and "Runtime.getRuntime().exec" not in forensics and "ProcessBuilder" not in forensics,
    "Red Queen session required": "SageRedQueenSession.isUnlocked(this)" in activity,
    "sweep runs real report": "SageRedQueenForensics.sweep(this)" in activity,
    "report stored in private vault": "SageRedQueenVault.saveRecord" in activity,
    "APK inspection pivot": "SagePackageCenterActivity.class" in activity,
    "file inspection pivot": "SageFileLabActivity.class" in activity,
    "network investigation pivot": "SageNetworkActivity.class" in activity,
    "Forge pivot": "SageForgeActivity.class" in activity,
    "copy report works": "setPrimaryClip" in activity,
    "share report works": "Intent.ACTION_SEND" in activity,
    "hidden activity": '.SageRedQueenForensicActivity' in manifest and 'android:exported="false"' in manifest,
    "Red Queen exposes console": '"Forensic Console"' in redqueen and "SageRedQueenForensicActivity.class" in redqueen,
}

for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " | " + name)
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("Red Queen forensic console regression failed: " + ", ".join(failed))
print("Red Queen forensic console regression passed")
