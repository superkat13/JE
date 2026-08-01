from pathlib import Path
import re
import sys


ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
SERVICE = (
    ROOT
    / "app/src/main/java/com/pineapple/sage/SageAccessibilityService.java"
)
BUILD_GRADLE = ROOT / "app/build.gradle.kts"

old = '''        int eventWindowId = event.getWindowId();
        boolean differentPackage = !numberedPackageName.isEmpty()
                && !eventPackage.isEmpty()
                && !numberedPackageName.equals(eventPackage);
        boolean differentWindow = !differentPackage
                && numberedWindowId >= 0
                && eventWindowId >= 0
                && numberedWindowId != eventWindowId;
        int type = event.getEventType();
        boolean deliberateMovement = type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED;
        // YouTube emits window/content churn while thumbnails finish loading. Keep the
        // numbered choices through that harmless churn; clear only after real navigation.
        if (differentPackage || differentWindow || deliberateMovement) {
            clearNumberOverlayInternal();
        }
'''

new = '''        boolean differentPackage = !numberedPackageName.isEmpty()
                && !eventPackage.isEmpty()
                && !numberedPackageName.equals(eventPackage);
        int type = event.getEventType();
        boolean deliberateMovement = type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED;
        // Accessibility overlays create their own windows. Android may report those
        // window IDs with the underlying app's package, so a window-ID difference alone
        // is not proof that the user navigated. Keep the markers through that churn.
        // A package change, click, or scroll still clears them, and selection revalidates
        // the saved package/window before performing any action.
        if (differentPackage || deliberateMovement) {
            clearNumberOverlayInternal();
        }
'''

text = SERVICE.read_text()
count = text.count(old)
if count != 1:
    raise SystemExit(
        f"number overlay window-churn fix: expected exactly one match, found {count}"
    )
SERVICE.write_text(text.replace(old, new, 1))

build_text = BUILD_GRADLE.read_text()
build_text, code_count = re.subn(
    r'versionCode\s*(?:=\s*)?\d+',
    'versionCode = 35',
    build_text,
    count=1,
)
build_text, name_count = re.subn(
    r'versionName\s*(?:=\s*)?["\'][^"\']+["\']',
    'versionName = "1.24.1"',
    build_text,
    count=1,
)
if code_count != 1 or name_count != 1:
    raise SystemExit(
        f"1.24.1 identity: expected one code and one name, got {code_count}/{name_count}"
    )
BUILD_GRADLE.write_text(build_text)

for xml in (ROOT / "app/src/main").rglob("*.xml"):
    xml_text = xml.read_text()
    updated = re.sub(
        r'Sage Commander(?:\s+\d+\.\d+(?:\.\d+)?)?',
        'Sage Commander 1.24.1',
        xml_text,
    )
    if updated != xml_text:
        xml.write_text(updated)

print("Applied Sage 1.24.1 numbered-overlay fix and release identity")
