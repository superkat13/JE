#!/usr/bin/env python3
"""Add the owner-authenticated Red Queen boundary and encrypted private vault."""

from pathlib import Path
import sys


SESSION = r'''package com.pineapple.sage;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;

/** Process-local Red Queen authorization. Reboot/process death can never preserve an unlock. */
final class SageRedQueenSession {
    private static final long SESSION_MS = 5L * 60L * 1000L;
    private static final long FAILURE_BLOCK_MS = 60L * 1000L;
    private static final String PREFS = "sage_red_queen_auth_limits";
    private static volatile long unlockedUntilMs;

    private SageRedQueenSession() {}

    static synchronized boolean isUnlocked(Context context) {
        KeyguardManager keyguard = (KeyguardManager)
                context.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isDeviceLocked()) {
            lock(context, "device_locked");
            return false;
        }
        if (unlockedUntilMs <= System.currentTimeMillis()) {
            if (unlockedUntilMs != 0L) lock(context, "inactivity_timeout");
            return false;
        }
        return true;
    }

    static synchronized void unlock(Context context) {
        unlockedUntilMs = System.currentTimeMillis() + SESSION_MS;
        clearFailures(context);
        SageRedQueenVault.appendAudit(context, "unlock", "owner credential accepted");
        SageDiagnostics.appendEvent(context, "RED QUEEN", "activated by device credential");
    }

    static synchronized void touch(Context context) {
        if (isUnlocked(context)) unlockedUntilMs = System.currentTimeMillis() + SESSION_MS;
    }

    static synchronized void lock(Context context, String reason) {
        boolean wasUnlocked = unlockedUntilMs > System.currentTimeMillis();
        unlockedUntilMs = 0L;
        if (wasUnlocked) SageRedQueenVault.appendAudit(context, "lock", clean(reason));
        SageDiagnostics.appendEvent(context, "RED QUEEN", "secured reason=" + clean(reason));
    }

    static boolean canAttempt(Context context) {
        return blockedUntil(context) <= System.currentTimeMillis();
    }

    static long retryAfterMs(Context context) {
        return Math.max(0L, blockedUntil(context) - System.currentTimeMillis());
    }

    static synchronized void recordFailure(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int failures = preferences.getInt("failures", 0) + 1;
        SharedPreferences.Editor editor = preferences.edit().putInt("failures", failures);
        if (failures >= 3) {
            editor.putLong("blocked_until", System.currentTimeMillis() + FAILURE_BLOCK_MS)
                    .putInt("failures", 0);
        }
        editor.commit();
        SageRedQueenVault.appendAudit(context, "authentication_failed",
                failures >= 3 ? "rate limited" : "credential rejected");
    }

    private static long blockedUntil(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong("blocked_until", 0L);
    }

    private static void clearFailures(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("failures").remove("blocked_until").commit();
    }

    private static String clean(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9_ -]", "").trim();
    }
}
'''

