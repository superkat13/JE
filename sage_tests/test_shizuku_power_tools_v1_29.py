#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build_129")
java = root / "app/src/main/java/com/pineapple/sage"
aidl = root / "app/src/main/aidl/com/pineapple/sage/ISageShizukuPower.aidl"
manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")

for name in ("SageShizukuUserService.java", "SageShizukuBridge.java", "SageAuthorityBridgeActivity.java", "SageBootReceiver.java", "SageAssistActivity.java"):
    assert (java / name).is_file(), f"missing {name}"
assert aidl.is_file(), "typed Shizuku AIDL missing"
assert "aidl = true" in gradle, "AGP AIDL generation must be explicitly enabled for typed Shizuku Binder interface"

service = (java / "SageShizukuUserService.java").read_text(encoding="utf-8")
bridge = (java / "SageShizukuBridge.java").read_text(encoding="utf-8")
activity = (java / "SageAuthorityBridgeActivity.java").read_text(encoding="utf-8")
authority = (java / "SageAuthority.java").read_text(encoding="utf-8")
boot = (java / "SageBootReceiver.java").read_text(encoding="utf-8")
assist = (java / "SageAssistActivity.java").read_text(encoding="utf-8")

# UserService is typed and bounded. No arbitrary shell text ever crosses AIDL.
for required in ("identityUid()", "authoritySnapshot()", "inspectPackage(String packageName)", "forceStopPackage(String packageName)"):
    assert required in aidl.read_text(encoding="utf-8")
for forbidden in ("runCommand(String", "exec(String command", "shell(String", "command(String"):
    assert forbidden not in aidl.read_text(encoding="utf-8")

# Snapshot commands are fixed and package mutation is limited to force-stop with package validation.
for required in ('run("id")', 'run("getenforce")', 'run("dpm", "list-owners")', 'run("cmd", "role", "holders", "android.app.role.ASSISTANT")', 'run("appops", "get", pkg)', 'run("dumpsys", "package", pkg)', 'run("am", "force-stop", pkg)', "MAX_OUTPUT = 48000", "PACKAGE.matcher(pkg).matches()"):
    assert required in service, f"missing bounded shell operation: {required}"
for forbidden in ("su -c", "magisk", "fastboot", "flash ", "rm -rf", "set-device-owner"):
    assert forbidden not in service, f"unexpected high-consequence primitive in UserService: {forbidden}"
for protected in ("com.pineapple.sagecommander.stable", "moe.shizuku.privileged.api", "com.android.systemui", "com.android.settings"):
    assert protected in service, f"critical package protection missing: {protected}"

# Shizuku uses the supported UserService API and preserves physical UID distinction.
for required in ("Shizuku.UserServiceArgs", "Shizuku.bindUserService", "Shizuku.unbindUserService", "serviceUid() == 2000", "serviceUid() == 0", "ISageShizukuPower.Stub.asInterface"):
    assert required in bridge, f"bridge missing {required}"
assert "newProcess" not in bridge, "deprecated Shizuku newProcess must not be used"

# Red Queen remains the only UI path to shell power and mutation gets explicit per-action confirmation.
for required in ("SageRedQueenSession.isUnlocked(this)", 'new AlertDialog.Builder(this).setTitle("Force-stop app?")', "runPower(\"force_stop\"", "runPower(\"authority_snapshot\"", "Deep-inspect package with shell authority"):
    assert required in activity, f"Red Queen power guard missing: {required}"

# Authority page tells the truth rather than calling intentional states unsupported.
for required in ("NOT_NEEDED", '"Shizuku shell authority"', "SageDeviceAuthority.isAdmin(context)", "boot_startup_enabled", "SageAuthorityBridgeActivity.class"):
    assert required in authority, f"authority cleanup missing {required}"
assert '"Sage does not declare a device-admin receiver' not in authority
assert '"device_admin", "Device admin",\n                State.UNSUPPORTED' not in authority

# Boot start is explicit owner opt-in and only starts the existing wake service.
for required in ("android.permission.RECEIVE_BOOT_COMPLETED", ".SageBootReceiver", "android.intent.action.BOOT_COMPLETED", ".SageAssistActivity", "android.intent.action.ASSIST"):
    assert required in manifest, f"manifest missing {required}"
for required in ("boot_startup_enabled", "SageVoiceService.ACTION_START", "Build.VERSION.SDK_INT >= 34"):
    assert required in boot, f"boot boundary missing {required}"
assert "ACTION_LISTEN_NOW" not in boot, "boot must not open an authorized command turn"

# Assistant invocation hands control to existing Sage and opens an authorized listen turn.
for required in ("MainActivity.class", "SageVoiceService.ACTION_LISTEN_NOW", '"ASSISTANT ROLE"'):
    assert required in assist, f"assistant role integration missing {required}"

# Preserve the stabilized conversation machine and package identity.
machine = (java / "SageConversationStateMachine.java").read_text(encoding="utf-8")
for state in ("COMMAND_LISTENING", "FINALIZING", "DISPATCHING", "SPEAKING", "ECHO_GUARD"):
    assert state in machine
assert 'applicationId = "com.pineapple.sagecommander.stable"' in gradle

print("Functional Shizuku shell power, generated typed AIDL, authority cleanup, Assistant role, and boot-start checks passed")