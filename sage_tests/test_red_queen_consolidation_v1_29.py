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
    "workspace has Forge hub": 'functional(root, "Forge"' in workspace,
    "workspace has Evidence Lab hub": 'functional(root, "Evidence Lab"' in workspace,
    "workspace has Network Lab hub": 'functional(root, "Network Lab"' in workspace,
    "workspace has Black Box hub": 'functional(root, "Black Box"' in workspace,
    "workspace has Control Room hub": 'functional(root, "Control Room"' in workspace,
    "workspace hides deferred placeholders": "deferred(root" not in workspace,
    "workspace removes duplicate generic Authority card": 'functional(root, "Authority"' not in workspace,
    "workspace removes duplicate Operations card": 'functional(root, "Operations"' not in workspace,
    "engineering state label removed": "— FUNCTIONAL" not in redqueen,
    "private vault remains": "SageRedQueenVault.saveRecord" in workspace,
    "private audit remains": "SageRedQueenVault.auditReport" in workspace,
    "explicit lock remains": 'secure("explicit_exit")' in workspace,
    "device lock boundary remains": "isDeviceLocked()" in session,
    "rate limit remains": "canAttempt" in session and "recordFailure" in session,
    "session remains process local": "static volatile long unlockedUntilMs" in session,
}

for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " | " + name)
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("Red Queen consolidation regression failed: " + ", ".join(failed))
print("Red Queen consolidation regression passed")
