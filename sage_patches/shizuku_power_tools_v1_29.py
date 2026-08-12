#!/usr/bin/env python3
"""Turn the physically-proven Shizuku UID 2000 bridge into bounded owner tools.

Additive only. Normal Sage remains unchanged. Red Queen gets a typed Shizuku UserService
rather than an arbitrary shell, Android authority reporting stops calling intentional/non-
permission states "Unsupported", Assistant role gets a real role request path, and boot
startup becomes an explicit owner-controlled option on the current Android 13 tablet.
"""
from pathlib import Path
import sys

AIDL = r'''package com.pineapple.sage;

interface ISageShizukuPower {
    int identityUid();
    String authoritySnapshot();
    String inspectPackage(String packageName);
    boolean forceStopPackage(String packageName);
}
'''

USER_SERVICE = r'''package com.pineapple.sage;

import android.os.Process;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Runs only inside Shizuku UserService with the service's real shell/root identity. */
public final class SageShizukuUserService extends ISageShizukuPower.Stub {
    private static final Pattern PACKAGE = Pattern.compile("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+");
    private static final int MAX_OUTPUT = 48000;

    public SageShizukuUserService() {}

    @Override public int identityUid() { return Process.myUid(); }

    @Override public String authoritySnapshot() {
        StringBuilder out = new StringBuilder();
        append(out, "identity", run("id"));
        append(out, "selinux", run("getenforce"));
        append(out, "verified_boot", run("getprop", "ro.boot.verifiedbootstate"));
        append(out, "bootloader_locked", run("getprop", "ro.boot.flash.locked"));
        append(out, "adb_enabled", run("settings", "get", "global", "adb_enabled"));
        append(out, "development_settings", run("settings", "get", "global", "development_settings_enabled"));
        append(out, "wireless_debugging", run("settings", "get", "global", "adb_wifi_enabled"));
        append(out, "device_policy", run("dpm", "list-owners"));
        append(out, "assistant_role", run("cmd", "role", "holders", "android.app.role.ASSISTANT"));
        append(out, "home_role", run("cmd", "role", "holders", "android.app.role.HOME"));
        append(out, "disabled_packages", run("pm", "list", "packages", "-d"));
        return bounded(out.toString());
    }

    @Override public String inspectPackage(String packageName) {
        String pkg = safePackage(packageName);
        if (pkg == null) return "Invalid package name.";
        StringBuilder out = new StringBuilder();
        out.append("PACKAGE ").append(pkg).append('\n');
        append(out, "path", run("pm", "path", pkg));
        append(out, "appops", run("appops", "get", pkg));
        append(out, "package_dump", run("dumpsys", "package", pkg));
        return bounded(out.toString());
    }

    @Override public boolean forceStopPackage(String packageName) {
        String pkg = safePackage(packageName);
        if (pkg == null || protectedPackage(pkg)) return false;
        String result = run("am", "force-stop", pkg);
        return !result.toLowerCase(Locale.US).startsWith("error:");
    }

    private static boolean protectedPackage(String pkg) {
        return pkg.equals("com.pineapple.sagecommander.stable")
                || pkg.equals("moe.shizuku.privileged.api")
                || pkg.equals("com.android.systemui")
                || pkg.equals("android")
                || pkg.equals("com.android.settings")
                || pkg.equals("com.google.android.gms");
    }

    private static String safePackage(String value) {
        if (value == null) return null;
        String pkg = value.trim();
        return PACKAGE.matcher(pkg).matches() ? pkg : null;
    }

    private static void append(StringBuilder out, String label, String value) {
        out.append("\n[").append(label).append("]\n").append(value == null ? "" : value.trim()).append('\n');
    }

    private static String run(String... command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(command);
            boolean done = process.waitFor(7, TimeUnit.SECONDS);
            if (!done) {
                process.destroy();
                return "timeout";
            }
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && out.length() < MAX_OUTPUT) out.append(line).append('\n');
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null && out.length() < MAX_OUTPUT) out.append(line).append('\n');
            }
            return bounded(out.toString());
        } catch (Exception error) {
            return "error: " + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String bounded(String value) {
        if (value == null) return "";
        return value.length() <= MAX_OUTPUT ? value : value.substring(0, MAX_OUTPUT) + "\n[truncated]";
    }
}
'''

