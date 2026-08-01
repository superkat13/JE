from pathlib import Path
import json
import re
import sys


ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
REPO = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"

access = (JAVA / "SageAccessibilityService.java").read_text()
authority = (JAVA / "SageAuthority.java").read_text()
authority_ui = (JAVA / "SageAuthorityActivity.java").read_text()
commands = (JAVA / "SageCommandEngine.java").read_text()
diagnostics = (JAVA / "SageDiagnostics.java").read_text()
main = (JAVA / "MainActivity.java").read_text()
memory = (JAVA / "SageMemoryStore.java").read_text()
repair = (JAVA / "SageRepairManager.java").read_text()
repair_ui = (JAVA / "SageRepairActivity.java").read_text()
voice = (JAVA / "SageVoiceService.java").read_text()
state_machine = (JAVA / "SageConversationStateMachine.java").read_text()
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
gradle = (ROOT / "app/build.gradle.kts").read_text()
workflow = (REPO / ".github/workflows/sage-approved-repair.yml").read_text()
schema = json.loads((REPO / "sage_repair/repair-packet.schema.json").read_text())


def require(source, markers, label):
    missing = [marker for marker in markers if marker not in source]
    if missing:
        raise SystemExit(f"{label} missing: {missing}")


require(access, [
    'clearNumberOverlayInternal("timeout", null)',
    '"ignored_sage_overlay_event"',
    '"ignored_content_or_window_churn"',
    '"package_navigation"',
    '"view_clicked"',
    '"view_scrolled"',
    '"selection_succeeded"',
    '"settings_manual_clear"' if False else '"manual_clear"',
    '"number_overlay_timeout_ms"',
    "overlayCreated(",
    "overlayCleared(",
], "overlay stability")
if "SageAccessibilityService.clearNumberOverlay();" in main:
    raise SystemExit("activity lifecycle still performs an unconditional overlay clear")
cancel = commands[commands.index("public void cancelFollowUp()"):
                  commands.index("public Result execute(")]
if "clearNumberOverlay" in cancel:
    raise SystemExit("follow-up cancellation still clears numbered overlays")

require(voice, [
    "String candidate = chooseBestCandidate(finalChoices);",
    'fallbackPartials.isEmpty() ? "empty_final" : "partial_without_final"',
    "CONFIDENCE_SCORES",
    "MAX_COMMAND_QUALITY_RETRIES = 1",
    '" final_not_executed retry_once reason="',
    '"speaker_echo"',
    '"wake_phrase_not_matched"',
], "speech reliability")
require(state_machine, ['"wake_phrase"', '"executable_final"'],
        "state-machine speech reliability")

require(commands, [
    'pendingAction = "remember_item"',
    'SageMemoryStore.save(context, value)',
    '"I already remember that."',
    '"what did i ask you to remember"',
    '.remove("memory_items")',
    '.remove("last_saved_memory")',
    '"last recall succeeded"',
    '"recall count=" + items.size()',
    '"diagnose yourself"',
    '"diagnose and prepare a fix"',
    "SageRepairActivity.EXTRA_PREPARE_FIX",
], "memory and voice repair routing")
require(memory, [
    "SaveResult.DUPLICATE",
    'ITEMS = "memory_items"',
    'LAST = "last_saved_memory"',
    ".commit()",
], "memory persistence")

capabilities = {
    "accessibility_service", "notification_access", "usage_access",
    "overlay_permission", "battery_optimization", "boot_startup",
    "default_assistant", "default_launcher", "device_admin", "device_owner",
}
for capability in capabilities:
    if f'"{capability}"' not in authority:
        raise SystemExit(f"authority capability missing: {capability}")
require(authority, [
    "enum State { ACTIVE, AVAILABLE, NEEDS_SETUP, UNSUPPORTED }",
    "accessibilityActive(context)",
    "notificationAccessActive(context)",
    "usageAccessActive(context)",
    "isIgnoringBatteryOptimizations",
    "isDeviceOwnerApp",
    "Sage does not declare a device-admin receiver",
    "Device-owner provisioning is not attempted",
], "authority truthfulness")
require(authority_ui, [
    '"Authority & Permissions"',
    '"Open Android setup"',
    "capability.setupIntent",
], "authority dashboard")

