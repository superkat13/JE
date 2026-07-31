from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"

main = (JAVA / "MainActivity.java").read_text()
diagnostics = (JAVA / "SageDiagnostics.java").read_text()
gradle = (ROOT / "app/build.gradle.kts").read_text()

required_main = (
    'diagnosticsTitle.setText("Sage Diagnostics and Continuity");',
    'makeButton("Copy diagnostics + continuity report")',
    'makeButton("Share diagnostics + continuity report")',
)
for marker in required_main:
    if marker not in main:
        raise SystemExit(f"Missing Sage 1.24 interface marker: {marker}")

required_report = (
    'Continuity identity: one Sage, same package and saved app data',
    'Custom wake profiles',
    'SageWakeProfileStore.summary(context)',
)
for marker in required_report:
    if marker not in diagnostics:
        raise SystemExit(f"Missing Sage 1.24 continuity report marker: {marker}")

if not re.search(r'versionCode\s*=\s*34\b', gradle):
    raise SystemExit("Sage 1.24 versionCode 34 is missing")
if not re.search(r'versionName\s*=\s*["\']1\.24["\']', gradle):
    raise SystemExit("Sage 1.24 versionName is missing")

if diagnostics.count('SageWakeProfileStore.summary(context)') != 1:
    raise SystemExit("Continuity wake-profile summary must be included exactly once")

print("Sage 1.24 continuity report source checks passed")
