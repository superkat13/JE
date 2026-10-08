#!/usr/bin/env python3
"""Consolidate Red Queen into a phrase-only hidden owner workspace.

This is an additive presentation repair. Existing authentication, vault, Forge,
network, package/file, diagnostics, signer, and Android authority code remain intact.
The ordinary Workbench no longer advertises Red Queen, and the Red Queen screen no
longer exposes a pile of duplicate/deferred cards.
"""
from pathlib import Path
import re
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def regex_once(path: Path, pattern: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, lambda _m: replacement, text, count=1, flags=re.DOTALL)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one regex match, found {count}")
    path.write_text(updated, encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: red_queen_consolidation_v1_29.py <reconstructed-source>")

    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    redqueen = java / "SageRedQueenActivity.java"
    workbench = java / "SageWorkbenchActivity.java"
    command = java / "SageCommandEngine.java"
    session = java / "SageRedQueenSession.java"
    for required in (redqueen, workbench, command, session):
        if not required.is_file():
            raise SystemExit(f"missing reconstructed source: {required.name}")

    replace_once(
        workbench,
        'card(r,"Red Queen Mode","Owner-authenticated black/crimson workspace with encrypted private storage and audited advanced tools",SageRedQueenActivity.class);',
        '',
        "remove visible Workbench Red Queen card",
    )

    replace_once(
        command,
        'return new Result("Owner authentication required. Open Sage Workbench and tap Red Queen Mode.");',
        'return new Result("Red Queen could not open. Try the phrase again from Sage.");',
        "remove manual Red Queen fallback",
    )

    workspace = r'''    private void showWorkspace() {
        if (!SageRedQueenSession.isUnlocked(this)) { showLocked(); return; }
        ScrollView scroll = shell();
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        root.addView(label("RED QUEEN", 30, Color.rgb(255, 60, 75)));
        root.addView(label("Private owner workspace. The plumbing stays behind the curtain.",
                15, Color.LTGRAY));

        functional(root, "Forge", "Dell engineering, approved jobs, pairing, and results",
                SageForgeActivity.class);
        functional(root, "Evidence Lab", "Packages, files, hashes, inspection, and local engineering tools",
                SageWorkbenchActivity.class);
        functional(root, "Network Lab", "Private-LAN investigation using Sage's saved host evidence",
                SageNetworkActivity.class);
        functional(root, "Black Box", "Diagnostics, repair evidence, and recovery tools",
                SageRepairActivity.class);
        functional(root, "Boot Evidence", "Read-only root and boot readiness evidence",
                SagePrivilegeReadinessActivity.class);
        functional(root, "Dell Evidence Import", "Interpret owner-collected ADB and boot evidence",
                SageDellEvidenceActivity.class);
        functional(root, "Device Authority", "Live Android authority and device-control status",
                SageDeviceAuthorityActivity.class);

        EditText note = new EditText(this);
        note.setHint("Encrypted private owner note");
        note.setTextColor(Color.WHITE);
        note.setHintTextColor(Color.GRAY);
        root.addView(note);
        Button save = button("Save private note");
        save.setOnClickListener(v -> {
            boolean saved = SageRedQueenVault.saveRecord(this, "note", "Owner note",
                    note.getText().toString());
            Toast.makeText(this, saved ? "Private note encrypted." : "Private note not saved.",
                    Toast.LENGTH_LONG).show();
            if (saved) note.setText("");
        });
        root.addView(save);

        TextView auditTitle = label("Recent private activity", 16, Color.LTGRAY);
        auditTitle.setPadding(0, dp(14), 0, dp(4));
        root.addView(auditTitle);
        TextView audit = label(SageRedQueenVault.auditReport(this), 13, Color.LTGRAY);
        audit.setTextIsSelectable(true);
        root.addView(audit);

        Button lock = button("Lock Red Queen");
        lock.setOnClickListener(v -> secure("explicit_exit"));
        root.addView(lock);
        setContentView(scroll);
        armInactivity();
    }

'''
    regex_once(
        redqueen,
        r'    private void showWorkspace\(\) \{.*?\n    private void functional\(',
        workspace + '    private void functional(',
        "replace Red Queen workspace with consolidated owner hubs",
    )

    replace_once(
        redqueen,
        'Button card = button(title + " — FUNCTIONAL\\n" + detail);',
        'Button card = button(title + "\\n" + detail);',
        "remove engineering-state label from Red Queen cards",
    )

    session_text = session.read_text(encoding="utf-8")
    for token in ("isDeviceLocked()", "canAttempt", "recordFailure", "unlockedUntilMs"):
        if token not in session_text:
            raise SystemExit("Red Queen consolidation would weaken owner authentication: " + token)

    print("Applied Red Queen phrase-only navigation and consolidated hidden workspace")


if __name__ == "__main__":
    main()
