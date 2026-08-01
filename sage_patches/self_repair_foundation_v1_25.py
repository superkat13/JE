from pathlib import Path
import re
import sys


ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"


def replace_once(path, old, new, label):
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1))


authority = r'''package com.pineapple.sage;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class SageAuthority {
    enum State { ACTIVE, AVAILABLE, NEEDS_SETUP, UNSUPPORTED }

    static final class Capability {
        final String key;
        final String label;
        final State state;
        final String explanation;
        final Intent setupIntent;

        Capability(String key, String label, State state, String explanation, Intent setupIntent) {
            this.key = key;
            this.label = label;
            this.state = state;
            this.explanation = explanation;
            this.setupIntent = setupIntent;
        }

        String displayState() {
            if (state == State.ACTIVE) return "Active";
            if (state == State.AVAILABLE) return "Available";
            if (state == State.NEEDS_SETUP) return "Needs setup";
            return "Unsupported";
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("capability", key);
                object.put("label", label);
                object.put("state", displayState());
                object.put("explanation", explanation);
                object.put("manual_approval_required", state == State.NEEDS_SETUP);
            } catch (Exception ignored) {
            }
            return object;
        }
    }

    private SageAuthority() {
    }

    static List<Capability> inspect(Context context) {
        List<Capability> result = new ArrayList<>();
        boolean accessibility = accessibilityActive(context);
        result.add(new Capability(
                "accessibility_service", "Accessibility service",
                accessibility ? State.ACTIVE : State.NEEDS_SETUP,
                accessibility
                        ? "Sage tablet control is enabled."
                        : "Android requires the owner to enable Sage in Accessibility settings.",
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        ));

        boolean notifications = notificationAccessActive(context);
        result.add(new Capability(
                "notification_access", "Notification access",
                notifications ? State.ACTIVE : State.NEEDS_SETUP,
                notifications
                        ? "Sage notification access is enabled."
                        : "Android requires the owner to approve notification access.",
                new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        ));

        boolean usage = usageAccessActive(context);
        result.add(new Capability(
                "usage_access", "Usage access",
                usage ? State.ACTIVE : State.NEEDS_SETUP,
                usage
                        ? "Android usage access is enabled for Sage."
                        : "Optional usage access requires manual Android approval.",
                new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        ));

        boolean overlayDeclared = permissionDeclared(context, "android.permission.SYSTEM_ALERT_WINDOW");
        boolean overlay = overlayDeclared && Settings.canDrawOverlays(context);
        result.add(new Capability(
                "overlay_permission", "System overlay permission",
                overlay ? State.ACTIVE : overlayDeclared ? State.NEEDS_SETUP : State.UNSUPPORTED,
                overlayDeclared
                        ? (overlay ? "System overlay permission is active."
                        : "Android requires the owner to approve system overlays.")
                        : "Not requested: numbered markers use the accessibility overlay authority instead.",
                overlayDeclared
                        ? new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + context.getPackageName()))
                        : null
        ));

        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean battery = power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
        result.add(new Capability(
                "battery_optimization", "Battery optimization exemption",
                battery ? State.ACTIVE : State.NEEDS_SETUP,
                battery
                        ? "Sage is exempt from battery optimization."
                        : "Optional exemption requires owner approval in Android settings.",
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        ));

        boolean boot = permissionDeclared(context, "android.permission.RECEIVE_BOOT_COMPLETED");
        result.add(new Capability(
                "boot_startup", "Boot startup",
                boot ? State.AVAILABLE : State.UNSUPPORTED,
                boot
                        ? "The build supports boot startup; activation remains an owner setting."
                        : "This build does not silently start Sage after reboot.",
                null
        ));

        result.add(roleCapability(context, "default_assistant", "Default assistant",
                "android.app.role.ASSISTANT", Settings.ACTION_VOICE_INPUT_SETTINGS));
        result.add(roleCapability(context, "default_launcher", "Default launcher",
                "android.app.role.HOME", Settings.ACTION_HOME_SETTINGS));

        DevicePolicyManager dpm =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        boolean owner = dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
        result.add(new Capability(
                "device_admin", "Device admin",
                State.UNSUPPORTED,
                "Sage does not declare a device-admin receiver. Self-repair authority is separate.",
                null
        ));
        result.add(new Capability(
                "device_owner", "Device owner",
                owner ? State.ACTIVE : State.UNSUPPORTED,
                owner
                        ? "Android reports Sage as device owner."
                        : "Device-owner provisioning is not attempted or offered automatically.",
                null
        ));
        return result;
    }

    static JSONArray toJson(Context context) {
        JSONArray array = new JSONArray();
        for (Capability capability : inspect(context)) array.put(capability.toJson());
        return array;
    }

    static boolean hasNeedsSetup(Context context) {
        for (Capability capability : inspect(context)) {
            if (capability.state == State.NEEDS_SETUP) return true;
        }
        return false;
    }

    private static Capability roleCapability(
            Context context, String key, String label, String role, String settingsAction
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new Capability(key, label, State.UNSUPPORTED,
                    "Android role management is unavailable on this Android version.", null);
        }
        RoleManager manager = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
        if (manager == null || !manager.isRoleAvailable(role)) {
            return new Capability(key, label, State.UNSUPPORTED,
                    "Android does not expose this role on this tablet.", null);
        }
        if (manager.isRoleHeld(role)) {
            return new Capability(key, label, State.ACTIVE,
                    "Android reports Sage currently holds this role.", null);
        }
        return new Capability(key, label, State.AVAILABLE,
                "Android supports this role, but Sage does not claim it is active.",
                new Intent(settingsAction));
    }

    private static boolean accessibilityActive(Context context) {
        AccessibilityManager manager =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        ComponentName expected = new ComponentName(context, SageAccessibilityService.class);
        for (AccessibilityServiceInfo info :
                manager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() != null
                    && info.getResolveInfo().serviceInfo != null
                    && expected.getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)
                    && expected.getClassName().equals(info.getResolveInfo().serviceInfo.name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean notificationAccessActive(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        ComponentName expected = new ComponentName(context, SageNotificationListener.class);
        for (String item : enabled.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(item);
            if (expected.equals(component)) return true;
        }
        return false;
    }

    private static boolean usageAccessActive(Context context) {
        AppOpsManager manager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (manager == null) return false;
        return manager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.getPackageName()
        ) == AppOpsManager.MODE_ALLOWED;
    }

    private static boolean permissionDeclared(Context context, String permission) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) {
                for (String requested : info.requestedPermissions) {
                    if (permission.equals(requested)) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
'''