BRIDGE = r'''package com.pineapple.sage;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import rikka.shizuku.Shizuku;

final class SageShizukuBridge {
    static final int REQUEST_CODE = 6219;
    static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";

    interface ConnectionCallback {
        void connected(ISageShizukuPower service);
        void failed(String reason);
    }

    private SageShizukuBridge() {}

    static boolean managerInstalled(Context context) {
        try { context.getPackageManager().getPackageInfo(MANAGER_PACKAGE, 0); return true; }
        catch (PackageManager.NameNotFoundException ignored) { return false; }
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

    static boolean shellAuthorityActive() { return permissionGranted() && serviceUid() == 2000; }
    static boolean rootAuthorityActive() { return permissionGranted() && serviceUid() == 0; }

    static String authorityLabel() {
        int uid = serviceUid();
        if (uid == 0) return "ROOT identity (UID 0)";
        if (uid == 2000) return "ADB shell identity (UID 2000)";
        if (uid >= 0) return "service UID " + uid;
        return "not connected";
    }

    static Shizuku.UserServiceArgs userServiceArgs(Context context) {
        return new Shizuku.UserServiceArgs(new ComponentName(context.getPackageName(), SageShizukuUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("sage_shell")
                .debuggable(false)
                .version(1);
    }

    static ServiceConnection bind(Context context, ConnectionCallback callback) {
        if (!permissionGranted()) { callback.failed("Shizuku permission is not active."); return null; }
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (binder == null || !binder.pingBinder()) { callback.failed("Shizuku returned an invalid UserService binder."); return; }
                callback.connected(ISageShizukuPower.Stub.asInterface(binder));
            }
            @Override public void onServiceDisconnected(ComponentName name) { callback.failed("Shizuku UserService disconnected."); }
        };
        try {
            Shizuku.bindUserService(userServiceArgs(context), connection);
            return connection;
        } catch (RuntimeException error) {
            callback.failed("Could not bind Shizuku UserService: " + error.getClass().getSimpleName());
            return null;
        }
    }

    static void unbind(Context context, ServiceConnection connection) {
        if (connection == null) return;
        try { Shizuku.unbindUserService(userServiceArgs(context), connection, true); }
        catch (RuntimeException ignored) {}
    }

    static String report(Context context) {
        boolean installed = managerInstalled(context), alive = binderAlive(), granted = permissionGranted();
        StringBuilder out = new StringBuilder("SAGE AUTHORITY BRIDGE\n\n");
        out.append("Shizuku manager installed: ").append(installed).append('\n');
        out.append("Shizuku/Sui binder alive: ").append(alive).append('\n');
        out.append("Sage permission granted: ").append(granted).append('\n');
        out.append("Service identity: ").append(authorityLabel()).append('\n');
        out.append("Shell power tools: ").append(shellAuthorityActive() || rootAuthorityActive() ? "READY" : "not ready").append('\n');
        if (shellAuthorityActive()) out.append("\nPhysical authority proven: Sage can execute only the typed, implemented UserService operations below with Android shell UID 2000. This is not root.\n");
        else if (rootAuthorityActive()) out.append("\nRoot-backed identity is available to explicitly implemented UserService operations.\n");
        else out.append("\nStart Shizuku and approve Sage before shell power tools become available.\n");
        out.append("\nNo arbitrary command shell is exposed. Red Queen owner verification still applies.");
        return out.toString();
    }
}
'''

