from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1))


main = JAVA / "MainActivity.java"
diagnostics = JAVA / "SageDiagnostics.java"
build_gradle = ROOT / "app/build.gradle.kts"

replace_once(
    main,
    'diagnosticsTitle.setText("Sage Diagnostics");',
    'diagnosticsTitle.setText("Sage Diagnostics and Continuity");',
    "diagnostics continuity heading",
)

replace_once(
    main,
    'Button copyDiagnostics = makeButton("Copy diagnostic report");',
    'Button copyDiagnostics = makeButton("Copy diagnostics + continuity report");',
    "continuity copy button",
)

replace_once(
    main,
    'Button shareDiagnostics = makeButton("Share diagnostic report");',
    'Button shareDiagnostics = makeButton("Share diagnostics + continuity report");',
    "continuity share button",
)

replace_once(
    diagnostics,
    '''                + "Conversation mode: "
                + (appPreferences.getBoolean("conversation_mode_enabled", true) ? "on" : "off")
                + "\\n\\nCurrent snapshot\\n"''',
    '''                + "Conversation mode: "
                + (appPreferences.getBoolean("conversation_mode_enabled", true) ? "on" : "off")
                + "\\nContinuity identity: one Sage, same package and saved app data"
                + "\\n\\nCustom wake profiles\\n"
                + SageWakeProfileStore.summary(context)
                + "\\n\\nCurrent snapshot\\n"''',
    "continuity report body",
)

text = build_gradle.read_text()
text, code_count = re.subn(
    r'versionCode\s*(?:=\s*)?\d+',
    'versionCode = 34',
    text,
    count=1,
)
text, name_count = re.subn(
    r'versionName\s*(?:=\s*)?["\'][^"\']+["\']',
    'versionName = "1.24"',
    text,
    count=1,
)
if code_count != 1 or name_count != 1:
    raise SystemExit(
        f"version identity: expected one code and one name, got {code_count}/{name_count}"
    )
build_gradle.write_text(text)

for xml in (ROOT / "app/src/main").rglob("*.xml"):
    text = xml.read_text()
    updated = re.sub(
        r'Sage Commander(?:\s+\d+\.\d+)?',
        'Sage Commander 1.24',
        text,
    )
    if updated != text:
        xml.write_text(updated)

print("Applied Sage 1.24 continuity report and permanent update identity")