authority_activity = r'''package com.pineapple.sage;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SageAuthorityActivity extends Activity {
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Sage Authority & Permissions");
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private View build() {
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);
        return scroll;
    }

    private void refresh() {
        list.removeAllViews();
        TextView title = label("Authority & Permissions", 28, Color.WHITE);
        list.addView(title);
        TextView warning = label(
                "Android keeps protected authority under owner control. Sage only reports verified state and opens the correct setup screen. Device admin/device owner are separate from supervised self-repair.",
                15, Color.LTGRAY);
        warning.setPadding(0, 8, 0, 18);
        list.addView(warning);
        for (SageAuthority.Capability capability : SageAuthority.inspect(this)) {
            TextView name = label(capability.label + " — " + capability.displayState(),
                    19, stateColor(capability.state));
            name.setPadding(0, 14, 0, 2);
            list.addView(name);
            list.addView(label(capability.explanation, 14, Color.LTGRAY));
            if (capability.setupIntent != null
                    && capability.state != SageAuthority.State.ACTIVE) {
                Button setup = new Button(this);
                setup.setText("Open Android setup");
                setup.setOnClickListener(v -> open(capability.setupIntent));
                list.addView(setup);
            }
        }
        SageAppearance.apply(this, scrollParent(), list);
    }

    private ScrollView scrollParent() {
        return (ScrollView) list.getParent();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
            SageDiagnostics.appendEvent(this, "AUTHORITY", "Opened manual Android setup: " + intent.getAction());
        } catch (Exception error) {
            Toast.makeText(this, "This tablet does not provide that setup screen.", Toast.LENGTH_LONG).show();
        }
    }

    private TextView label(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private int stateColor(SageAuthority.State state) {
        if (state == SageAuthority.State.ACTIVE) return Color.rgb(74, 222, 128);
        if (state == SageAuthority.State.NEEDS_SETUP) return Color.rgb(251, 191, 36);
        if (state == SageAuthority.State.AVAILABLE) return Color.rgb(96, 165, 250);
        return Color.rgb(156, 163, 175);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
'''