require(repair, [
    'CLASS_CONFIGURATION = "configuration"',
    'CLASS_PERMISSION = "permission"',
    'CLASS_TRANSIENT = "transient_runtime_issue"',
    'CLASS_CODE = "likely_code_defect"',
    '"repair-packet.json"',
    '"repair-report.md"',
    '"sanitized.log"',
    "ZipOutputStream",
    '"signing_certificate_sha256"',
    "int signingFlag = Build.VERSION.SDK_INT >= 28",
    '"confirmed_evidence"',
    '"theories"',
    '"owner_approval_required", true',
    '"arbitrary_commands", new JSONArray()',
    '"[REDACTED_TOKEN]"',
    '"Owner-requested reversible configuration repair',
], "repair bundle")
require(repair_ui, [
    '"Nothing is sent, installed, merged, or granted automatically.',
    '"Approve and export repair bundle"',
    "SageRepairManager.noteApprovedExport(this)",
    "FileProvider.getUriForFile",
], "explicit approval")
require(main, [
    '"Diagnose Sage / prepare repair bundle"',
    '"Authority & Permissions"',
], "existing diagnostics entry points")
require(diagnostics, ["recentEvents(Context context)"], "diagnostic export")

require(manifest, [
    'android:name=".SageRepairActivity"',
    'android:name=".SageAuthorityActivity"',
    'android:name="androidx.core.content.FileProvider"',
    'android.permission.PACKAGE_USAGE_STATS',
], "manifest wiring")
if 'android.permission.SYSTEM_ALERT_WINDOW' in manifest:
    raise SystemExit("unnecessary system overlay permission was added")
if 'android.permission.BIND_DEVICE_ADMIN' in manifest:
    raise SystemExit("device admin was silently introduced")

if 'applicationId = "com.pineapple.sagecommander.stable"' not in gradle:
    raise SystemExit("stable package identity changed")
if 'versionCode = 39' not in gradle or 'versionName = "1.27.0"' not in gradle:
    raise SystemExit("Sage 1.27.0 release identity missing")
if "sagePermanentSigning" not in gradle:
    raise SystemExit("permanent signing configuration missing")

assert schema["additionalProperties"] is False
properties = schema["properties"]
assert properties["repository"]["const"] == "superkat13/JE"
assert properties["base_branch"]["const"] == "agent/sage-1-27-unified-20260801"
assert properties["package_name"]["const"] == "com.pineapple.sagecommander.stable"
assert properties["version_name"]["const"] == "1.27.0"
assert properties["version_code"]["const"] == 39
assert properties["owner_approval_required"]["const"] is True
assert properties["arbitrary_commands"]["maxItems"] == 0
assert set(properties["requested_operations"]["items"]["enum"]) == {
    "inspect_sage_diagnostics", "modify_sage_source", "add_regression_tests"
}

require(workflow, [
    "workflow_dispatch:",
    "contents: read",
    'packet["repository"] == "superkat13/JE"',
    'packet["arbitrary_commands"] == []',
    'git switch -c "repair/approved-${GITHUB_RUN_ID}"',
    "self_repair_foundation_v1_25",
    ":app:lintRelease",
    ":app:assembleRelease",
    "apksigner\" verify --verbose \"$APK\"",
    "versionCode='39' versionName='1.27.0'",
    "actions/upload-artifact@v4",
], "safe repair workflow")
for forbidden in ("git push", "gh pr merge", "adb install", "create-release", "softprops/action-gh-release"):
    if forbidden in workflow:
        raise SystemExit(f"unsafe repair workflow action present: {forbidden}")


def choose(finals, partials):
    pool = finals if finals else partials
    return max(pool, key=len) if pool else ""


assert choose(["remember that my dog is Ada"], ["remember"]) == \
       "remember that my dog is Ada"
assert choose([], ["show numbers"]) == "show numbers"


def sanitize(value):
    value = re.sub(
        r"(?i)(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|secret|password|authorization)\s*[:=]\s*[^\s,;]+",
        r"\1=[REDACTED]", value,
    )
    value = re.sub(r"(?i)bearer\s+[A-Za-z0-9._~+/-]+=*", "Bearer [REDACTED]", value)
    return re.sub(r"(?i)(ghp_|github_pat_|sk-)[A-Za-z0-9_-]{8,}", "[REDACTED_TOKEN]", value)


redacted = sanitize("api_key=abc123 bearer tokenvalue ghp_abcdefghijklmnop")
assert "abc123" not in redacted and "tokenvalue" not in redacted
assert "ghp_abcdefghijklmnop" not in redacted

print("Sage Commander 1.27.0 supervised self-repair regressions passed")
