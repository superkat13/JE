from pathlib import Path
import sys

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
java = ROOT / "app/src/main/java/com/pineapple/sage"

main = (java / "MainActivity.java").read_text()
diagnostics = (java / "SageDiagnostics.java").read_text()

checks = {
    "continuity heading": "Sage Diagnostics and Continuity" in main,
    "continuity invariant": "Continuity identity: one Sage, same package and saved app data" in diagnostics,
    "wake-profile export": "SageWakeProfileStore.summary(context)" in diagnostics,
}
failed = [name for name, passed in checks.items() if not passed]
if failed:
    raise SystemExit("Second-pass continuity checks failed: " + ", ".join(failed))

print("Independent Sage 1.24 second-pass checks passed")
