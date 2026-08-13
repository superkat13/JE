#!/usr/bin/env python3
from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_red_queen_consolidation_v1_29.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
redqueen = (java / "SageRedQueenActivity.java").read_text(encoding="utf-8")
workbench = (java / "SageWorkbenchActivity.java").read_text(encoding="utf-8")
toolbelt = (java / "SageToolbeltActivity.java").read_text(encoding="utf-8")
main = (java / "MainActivity.java").read_text(encoding="utf-8")
command = (java / "SageCommandEngine.java").read_text(encoding="utf-8")
session = (java / "SageRedQueenSession.java").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")

workspace_match = re.search(r'private void showWorkspace\(\) \{(.*?)\n    private void functional\(', redqueen, re.S)
workspace = workspace_match.group(1) if workspace_match else ""

checks = {
    "stable package": 'applicationId = "com.pineapple.sagecommander.stable"' in gradle,
    "Red Queen no longer advertised in Workbench": 'card(r,"Red Queen Mode"' not in workbench,
    "no manual button fallback": "Open Sage Workbench and tap Red Queen Mode" not in command,
    "spoken retry fallback": "Try the phrase again from Sage" in command,
    "workspace replacement found": bool(workspace_match),
    "workspace has exclusive Sage Autonomy": 'functional(root, "Sage Autonomy"' in workspace,
    "workspace has exclusive shell authority": 'functional(root, "Shell Authority"' in workspace,
    "workspace has forensic console": 'functional(root, "Forensic Console"' in workspace,
    "workspace has mature research": 'functional(root, "Mature Research"' in workspace,
    "workspace removes duplicate Forge hub": 'functional(root, "Forge"' not in workspace,
    "workspace removes duplicate Evidence Lab hub": 'functional(root, "Evidence Lab"' not in workspace,
    "workspace removes duplicate Network Lab hub": 'functional(root, "Network Lab"' not in workspace,
    "workspace removes duplicate Black Box hub": 'functional(root, "Black Box"' not in workspace,
    "workspace removes duplicate Device Authority hub": 'functional(root, "Device Authority"' not in workspace,
    "workspace removes duplicate Boot Evidence": 'functional(root, "Boot Evidence"' not in workspace,
    "workspace removes duplicate Dell Evidence Import": 'functional(root, "Dell Evidence Import"' not in workspace,
    "workspace hides deferred placeholders": "deferred(root" not in workspace,
    "workspace removes duplicate generic Authority card": 'functional(root, "Authority"' not in workspace,
    "workspace removes duplicate Operations card": 'functional(root, "Operations"' not in workspace,
    "engineering state label removed": "— FUNCTIONAL" not in redqueen,
    "private vault remains": "SageRedQueenVault.saveRecord" in workspace,
    "private audit remains": "SageRedQueenVault.auditReport" in workspace,
    "explicit lock remains": 'secure("explicit_exit")' in workspace,
    "autonomy not exposed in normal Workbench": "SageAutonomyActivity.class" not in workbench,
    "autonomy not exposed in normal Toolbelt": "SageAutonomyActivity.class" not in toolbelt,
    "autonomy not exposed on normal home": "SageAutonomyActivity.class" not in main,
    "device lock boundary remains": "isDeviceLocked()" in session,
    "rate limit remains": "canAttempt" in session and "recordFailure" in session,
    "session remains process local": "static volatile long unlockedUntilMs" in session,
}

for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " | " + name)
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("Red Queen autonomy consolidation regression failed: " + ", ".join(failed))
print("Red Queen autonomy consolidation regression passed")