repair_manager = r'''package com.pineapple.sage;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class SageRepairManager {
    static final String CLASS_CONFIGURATION = "configuration";
    static final String CLASS_PERMISSION = "permission";
    static final String CLASS_TRANSIENT = "transient_runtime_issue";
    static final String CLASS_CODE = "likely_code_defect";
    static final String[] ALLOWED_OPERATIONS = {
            "inspect_sage_diagnostics", "modify_sage_source", "add_regression_tests"
    };
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|secret|password|authorization)\\s*[:=]\\s*[^\\s,;]+"
    );
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*");

    static final class Draft {
        final File zip;
        final File markdown;
        final File packet;
        final String classification;

        Draft(File zip, File markdown, File packet, String classification) {
            this.zip = zip;
            this.markdown = markdown;
            this.packet = packet;
            this.classification = classification;
        }
    }

    private SageRepairManager() {
    }

    static Draft prepare(Context context, boolean prepareFix, String reproduction) throws Exception {
        File directory = new File(context.getCacheDir(), "sage-repair-draft");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create private repair draft directory");
        }
        String events = sanitize(SageDiagnostics.recentEvents(context));
        String classification = classify(context, events);
        if (prepareFix) repairSafeConfiguration(context);
        JSONObject packet = buildPacket(context, classification, reproduction, events);
        String markdown = buildMarkdown(context, packet, events);
        String logs = buildSanitizedLogs(events);

        File jsonFile = new File(directory, "repair-packet.json");
        File markdownFile = new File(directory, "repair-report.md");
        File logFile = new File(directory, "sanitized.log");
        write(jsonFile, packet.toString(2));
        write(markdownFile, markdown);
        write(logFile, logs);
        File zipFile = new File(directory, "Sage-1.25.0-repair-bundle.zip");
        zip(zipFile, jsonFile, markdownFile, logFile);
        SageDiagnostics.appendEvent(
                context, "SELF REPAIR",
                "Draft prepared classification=" + classification + " export_approved=false"
        );
        return new Draft(zipFile, markdownFile, jsonFile, classification);
    }

    static void noteApprovedExport(Context context) {
        SageDiagnostics.appendEvent(
                context, "SELF REPAIR",
                "Owner approved repair bundle export; no code install or permission grant performed"
        );
    }

    static String sanitize(String input) {
        String value = input == null ? "" : input;
        value = SECRET.matcher(value).replaceAll("$1=[REDACTED]");
        value = BEARER.matcher(value).replaceAll("Bearer [REDACTED]");
        return value.replaceAll("(?i)(ghp_|github_pat_|sk-)[A-Za-z0-9_-]{8,}", "[REDACTED_TOKEN]");
    }

    private static void repairSafeConfiguration(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences("sage_state", Context.MODE_PRIVATE);
        long timeout = preferences.getLong("number_overlay_timeout_ms", 60000L);
        boolean valid = timeout == 15000L || timeout == 30000L || timeout == 60000L
                || timeout == 120000L || timeout == -1L;
        if (!valid) {
            preferences.edit().putLong("number_overlay_timeout_ms", 60000L).apply();
            SageDiagnostics.appendEvent(
                    context, "SELF REPAIR ACTION",
                    "Owner-requested reversible configuration repair: overlay timeout reset to 60000ms"
            );
        } else {
            SageDiagnostics.appendEvent(
                    context, "SELF REPAIR ACTION",
                    "Owner-requested configuration check completed; no safe change required"
            );
        }
    }

    private static String classify(Context context, String events) {
        long timeout = context.getSharedPreferences("sage_state", Context.MODE_PRIVATE)
                .getLong("number_overlay_timeout_ms", 60000L);
        if (!(timeout == 15000L || timeout == 30000L || timeout == 60000L
                || timeout == 120000L || timeout == -1L)) return CLASS_CONFIGURATION;
        String lower = events.toLowerCase(Locale.US);
        if (lower.contains("error") || lower.contains("save failed")
                || lower.contains("overlay render")) return CLASS_CODE;
        if (lower.contains("recognizer busy") || lower.contains("network timeout")
                || lower.contains("temporarily unavailable")) return CLASS_TRANSIENT;
        if (SageAuthority.hasNeedsSetup(context)) return CLASS_PERMISSION;
        return CLASS_CONFIGURATION;
    }

    private static JSONObject buildPacket(
            Context context, String classification, String reproduction, String events
    ) throws Exception {
        int signingFlag = Build.VERSION.SDK_INT >= 28 ? 0x08000000 : 0x00000040;
        PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), signingFlag);
        JSONObject packet = new JSONObject();
        packet.put("schema_version", "1.0");
        packet.put("packet_id", "sage-" + System.currentTimeMillis());
        packet.put("created_at", timestamp());
        packet.put("repository", "superkat13/JE");
        packet.put("base_branch", "agent/sage-continuity-v1-24");
        packet.put("package_name", context.getPackageName());
        packet.put("version_name", info.versionName);
        packet.put("version_code", Build.VERSION.SDK_INT >= 28
                ? info.getLongVersionCode() : info.versionCode);
        packet.put("signing_certificate_sha256", signingDigest(info));
        packet.put("classification", classification);
        packet.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        packet.put("android_version", Build.VERSION.RELEASE);
        packet.put("android_api", Build.VERSION.SDK_INT);
        packet.put("authority_states", SageAuthority.toJson(context));
        packet.put("brain_status", SageBrainManager.get(context).getStatusSummary());
        packet.put("wake_status", SageDiagnostics.buildSummary(context));
        packet.put("reproduction_steps", sanitize(reproduction));
        packet.put("confirmed_evidence", evidence(events));
        packet.put("theories", theories(classification));
        JSONArray operations = new JSONArray();
        operations.put("inspect_sage_diagnostics");
        if (CLASS_CODE.equals(classification)) {
            operations.put("modify_sage_source");
            operations.put("add_regression_tests");
        }
        packet.put("requested_operations", operations);
        packet.put("owner_approval_required", true);
        packet.put("arbitrary_commands", new JSONArray());
        return packet;
    }

    private static JSONArray evidence(String events) {
        JSONArray values = new JSONArray();
        values.put("Repair packet generated locally by installed Sage.");
        if (events.contains("OVERLAY")) values.put("Overlay lifecycle diagnostics are present.");
        if (events.contains("RECOGNITION")) values.put("Speech recognition decisions are present.");
        if (events.contains("MEMORY")) values.put("Memory lifecycle diagnostics are present.");
        if (events.contains("ERROR")) values.put("Recent error diagnostics are present.");
        return values;
    }

    private static JSONArray theories(String classification) {
        JSONArray values = new JSONArray();
        if (CLASS_PERMISSION.equals(classification)) {
            values.put("One or more manually approved Android authorities may be missing.");
        } else if (CLASS_TRANSIENT.equals(classification)) {
            values.put("A platform service may have been temporarily unavailable.");
        } else if (CLASS_CODE.equals(classification)) {
            values.put("A Sage code path may require isolated reproduction and regression coverage.");
        } else {
            values.put("A persisted setting may be outside Sage's supported configuration values.");
        }
        return values;
    }

    private static String buildMarkdown(Context context, JSONObject packet, String events)
            throws Exception {
        return "# Sage supervised repair report\n\n"
                + "Export status: **Draft — owner approval required**\n\n"
                + "- Version: " + packet.getString("version_name") + " ("
                + packet.getLong("version_code") + ")\n"
                + "- Package: `" + packet.getString("package_name") + "`\n"
                + "- Signing SHA-256: `" + packet.getString("signing_certificate_sha256") + "`\n"
                + "- Device: " + packet.getString("device") + "\n"
                + "- Android: " + packet.getString("android_version") + " (API "
                + packet.getInt("android_api") + ")\n"
                + "- Classification: **" + packet.getString("classification") + "**\n\n"
                + "## Reproduction steps\n\n"
                + emptyFallback(packet.optString("reproduction_steps"), "No steps supplied.") + "\n\n"
                + "## Confirmed evidence\n\n" + bulletList(packet.getJSONArray("confirmed_evidence"))
                + "\n## Theories\n\n" + bulletList(packet.getJSONArray("theories"))
                + "\n## Authority states\n\n" + authorityMarkdown(context)
                + "\n## Brain\n\n" + sanitize(packet.optString("brain_status"))
                + "\n\n## Recent sanitized diagnostics\n\n```text\n"
                + emptyFallback(events, "No recent events.") + "\n```\n";
    }

    private static String authorityMarkdown(Context context) {
        StringBuilder result = new StringBuilder();
        for (SageAuthority.Capability capability : SageAuthority.inspect(context)) {
            result.append("- ").append(capability.label).append(": **")
                    .append(capability.displayState()).append("** — ")
                    .append(capability.explanation).append("\n");
        }
        return result.toString();
    }

    private static String buildSanitizedLogs(String events) {
        return "Sage sanitized diagnostic export\nCreated: " + timestamp()
                + "\nSecrets: redacted\n\n" + emptyFallback(events, "No recent events.");
    }

    private static String signingDigest(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
                ? info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signatures == null || signatures.length == 0) return "unavailable";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
        StringBuilder value = new StringBuilder();
        for (byte item : digest) value.append(String.format(Locale.US, "%02x", item));
        return value.toString();
    }

    private static void write(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void zip(File output, File... files) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output, false))) {
            for (File file : files) {
                zip.putNextEntry(new ZipEntry(file.getName()));
                zip.write(java.nio.file.Files.readAllBytes(file.toPath()));
                zip.closeEntry();
            }
        }
    }

    private static String bulletList(JSONArray values) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length(); index++) {
            result.append("- ").append(values.optString(index)).append("\n");
        }
        return result.toString();
    }

    private static String emptyFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                .format(new Date());
    }
}
'''


