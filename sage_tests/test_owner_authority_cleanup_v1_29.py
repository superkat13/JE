#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_owner_authority_cleanup_v1_29.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
policy = (java / "SageOwnerAuthorityPolicy.java").read_text()
host = (java / "SageHostInspectorActivity.java").read_text()
session = (java / "SageRedQueenSession.java").read_text()

checks = {
    "central consequence model exists": all(token in policy for token in (
        "READ_ONLY", "REVERSIBLE_LOCAL", "SYSTEM_CHANGE", "IRREVERSIBLE_OR_EXTERNAL")),
    "central decisions exist": all(token in policy for token in (
        "PROCEED_AND_AUDIT", "CONFIRM_ACTION", "REQUIRE_RED_QUEEN", "DENY_PLATFORM")),
    "verified owner can satisfy duplicate read-only prompt": "verifiedOwnerMayProceedReadOnly" in policy,
    "irreversible action still confirms": "IRREVERSIBLE_OR_EXTERNAL" in policy and "CONFIRM_ACTION" in policy,
    "selected-host inspection uses owner authority": "SageOwnerAuthorityPolicy.verifiedOwnerMayProceedReadOnly" in host,
    "fallback confirmation remains for non-Red-Queen entry": "SageConfirmation.require" in host,
    "Red Queen device lock remains": "isDeviceLocked()" in session,
    "Red Queen remains process-local": "private static volatile long unlockedUntilMs" in session,
    "policy cannot unlock Red Queen": "SageRedQueenSession.unlock" not in policy,
    "policy cannot bypass Android permissions": "requestPermissions(" not in policy and "DevicePolicyManager" not in policy,
    "policy cannot execute shell": "Runtime.getRuntime().exec" not in policy and "ProcessBuilder" not in policy,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " | " + name)
if failed:
    raise SystemExit("Checkpoint 5 owner authority cleanup failed: " + ", ".join(failed))
print("Checkpoint 5 owner authority cleanup regression passed")