VAULT = r'''package com.pineapple.sage;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.Date;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private AES-GCM store that is never queried by Standard Sage. */
final class SageRedQueenVault {
    private static final String KEY_ALIAS = "sage_red_queen_private_v1";
    private static final String FILE_NAME = "red_queen_private_vault.bin";

    private SageRedQueenVault() {}

    static synchronized boolean saveRecord(Context context, String category,
                                           String title, String value) {
        if (!SageRedQueenSession.isUnlocked(context)) return false;
        try {
            JSONObject root = load(context);
            JSONArray records = root.optJSONArray("records");
            if (records == null) records = new JSONArray();
            JSONObject record = new JSONObject();
            record.put("category", clean(category));
            record.put("title", clean(title));
            record.put("value", value == null ? "" : value.trim());
            record.put("sha256", sha256((value == null ? "" : value)
                    .getBytes(StandardCharsets.UTF_8)));
            record.put("created_at_ms", System.currentTimeMillis());
            records.put(record);
            root.put("records", records);
            save(context, root);
            appendAudit(context, "private_record_saved", clean(category));
            return true;
        } catch (Exception error) {
            SageDiagnostics.recordError(context, "Red Queen vault save failed: "
                    + error.getClass().getSimpleName());
            return false;
        }
    }

    static synchronized void appendAudit(Context context, String action, String detail) {
        try {
            JSONObject root = load(context);
            JSONArray audit = root.optJSONArray("audit");
            if (audit == null) audit = new JSONArray();
            JSONObject event = new JSONObject();
            event.put("time_ms", System.currentTimeMillis());
            event.put("action", clean(action));
            event.put("detail", clean(detail));
            audit.put(event);
            while (audit.length() > 250) audit.remove(0);
            root.put("audit", audit);
            save(context, root);
        } catch (Exception error) {
            SageDiagnostics.recordError(context, "Red Queen audit write failed: "
                    + error.getClass().getSimpleName());
        }
    }

    static synchronized String auditReport(Context context) {
        if (!SageRedQueenSession.isUnlocked(context)) return "Owner authentication required.";
        try {
            JSONArray audit = load(context).optJSONArray("audit");
            if (audit == null || audit.length() == 0) return "No Red Queen activity recorded.";
            StringBuilder out = new StringBuilder();
            int start = Math.max(0, audit.length() - 40);
            for (int index = start; index < audit.length(); index++) {
                JSONObject event = audit.getJSONObject(index);
                out.append(DateFormat.getDateTimeInstance().format(
                        new Date(event.optLong("time_ms"))))
                        .append(" • ").append(event.optString("action"))
                        .append(" • ").append(event.optString("detail")).append('\n');
            }
            return out.toString().trim();
        } catch (Exception error) {
            return "Encrypted audit could not be read: " + error.getClass().getSimpleName();
        }
    }

    private static JSONObject load(Context context) throws Exception {
        AtomicFile file = file(context);
        if (!file.getBaseFile().isFile()) return empty();
        byte[] stored;
        try (FileInputStream input = file.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            stored = output.toByteArray();
        }
        String[] parts = new String(stored, StandardCharsets.US_ASCII).split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("vault envelope invalid");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                Base64.decode(parts[0], Base64.NO_WRAP)));
        byte[] clear = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
        return new JSONObject(new String(clear, StandardCharsets.UTF_8));
    }

    private static void save(Context context, JSONObject root) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(root.toString().getBytes(StandardCharsets.UTF_8));
        String envelope = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        AtomicFile file = file(context);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(envelope.getBytes(StandardCharsets.US_ASCII));
            file.finishWrite(output);
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            throw error;
        }
    }

    private static AtomicFile file(Context context) {
        File directory = new File(context.getNoBackupFilesDir(), "red_queen");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("private directory unavailable");
        }
        return new AtomicFile(new File(directory, FILE_NAME));
    }

    private static JSONObject empty() throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("records", new JSONArray());
        root.put("audit", new JSONArray());
        return root;
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private static String sha256(byte[] value) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256").digest(value)) {
            out.append(String.format(java.util.Locale.US, "%02x", item));
        }
        return out.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ").trim();
    }
}
'''