repair_activity = r'''package com.pineapple.sage;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class SageRepairActivity extends Activity {
    public static final String EXTRA_PREPARE_FIX = "prepare_fix";
    private EditText reproduction;
    private TextView preview;
    private SageRepairManager.Draft draft;
    private boolean prepareFix;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prepareFix = getIntent().getBooleanExtra(EXTRA_PREPARE_FIX, false);
        setTitle("Sage Supervised Repair");
        setContentView(build());
        prepare();
    }

    private View build() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = text(prepareFix
                ? "Diagnose and prepare a fix" : "Diagnose Sage", 27);
        root.addView(title);
        TextView guard = text(
                "Nothing is sent, installed, merged, or granted automatically. Review the draft below. Export requires your explicit approval.", 15);
        guard.setPadding(0, 6, 0, 14);
        root.addView(guard);

        reproduction = new EditText(this);
        reproduction.setHint("Reproduction steps (optional)");
        reproduction.setMinLines(3);
        root.addView(reproduction);

        Button refresh = new Button(this);
        refresh.setText("Refresh private repair draft");
        refresh.setOnClickListener(v -> prepare());
        root.addView(refresh);

        Button authority = new Button(this);
        authority.setText("Authority & Permissions");
        authority.setOnClickListener(v ->
                startActivity(new Intent(this, SageAuthorityActivity.class)));
        root.addView(authority);

        preview = text("Preparing private draft…", 13);
        preview.setTextIsSelectable(true);
        preview.setPadding(12, 14, 12, 14);
        root.addView(preview);

        Button export = new Button(this);
        export.setText("Approve and export repair bundle");
        export.setOnClickListener(v -> approveAndExport());
        root.addView(export);

        SageAppearance.apply(this, scroll, root);
        return scroll;
    }

    private void prepare() {
        try {
            String steps = reproduction == null ? "" : reproduction.getText().toString();
            draft = SageRepairManager.prepare(this, prepareFix, steps);
            preview.setText(new String(
                    Files.readAllBytes(draft.markdown.toPath()), StandardCharsets.UTF_8));
        } catch (Exception error) {
            draft = null;
            preview.setText("Repair draft failed: " + error.getClass().getSimpleName());
            SageDiagnostics.recordError(this, "Repair draft failed: " + error);
        }
    }

    private void approveAndExport() {
        if (draft == null || !draft.zip.isFile()) {
            Toast.makeText(this, "Prepare the draft first.", Toast.LENGTH_LONG).show();
            return;
        }
        SageRepairManager.noteApprovedExport(this);
        Uri uri = FileProvider.getUriForFile(
                this, getPackageName() + ".files", draft.zip);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/zip");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Export approved Sage repair bundle"));
    }

    private TextView text(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(Color.WHITE);
        return text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
'''


