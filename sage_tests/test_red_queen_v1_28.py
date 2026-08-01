#!/usr/bin/env python3
from pathlib import Path
import sys


if len(sys.argv) != 2:
    raise SystemExit("usage: test_red_queen_v1_28.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
activity = (java / "SageRedQueenActivity.java").read_text()
session = (java / "SageRedQueenSession.java").read_text()
vault = (java / "SageRedQueenVault.java").read_text()
command = (java / "SageCommandEngine.java").read_text()
workbench = (java / "SageWorkbenchActivity.java").read_text()
manifest = (root / "app/src/main/AndroidManifest.xml").read_text()

checks = {
    "voice never unlocks": "Owner authentication required." in command
        and "SageRedQueenSession.unlock" not in command,
    "device credential authentication": "createConfirmDeviceCredentialIntent" in activity
        and "isDeviceSecure" in activity,
    "rate limited failures": "FAILURE_BLOCK_MS" in session and "failures >= 3" in session,
    "process local secure state": "static volatile long unlockedUntilMs" in session,
    "inactivity lock": "INACTIVITY_MS" in activity and "inactivity_timeout" in activity,
    "background lock": "onStop" in activity and "app_backgrounded" in activity,
    "device-lock gate": "isDeviceLocked" in session,
    "recent-app protection": "FLAG_SECURE" in activity,
    "encrypted no-backup vault": "AndroidKeyStore" in vault
        and "AES/GCM/NoPadding" in vault and "getNoBackupFilesDir" in vault,
    "authenticated private reads": "Owner authentication required." in vault
        and "SageRedQueenSession.isUnlocked" in vault,
    "audit log": "appendAudit" in vault and "authentication_failed" in session,
    "lifecycle phrases": all(value in activity + command for value in (
        "Owner authentication required.", "Red Queen Mode activated.", "Red Queen secured.")),
    "black crimson workspace": "Color.BLACK" in activity and "Color.rgb(180, 20, 40)" in activity,
    "functional areas only": all(value in activity for value in (
        "Package Lab", "File Lab", "Model Lab", "Network Operations", "Activity / Audit")),
    "deferred areas labeled": "DEFERRED" in activity and "OSINT Desk" in activity
        and "Digital Forensics" in activity,
    "manual Workbench entry": "SageRedQueenActivity.class" in workbench,
    "non-exported activity": '<activity android:name=".SageRedQueenActivity" android:exported="false" />' in manifest,
}

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'}: {name}")
if failed:
    raise SystemExit("Red Queen 1.28 failures: " + ", ".join(failed))
