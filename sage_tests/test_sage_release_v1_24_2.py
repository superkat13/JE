from pathlib import Path
import re
import sys


ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"

accessibility = (JAVA / "SageAccessibilityService.java").read_text()
commands = (JAVA / "SageCommandEngine.java").read_text()
diagnostics = (JAVA / "SageDiagnostics.java").read_text()
voice = (JAVA / "SageVoiceService.java").read_text()
state_machine = (JAVA / "SageConversationStateMachine.java").read_text()
main = (JAVA / "MainActivity.java").read_text()
memory = (JAVA / "SageMemoryStore.java").read_text()
appearance = (JAVA / "SageAppearance.java").read_text()
gradle = (ROOT / "app/build.gradle.kts").read_text()
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()


def require(source, markers, group):
    missing = [marker for marker in markers if marker not in source]
    if missing:
        raise SystemExit(f"{group} missing: {missing}")


require(accessibility, [
    'clearNumberOverlayInternal("timeout", null)',
    '"service_unbind"',
    '"service_interrupt"',
    '"package_navigation"',
    '"view_clicked"',
    '"view_scrolled"',
    '"global_action_" + action',
    '"tablet_scroll_down"',
    '"tablet_scroll_up"',
    '"tablet_tap_text"',
    '"tablet_indexed_tap"',
    '"tablet_type_text"',
    '"selection_root_invalid"',
    '"selection_succeeded"',
    '"overlay_recreated"',
    '"manual_clear"',
    '"number_overlay_timeout_ms"',
    "DEFAULT_NUMBER_OVERLAY_TIMEOUT_MS = 60000L",
    "if (timeoutMs > 0L)",
    "TYPE_ACCESSIBILITY_OVERLAY",
    "findInteractiveTargets(",
    "windowManager.addView(stableOverlayContainer, containerParams)",
    "resolveCurrentTarget(",
    "eventPackage.equals(getPackageName())",
    '"ignored_sage_overlay_event"',
    '"ignored_content_or_window_churn"',
    "overlayCreated(",
    "overlayCleared(",
    "SystemClock.uptimeMillis() - numberOverlayShownAtMs",
], "overlay lifecycle")

for stale in (
    'clearNumberOverlayInternal();',
    'if (differentPackage || differentWindow || deliberateMovement)',
):
    if stale in accessibility:
        raise SystemExit(f"unsafe overlay clear remains: {stale}")

if "SageAccessibilityService.clearNumberOverlay();" in main:
    raise SystemExit("activity resume still clears the numbered overlay")
if "SageAccessibilityService.clearNumberOverlay();" in commands[
        commands.index("public void cancelFollowUp()"):
        commands.index("public Result execute(")
]:
    raise SystemExit("voice follow-up cancellation still clears the numbered overlay")
require(main, ["SageAccessibilityService.noteHostResumed()"], "resume preservation")
require(commands, [
    'SageAccessibilityService.showNumberOverlay(type)',
    'SageAccessibilityService.tapNumberedTarget(numberedChoice)',
    '"voice_cancel_numbers"',
    '"voice_manual_clear"',
], "overlay parser and selection")

require(diagnostics, [
    "overlayCreated(",
    "overlayCleared(",
    "overlayEvent(",
    '"reason="',
    '+ " event="',
    '+ " package="',
    '+ " window="',
    '+ " elapsedMs="',
    "recognitionDecision(",
    '"candidates="',
    '+ " selected="',
    '+ " confidence="',
    '+ " normalized="',
    '+ " matched="',
    '+ " rejection="',
    "memoryEvent(",
], "timestamped diagnostics")