ACTIVITY = r'''package com.pineapple.sage;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SageRedQueenActivity extends Activity {
    private static final int AUTH_REQUEST = 9128;
    private static final long INACTIVITY_MS = 5L * 60L * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable inactivityLock = () -> secure("inactivity_timeout");
    private boolean authenticating;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setTitle("Red Queen Mode");
        showLocked();
        if (state == null) authenticate();
    }

    @Override public void onResume() {
        super.onResume();
        if (!authenticating && !SageRedQueenSession.isUnlocked(this)) showLocked();
        armInactivity();
    }

    @Override public void onUserInteraction() {
        super.onUserInteraction();
        SageRedQueenSession.touch(this);
        armInactivity();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(inactivityLock);
        if (!authenticating) SageRedQueenSession.lock(this, "app_backgrounded");
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        SageRedQueenSession.lock(this, "workspace_closed");
        super.onDestroy();
    }

    private void authenticate() {
        if (!SageRedQueenSession.canAttempt(this)) {
            long seconds = Math.max(1L, SageRedQueenSession.retryAfterMs(this) / 1000L);
            Toast.makeText(this, "Authentication rate limited. Try again in " + seconds
                    + " seconds.", Toast.LENGTH_LONG).show();
            return;
        }
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            Toast.makeText(this, "Set a secure device PIN, password, or biometric first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = keyguard.createConfirmDeviceCredentialIntent(
                "Owner authentication required.",
                "Authenticate to activate Red Queen Mode.");
        if (intent == null) {
            Toast.makeText(this, "Android credential confirmation is unavailable.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        authenticating = true;
        SageRedQueenVault.appendAudit(this, "authentication_requested", "manual owner entry");
        startActivityForResult(intent, AUTH_REQUEST);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != AUTH_REQUEST) return;
        authenticating = false;
        if (result == RESULT_OK) {
            SageRedQueenSession.unlock(this);
            showWorkspace();
            Toast.makeText(this, "Red Queen Mode activated.", Toast.LENGTH_LONG).show();
        } else {
            SageRedQueenSession.recordFailure(this);
            showLocked();
        }
    }

    private void showLocked() {
        ScrollView scroll = shell();
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        root.addView(label("RED QUEEN SECURED", 30, Color.rgb(255, 70, 85)));
        root.addView(label("Owner authentication required.", 17, Color.LTGRAY));
        Button unlock = button("Authenticate owner");
        unlock.setOnClickListener(v -> authenticate());
        root.addView(unlock);
        setContentView(scroll);
    }

    private void showWorkspace() {
        if (!SageRedQueenSession.isUnlocked(this)) { showLocked(); return; }
        ScrollView scroll = shell();
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        root.addView(label("RED QUEEN MODE", 30, Color.rgb(255, 60, 75)));
        root.addView(label("Sage under verified owner authority. Android permissions and each sensitive operation remain separately controlled.",
                15, Color.LTGRAY));
        functional(root, "Operations", "Authenticated owner workspace and real operation status",
                SageAuthorityActivity.class);
        functional(root, "Forge", "Pinned-TLS Dell pairing and approved system-information jobs",
                SageForgeActivity.class);
        functional(root, "Black Box", "Sanitized diagnostics and supervised repair evidence",
                SageRepairActivity.class);
        functional(root, "Package Lab", "Static APK identity, signer, hashes, permissions, and installer handoff",
                SagePackageCenterActivity.class);
        functional(root, "File Lab", "Owner-selected local file hashing with cancellation and export",
                SageFileHasherActivity.class);
        functional(root, "Model Lab", "Real GGUF load and generated-output Brain test",
                SageBrainTestActivity.class);
        functional(root, "Network Operations", "Confirmed private-LAN snapshot only",
                SageNetworkActivity.class);
        functional(root, "Activity / Audit", "Read the encrypted Red Queen activity trail",
                null);
        functional(root, "Authority", "ACTIVE, AVAILABLE, NEEDS SETUP, and UNSUPPORTED states",
                SageAuthorityActivity.class);
        deferred(root, "OSINT Desk", "Deferred: curated public-source workflows are not yet functional.");
        deferred(root, "Reverse Engineering", "Deferred: Package Lab static APK inspection is the current authorized slice.");
        deferred(root, "Digital Forensics", "Deferred: validated acquisition and chain-of-custody UI are not implemented.");
        deferred(root, "Automation", "Deferred: unrestricted scripts are never accepted.");
        deferred(root, "Agent Registry", "Deferred UI; Forge accepts only declarative, hash-verified agent definitions.");
        deferred(root, "Tool Registry", "Deferred UI; only compiled allowlisted tools currently execute.");
        deferred(root, "Experimental", "Deferred until a functional, testable owner-controlled slice exists.");

        EditText note = new EditText(this);
        note.setHint("Encrypted private owner note");
        note.setTextColor(Color.WHITE);
        note.setHintTextColor(Color.GRAY);
        root.addView(note);
        Button save = button("Save encrypted private note");
        save.setOnClickListener(v -> {
            boolean saved = SageRedQueenVault.saveRecord(this, "note", "Owner note",
                    note.getText().toString());
            Toast.makeText(this, saved ? "Private note encrypted." : "Private note not saved.",
                    Toast.LENGTH_LONG).show();
            if (saved) note.setText("");
        });
        root.addView(save);
        TextView audit = label(SageRedQueenVault.auditReport(this), 13, Color.LTGRAY);
        audit.setTextIsSelectable(true);
        root.addView(audit);
        Button lock = button("Secure Red Queen now");
        lock.setOnClickListener(v -> secure("explicit_exit"));
        root.addView(lock);
        setContentView(scroll);
        armInactivity();
    }

    private void functional(LinearLayout root, String title, String detail, Class<?> target) {
        Button card = button(title + " — FUNCTIONAL\n" + detail);
        card.setOnClickListener(v -> {
            if (!SageRedQueenSession.isUnlocked(this)) { showLocked(); return; }
            SageRedQueenVault.appendAudit(this, "workspace_open", title);
            if (target == null) showWorkspace();
            else startActivity(new Intent(this, target));
        });
        root.addView(card);
    }

    private void deferred(LinearLayout root, String title, String detail) {
        TextView card = label(title + " — DEFERRED\n" + detail, 15, Color.rgb(180, 180, 180));
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(card);
    }

    private void secure(String reason) {
        SageRedQueenSession.lock(this, reason);
        Toast.makeText(this, "Red Queen secured.", Toast.LENGTH_LONG).show();
        showLocked();
    }

    private void armInactivity() {
        handler.removeCallbacks(inactivityLock);
        if (SageRedQueenSession.isUnlocked(this))
            handler.postDelayed(inactivityLock, INACTIVITY_MS);
    }

    private ScrollView shell() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root);
        return scroll;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(48, 5, 12));
        background.setStroke(dp(1), Color.rgb(180, 20, 40));
        background.setCornerRadius(dp(8));
        button.setBackground(background);
        button.setMinHeight(dp(72));
        return button;
    }

    private TextView label(String value, int size, int color) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        return label;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
'''


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected one {label}, found {count}")
    path.write_text(text.replace(old, new, 1))


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: red_queen_v1_28.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    (java / "SageRedQueenSession.java").write_text(SESSION)
    (java / "SageRedQueenVault.java").write_text(VAULT)
    (java / "SageRedQueenActivity.java").write_text(ACTIVITY)

    manifest = root / "app/src/main/AndroidManifest.xml"
    replace_once(
        manifest,
        '        <activity android:name=".SageBrainTestActivity" android:exported="false" />',
        '        <activity android:name=".SageBrainTestActivity" android:exported="false" />\n'
        '        <activity android:name=".SageRedQueenActivity" android:exported="false" />',
        "Red Queen manifest anchor",
    )

    workbench = java / "SageWorkbenchActivity.java"
    replace_once(
        workbench,
        'card(r,"Sage Toolbelt","Package, QR, file-hash, network, media, and deliberate voice-command tools",SageToolbeltActivity.class);',
        'card(r,"Red Queen Mode","Owner-authenticated black/crimson workspace with encrypted private storage and audited advanced tools",SageRedQueenActivity.class);'
        'card(r,"Sage Toolbelt","Package, QR, file-hash, network, media, and deliberate voice-command tools",SageToolbeltActivity.class);',
        "Workbench Red Queen card",
    )

    command = java / "SageCommandEngine.java"
    replace_once(
        command,
        '''        if (isAny(lower, "red queen mode", "activate red queen mode", "red queen")) {
            return new Result("You're all going to die down here.");
        }''',
        '''        if (isAny(lower, "red queen mode", "activate red queen mode", "red queen")) {
            Intent ownerAuth = new Intent(context, SageRedQueenActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(ownerAuth);
                return new Result("Owner authentication required.");
            } catch (Exception error) {
                SageDiagnostics.recordError(context, "Red Queen entry failed: " + error);
                return new Result("Owner authentication required. Open Sage Workbench and tap Red Queen Mode.");
            }
        }''',
        "Red Queen voice entry",
    )
    print("Applied Sage 1.28 owner-authenticated Red Queen boundary")


if __name__ == "__main__":
    main()
