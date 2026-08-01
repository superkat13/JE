from pathlib import Path
import re
import sys


ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
service = (
    ROOT
    / "app/src/main/java/com/pineapple/sage/SageAccessibilityService.java"
).read_text()
command_engine = (
    ROOT
    / "app/src/main/java/com/pineapple/sage/SageCommandEngine.java"
).read_text()
voice_service = (
    ROOT
    / "app/src/main/java/com/pineapple/sage/SageVoiceService.java"
).read_text()
main_activity = (
    ROOT
    / "app/src/main/java/com/pineapple/sage/MainActivity.java"
).read_text()
build_gradle = (ROOT / "app/build.gradle.kts").read_text()

if any(version in build_gradle for version in (
        'versionName = "1.24.2"', 'versionName = "1.25.0"', 'versionName = "1.26.0"',
        'versionName = "1.27.0"')):
    if "SageAccessibilityService.clearNumberOverlay();" in main_activity:
        raise SystemExit("1.24.2 activity lifecycle regressed to clearing overlays")
    cancel_body = command_engine[
        command_engine.index("public void cancelFollowUp()"):
        command_engine.index("public Result execute(")
    ]
    if "SageAccessibilityService.clearNumberOverlay();" in cancel_body:
        raise SystemExit("1.24.2 follow-up cancellation regressed to clearing overlays")
    if '"ignored_sage_overlay_event"' not in service:
        raise SystemExit("1.24.2 own-window suppression is missing")
    print("Sage 1.24.1 numbered-overlay compatibility checks passed on 1.24.2")
    raise SystemExit(0)

required = (
    "if (differentPackage || deliberateMovement)",
    "Accessibility overlays create their own windows.",
    "service.isOverlayForRoot(root)",
    "service.clearNumberOverlayInternal();",
    "mainHandler.postDelayed(clearNumberOverlayRunnable, NUMBER_OVERLAY_TIMEOUT_MS)",
)
for marker in required:
    if marker not in service:
        raise SystemExit(f"missing numbered-overlay safety marker: {marker}")

for forbidden in (
    "if (differentPackage || differentWindow || deliberateMovement)",
    "boolean differentWindow =",
):
    if forbidden in service:
        raise SystemExit(f"stale window-churn invalidation remains: {forbidden}")

execution_path = {
    "voice command reaches deterministic parser":
        "SageCommandEngine.Result result = commandEngine.execute(cleaned);"
        in voice_service,
    "parser recognizes generic number request":
        '"show numbers", "number the screen"' in command_engine,
    "parser requests overlay creation":
        "SageAccessibilityService.showNumberOverlay(type)" in command_engine,
    "service obtains active accessibility root":
        "AccessibilityNodeInfo root = service.getRootInActiveWindow();" in service,
    "service collects interactive nodes":
        "findInteractiveTargets(" in service,
    "service creates accessibility marker windows":
        "WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY" in service
        and "windowManager.addView(marker, params);" in service,
    "spoken number reaches selection":
        "SageAccessibilityService.tapNumberedTarget(numberedChoice)" in command_engine,
    "selection revalidates the current root":
        "service.isOverlayForRoot(root)" in service
        and "resolveCurrentTarget(" in service,
    "successful selection cleans up":
        "if (opened) {\n            service.clearNumberOverlayInternal();" in service,
}
for label, present in execution_path.items():
    if not present:
        raise SystemExit(f"broken numbered-overlay execution path: {label}")

failure_and_cleanup_paths = {
    "accessibility service unavailable": "if (service == null) {\n            return 0;",
    "active root unavailable": "if (root == null) {\n            return 0;",
    "no candidates or window manager": (
        "if (windowManager == null || candidates == null || candidates.isEmpty())"
    ),
    "empty target bounds skipped": "if (bounds.isEmpty()) {\n                continue;",
    "overlay add failure contained": "windowManager.addView(marker, params);",
    "prior overlay cleared before recreation": (
        "    ) {\n        clearNumberOverlayInternal();\n"
        "        if (windowManager == null"
    ),
    "different app clears": "if (differentPackage || deliberateMovement)",
    "click and scroll clear": (
        "type == AccessibilityEvent.TYPE_VIEW_SCROLLED\n"
        "                || type == AccessibilityEvent.TYPE_VIEW_CLICKED"
    ),
    "service unbind clears": (
        "public boolean onUnbind(android.content.Intent intent) {\n"
        "        clearNumberOverlayInternal();"
    ),
    "service interruption clears": (
        "public void onInterrupt() {\n        clearNumberOverlayInternal();"
    ),
    "timeout scheduled": (
        "mainHandler.postDelayed(clearNumberOverlayRunnable, "
        "NUMBER_OVERLAY_TIMEOUT_MS)"
    ),
    "timeout is two minutes": "NUMBER_OVERLAY_TIMEOUT_MS = 120000L",
    "global action clears": "private static boolean performGlobalActionAndClear",
    "scroll clears": (
        "public static boolean scroll(boolean down)"
        in service and "service.clearNumberOverlayInternal();" in service
    ),
    "manual command clears": (
        '"hide numbers", "clear numbers", "remove numbers"' in command_engine
    ),
    "follow-up cancellation clears": (
        "public void cancelFollowUp()" in command_engine
        and "SageAccessibilityService.clearNumberOverlay();" in command_engine
    ),
    "activity manual clear": '"Clear screen numbers"' in main_activity,
    "activity resume clears": (
        "protected void onResume()" in main_activity
        and "SageAccessibilityService.clearNumberOverlay();" in main_activity
    ),
    "stale root clears": (
        "if (root == null || !service.isOverlayForRoot(root))" in service
    ),
}
for label, marker in failure_and_cleanup_paths.items():
    present = marker if isinstance(marker, bool) else marker in service
    if label in {
        "manual command clears",
        "follow-up cancellation clears",
    }:
        present = marker
    if label.startswith("activity "):
        present = marker
    if not present:
        raise SystemExit(f"unverified overlay failure/cleanup path: {label}")

if not re.search(r"versionCode\s*=\s*35\b", build_gradle):
    raise SystemExit("Sage 1.24.1 versionCode 35 is missing")
if not re.search(r'versionName\s*=\s*"1\.24\.1"', build_gradle):
    raise SystemExit("Sage 1.24.1 versionName is missing")

print("Sage 1.24.1 numbered-overlay lifecycle regression checks passed")