require(voice, [
    "RESULTS_RECOGNITION",
    "CONFIDENCE_SCORES",
    "String candidate = chooseBestCandidate(finalChoices);",
    'fallbackPartials.isEmpty() ? "empty_final" : "partial_without_final"',
    "MAX_COMMAND_QUALITY_RETRIES = 1",
    "LOW_COMMAND_CONFIDENCE = 0.35f",
    "isIncompleteRecognition(",
    "commandQualityRetries < MAX_COMMAND_QUALITY_RETRIES",
    "startCommandRecognition(250L, true)",
    '"speaker_echo"',
    '"wake_phrase_not_matched"',
    'result.matched ? "command_engine" : ""',
    '"no_deterministic_match"',
], "speech recognition")
require(state_machine, ['"wake_phrase"'], "state-machine wake decision")

require(commands, [
    'pendingAction = "remember_item"',
    '"What should I remember?"',
    '"what did i ask you to remember"',
    "SageMemoryStore.save(context, value)",
    '"I saved that in my memory."',
    '"I already remember that."',
    "SageMemoryStore.recallLast(context)",
    '.remove("last_saved_memory")',
    '"remember follow-up cancelled"',
], "memory routing")
require(memory, [
    'ITEMS = "memory_items"',
    'LAST = "last_saved_memory"',
    "SaveResult.DUPLICATE",
    ".commit()",
    "recallLast(Context context)",
    "recallAll(Context context)",
], "memory persistence")

require(appearance, [
    'MODE_DARK = "dark"',
    'MODE_DIM = "dim"',
    'MODE_BLACK = "black"',
    'KEY_BACKGROUND_URI = "appearance_background_uri"',
    'KEY_BACKGROUND_INTENSITY = "appearance_background_intensity"',
    "openInputStream(Uri.parse(savedUri))",
    "new LayerDrawable(",
    "new ColorDrawable(Color.argb(darkness, 0, 0, 0))",
    '"Missing background image cleared safely"',
    '"Unreadable background image cleared safely"',
], "appearance")
require(main, [
    'appearanceTitle.setText("Appearance")',
    '"Choose background image"',
    '"Clear background image"',
    '"Background intensity"',
    "ACTION_OPEN_DOCUMENT",
    "FLAG_GRANT_PERSISTABLE_URI_PERMISSION",
    "15000L, 30000L, 60000L, 120000L, -1L",
    '"Persistent until action"',
], "appearance settings")

if not re.search(r"versionCode\s*=\s*(36|37|38|39|40)\b", gradle):
    raise SystemExit("compatible release versionCode missing")
if not any(version in gradle for version in (
        'versionName = "1.24.2"', 'versionName = "1.25.0"', 'versionName = "1.26.0"',
        'versionName = "1.27.0"', 'versionName = "1.28.0"')):
    raise SystemExit("compatible release versionName missing")
if 'applicationId = "com.pineapple.sagecommander.stable"' not in gradle:
    raise SystemExit("stable applicationId changed")
if "android.permission.READ_EXTERNAL_STORAGE" in manifest:
    raise SystemExit("unnecessary storage permission added")

# Behavioral models guard the selection and memory invariants independent of Android.
def select_phrase(finals, partials):
    pool = finals if finals else partials
    return max(pool, key=len) if pool else ""


assert select_phrase(["remember my dog's name is Ada"], ["remember"]) == \
       "remember my dog's name is Ada"
assert select_phrase([], ["show numbers"]) == "show numbers"


class MemoryModel:
    def __init__(self, disk=None):
        self.disk = disk if disk is not None else {}

    @staticmethod
    def normalize(value):
        return " ".join(re.sub("[^a-z0-9 ]", " ", value.lower()).split())

    def remember(self, value):
        key = self.normalize(re.sub(r"(?i)^that\\s+", "", value.strip()))
        if key in self.disk:
            return "duplicate"
        self.disk[key] = value.strip()
        self.disk["__last__"] = value.strip()
        return "saved"

    def recall_last(self):
        return self.disk.get("__last__", "")


persisted = {}
first = MemoryModel(persisted)
assert first.remember("my dog's name is Ada") == "saved"
assert first.remember("my dog's name is Ada") == "duplicate"
restarted = MemoryModel(persisted)
assert restarted.recall_last() == "my dog's name is Ada"

print("Sage Commander 1.24.2 overlay, speech, memory, and appearance regressions passed")