ACTIVITY = r'''package com.pineapple.sage;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public final class SageAuthorityBridgeActivity extends Activity {
    private TextView report;
    private EditText packageName;
    private ISageShizukuPower power;
    private ServiceConnection powerConnection;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Shizuku.OnBinderReceivedListener binderReceived = this::refreshAndBind;
    private final Shizuku.OnBinderDeadListener binderDead = () -> { power = null; refresh(); };
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (requestCode, grantResult) -> {
        if (requestCode == SageShizukuBridge.REQUEST_CODE) {
            Toast.makeText(this, grantResult == PackageManager.PERMISSION_GRANTED ? "Sage shell authority approved." : "Sage shell authority not approved.", Toast.LENGTH_SHORT).show();
            refreshAndBind();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!SageRedQueenSession.isUnlocked(this)) { finish(); return; }
        setTitle("Red Queen Shell Authority");
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);
        root.addView(text("SHELL AUTHORITY", 28));
        root.addView(text("Shizuku UID 2000 is now useful, not decorative. These are bounded Red Queen operations backed by a typed UserService, never an arbitrary shell box.", 14));
        report = text("", 13); report.setTextIsSelectable(true); root.addView(report);

        Button snapshot = button("Run deep authority snapshot");
        snapshot.setOnClickListener(v -> runPower("authority_snapshot", service -> service.authoritySnapshot())); root.addView(snapshot);

        packageName = new EditText(this); packageName.setHint("Package, e.g. com.google.android.youtube"); root.addView(packageName);
        Button inspect = button("Deep-inspect package with shell authority");
        inspect.setOnClickListener(v -> runPower("package_inspection", service -> service.inspectPackage(packageName.getText().toString().trim()))); root.addView(inspect);

        Button stop = button("Force-stop selected app");
        stop.setOnClickListener(v -> confirmForceStop()); root.addView(stop);

        Button assistant = button("Make Sage the Android assistant");
        assistant.setOnClickListener(v -> requestAssistantRole()); root.addView(assistant);

        Button admin = button("Activate Sage device admin");
        admin.setOnClickListener(v -> requestAdmin()); root.addView(admin);

        Button boot = button("Toggle start Sage after reboot");
        boot.setOnClickListener(v -> toggleBoot()); root.addView(boot);

        Button permission = button("Request Sage access from Shizuku"); permission.setOnClickListener(v -> requestBridgePermission()); root.addView(permission);
        Button manager = button("Open Shizuku manager"); manager.setOnClickListener(v -> openManager()); root.addView(manager);
        Button refresh = button("Refresh authority state"); refresh.setOnClickListener(v -> refreshAndBind()); root.addView(refresh);

        SageAppearance.apply(this, scroll, root); setContentView(scroll); refreshAndBind();
    }

    @Override protected void onResume() { super.onResume(); registerListeners(); refreshAndBind(); }
    @Override protected void onPause() { unregisterListeners(); super.onPause(); }
    @Override protected void onDestroy() { SageShizukuBridge.unbind(this, powerConnection); worker.shutdownNow(); super.onDestroy(); }

    private interface PowerCall { String call(ISageShizukuPower service) throws Exception; }

    private void runPower(String operation, PowerCall call) {
        if (!SageRedQueenSession.isUnlocked(this)) { finish(); return; }
        ISageShizukuPower service = power;
        if (service == null) { Toast.makeText(this, "Shell authority service is not ready yet.", Toast.LENGTH_LONG).show(); refreshAndBind(); return; }
        report.setText("Running " + operation.replace('_', ' ') + "…");
        worker.execute(() -> {
            try {
                String result = call.call(service);
                main.post(() -> { report.setText(result); SageDiagnostics.appendEvent(this, "SHIZUKU POWER", "operation=" + operation + " result=completed uid=" + SageShizukuBridge.serviceUid()); });
            } catch (Exception error) {
                main.post(() -> { report.setText("Operation failed: " + error.getClass().getSimpleName()); SageDiagnostics.recordError(this, "Shizuku power operation failed: " + error); });
            }
        });
    }

    private void confirmForceStop() {
        String pkg = packageName.getText().toString().trim();
        if (pkg.isEmpty()) { Toast.makeText(this, "Enter a package name first.", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle("Force-stop app?")
                .setMessage(pkg + "\n\nThis closes the selected app and its background work. It does not uninstall it or erase its data.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Force stop", (d, which) -> runPower("force_stop", service -> service.forceStopPackage(pkg) ? "Force-stopped " + pkg : "Force-stop was refused or failed for " + pkg))
                .show();
    }

    private void requestAssistantRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager manager = (RoleManager) getSystemService(ROLE_SERVICE);
            if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                if (manager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) { Toast.makeText(this, "Sage is already the Android assistant.", Toast.LENGTH_SHORT).show(); return; }
                startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 8110); return;
            }
        }
        try { startActivity(new Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)); }
        catch (RuntimeException e) { Toast.makeText(this, "Assistant role setup is unavailable on this tablet.", Toast.LENGTH_LONG).show(); }
    }

    private void requestAdmin() {
        if (SageDeviceAuthority.isAdmin(this)) { Toast.makeText(this, "Sage device admin is already active.", Toast.LENGTH_SHORT).show(); return; }
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, SageDeviceAuthority.admin(this));
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Owner-approved Sage device administration. This does not make Sage device owner or root.");
        startActivity(intent);
    }

    private void toggleBoot() {
        boolean enabled = !getSharedPreferences("sage_state", MODE_PRIVATE).getBoolean("boot_startup_enabled", false);
        getSharedPreferences("sage_state", MODE_PRIVATE).edit().putBoolean("boot_startup_enabled", enabled).apply();
        SageDiagnostics.appendEvent(this, "BOOT STARTUP", "owner_enabled=" + enabled);
        Toast.makeText(this, enabled ? "Sage will restart her wake service after reboot on this Android 13 tablet." : "Automatic Sage startup after reboot disabled.", Toast.LENGTH_LONG).show();
        refresh();
    }

    private void bindPower() {
        if (!SageShizukuBridge.permissionGranted() || power != null || powerConnection != null) return;
        powerConnection = SageShizukuBridge.bind(this, new SageShizukuBridge.ConnectionCallback() {
            @Override public void connected(ISageShizukuPower service) { power = service; main.post(() -> { refresh(); SageDiagnostics.appendEvent(SageAuthorityBridgeActivity.this, "SHIZUKU POWER", "userservice=connected uid=" + SageShizukuBridge.serviceUid()); }); }
            @Override public void failed(String reason) { power = null; powerConnection = null; main.post(() -> { refresh(); SageDiagnostics.appendEvent(SageAuthorityBridgeActivity.this, "SHIZUKU POWER", "userservice=failed reason=" + reason); }); }
        });
    }

    private void requestBridgePermission() {
        if (!SageShizukuBridge.managerInstalled(this)) { Toast.makeText(this, "Shizuku is not installed.", Toast.LENGTH_LONG).show(); openManager(); return; }
        if (!SageShizukuBridge.binderAlive()) { Toast.makeText(this, "Start Shizuku first.", Toast.LENGTH_LONG).show(); openManager(); return; }
        if (SageShizukuBridge.permissionGranted()) { Toast.makeText(this, "Sage already has Shizuku permission.", Toast.LENGTH_SHORT).show(); bindPower(); return; }
        try { Shizuku.requestPermission(SageShizukuBridge.REQUEST_CODE); }
        catch (RuntimeException error) { Toast.makeText(this, "Shizuku permission request failed.", Toast.LENGTH_LONG).show(); }
    }

    private void openManager() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(SageShizukuBridge.MANAGER_PACKAGE);
            if (launch != null) { startActivity(launch); return; }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")));
        } catch (RuntimeException error) { Toast.makeText(this, "Could not open Shizuku setup.", Toast.LENGTH_LONG).show(); }
    }

    private void registerListeners() { try { Shizuku.addBinderReceivedListenerSticky(binderReceived); Shizuku.addBinderDeadListener(binderDead); Shizuku.addRequestPermissionResultListener(permissionResult); } catch (RuntimeException ignored) {} }
    private void unregisterListeners() { try { Shizuku.removeBinderReceivedListener(binderReceived); Shizuku.removeBinderDeadListener(binderDead); Shizuku.removeRequestPermissionResultListener(permissionResult); } catch (RuntimeException ignored) {} }
    private void refreshAndBind() { refresh(); bindPower(); }
    private void refresh() {
        if (report == null) return;
        boolean boot = getSharedPreferences("sage_state", MODE_PRIVATE).getBoolean("boot_startup_enabled", false);
        report.setText(SageShizukuBridge.report(this) + "\nDevice admin: " + (SageDeviceAuthority.isAdmin(this) ? "ACTIVE" : "available") + "\nBoot startup: " + (boot ? "ACTIVE" : "available") + "\nAssistant role: " + assistantState());
        SageDiagnostics.appendEvent(this, "AUTHORITY BRIDGE", "installed=" + SageShizukuBridge.managerInstalled(this) + " binder=" + SageShizukuBridge.binderAlive() + " permission=" + SageShizukuBridge.permissionGranted() + " uid=" + SageShizukuBridge.serviceUid());
    }
    private String assistantState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unsupported on this Android";
        RoleManager manager = (RoleManager) getSystemService(ROLE_SERVICE);
        return manager != null && manager.isRoleHeld(RoleManager.ROLE_ASSISTANT) ? "ACTIVE" : "available";
    }
    private Button button(String value) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); return b; }
    private TextView text(String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); return t; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
'''

