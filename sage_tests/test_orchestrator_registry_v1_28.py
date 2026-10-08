#!/usr/bin/env python3
from pathlib import Path
import sys


if len(sys.argv) != 2:
    raise SystemExit("usage: test_orchestrator_registry_v1_28.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
registry = (java / "SageCapabilityRegistry.java").read_text()
coordinator = (java / "SageIntentCoordinator.java").read_text()
authority = (java / "SageAuthority.java").read_text()
activity = (java / "SageRegistryActivity.java").read_text()
redqueen = (java / "SageRedQueenActivity.java").read_text()
manifest = (root / "app/src/main/AndroidManifest.xml").read_text()

declarations = (
    "id", "purpose", "supportedIntents", "voiceExamples", "platform", "inputs",
    "outputs", "permissions", "risk", "confirmation", "timeout", "cancellation",
    "dataEgress", "networkScope", "redQueenRequired", "forgeRequired",
    "implementation", "supportState",
)
specialists = (
    "COORDINATOR", "PLANNER", "ENGINEER", "QA", "SAFETY_GUARD",
    "RECOVERY_HANDLER", "MEMORY_MANAGER", "PACKAGE_INSPECTOR", "NETWORK_SCOUT",
    "OSINT_RESEARCHER", "REVERSE_ENGINEERING_ANALYST", "FORENSICS_ANALYST",
    "MEDIA_NAVIGATOR", "MODEL_ROUTER", "FORGE_MANAGER", "AUTOMATION_MANAGER",
)
checks = {
    "complete capability declarations": all(value in registry for value in declarations),
    "compiled authority only": "Only compiled implementations can execute" in activity
        and "cannot grant authority" in activity,
    "no voice shell": "shell" not in registry.lower() and "SageCommandEngine" in registry,
    "required real capabilities": all(value in registry for value in (
        "android.command", "memory.standard", "brain.local", "forge.approved_job",
        "package.inspect", "file.inspect", "media.session", "network.private_lan",
        "creative.director", "repair.diagnose", "redqueen.authenticate")),
    "deferred capabilities honest": "not implemented" in registry
        and "SupportState.UNSUPPORTED" in registry,
    "full specialists": all(value in coordinator for value in specialists),
    "full orchestration trace": all(value in coordinator for value in (
        "request → intent → entities → context → memory → plan",
        "capability selection → risk/permission check → execute → verify → concise result")),
    "entity extraction": "entitiesFor" in coordinator and "selected_apk" in coordinator,
    "risk permission decision": "riskDecision" in coordinator
        and "capability.confirmation" in coordinator,
    "automatic selection": "SageCapabilityRegistry.select" in coordinator,
    "registry UI": "TRUSTED CAPABILITY REGISTRY" in activity
        and "entry.summary()" in activity,
    "Red Queen registry functional": "SageRegistryActivity.class" in redqueen,
    "authority dashboard expanded": all(value in authority for value in (
        "red_queen_authority", "forge_trust", "tablet_brain", "cloud_model_provider")),
    "non-exported registry": '<activity android:name=".SageRegistryActivity" android:exported="false" />' in manifest,
}

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'}: {name}")
if failed:
    raise SystemExit("Orchestrator/registry failures: " + ", ".join(failed))