(JAVA / "SageAuthority.java").write_text(authority)
(JAVA / "SageAuthorityActivity.java").write_text(authority_activity)
(JAVA / "SageRepairManager.java").write_text(repair_manager)
(JAVA / "SageRepairActivity.java").write_text(repair_activity)

diagnostics = JAVA / "SageDiagnostics.java"
replace_once(
    diagnostics,
    '''    public static void clear(Context context) {''',
    '''    public static String recentEvents(Context context) {
        String events = preferences(context).getString(KEY_EVENT_LOG, "");
        return events == null ? "" : events;
    }

    public static void clear(Context context) {''',
    "diagnostics event export",
)

command = JAVA / "SageCommandEngine.java"
replace_once(
    command,
    '''        if (isAny(lower, "remember", "remember something", "save a memory")) {''',
    '''        if (isAny(lower, "diagnose yourself", "diagnose sage", "run self diagnosis")) {
            return openRepair(false);
        }
        if (isAny(
                lower,
                "diagnose and prepare a fix",
                "diagnose yourself and prepare a fix",
                "prepare a sage fix"
        )) {
            return openRepair(true);
        }

        if (isAny(lower, "remember", "remember something", "save a memory")) {''',
    "repair voice commands",
)
replace_once(
    command,
    '''    private Result remember(String item) {''',
    '''    private Result openRepair(boolean prepareFix) {
        Intent intent = new Intent(context, SageRepairActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SageRepairActivity.EXTRA_PREPARE_FIX, prepareFix);
        try {
            context.startActivity(intent);
            SageDiagnostics.appendEvent(
                    context,
                    "SELF REPAIR",
                    prepareFix ? "Owner requested diagnosis and fix preparation"
                            : "Owner requested self diagnosis"
            );
            return new Result(prepareFix
                    ? "I prepared a supervised repair review. Nothing leaves the tablet until you approve export."
                    : "My private diagnostic review is open. Nothing is shared until you approve it.");
        } catch (Exception error) {
            SageDiagnostics.recordError(context, "Could not open supervised repair: " + error);
            return new Result("I could not open the repair review. Check Sage diagnostics.");
        }
    }

    private Result remember(String item) {''',
    "repair activity launcher",
)
replace_once(
    command,
    '''    private Result recallLast() {
        String last = SageMemoryStore.recallLast(context);
        return last.isEmpty()
                ? new Result("You have not asked me to remember anything yet.")
                : new Result("You asked me to remember: " + last + ".");
    }

    private Result recall() {
        List<String> items = SageMemoryStore.recallAll(context);
        if (items.isEmpty()) {
            return new Result("I do not have any saved notes yet.");
        }
        return new Result("I remember: " + String.join(". ", items));
    }''',
    '''    private Result recallLast() {
        String last = SageMemoryStore.recallLast(context);
        SageDiagnostics.memoryEvent(
                context,
                last.isEmpty() ? "last recall empty" : "last recall succeeded"
        );
        return last.isEmpty()
                ? new Result("You have not asked me to remember anything yet.")
                : new Result("You asked me to remember: " + last + ".");
    }

    private Result recall() {
        List<String> items = SageMemoryStore.recallAll(context);
        SageDiagnostics.memoryEvent(
                context,
                items.isEmpty() ? "recall empty" : "recall count=" + items.size()
        );
        if (items.isEmpty()) {
            return new Result("I do not have any saved notes yet.");
        }
        return new Result("I remember: " + String.join(". ", items));
    }''',
    "memory recall diagnostics",
)

