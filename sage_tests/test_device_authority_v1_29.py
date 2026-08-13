#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_device_authority_v1_29.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
receiver = (java / "SageDeviceAdminReceiver.java").read_text()
authority = (java / "SageDeviceAuthority.java").read_text()
activity = (java / "SageDeviceAuthorityActivity.java").read_text()
redqueen = (java / "SageRedQueenActivity.java").read_text()
bridge = (java / "SageAuthorityBridgeActivity.java").read_text()
policy = (root / "app/src/main/res/xml/sage_device_admin.xml").read_text()
build = (root / "app/build.gradle.kts").read_text()

checks = {
    "package identity preserved": 'applicationId = "com.pineapple.sagecommander.stable"' in build,
    "real DeviceAdminReceiver": "extends DeviceAdminReceiver" in receiver,
    "admin status uses Android source of truth": "isAdminActive" in authority,
    "device owner status uses Android source of truth": "isDeviceOwnerApp" in authority,
    "profile owner status uses Android source of truth": "isProfileOwnerApp" in authority,
    "no fake root claim": "does not claim root" in authority,
    "ADB device-owner command is visible not executed": "adb shell dpm set-device-owner" in authority and "Runtime.getRuntime" not in authority,
    "device admin activation uses Android confirmation": "ACTION_ADD_DEVICE_ADMIN" in activity,
    "developer settings route exists": "ACTION_APPLICATION_DEVELOPMENT_SETTINGS" in activity,
    "accessibility settings route exists": "ACTION_ACCESSIBILITY_SETTINGS" in activity,
    "manifest receiver permission": "android.permission.BIND_DEVICE_ADMIN" in manifest,
    "manifest receiver and activity": ".SageDeviceAdminReceiver" in manifest and ".SageDeviceAuthorityActivity" in manifest,
    "minimal no-policy admin xml": "<uses-policies />" in policy,
    "Red Queen consolidates authority into one exclusive shell surface": '"Shell Authority"' in redqueen and "SageAuthorityBridgeActivity.class" in redqueen,
    "duplicate Device Authority card removed": '"Device Authority"' not in redqueen and "SageDeviceAuthorityActivity.class" not in redqueen,
    "shell authority retains device-admin owner action": "requestAdmin()" in bridge and "SageDeviceAdminReceiver" in bridge,
    "existing authority architecture retained": all((java / "SageAuthority.java").read_text().find(token) >= 0 for token in (
        "default_assistant", "red_queen_authority", "forge_trust", "tablet_brain")),
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'}: {name}")
if failed:
    raise SystemExit("Device authority failures: " + ", ".join(failed))
