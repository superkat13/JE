#!/usr/bin/env python3
from pathlib import Path
import sys

REPORT = r'''package com.pineapple.sage;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

final class SageRedQueenForensics {
    private SageRedQueenForensics() {}

    static String sweep(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("RED QUEEN — LOCAL FORENSIC SWEEP\n");
        out.append("Generated: ").append(DateFormat.getDateTimeInstance().format(new Date())).append("\n\n");
        out.append("DEVICE\n");
        out.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n');
        out.append("Model: ").append(Build.MODEL).append('\n');
        out.append("Device: ").append(Build.DEVICE).append('\n');
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Build fingerprint: ").append(Build.FINGERPRINT).append('\n');
        out.append("Uptime: ").append(SystemClock.elapsedRealtime() / 1000L).append(" seconds\n\n");

        out.append("SAGE AUTHORITY\n");
        out.append("Package: ").append(context.getPackageName()).append('\n');
        out.append("Device admin: ").append(SageDeviceAuthority.isAdmin(context)).append('\n');
        out.append("Device owner: ").append(SageDeviceAuthority.isDeviceOwner(context)).append('\n');
        out.append("Profile owner: ").append(SageDeviceAuthority.isProfileOwner(context)).append('\n');
        out.append("Accessibility: ").append(SageAccessibilityService.isReady()).append('\n');
        out.append("Forge paired: ").append(SageForgeStore.isPaired(context)).append('\n');
        out.append("Red Queen session: ").append(SageRedQueenSession.isUnlocked(context)).append("\n\n");

        out.append("STORAGE / MEMORY\n");
        try {
            File data = Environment.getDataDirectory();
            StatFs fs = new StatFs(data.getAbsolutePath());
            out.append("Data total: ").append(fs.getTotalBytes()).append(" bytes\n");
            out.append("Data available: ").append(fs.getAvailableBytes()).append(" bytes\n");
        } catch (RuntimeException error) {
            out.append("Storage: unavailable: ").append(error.getClass().getSimpleName()).append('\n');
        }
        try {
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.getMemoryInfo(mem);
                out.append("RAM total: ").append(mem.totalMem).append(" bytes\n");
                out.append("RAM available: ").append(mem.availMem).append(" bytes\n");
                out.append("Low-memory state: ").append(mem.lowMemory).append('\n');
            }
        } catch (RuntimeException error) {
            out.append("Memory: unavailable: ").append(error.getClass().getSimpleName()).append('\n');
        }
        out.append('\n');

        out.append("NETWORK INTERFACES\n");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                out.append("No interfaces reported\n");
            } else {
                for (NetworkInterface nic : Collections.list(interfaces)) {
                    out.append(nic.getName()).append(" up=").append(nic.isUp())
                            .append(" loopback=").append(nic.isLoopback()).append('\n');
                    Enumeration<InetAddress> addresses = nic.getInetAddresses();
                    for (InetAddress address : Collections.list(addresses)) {
                        out.append("  ").append(address.getHostAddress()).append('\n');
                    }
                }
            }
        } catch (Exception error) {
            out.append("Network inventory unavailable: ").append(error.getClass().getSimpleName()).append('\n');
        }
        out.append('\n');

        out.append("INSTALLED PACKAGE INVENTORY\n");
        try {
            PackageManager pm = context.getPackageManager();
            List<PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
            int system = 0, user = 0, requestedPermissions = 0;
            for (PackageInfo info : packages) {
                ApplicationInfo app = info.applicationInfo;
                boolean systemApp = app != null && (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (systemApp) system++; else user++;
                if (info.requestedPermissions != null) requestedPermissions += info.requestedPermissions.length;
            }
            out.append("Visible packages: ").append(packages.size()).append('\n');
            out.append("System packages: ").append(system).append('\n');
            out.append("User packages: ").append(user).append('\n');
            out.append("Requested-permission declarations: ").append(requestedPermissions).append('\n');
        } catch (RuntimeException error) {
            out.append("Package inventory unavailable: ").append(error.getClass().getSimpleName()).append('\n');
        }
        out.append('\n');

        out.append("ROOT / BOOT HINTS (READ ONLY)\n");
        String[] rootPaths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/adb/magisk"};
        boolean rootArtifact = false;
        for (String path : rootPaths) {
            boolean exists = new File(path).exists();
            out.append(path).append(": ").append(exists ? "present" : "not visible").append('\n');
            rootArtifact |= exists;
        }
        out.append("Root conclusion: ").append(rootArtifact ? "artifact hint present; root still not proven" : "not proven").append('\n');
        out.append("\nScope: local owner-device evidence only. No exploit, unlock, wipe, flash, shell, or remote-host action was executed.\n");
        return out.toString();
    }
}
'''

