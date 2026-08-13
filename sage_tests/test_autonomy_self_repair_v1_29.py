#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def get_build() -> Path:
    if len(sys.argv) > 1:
        candidate = Path(sys.argv[1])
        if candidate.is_dir():
            return candidate
    td = tempfile.mkdtemp(prefix="sage-autonomy-test-")
    out = Path(td) / "sage"
    subprocess.run(["bash", str(ROOT / "sage_tools/reconstruct_v1_29.sh"), str(out)], cwd=ROOT, check=True)
    return out


def main() -> None:
    out = get_build()
    java = out / "app/src/main/java/com/pineapple/sage"
    manifest = (out / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    store = (java / "SageAutonomyStore.java").read_text(encoding="utf-8")
    activity = (java / "SageAutonomyActivity.java").read_text(encoding="utf-8")
    heartbeat = (java / "SageAutonomyHeartbeatReceiver.java").read_text(encoding="utf-8")
    redqueen = (java / "SageRedQueenActivity.java").read_text(encoding="utf-8")
    voice = (java / "SageVoiceService.java").read_text(encoding="utf-8")

    required_store = [
        "FIVE_MINUTES_MS = 5L * 60L * 1000L",
        '"DIAGNOSING"',
        '"PLANNED"',
        '"READY_TO_DELEGATE"',
        '"DELEGATED"',
        '"VERIFYING"',
        '"READY_FOR_OWNER"',
        '"SOLVED"',
        '"CANCELLED"',
        "SharedPreferences",
        "scheduleHeartbeat",
        "enforceFiveMinuteRule",
        "companyOrder",
        "attachDelegateResult",
        "physicalResult",
        "route_hint=tablet Brain",
        "brain_route_without_execution",
        "The Company / external developer agent",
        "Green automation is evidence, not physical proof",
        "Do not loop",
    ]
    for token in required_store:
        assert token in store, token

    for forbidden in (
        "Runtime.getRuntime().exec",
        "ProcessBuilder",
        "su -c",
        "adb shell",
        "pm install",
        "pm uninstall",
        "fastboot",
    ):
        assert forbidden not in store
        assert forbidden not in activity
        assert forbidden not in heartbeat

    assert "SageRedQueenSession.isUnlocked(this)" in activity
    assert "Continue moving forward" in activity
    assert "Copy Sage's order for The Company" in activity
    assert "Glass PASS" in activity and "Glass FAIL" in activity
    assert "SageRedQueenVault.saveRecord" in activity

    assert 'SageAutonomyActivity" android:exported="false"' in manifest
    assert 'SageAutonomyHeartbeatReceiver" android:exported="false"' in manifest
    assert "com.pineapple.sagecommander.stable" in manifest

    # Red Queen now has a reason to be hidden: its engineering surface is exclusive.
    assert 'functional(root, "Sage Autonomy"' in redqueen
    assert 'functional(root, "Shell Authority"' in redqueen
    assert 'functional(root, "Forensic Console"' in redqueen
    assert 'functional(root, "Mature Research"' in redqueen
    for duplicate in (
        'functional(root, "Forge"',
        'functional(root, "Evidence Lab"',
        'functional(root, "Network Lab"',
        'functional(root, "Black Box"',
        'functional(root, "Boot Evidence"',
        'functional(root, "Dell Evidence Import"',
        'functional(root, "Device Authority"',
    ):
        assert duplicate not in redqueen, duplicate

    for ordinary_name in ("SageToolbeltActivity.java", "SageWorkbenchActivity.java", "MainActivity.java"):
        ordinary = (java / ordinary_name).read_text(encoding="utf-8")
        assert "SageAutonomyActivity.class" not in ordinary, ordinary_name

    # The stabilized voice state machine survives the pivot untouched.
    for state in ("IDLE_WAKE", "WAKE_ACCEPTED", "COMMAND_LISTENING", "DISPATCHING", "SPEAKING"):
        assert state in voice, state

    print("Sage autonomy/self-repair + five-minute rule + Red Queen exclusivity regression passed")


if __name__ == "__main__":
    main()
