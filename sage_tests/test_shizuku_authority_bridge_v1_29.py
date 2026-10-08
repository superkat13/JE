#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_shizuku_authority_bridge_v1_29.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
build = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
bridge = (java / "SageShizukuBridge.java").read_text(encoding="utf-8")
activity = (java / "SageAuthorityBridgeActivity.java").read_text(encoding="utf-8")
redqueen = (java / "SageRedQueenActivity.java").read_text(encoding="utf-8")

checks = {
    "Shizuku API pinned": 'dev.rikka.shizuku:api:13.1.5' in build,
    "Shizuku provider pinned": 'dev.rikka.shizuku:provider:13.1.5' in build,
    "provider declared": 'rikka.shizuku.ShizukuProvider' in manifest,
    "provider authority uses Sage package": '${applicationId}.shizuku' in manifest,
    "bridge activity private": '.SageAuthorityBridgeActivity' in manifest and 'android:exported="false"' in manifest,
    "real binder ping": 'Shizuku.pingBinder()' in bridge,
    "real permission check": 'Shizuku.checkSelfPermission()' in bridge,
    "real UID check": 'Shizuku.getUid()' in bridge,
    "ADB identity distinguished": 'uid == 2000' in bridge,
    "root identity distinguished": 'uid == 0' in bridge,
    "permission requested through Shizuku": 'Shizuku.requestPermission' in activity,
    "Red Queen session required": 'SageRedQueenSession.isUnlocked(this)' in activity,
    "Red Queen exposes exclusive shell authority": 'functional(root, "Shell Authority"' in redqueen and 'SageAuthorityBridgeActivity.class' in redqueen,
    "old Authority Bridge duplicate label removed": 'functional(root, "Authority Bridge"' not in redqueen,
    "normal package preserved": 'applicationId = "com.pineapple.sagecommander.stable"' in build,
    "no fake authority grant": 'grantRuntimePermission' not in bridge + activity and 'set-device-owner' not in bridge + activity,
}

for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " | " + name)
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("Shizuku authority bridge regression failed: " + ", ".join(failed))
print("Shizuku authority bridge regression passed")