ACTIVITY = r'''package com.pineapple.sage;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class SageRedQueenForensicActivity extends Activity {
    private TextView report;
    private String lastReport = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Red Queen Forensics");
        if (!SageRedQueenSession.isUnlocked(this)) {
            finish();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);

        TextView title = text("FORENSIC CONSOLE", 28);
        root.addView(title);
        root.addView(text("A real local evidence sweep of this tablet. Results are generated from live Android state, not placeholder text.", 14));

        Button run = button("Run full local forensic sweep");
        run.setOnClickListener(v -> runSweep());
        root.addView(run);

        Button packageLab = button("Inspect an APK deeply");
        packageLab.setOnClickListener(v -> startActivity(new Intent(this, SagePackageCenterActivity.class)));
        root.addView(packageLab);

        Button fileLab = button("Inspect a file without executing it");
        fileLab.setOnClickListener(v -> startActivity(new Intent(this, SageFileLabActivity.class)));
        root.addView(fileLab);

        Button network = button("Open private-LAN investigation");
        network.setOnClickListener(v -> startActivity(new Intent(this, SageNetworkActivity.class)));
        root.addView(network);

        Button forge = button("Send engineering work to Forge");
        forge.setOnClickListener(v -> startActivity(new Intent(this, SageForgeActivity.class)));
        root.addView(forge);

        Button copy = button("Copy forensic report");
        copy.setOnClickListener(v -> copyReport());
        root.addView(copy);

        Button share = button("Share forensic report");
        share.setOnClickListener(v -> shareReport());
        root.addView(share);

        report = text("No sweep run yet.", 13);
        report.setTextIsSelectable(true);
        root.addView(report);
        SageAppearance.apply(this, scroll, root);
        setContentView(scroll);
    }

    private void runSweep() {
        if (!SageRedQueenSession.isUnlocked(this)) { finish(); return; }
        lastReport = SageRedQueenForensics.sweep(this);
        report.setText(lastReport);
        SageDiagnostics.appendEvent(this, "RED QUEEN FORENSICS", "local_sweep_complete chars=" + lastReport.length());
        SageRedQueenVault.saveRecord(this, "forensic_sweep", "Local forensic sweep", lastReport);
    }

    private void copyReport() {
        if (lastReport.isEmpty()) { Toast.makeText(this, "Run a sweep first.", Toast.LENGTH_SHORT).show(); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Red Queen forensic sweep", lastReport));
        Toast.makeText(this, "Forensic report copied.", Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        if (lastReport.isEmpty()) { Toast.makeText(this, "Run a sweep first.", Toast.LENGTH_SHORT).show(); return; }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, lastReport);
        startActivity(Intent.createChooser(share, "Share Red Queen forensic report"));
    }

    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); return b; }
    private TextView text(String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); return t; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
'''


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: red_queen_forensic_console_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    (java / "SageRedQueenForensics.java").write_text(REPORT, encoding="utf-8")
    (java / "SageRedQueenForensicActivity.java").write_text(ACTIVITY, encoding="utf-8")

    manifest = root / "app/src/main/AndroidManifest.xml"
    replace_once(manifest, "    </application>", '        <activity android:name=".SageRedQueenForensicActivity" android:exported="false" />\n    </application>', "forensic activity manifest")

    redqueen = java / "SageRedQueenActivity.java"
    anchor = '''        functional(root, "Forge", "Dell engineering, approved jobs, pairing, and results",\n                SageForgeActivity.class);'''
    replacement = '''        functional(root, "Forensic Console", "Run a live local evidence sweep, inspect APKs/files, pivot into private-LAN investigation, or hand work to Forge",\n                SageRedQueenForensicActivity.class);\n''' + anchor
    replace_once(redqueen, anchor, replacement, "Red Queen forensic console entry")

    print("Applied functional Red Queen forensic console")


if __name__ == "__main__":
    main()