voice = JAVA / "SageVoiceService.java"
replace_once(
    voice,
    '''                        commandQualityRetries++;
                        broadcastStatus("I only caught part of that — listening once more");''',
    '''                        commandQualityRetries++;
                        SageDiagnostics.appendEvent(
                                SageVoiceService.this,
                                "RECOGNITION ACTION",
                                "retry_once reason=" + rejection
                        );
                        broadcastStatus("I only caught part of that — listening once more");''',
    "recognition retry diagnostics",
)
replace_once(
    voice,
    '''                    } else if (!candidate.isEmpty()) {
                        commandQualityRetries = 0;
                        handleCommand(candidate);''',
    '''                    } else if (!candidate.isEmpty()) {
                        SageDiagnostics.appendEvent(
                                SageVoiceService.this,
                                "RECOGNITION ACTION",
                                rejection.isEmpty()
                                        ? "accepted final_or_partial_fallback"
                                        : "accepted_after_single_retry reason=" + rejection
                        );
                        commandQualityRetries = 0;
                        handleCommand(candidate);''',
    "recognition acceptance diagnostics",
)

main = JAVA / "MainActivity.java"
replace_once(
    main,
    '''        Button clearDiagnostics = makeButton("Clear diagnostic history");''',
    '''        Button diagnose = makeButton("Diagnose Sage / prepare repair bundle");
        diagnose.setOnClickListener(v ->
                startActivity(new Intent(this, SageRepairActivity.class)));
        root.addView(diagnose, spacedSmall());

        Button authorityDashboard = makeButton("Authority & Permissions");
        authorityDashboard.setOnClickListener(v ->
                startActivity(new Intent(this, SageAuthorityActivity.class)));
        root.addView(authorityDashboard, spacedSmall());

        Button clearDiagnostics = makeButton("Clear diagnostic history");''',
    "diagnostics controls",
)

