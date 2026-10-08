#!/usr/bin/env python3
"""Checkpoint 8: add non-destructive root/boot privilege readiness diagnostics.

Sage reports only evidence observable from ordinary Android app authority. It never executes
su, adb, fastboot, unlock, flash, wipe, or rooting commands. Dell/ADB evidence commands are
shown as copyable diagnostics instructions for later owner-run physical verification.
"""
from pathlib import Path
import sys

READINESS = r'''package com.pineapple.sage;

import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.io.File;

/** Read-only privilege/boot readiness evidence. Existence is evidence, never proof of authority. */
final class SagePrivilegeReadiness {
    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/data/local/bin/su", "/data/local/xbin/su"
    };

    private SagePrivilegeReadiness() {}

    static boolean suArtifactObserved() {
        for (String path : SU_PATHS) if (new File(path).exists()) return true;
        return false;
    }

    static String report(Context context) {
        String tags = Build.TAGS == null ? "unknown" : Build.TAGS;
        String type = Build.TYPE == null ? "unknown" : Build.TYPE;
        String bootloader = Build.BOOTLOADER == null || Build.BOOTLOADER.trim().isEmpty()
                ? "unknown" : Build.BOOTLOADER;
        boolean suArtifact = suArtifactObserved();
        boolean testKeys = tags.contains("test-keys");

        StringBuilder out = new StringBuilder();
        out.append("PRIVILEGE READINESS — READ ONLY\n\n");
        out.append("Package: ").append(context.getPackageName()).append('\n');
        out.append("UID: ").append(Process.myUid()).append(" (root UID would be 0; this report never elevates)\n");
        out.append("Build type: ").append(type).append('\n');
        out.append("Build tags: ").append(tags).append('\n');
        out.append("Fingerprint: ").append(Build.FINGERPRINT).append('\n');
        out.append("Bootloader string: ").append(bootloader).append('\n');
        out.append("Device admin: ").append(SageDeviceAuthority.isAdmin(context) ? "ACTIVE" : "not active").append('\n');
        out.append("Device owner: ").append(SageDeviceAuthority.isDeviceOwner(context) ? "ACTIVE" : "not active").append('\n');
        out.append("Profile owner: ").append(SageDeviceAuthority.isProfileOwner(context) ? "ACTIVE" : "not active").append('\n');
        out.append("Accessibility: ").append(SageAccessibilityService.isReady() ? "ACTIVE" : "not active").append('\n');
        out.append("Forge pairing: ").append(SageForgeStore.isPaired(context) ? "PAIRED" : "not paired").append('\n');
        out.append("su filesystem artifact: ").append(suArtifact ? "OBSERVED — root still NOT PROVEN" : "not observed").append('\n');
        out.append("test-keys build hint: ").append(testKeys ? "OBSERVED — unlock/root still NOT PROVEN" : "not observed").append('\n');
        out.append("ROOT: NOT PROVEN\n");
        out.append("BOOTLOADER UNLOCK: UNKNOWN FROM ORDINARY APP AUTHORITY\n");
        out.append("VERIFIED BOOT STATE: REQUIRES ADB/BOOTLOADER EVIDENCE\n\n");
        out.append("Dell evidence collection (owner runs these separately):\n");
        out.append("adb devices\n");
        out.append("adb shell getprop ro.boot.flash.locked\n");
        out.append("adb shell getprop ro.boot.vbmeta.device_state\n");
        out.append("adb shell getprop ro.boot.verifiedbootstate\n");
        out.append("adb shell getprop ro.product.device\n");
        out.append("adb shell getprop ro.build.fingerprint\n");
        out.append("adb shell cat /proc/partitions\n\n");
        out.append("No unlock, flash, wipe, root, su, adb, or fastboot command is executed by this screen.");
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

public final class SagePrivilegeReadinessActivity extends Activity {
    private TextView report;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Sage Privilege Readiness");
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);
        root.addView(text("ROOT / BOOT READINESS", 27));
        root.addView(text("Read-only evidence only. This screen does not root, unlock, flash, wipe, run su, or issue ADB/fastboot commands.", 14));
        report = text("", 13);
        report.setTextIsSelectable(true);
        root.addView(report);
        Button refresh = button("Refresh read-only evidence");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);
        SageAppearance.apply(this, scroll, root);
        setContentView(scroll);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        if (report != null) refresh();
    }

    private void refresh() {
        report.setText(SagePrivilegeReadiness.report(this));
        SageDiagnostics.appendEvent(this, "PRIVILEGE READINESS",
                "root_proven=false su_artifact=" + SagePrivilegeReadiness.suArtifactObserved()
                        + " device_owner=" + SageDeviceAuthority.isDeviceOwner(this));
    }

    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); return b; }
    private TextView text(String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); return t; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
'''


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1))


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: privilege_readiness_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    required = (java / "SageDeviceAuthority.java", java / "SageRedQueenActivity.java")
    for value in required:
        if not value.is_file():
            raise SystemExit(f"Checkpoint 8 missing Checkpoint 7 source: {value.name}")

    (java / "SagePrivilegeReadiness.java").write_text(READINESS)
    (java / "SagePrivilegeReadinessActivity.java").write_text(ACTIVITY)

    manifest = root / "app/src/main/AndroidManifest.xml"
    replace_once(manifest, "    </application>",
        '        <activity android:name=".SagePrivilegeReadinessActivity" android:exported="false" />\n    </application>',
        "privilege-readiness manifest insertion")

    redqueen = java / "SageRedQueenActivity.java"
    anchor = '''        functional(root, "Device Authority", "Live Android admin, device-owner, accessibility, ADB provisioning, and Forge authority status",
                SageDeviceAuthorityActivity.class);'''
    replacement = anchor + '''
        functional(root, "Root / Boot Readiness", "Read-only root artifacts, build evidence, and Dell/ADB evidence checklist; never unlocks, flashes, wipes, or elevates",
                SagePrivilegeReadinessActivity.class);'''
    replace_once(redqueen, anchor, replacement, "Red Queen privilege-readiness card")

    print("Applied Checkpoint 8: read-only privilege and boot readiness diagnostics")


if __name__ == "__main__":
    main()