BOOT_RECEIVER = r'''package com.pineapple.sage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Owner-opt-in restart of Sage's existing foreground wake service after device boot. */
public final class SageBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean enabled = context.getSharedPreferences("sage_state", Context.MODE_PRIVATE).getBoolean("boot_startup_enabled", false);
        if (!enabled) { SageDiagnostics.appendEvent(context, "BOOT STARTUP", "boot_received owner_enabled=false"); return; }
        if (Build.VERSION.SDK_INT >= 34) {
            SageDiagnostics.appendEvent(context, "BOOT STARTUP", "boot_received deferred=true reason=microphone_fgs_background_restriction");
            return;
        }
        Intent start = new Intent(context, SageVoiceService.class).setAction(SageVoiceService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(start); else context.startService(start);
            SageDiagnostics.appendEvent(context, "BOOT STARTUP", "wake_service_started=true");
        } catch (RuntimeException error) { SageDiagnostics.recordError(context, "Boot startup failed: " + error); }
    }
}
'''

ASSIST_ACTIVITY = r'''package com.pineapple.sage;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Qualifies Sage for Android's Assistant role and hands assist invocation to Sage. */
public final class SageAssistActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(open);
        Intent listen = new Intent(this, SageVoiceService.class).setAction(SageVoiceService.ACTION_LISTEN_NOW);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(listen); else startService(listen);
        SageDiagnostics.appendEvent(this, "ASSISTANT ROLE", "android_assist_invocation=true");
        finish();
    }
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
        raise SystemExit("usage: shizuku_power_tools_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    aidl = root / "app/src/main/aidl/com/pineapple/sage"
    manifest = root / "app/src/main/AndroidManifest.xml"
    authority = java / "SageAuthority.java"
    authority_activity = java / "SageAuthorityActivity.java"
    for required in (java / "SageShizukuBridge.java", java / "SageAuthorityBridgeActivity.java", authority, authority_activity, manifest):
        if not required.is_file(): raise SystemExit("Shizuku power pass missing reconstructed dependency: " + required.name)

    aidl.mkdir(parents=True, exist_ok=True)
    (aidl / "ISageShizukuPower.aidl").write_text(AIDL, encoding="utf-8")
    (java / "SageShizukuUserService.java").write_text(USER_SERVICE, encoding="utf-8")
    (java / "SageShizukuBridge.java").write_text(BRIDGE, encoding="utf-8")
    (java / "SageAuthorityBridgeActivity.java").write_text(ACTIVITY, encoding="utf-8")
    (java / "SageBootReceiver.java").write_text(BOOT_RECEIVER, encoding="utf-8")
    (java / "SageAssistActivity.java").write_text(ASSIST_ACTIVITY, encoding="utf-8")

    m = manifest.read_text(encoding="utf-8")
    if "android.permission.RECEIVE_BOOT_COMPLETED" not in m:
        m = m.replace("    <application", '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n    <application', 1)
    manifest.write_text(m, encoding="utf-8")
    insertion = '''        <receiver android:name=".SageBootReceiver" android:enabled="true" android:exported="true">\n            <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>\n        </receiver>\n        <activity android:name=".SageAssistActivity" android:exported="true">\n            <intent-filter>\n                <action android:name="android.intent.action.ASSIST" />\n                <category android:name="android.intent.category.DEFAULT" />\n            </intent-filter>\n        </activity>\n'''
    replace_once(manifest, "    </application>", insertion + "    </application>", "boot/assistant manifest components")

    # Replace misleading authority states with real, actionable states.
    text = authority.read_text(encoding="utf-8")
    text = text.replace("enum State { ACTIVE, AVAILABLE, NEEDS_SETUP, UNSUPPORTED }", "enum State { ACTIVE, AVAILABLE, NEEDS_SETUP, NOT_NEEDED, UNSUPPORTED }")
    text = text.replace('            if (state == State.NEEDS_SETUP) return "Needs setup";\n            return "Unsupported";', '            if (state == State.NEEDS_SETUP) return "Needs setup";\n            if (state == State.NOT_NEEDED) return "Not needed";\n            return "Unsupported";')
    old_boot = '''        boolean boot = permissionDeclared(context, "android.permission.RECEIVE_BOOT_COMPLETED");\n        result.add(new Capability(\n                "boot_startup", "Boot startup",\n                boot ? State.AVAILABLE : State.UNSUPPORTED,\n                boot\n                        ? "The build supports boot startup; activation remains an owner setting."\n                        : "This build does not silently start Sage after reboot.",\n                null\n        ));'''
    new_boot = '''        boolean boot = permissionDeclared(context, "android.permission.RECEIVE_BOOT_COMPLETED");\n        boolean bootEnabled = context.getSharedPreferences("sage_state", Context.MODE_PRIVATE).getBoolean("boot_startup_enabled", false);\n        result.add(new Capability(\n                "boot_startup", "Boot startup",\n                bootEnabled ? State.ACTIVE : boot ? State.AVAILABLE : State.UNSUPPORTED,\n                bootEnabled ? "Owner enabled Sage wake-service restart after reboot on this Android 13 tablet."\n                        : boot ? "Supported by this build. Enable it from Red Queen Shell Authority if you want Sage awake after reboot."\n                        : "This build does not contain boot-start support.",\n                boot && !bootEnabled ? new Intent(context, SageAuthorityBridgeActivity.class) : null\n        ));'''
    if old_boot not in text: raise SystemExit("boot authority block not found")
    text = text.replace(old_boot, new_boot, 1)
    text = text.replace('overlay ? State.ACTIVE : overlayDeclared ? State.NEEDS_SETUP : State.UNSUPPORTED,', 'overlay ? State.ACTIVE : overlayDeclared ? State.NEEDS_SETUP : State.NOT_NEEDED,', 1)
    text = text.replace('"No cloud provider is configured; Sage will not claim cloud authority.",\n                null', '"Optional integration, not an Android permission. Sage does not need a cloud provider for local authority.",\n                null', 1).replace('"cloud_model_provider", "Approved cloud model provider",\n                State.UNSUPPORTED,', '"cloud_model_provider", "Approved cloud model provider",\n                State.NOT_NEEDED,', 1)
    text = text.replace('result.add(roleCapability(context, "default_launcher", "Default launcher",\n                "android.app.role.HOME", Settings.ACTION_HOME_SETTINGS));', 'result.add(new Capability("default_launcher", "Default launcher", State.NOT_NEEDED,\n                "Optional UI choice, not a permission. Sage already controls the tablet without replacing your launcher.", null));')
    old_admin = '''        result.add(new Capability(\n                "device_admin", "Device admin",\n                State.UNSUPPORTED,\n                "Sage does not declare a device-admin receiver. Self-repair authority is separate.",\n                null\n        ));\n        result.add(new Capability(\n                "device_owner", "Device owner",\n                owner ? State.ACTIVE : State.UNSUPPORTED,\n                owner\n                        ? "Android reports Sage as device owner."\n                        : "Device-owner provisioning is not attempted or offered automatically.",\n                null\n        ));'''
    new_admin = '''        boolean admin = SageDeviceAuthority.isAdmin(context);\n        result.add(new Capability(\n                "device_admin", "Device admin",\n                admin ? State.ACTIVE : State.AVAILABLE,\n                admin ? "Android reports Sage device admin active."\n                        : "Sage declares a device-admin receiver. Owner activation is available without root.",\n                admin ? null : new Intent(context, SageDeviceAuthorityActivity.class)\n        ));\n        result.add(new Capability(\n                "device_owner", "Device owner",\n                owner ? State.ACTIVE : State.AVAILABLE,\n                owner ? "Android reports Sage as device owner."\n                        : "Potential higher Android management authority. Provision only after Dell/ADB eligibility confirms this tablet can accept it without disrupting Sage data.",\n                new Intent(context, SageDeviceAuthorityActivity.class)\n        ));\n        boolean shizuku = SageShizukuBridge.shellAuthorityActive() || SageShizukuBridge.rootAuthorityActive();\n        result.add(new Capability(\n                "shizuku_shell", "Shizuku shell authority",\n                shizuku ? State.ACTIVE : State.AVAILABLE,\n                shizuku ? "Physical bridge is active as " + SageShizukuBridge.authorityLabel() + ". Red Queen can use implemented shell power tools."\n                        : "Optional ADB-shell authority. Start Shizuku and approve Sage.",\n                shizuku ? null : new Intent(context, SageAuthorityBridgeActivity.class)\n        ));'''
    if old_admin not in text: raise SystemExit("device admin authority block not found")
    text = text.replace(old_admin, new_admin, 1)

    # Assistant role should open a real role request surface, not unrelated voice-input settings.
    text = text.replace('result.add(roleCapability(context, "default_assistant", "Default assistant",\n                "android.app.role.ASSISTANT", Settings.ACTION_VOICE_INPUT_SETTINGS));', 'result.add(roleCapability(context, "default_assistant", "Default assistant",\n                "android.app.role.ASSISTANT", Settings.ACTION_VOICE_INPUT_SETTINGS));')
    old_role_return = '''        return new Capability(key, label, State.AVAILABLE,\n                "Android supports this role, but Sage does not claim it is active.",\n                new Intent(settingsAction));'''
    new_role_return = '''        Intent setup = "android.app.role.ASSISTANT".equals(role)\n                ? new Intent(context, SageAuthorityBridgeActivity.class) : new Intent(settingsAction);\n        return new Capability(key, label, State.AVAILABLE,\n                "Android supports this role. Sage can request it through the owner-approved Android role dialog.", setup);'''
    if old_role_return not in text: raise SystemExit("role capability return block not found")
    text = text.replace(old_role_return, new_role_return, 1)
    authority.write_text(text, encoding="utf-8")

    ui = authority_activity.read_text(encoding="utf-8")
    ui = ui.replace('        if (state == SageAuthority.State.AVAILABLE) return Color.rgb(96, 165, 250);\n        return Color.rgb(156, 163, 175);', '        if (state == SageAuthority.State.AVAILABLE) return Color.rgb(96, 165, 250);\n        if (state == SageAuthority.State.NOT_NEEDED) return Color.rgb(148, 163, 184);\n        return Color.rgb(156, 163, 175);')
    authority_activity.write_text(ui, encoding="utf-8")

    print("Applied Shizuku power tools, accurate authority states, Assistant-role path, and owner-controlled boot startup")

if __name__ == "__main__":
    main()