manifest = ROOT / "app/src/main/AndroidManifest.xml"
replace_once(
    manifest,
    '''<manifest xmlns:android="http://schemas.android.com/apk/res/android">''',
    '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">''',
    "manifest tools namespace",
)
replace_once(
    manifest,
    '''    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />''',
    '''    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <!-- Usage access is an owner-controlled app-op. Declaration only makes Sage
         eligible to appear in Android's Usage Access screen; it grants nothing. -->
    <uses-permission
        android:name="android.permission.PACKAGE_USAGE_STATS"
        tools:ignore="ProtectedPermissions" />''',
    "usage authority declaration",
)
replace_once(
    manifest,
    '''        <activity
            android:name=".MainActivity"
            android:exported="true">''',
    '''        <activity
            android:name=".SageRepairActivity"
            android:exported="false" />
        <activity
            android:name=".SageAuthorityActivity"
            android:exported="false" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.files"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

        <activity
            android:name=".MainActivity"
            android:exported="true">''',
    "repair activities and provider",
)

file_paths = ROOT / "app/src/main/res/xml/file_paths.xml"
file_paths.parent.mkdir(parents=True, exist_ok=True)
file_paths.write_text('''<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <cache-path name="repair_bundle" path="sage-repair-draft/" />
</paths>
''')

build = ROOT / "app/build.gradle.kts"
text = build.read_text()
text, code_count = re.subn(r'versionCode\s*=\s*\d+', 'versionCode = 37', text, count=1)
text, name_count = re.subn(
    r'versionName\s*=\s*["\'][^"\']+["\']', 'versionName = "1.25.0"', text, count=1)
if code_count != 1 or name_count != 1:
    raise SystemExit(f"release identity replacement failed: {code_count}/{name_count}")
if 'implementation("androidx.core:core:1.15.0")' not in text:
    text = text.replace(
        'dependencies {',
        '''configurations.configureEach {
    // Kotlin 1.8 folded the old jdk7/jdk8 artifacts into kotlin-stdlib. Older
    // transitive metadata can otherwise package the same classes twice.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

dependencies {
    implementation("androidx.core:core:1.15.0")''',
        1,
    )
build.write_text(text)

for xml in (ROOT / "app/src/main").rglob("*.xml"):
    value = xml.read_text()
    updated = re.sub(
        r'Sage Commander(?:\s+\d+\.\d+(?:\.\d+)?)?',
        'Sage Commander 1.25.0',
        value,
    )
    if updated != value:
        xml.write_text(updated)

print("Applied Sage Commander 1.25.0 supervised self-repair foundation")
