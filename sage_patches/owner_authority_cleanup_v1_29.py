#!/usr/bin/env python3
"""Checkpoint 5: central owner-authority cleanup for duplicate confirmations.

Verified Red Queen authority may satisfy a second confirmation only for scoped,
read-only, reversible inspection. Irreversible, destructive, install, credential,
external-commitment, or authority-changing actions still require explicit action
confirmation. Existing Android permission boundaries remain untouched.
"""
from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1))


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: owner_authority_cleanup_v1_29.py <reconstructed-source>")

    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    host = java / "SageHostInspectorActivity.java"
    session = java / "SageRedQueenSession.java"
    if not host.is_file() or not session.is_file():
        raise SystemExit("Checkpoint 5 requires Red Queen session and selected-host inspector")

    policy = r'''package com.pineapple.sage;

import android.content.Context;

import java.util.Locale;

/** Central decision for when verified owner authority may satisfy duplicate prompts. */
final class SageOwnerAuthorityPolicy {
    enum Consequence { READ_ONLY, REVERSIBLE_LOCAL, SYSTEM_CHANGE, IRREVERSIBLE_OR_EXTERNAL }
    enum Decision { PROCEED_AND_AUDIT, CONFIRM_ACTION, REQUIRE_RED_QUEEN, DENY_PLATFORM }

    private SageOwnerAuthorityPolicy() {}

    static Decision decide(Context context, Consequence consequence,
                           boolean redQueenRequired, boolean platformDenied) {
        if (platformDenied) return Decision.DENY_PLATFORM;
        boolean ownerVerified = SageRedQueenSession.isUnlocked(context);
        if (redQueenRequired && !ownerVerified) return Decision.REQUIRE_RED_QUEEN;
        if (consequence == Consequence.IRREVERSIBLE_OR_EXTERNAL) return Decision.CONFIRM_ACTION;
        if (consequence == Consequence.SYSTEM_CHANGE && !ownerVerified) return Decision.CONFIRM_ACTION;
        return Decision.PROCEED_AND_AUDIT;
    }

    static boolean verifiedOwnerMayProceedReadOnly(Context context, String operation) {
        Decision decision = decide(context, Consequence.READ_ONLY, true, false);
        if (decision != Decision.PROCEED_AND_AUDIT) return false;
        SageDiagnostics.appendEvent(context, "OWNER AUTHORITY",
                "decision=PROCEED_AND_AUDIT consequence=READ_ONLY operation=" + clean(operation));
        SageRedQueenSession.touch(context);
        return true;
    }

    static boolean mustConfirmIrreversible(Context context, String operation) {
        SageDiagnostics.appendEvent(context, "OWNER AUTHORITY",
                "decision=CONFIRM_ACTION consequence=IRREVERSIBLE_OR_EXTERNAL operation=" + clean(operation));
        return true;
    }

    private static String clean(String value) {
        if (value == null) return "unknown";
        String cleaned = value.toLowerCase(Locale.US).replaceAll("[^a-z0-9_ -]", " ")
                .replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120);
    }
}
'''
    (java / "SageOwnerAuthorityPolicy.java").write_text(policy)

    old = '''    private void confirm(){String target=ip.getText().toString().trim();if(!SageNetworkScanner.isPrivate(target+"/32")){Toast.makeText(this,"Only an exact private IPv4 host is allowed.",Toast.LENGTH_LONG).show();return;}
        SageConfirmation.require(this,"Inspect one saved private-LAN host",target,"INTERNET; exact host must already exist in Sage's saved snapshot","Private-LAN packets only","Cancel immediately; saved network snapshots are unchanged",()->start(target));}'''
    new = '''    private void confirm(){String target=ip.getText().toString().trim();if(!SageNetworkScanner.isPrivate(target+"/32")){Toast.makeText(this,"Only an exact private IPv4 host is allowed.",Toast.LENGTH_LONG).show();return;}
        if(SageOwnerAuthorityPolicy.verifiedOwnerMayProceedReadOnly(this,"selected private-LAN host inspection")){start(target);return;}
        SageConfirmation.require(this,"Inspect one saved private-LAN host",target,"INTERNET; exact host must already exist in Sage's saved snapshot","Private-LAN packets only","Cancel immediately; saved network snapshots are unchanged",()->start(target));}'''
    replace_once(host, old, new, "selected-host owner authority")

    # Hard guard: this checkpoint must not change the device-lock/reboot/process-local
    # nature of Red Queen authorization.
    session_text = session.read_text()
    required = ("KeyguardManager", "isDeviceLocked()", "unlockedUntilMs", "lock(context")
    missing = [token for token in required if token not in session_text]
    if missing:
        raise SystemExit("Checkpoint 5 would weaken Red Queen session boundaries: " + ", ".join(missing))

    print("Applied Checkpoint 5: verified owner authority removes duplicate read-only confirmation while preserving consequence boundaries")


if __name__ == "__main__":
    main()
