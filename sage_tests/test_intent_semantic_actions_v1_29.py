#!/usr/bin/env python3
"""Executable/source regressions for the Sage 1.29 intent and semantic action slice."""

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build_129")
REPO = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"
paths = {
    "gradle": ROOT / "app/build.gradle.kts",
    "manifest": ROOT / "app/src/main/AndroidManifest.xml",
    "policy": JAVA / "SageIntentActionPolicy.java",
    "semantic": JAVA / "SageSemanticTargetPolicy.java",
    "commands": JAVA / "SageCommandEngine.java",
    "accessibility": JAVA / "SageAccessibilityService.java",
    "apps": JAVA / "SageTrustedAppRegistry.java",
    "context": JAVA / "SageActionContextStore.java",
    "coordinator": JAVA / "SageIntentCoordinator.java",
    "capabilities": JAVA / "SageCapabilityRegistry.java",
    "files": JAVA / "SageFileLabActivity.java",
}
source = {name: path.read_text(encoding="utf-8") for name, path in paths.items()}

checks = {
    "identity preserved": all(x in source["gradle"] for x in (
        'applicationId = "com.pineapple.sagecommander.stable"',
        'versionName = "1.29.0"', "versionCode = 41", "sagePermanentSigning")),
    "complete trace": "request → intent → entities → context → remembered preference →" in source["policy"]
        and "permission/risk check → execute → verify → concise result" in source["policy"],
    "compiled entry point": "executeSemanticSlice(raw, lower)" in source["commands"],
    "youtube direct deep link": 'Uri.parse("vnd.youtube://")' in source["commands"]
        and '.setPackage("com.google.android.youtube")' in source["commands"],
    "downloads documents intent": "Intent.ACTION_OPEN_DOCUMENT" in source["commands"]
        and "Intent.CATEGORY_OPENABLE" in source["commands"],
    "adobe installed resolution": "installedAdobeApps" in source["apps"]
        and "getApplicationInfo" in source["apps"] and "getLaunchIntentForPackage" in source["apps"],
    "adobe honest unavailable": "not installed or has no launchable activity" in source["commands"],
    "approved adobe edit": "Intent.ACTION_EDIT" in source["apps"]
        and "FLAG_GRANT_READ_URI_PERMISSION" in source["apps"]
        and '"content".equalsIgnoreCase(contentUri.getScheme())' in source["apps"],
    "no unsupported adobe api claim": "Adobe API" not in source["apps"] + source["commands"],
    "selected file context": "rememberSelectedContent" in source["files"]
        and "selectedContent" in source["commands"],
    "adobe preference": "preferAdobeForVideo" in source["context"]
        and "I prefer Adobe whenever I edit videos" in source["commands"],
    "trusted adobe registry": '"trusted.adobe"' in source["capabilities"]
        and "temporary content URI grant only" in source["capabilities"],
    "media direct first": 'mediaSessionBridge.control("play")' in source["commands"]
        and 'SageAccessibilityService.tapText("play")' in source["commands"]
        and source["commands"].index('mediaSessionBridge.control("play")')
        < source["commands"].index('SageAccessibilityService.tapText("play")'),
    "semantic text description id role": all(x in source["accessibility"] for x in (
        "node.getText()", "node.getContentDescription()", "getViewIdResourceName()", "getClassName()")),
    "clickable ancestor": "nearestClickable" in source["accessibility"],
    "ordinal semantic route": 'tapIndexedItem(decision.ordinal, "generic")' in source["commands"]
        and "selectOrdinal" in source["semantic"],
    "immediate revalidation": "refreshedRoot" in source["accessibility"]
        and "resolveCurrentTarget" in source["accessibility"]
        and "semanticIdentityMatches" in source["accessibility"],
    "hidden disabled blocked": "!node.isVisibleToUser() || !node.isEnabled()" in source["accessibility"],
    "mismatch blocked": "SageSemanticTargetPolicy.revalidate" in source["accessibility"]
        and "!savedText.equals(currentText)" in source["semantic"],
    "strong route order": source["commands"].index("showTargetDrawer(type)")
        < source["commands"].index("showGrid()", source["commands"].index("showTargetDrawer(type)"))
        < source["commands"].index("showNumberOverlay(type)", source["commands"].index("showGrid()", source["commands"].index("showTargetDrawer(type)"))),
    "numbers explicit policy": 'value.equals("show numbers")' in source["policy"]
        and "Action.SHOW_NUMBERS" in source["policy"],
    "no automatic show-numbers answer": 'new result("show numbers")' not in source["commands"].lower(),
    "one destructive clarification": "isAmbiguousDestructive" in source["policy"]
        and "Which exact item should I act on?" in source["policy"],
    "radio context": "FIND_RADIO_DIAGRAM" in source["policy"]
        and "SageActionContextStore.subject(context)" in source["commands"],
    "video context": "FIND_VIDEO" in source["policy"]
        and "searchYouTube(SageActionContextStore.subject(context))" in source["commands"],
    "context captured": "rememberSubject" in source["coordinator"]
        and 'getString("last_query"' in source["context"],
    "functional drawer": "TYPE_ACCESSIBILITY_OVERLAY" in source["accessibility"]
        and "showTargetDrawerInternal" in source["accessibility"],
    "functional grid": "chooseGridRegion" in source["accessibility"]
        and "refineGrid" in source["accessibility"],
    "stable cancellable overlay": "stableOverlayContainer" in source["accessibility"]
        and "scheduleStableOverlayTimeout" in source["accessibility"]
        and "clearNumberOverlayInternal" in source["accessibility"],
    "no voice shell": all(x not in source["commands"] for x in (
        "Runtime.getRuntime().exec", "ProcessBuilder", "su -c", "/bin/sh")),
}

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(("PASS" if passed else "FAIL") + ": " + name)
if failed:
    raise SystemExit("Sage 1.29 intent/semantic failures: " + "; ".join(failed))

java, javac = shutil.which("java"), shutil.which("javac")
if not java or not javac:
    raise SystemExit("JDK required for executable semantic regressions")
with tempfile.TemporaryDirectory(prefix="sage-intent-semantic-129-") as target:
    subprocess.run([javac, "-d", target, str(paths["policy"]), str(paths["semantic"]),
                    str(REPO / "sage_tests/SageIntentSemanticHarness.java")], check=True)
    subprocess.run([java, "-cp", target,
                    "com.pineapple.sage.SageIntentSemanticHarness"], check=True)

print(f"All {len(checks)} Sage 1.29 intent/semantic source checks passed.")
