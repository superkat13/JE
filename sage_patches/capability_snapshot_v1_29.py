#!/usr/bin/env python3
from pathlib import Path
import sys

SNAPSHOT = r'''package com.pineapple.sage;

import android.content.Context;
import android.os.Build;

/** Live, read-only summary of what this one Sage can actually use right now. */
final class SageCapabilitySnapshot {
    private SageCapabilitySnapshot() {}

    static String report(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("SAGE CAPABILITY SNAPSHOT\n");
        out.append("Package: ").append(context.getPackageName()).append('\n');
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Conversation: available\n");
        out.append("Accessibility: ").append(SageAccessibilityService.isReady() ? "ACTIVE" : "needs setup").append('\n');
        out.append("Device admin: ").append(SageDeviceAuthority.isAdmin(context) ? "ACTIVE" : "not active").append('\n');
        out.append("Device owner: ").append(SageDeviceAuthority.isDeviceOwner(context) ? "ACTIVE" : "not active").append('\n');
        out.append("Profile owner: ").append(SageDeviceAuthority.isProfileOwner(context) ? "ACTIVE" : "not active").append('\n');
        out.append("Forge: ").append(SageForgeStore.isPaired(context) ? "PAIRED" : "not paired").append('\n');
        out.append("Internal specialists: automatic\n");
        out.append("Red Queen: hidden elevated workspace; owner verification required\n");
        out.append("Root: NOT CLAIMED unless separately proven by device evidence\n");
        out.append("Sage does not create a second assistant or silently grant itself Android authority.");
        return out.toString();
    }
}
'''

ACTIVITY = r'''package com.pineapple.sage;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class SageCapabilitySnapshotActivity extends Activity {
    private TextView report;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Sage Capability Snapshot");
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);
        TextView title = new TextView(this); title.setText("WHAT SAGE CAN USE RIGHT NOW"); title.setTextSize(25); root.addView(title);
        report = new TextView(this); report.setTextSize(14); report.setTextIsSelectable(true); root.addView(report);
        Button refresh = new Button(this); refresh.setText("Refresh live capability snapshot"); refresh.setAllCaps(false); refresh.setOnClickListener(v -> refresh()); root.addView(refresh);
        SageAppearance.apply(this, scroll, root); setContentView(scroll); refresh();
    }
    @Override protected void onResume() { super.onResume(); if (report != null) refresh(); }
    private void refresh() { report.setText(SageCapabilitySnapshot.report(this)); SageDiagnostics.appendEvent(this, "CAPABILITY SNAPSHOT", "live=true"); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
'''

def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1: raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1))

def main() -> None:
    if len(sys.argv) != 2: raise SystemExit("usage: capability_snapshot_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1]); java = root / "app/src/main/java/com/pineapple/sage"
    main_activity = java / "MainActivity.java"; voice = java / "SageVoiceService.java"
    if not (java / "SageDeviceAuthority.java").is_file(): raise SystemExit("Checkpoint 10 requires Checkpoint 7 device authority")
    (java / "SageCapabilitySnapshot.java").write_text(SNAPSHOT)
    (java / "SageCapabilitySnapshotActivity.java").write_text(ACTIVITY)

    manifest = root / "app/src/main/AndroidManifest.xml"
    replace_once(manifest, "    </application>", '        <activity android:name=".SageCapabilitySnapshotActivity" android:exported="false" />\n    </application>', "capability snapshot manifest")

    marker = '''        TextView voiceResponseTitle = new TextView(this);'''
    ui = '''        Button capabilitySnapshot = makeButton("What Sage can use right now");\n        capabilitySnapshot.setOnClickListener(v -> startActivity(new Intent(this, SageCapabilitySnapshotActivity.class)));\n        root.addView(capabilitySnapshot, spacedSmall());\n\n'''
    replace_once(main_activity, marker, ui + marker, "normal Sage capability button")

    voice_text = voice.read_text()
    anchor = '''        SageEasterEggStore.Entry easterEgg = SageEasterEggStore.find(this, cleaned);'''
    insert = '''        String capabilityPhrase = cleaned == null ? "" : cleaned.trim().toLowerCase(java.util.Locale.US);\n        if (capabilityPhrase.equals("what can you do right now")\n                || capabilityPhrase.equals("what can you use right now")\n                || capabilityPhrase.equals("sage capability status")) {\n            String capabilityReport = SageCapabilitySnapshot.report(this);\n            SageDiagnostics.appendEvent(this, "CAPABILITY SNAPSHOT", "voice=true");\n            broadcastLine("Sage", capabilityReport);\n            speak(capabilityReport);\n            return;\n        }\n\n'''
    replace_once(voice, anchor, insert + anchor, "voice capability snapshot")
    print("Applied Checkpoint 10: live one-Sage capability snapshot")

if __name__ == "__main__": main()
