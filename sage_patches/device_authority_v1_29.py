#!/usr/bin/env python3
"""Checkpoint 7: add a real Android authority inspector and optional device-admin receiver.

This does not assume root or device-owner status. It reports actual Android authority,
opens only Android's supported admin/settings flows, and surfaces the exact ADB device-owner
command for a separately approved provisioning attempt on an eligible device.
"""
from pathlib import Path
import sys

RECEIVER = r'''package com.pineapple.sage;

import android.app.admin.DeviceAdminReceiver;

/** Minimal Sage device-admin receiver. No policy is silently changed by activation. */
public final class SageDeviceAdminReceiver extends DeviceAdminReceiver {}
'''

INSPECTOR = r'''package com.pineapple.sage;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

/** Read-only source of truth for Sage's current Android authority. */
final class SageDeviceAuthority {
    private SageDeviceAuthority() {}

    static ComponentName admin(Context context) {
        return new ComponentName(context, SageDeviceAdminReceiver.class);
    }

    static boolean isAdmin(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(admin(context));
    }

    static boolean isDeviceOwner(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    static boolean isProfileOwner(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isProfileOwnerApp(context.getPackageName());
    }

    static String report(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("Package: ").append(context.getPackageName()).append('\n');
        out.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Device admin: ").append(isAdmin(context) ? "ACTIVE" : "not active").append('\n');
        out.append("Device owner: ").append(isDeviceOwner(context) ? "ACTIVE" : "not active").append('\n');
        out.append("Profile owner: ").append(isProfileOwner(context) ? "ACTIVE" : "not active").append('\n');
        out.append("Accessibility: ").append(SageAccessibilityService.isReady() ? "ACTIVE" : "not active").append('\n');
        out.append("Forge pairing: ").append(SageForgeStore.isPaired(context) ? "PAIRED" : "not paired").append('\n');
        out.append("\nDevice-owner provisioning is an Android/ADB operation, not an in-app toggle.\n");
        out.append("Candidate command (only on an eligible, separately approved device):\n");
        out.append("adb shell dpm set-device-owner ")
                .append(context.getPackageName())
                .append("/.SageDeviceAdminReceiver\n");
        out.append("Sage does not claim root, device-owner, or hidden privileges unless Android reports them active.");
        return out.toString();
    }
}
'''

ACTIVITY = r'''package com.pineapple.sage;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class SageDeviceAuthorityActivity extends Activity {
    private TextView report;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Sage Device Authority");
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);
        TextView title = text("DEVICE AUTHORITY", 27);
        root.addView(title);
        root.addView(text("Live Android authority only. Sage will not label ADB, admin, device-owner, accessibility, Forge, or root capabilities as active unless the device actually reports them.", 14));
        report = text("", 14);
        report.setTextIsSelectable(true);
        root.addView(report);

        Button refresh = button("Refresh authority report");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);

        Button admin = button("Open Android device-admin activation");
        admin.setOnClickListener(v -> requestAdmin());
        root.addView(admin);

        Button accessibility = button("Open Accessibility settings");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        Button development = button("Open Developer options");
        development.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (RuntimeException error) { Toast.makeText(this, "Developer options are unavailable on this Android build.", Toast.LENGTH_LONG).show(); }
        });
        root.addView(development);

        SageAppearance.apply(this, scroll, root);
        setContentView(scroll);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        if (report != null) refresh();
    }

    private void requestAdmin() {
        if (SageDeviceAuthority.isAdmin(this)) {
            Toast.makeText(this, "Sage device admin is already active.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, SageDeviceAuthority.admin(this));
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Optional Android device-admin authority for owner-approved Sage device management. Activation does not make Sage device owner or root.");
        startActivity(intent);
    }

    private void refresh() {
        report.setText(SageDeviceAuthority.report(this));
        SageDiagnostics.appendEvent(this, "DEVICE AUTHORITY",
                "admin=" + SageDeviceAuthority.isAdmin(this)
                        + " device_owner=" + SageDeviceAuthority.isDeviceOwner(this)
                        + " profile_owner=" + SageDeviceAuthority.isProfileOwner(this));
    }

    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); return b; }
    private TextView text(String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); return t; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
'''

POLICIES = r'''<?xml version="1.0" encoding="utf-8"?>
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies />
</device-admin>
'''


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1))


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: device_authority_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    xml = root / "app/src/main/res/xml"
    xml.mkdir(parents=True, exist_ok=True)
    (java / "SageDeviceAdminReceiver.java").write_text(RECEIVER)
    (java / "SageDeviceAuthority.java").write_text(INSPECTOR)
    (java / "SageDeviceAuthorityActivity.java").write_text(ACTIVITY)
    (xml / "sage_device_admin.xml").write_text(POLICIES)

    manifest = root / "app/src/main/AndroidManifest.xml"
    insertion = '''        <activity android:name=".SageDeviceAuthorityActivity" android:exported="false" />
        <receiver
            android:name=".SageDeviceAdminReceiver"
            android:permission="android.permission.BIND_DEVICE_ADMIN"
            android:exported="true">
            <meta-data android:name="android.app.device_admin" android:resource="@xml/sage_device_admin" />
            <intent-filter>
                <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
            </intent-filter>
        </receiver>
'''
    replace_once(manifest, "    </application>", insertion + "    </application>", "device-authority manifest insertion")

    redqueen = java / "SageRedQueenActivity.java"
    anchor = '''        functional(root, "Authority", "ACTIVE, AVAILABLE, NEEDS SETUP, and UNSUPPORTED states",
                SageAuthorityActivity.class);'''
    replacement = anchor + '''
        functional(root, "Device Authority", "Live Android admin, device-owner, accessibility, ADB provisioning, and Forge authority status",
                SageDeviceAuthorityActivity.class);'''
    replace_once(redqueen, anchor, replacement, "Red Queen device-authority card")

    authority = java / "SageAuthority.java"
    text = authority.read_text()
    required = ("default_assistant", "red_queen_authority", "forge_trust", "tablet_brain")
    missing = [token for token in required if token not in text]
    if missing:
        raise SystemExit("Checkpoint 7 would replace existing authority architecture: " + ", ".join(missing))

    print("Applied Checkpoint 7: truthful Android device authority inspector and optional device-admin activation")


if __name__ == "__main__":
    main()
