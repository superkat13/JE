#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_dell_evidence_import_v1_29.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
parser = (java / "SageDellEvidence.java").read_text()
activity = (java / "SageDellEvidenceActivity.java").read_text()
manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
redqueen = (java / "SageRedQueenActivity.java").read_text()

required_parser = [
    "ro.boot.flash.locked",
    "ro.boot.vbmeta.device_state",
    "ro.boot.verifiedbootstate",
    "ROOT: NOT PROVEN BY THESE BOOT PROPERTIES",
    "owner-pasted text only",
]
for token in required_parser:
    assert token in parser, token

assert "SageDellEvidence.report(raw)" in activity
assert "DELL EVIDENCE" in activity
assert "pasted_chars=" in activity
assert 'android:name=".SageDellEvidenceActivity"' in manifest

# Capability remains installed and callable, but the autonomy pivot deliberately removes
# this ordinary diagnostic utility from the hidden Red Queen menu to avoid duplication.
assert "Dell Evidence Import" not in redqueen
assert "SageDellEvidenceActivity.class" not in redqueen

forbidden = [
    "Runtime.getRuntime", "ProcessBuilder", "java.lang.Process", "Os.execv",
    "set-device-owner", "fastboot flashing unlock", "fastboot oem unlock",
    "adb reboot bootloader", "su -c", "magisk", "dd if=", "factory reset",
]
combined = parser + "\n" + activity
for token in forbidden:
    assert token not in combined, token

assert '"0".equals(flashLocked)' in parser
assert '"unlocked".equalsIgnoreCase(vbmetaState)' in parser
assert "ROOT: NOT PROVEN" in parser

print("Checkpoint 9 Dell evidence import tests passed without Red Queen duplication")
