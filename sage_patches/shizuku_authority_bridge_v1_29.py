#!/usr/bin/env python3
"""Add an optional Shizuku/Sui authority bridge without changing Sage's normal path.

The bridge is deliberately separate from normal Sage and Red Queen authorization. It only
becomes useful after the owner installs/starts Shizuku (ADB identity) or Sui (root identity)
and explicitly grants Sage access through Shizuku's own permission UI.
"""
from pathlib import Path
import sys

BRIDGE = r'''package com.pineapple.sage;

import android.content.Context;
import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

final class SageShizukuBridge {
    static final int REQUEST_CODE = 6219;
    static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";

    private SageShizukuBridge() {}

    static boolean managerInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(MANAGER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    static boolean binderAlive() {
        try { return Shizuku.pingBinder(); }
        catch (RuntimeException error) { return false; }
    }

    static boolean permissionGranted() {
        if (!binderAlive()) return false;
        try { return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException error) { return false; }
    }

    static int serviceUid() {
        if (!binderAlive()) return -1;
        try { return Shizuku.getUid(); }
        catch (RuntimeException error) { return -1; }
    }

    static String authorityLabel() {
        int uid = serviceUid();
        if (uid == 0) return "ROOT identity (UID 0)";
        if (uid == 2000) return "ADB shell identity (UID 2000)";
        if (uid >= 0) return "elevated service UID " + uid;
        return "not connected";
    }

    static String report(Context context) {
        boolean installed = managerInstalled(context);
        boolean alive = binderAlive();
        boolean granted = permissionGranted();
        StringBuilder out = new StringBuilder();
        out.append("SAGE AUTHORITY BRIDGE\n\n");
        out.append("Shizuku manager installed: ").append(installed).append('\n');
        out.append("Shizuku/Sui binder alive: ").append(alive).append('\n');
        out.append("Sage permission granted: ").append(granted).append('\n');
        out.append("Service identity: ").append(authorityLabel()).append('\n');
        out.append("\nMeaning:\n");
        if (!installed) {
            out.append("Install Shizuku first. Sage has not gained any additional authority.\n");
        } else if (!alive) {
            out.append("Shizuku is installed but its service is not running. On this non-root Android 13 tablet it can be started with ADB/wireless debugging.\n");
        } else if (!granted) {
            out.append("The elevated service is alive, but Sage still has ordinary app authority until the owner grants Sage access.\n");
        } else if (serviceUid() == 2000) {
            out.append("Sage can now use explicitly implemented Shizuku operations with Android shell/ADB authority. This is stronger than a normal app but is not root.\n");
        } else if (serviceUid() == 0) {
            out.append("Sage is connected through a root-backed Shizuku/Sui service. Root-backed operations must still be explicitly implemented.\n");
        } else {
            out.append("An elevated Shizuku-compatible service is available. Sage will only use operations implemented behind this bridge.\n");
        }
        out.append("\nThis bridge does not bypass Red Queen owner verification and does not grant authority merely because the screen is opened.");
        return out.toString();
    }
}
'''

