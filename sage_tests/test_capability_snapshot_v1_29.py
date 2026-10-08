#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_capability_snapshot_v1_29.py <reconstructed-source>")
root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
snapshot = (java / "SageCapabilitySnapshot.java").read_text()
activity = (java / "SageCapabilitySnapshotActivity.java").read_text()
main = (java / "MainActivity.java").read_text()
voice = (java / "SageVoiceService.java").read_text()
manifest = (root / "app/src/main/AndroidManifest.xml").read_text()

checks = {
    "snapshot class exists": "class SageCapabilitySnapshot" in snapshot,
    "actual accessibility source": "SageAccessibilityService.isReady()" in snapshot,
    "actual admin source": "SageDeviceAuthority.isAdmin(context)" in snapshot,
    "actual device owner source": "SageDeviceAuthority.isDeviceOwner(context)" in snapshot,
    "actual profile owner source": "SageDeviceAuthority.isProfileOwner(context)" in snapshot,
    "actual forge source": "SageForgeStore.isPaired(context)" in snapshot,
    "root not falsely claimed": "Root: NOT CLAIMED" in snapshot,
    "one Sage language": "second assistant" in snapshot,
    "activity is read only": "Runtime.exec" not in activity and "ProcessBuilder" not in activity,
    "normal Sage button": "What Sage can use right now" in main,
    "voice phrase available": "what can you do right now" in voice,
    "voice uses same snapshot": "SageCapabilitySnapshot.report(this)" in voice,
    "manifest activity non exported": 'android:name=".SageCapabilitySnapshotActivity" android:exported="false"' in manifest,
    "package preserved": "com.pineapple.sage" in snapshot,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items(): print(("PASS" if passed else "FAIL") + " | " + name)
if failed: raise SystemExit("Checkpoint 10 failed: " + ", ".join(failed))
print("Checkpoint 10 live capability snapshot regression passed")
