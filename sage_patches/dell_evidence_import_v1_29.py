#!/usr/bin/env python3
"""Checkpoint 9: import Dell/ADB evidence without executing device-control commands."""
from pathlib import Path
import sys

PARSER = r'''package com.pineapple.sage;

import java.util.Locale;

final class SageDellEvidence {
    private SageDellEvidence() {}

    static String valueFor(String raw, String key) {
        if (raw == null || key == null) return "";
        String wanted = key.toLowerCase(Locale.ROOT);
        for (String sourceLine : raw.split("\\r?\\n")) {
            String line = sourceLine.trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (!lower.contains(wanted)) continue;
            int bracket = line.lastIndexOf(": [");
            if (bracket >= 0 && line.endsWith("]")) return line.substring(bracket + 3, line.length() - 1).trim();
            int equals = line.indexOf('=');
            if (equals >= 0) return line.substring(equals + 1).trim();
            int colon = line.lastIndexOf(':');
            if (colon >= 0) return line.substring(colon + 1).trim();
            String[] pieces = line.split("\\s+");
            if (pieces.length > 1) return pieces[pieces.length - 1].trim();
        }
        return "";
    }

    static String report(String raw) {
        String flashLocked = valueFor(raw, "ro.boot.flash.locked");
        String vbmetaState = valueFor(raw, "ro.boot.vbmeta.device_state");
        String verifiedBoot = valueFor(raw, "ro.boot.verifiedbootstate");
        String product = valueFor(raw, "ro.product.device");
        String fingerprint = valueFor(raw, "ro.build.fingerprint");
        StringBuilder out = new StringBuilder();
        out.append("DELL EVIDENCE INTERPRETATION — READ ONLY\n\n");
        out.append("flash.locked: ").append(show(flashLocked)).append('\n');
        out.append("vbmeta.device_state: ").append(show(vbmetaState)).append('\n');
        out.append("verifiedbootstate: ").append(show(verifiedBoot)).append('\n');
        out.append("product.device: ").append(show(product)).append('\n');
        out.append("build.fingerprint: ").append(show(fingerprint)).append("\n\n");
        if ("0".equals(flashLocked)) out.append("BOOTLOADER PROPERTY: reports unlocked/flash-unlocked.\n");
        else if ("1".equals(flashLocked)) out.append("BOOTLOADER PROPERTY: reports locked.\n");
        else out.append("BOOTLOADER PROPERTY: not established from pasted evidence.\n");
        if ("unlocked".equalsIgnoreCase(vbmetaState)) out.append("VBMETA DEVICE STATE: reports unlocked.\n");
        else if ("locked".equalsIgnoreCase(vbmetaState)) out.append("VBMETA DEVICE STATE: reports locked.\n");
        else out.append("VBMETA DEVICE STATE: not established.\n");
        if (!verifiedBoot.isEmpty()) out.append("VERIFIED BOOT: device reports ").append(verifiedBoot).append(".\n");
        else out.append("VERIFIED BOOT: not established.\n");
        out.append("ROOT: NOT PROVEN BY THESE BOOT PROPERTIES.\n");
        out.append("Evidence source: owner-pasted text only; Sage executed no ADB, fastboot, su, unlock, flash, wipe, or root command.");
        return out.toString();
    }

    private static String show(String value) { return value == null || value.isEmpty() ? "not found" : value; }
}
'''

ACTIVITY = r'''package com.pineapple.sage;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class SageDellEvidenceActivity extends Activity {
    private EditText evidence;
    private TextView result;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setTitle("Sage Dell Evidence");
        ScrollView scroll = new ScrollView(this); LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18), dp(18), dp(18), dp(24)); scroll.addView(root);
        root.addView(text("DELL EVIDENCE IMPORT", 27));
        root.addView(text("Paste the output collected on the Dell. Sage only interprets the text you provide here. It does not run ADB, fastboot, su, unlock, flash, wipe, or root commands.", 14));
        evidence = new EditText(this); evidence.setHint("Paste Dell / ADB evidence here"); evidence.setMinLines(8); evidence.setGravity(android.view.Gravity.TOP);
        evidence.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); root.addView(evidence);
        Button parse = button("Interpret pasted evidence"); parse.setOnClickListener(v -> interpret()); root.addView(parse);
        Button clear = button("Clear evidence"); clear.setOnClickListener(v -> { evidence.setText(""); result.setText("No evidence interpreted yet."); }); root.addView(clear);
        result = text("No evidence interpreted yet.", 13); result.setTextIsSelectable(true); root.addView(result);
        SageAppearance.apply(this, scroll, root); setContentView(scroll);
    }
    private void interpret() { String raw = evidence.getText().toString(); result.setText(SageDellEvidence.report(raw)); SageDiagnostics.appendEvent(this, "DELL EVIDENCE", "pasted_chars=" + raw.length() + " root_proven=false"); }
    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); return b; }
    private TextView text(String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); return t; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
'''

def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(); count = text.count(old)
    if count != 1: raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1))

def main() -> None:
    if len(sys.argv) != 2: raise SystemExit("usage: dell_evidence_import_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1]); java = root / "app/src/main/java/com/pineapple/sage"
    readiness = java / "SagePrivilegeReadinessActivity.java"; redqueen = java / "SageRedQueenActivity.java"
    if not readiness.is_file() or not redqueen.is_file(): raise SystemExit("Checkpoint 9 requires Checkpoint 8 privilege readiness")
    (java / "SageDellEvidence.java").write_text(PARSER); (java / "SageDellEvidenceActivity.java").write_text(ACTIVITY)
    manifest = root / "app/src/main/AndroidManifest.xml"
    replace_once(manifest, "    </application>", '        <activity android:name=".SageDellEvidenceActivity" android:exported="false" />\n    </application>', "Dell-evidence manifest insertion")
    anchor = '''        functional(root, "Root / Boot Readiness", "Read-only root artifacts, build evidence, and Dell/ADB evidence checklist; never unlocks, flashes, wipes, or elevates",\n                SagePrivilegeReadinessActivity.class);'''
    replacement = anchor + '''\n        functional(root, "Dell Evidence Import", "Paste owner-collected ADB evidence and interpret boot properties conservatively; no device-control command execution",\n                SageDellEvidenceActivity.class);'''
    replace_once(redqueen, anchor, replacement, "Red Queen Dell-evidence card")
    print("Applied Checkpoint 9: paste-only Dell evidence import and conservative boot-state interpretation")

if __name__ == "__main__": main()
