#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
manager = (java / "SageAutomationManager.java").read_text()
activity = (java / "SageAutomationActivity.java").read_text()
receiver = (java / "SageDownloadAutomationReceiver.java").read_text()
forge = (java / "SageForgeActivity.java").read_text()
network = (java / "SageNetworkStore.java").read_text()
manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
registry = (java / "SageCapabilityRegistry.java").read_text()
command = (java / "SageCommandEngine.java").read_text()

checks = {
    "exact compiled routines": all(v in manager for v in ("download.sage_apk_signer", "forge.job_finished", "network.new_device")),
    "permanent identity digest": "99e0a7c655cdefb3bb4ac85e5961d19358ee0ffdb3dce9b3a145f9cbcda78d35" in manager,
    "complete declarations": all(v in manager for v in ("trigger", "conditions", "steps", "tools", "target", "permission", "risk", "confirmation", "timeout", "cancel", "logs", "rollback")),
    "compiled authority only": "COMPILED" in manager and "find(id)==null" in manager,
    "no arbitrary shell": all(v not in (manager+activity+receiver).lower() for v in ("runtime.exec", "processbuilder", "/bin/sh", "arbitrary shell")),
    "owner enable confirmation": "SageConfirmation.require" in activity and "setEnabled" in activity,
    "download inspection": "ACTION_DOWNLOAD_COMPLETE" in receiver and "SagePackageInspector.inspect" in receiver,
    "forge terminal event": "EVENT_FORGE" in forge and "status\",\"completed" in forge,
    "network new device event": "EVENT_NETWORK" in network and "added.removeAll" in network,
    "notification permission": "POST_NOTIFICATIONS" in manifest and "requestPermissions" in activity,
    "receiver non-exported": 'SageDownloadAutomationReceiver" android:exported="false"' in manifest,
    "audit and disable": "activity" in manager and "DISABLED by owner" in manager,
    "automation active registry": "automation.compiled" in registry and "SupportState.ACTIVE" in registry,
    "voice route": "open automation desk" in command and "SageAutomationActivity.class" in command,
    "Red Queen functional": 'functional(root, "Automation"' in (java / "SageRedQueenActivity.java").read_text(),
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items(): print(("PASS: " if passed else "FAIL: ") + name)
if failed: raise SystemExit("automation checks failed: " + ", ".join(failed))