ACTIVITY = r'''package com.pineapple.sage;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public final class SageAuthorityBridgeActivity extends Activity {
    private TextView report;

    private final Shizuku.OnBinderReceivedListener binderReceived = this::refresh;
    private final Shizuku.OnBinderDeadListener binderDead = this::refresh;
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (requestCode, grantResult) -> {
        if (requestCode == SageShizukuBridge.REQUEST_CODE) {
            Toast.makeText(this, grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ? "Sage authority bridge approved." : "Sage authority bridge not approved.", Toast.LENGTH_SHORT).show();
            refresh();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!SageRedQueenSession.isUnlocked(this)) { finish(); return; }
        setTitle("Red Queen Authority Bridge");
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);
        root.addView(text("AUTHORITY BRIDGE", 28));
        root.addView(text("Optional non-root ADB-shell authority through Shizuku, or root identity later through Sui. This screen reports the real service identity before Sage uses it.", 14));
        report = text("", 13);
        report.setTextIsSelectable(true);
        root.addView(report);

        Button permission = button("Request Sage access from Shizuku");
        permission.setOnClickListener(v -> requestBridgePermission());
        root.addView(permission);

        Button manager = button("Open Shizuku manager");
        manager.setOnClickListener(v -> openManager());
        root.addView(manager);

        Button refresh = button("Refresh bridge status");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);

        SageAppearance.apply(this, scroll, root);
        setContentView(scroll);
        refresh();
    }

    @Override protected void onResume() { super.onResume(); registerListeners(); refresh(); }
    @Override protected void onPause() { unregisterListeners(); super.onPause(); }

    private void registerListeners() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceived);
            Shizuku.addBinderDeadListener(binderDead);
            Shizuku.addRequestPermissionResultListener(permissionResult);
        } catch (RuntimeException ignored) {}
    }

    private void unregisterListeners() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceived);
            Shizuku.removeBinderDeadListener(binderDead);
            Shizuku.removeRequestPermissionResultListener(permissionResult);
        } catch (RuntimeException ignored) {}
    }

    private void requestBridgePermission() {
        if (!SageRedQueenSession.isUnlocked(this)) { finish(); return; }
        if (!SageShizukuBridge.managerInstalled(this)) {
            Toast.makeText(this, "Shizuku is not installed yet.", Toast.LENGTH_LONG).show();
            openManager();
            return;
        }
        if (!SageShizukuBridge.binderAlive()) {
            Toast.makeText(this, "Shizuku is installed but its service is not running yet.", Toast.LENGTH_LONG).show();
            openManager();
            return;
        }
        if (SageShizukuBridge.permissionGranted()) {
            Toast.makeText(this, "Sage already has Shizuku permission.", Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        try { Shizuku.requestPermission(SageShizukuBridge.REQUEST_CODE); }
        catch (RuntimeException error) {
            Toast.makeText(this, "Shizuku permission request failed: " + error.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    private void openManager() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(SageShizukuBridge.MANAGER_PACKAGE);
            if (launch != null) { startActivity(launch); return; }
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")));
        } catch (RuntimeException error) {
            Toast.makeText(this, "Could not open Shizuku setup.", Toast.LENGTH_LONG).show();
        }
    }

    private void refresh() {
        if (report == null) return;
        report.setText(SageShizukuBridge.report(this));
        SageDiagnostics.appendEvent(this, "AUTHORITY BRIDGE",
                "installed=" + SageShizukuBridge.managerInstalled(this)
                        + " binder=" + SageShizukuBridge.binderAlive()
                        + " permission=" + SageShizukuBridge.permissionGranted()
                        + " uid=" + SageShizukuBridge.serviceUid());
    }

    private Button button(String value) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); return b; }
    private TextView text(String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); return t; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
'''


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: shizuku_authority_bridge_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    build = root / "app/build.gradle.kts"
    manifest = root / "app/src/main/AndroidManifest.xml"
    redqueen = java / "SageRedQueenActivity.java"
    if not all(p.is_file() for p in (build, manifest, redqueen)):
        raise SystemExit("authority bridge requires reconstructed Sage 1.29 source")

    build_text = build.read_text(encoding="utf-8")
    if "dev.rikka.shizuku:api:13.1.5" not in build_text:
        anchor = "dependencies {"
        if anchor not in build_text:
            raise SystemExit("dependencies block not found")
        build_text = build_text.replace(anchor, anchor + '\n    implementation("dev.rikka.shizuku:api:13.1.5")\n    implementation("dev.rikka.shizuku:provider:13.1.5")', 1)
        build.write_text(build_text, encoding="utf-8")

    (java / "SageShizukuBridge.java").write_text(BRIDGE, encoding="utf-8")
    (java / "SageAuthorityBridgeActivity.java").write_text(ACTIVITY, encoding="utf-8")

    provider = '''        <provider\n            android:name="rikka.shizuku.ShizukuProvider"\n            android:authorities="${applicationId}.shizuku"\n            android:multiprocess="false"\n            android:enabled="true"\n            android:exported="true"\n            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />\n        <activity android:name=".SageAuthorityBridgeActivity" android:exported="false" />\n'''
    replace_once(manifest, "    </application>", provider + "    </application>", "Shizuku provider/activity manifest")

    anchor = '''        functional(root, "Forensic Console", "Run a live local evidence sweep, inspect APKs/files, pivot into private-LAN investigation, or hand work to Forge",\n                SageRedQueenForensicActivity.class);'''
    replacement = anchor + '''\n        functional(root, "Authority Bridge", "Optional Shizuku/Sui bridge: real ADB-shell authority without root, or root-backed identity later",\n                SageAuthorityBridgeActivity.class);'''
    replace_once(redqueen, anchor, replacement, "Red Queen authority bridge entry")

    print("Applied optional Shizuku/Sui authority bridge with explicit owner permission")


if __name__ == "__main__":
    main()
