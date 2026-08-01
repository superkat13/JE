#!/usr/bin/env python3
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
REPO = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"

files = {
    "gradle": ROOT / "app/build.gradle.kts",
    "coordinator": JAVA / "SageIntentCoordinator.java",
    "voice": JAVA / "SageVoiceService.java",
    "brain": JAVA / "SageBrainManager.java",
    "memory": JAVA / "SageMemoryStore.java",
    "commands": JAVA / "SageCommandEngine.java",
    "creative": JAVA / "SageCreativeEngine.java",
}
source = {name: path.read_text() for name, path in files.items()}

specialists = (
    "COORDINATOR", "PLANNER", "ENGINEER", "RESEARCHER", "QA", "MEMORY_MANAGER",
    "RECOVERY_HANDLER", "MEDIA_NAVIGATOR", "PACKAGE_INSPECTOR", "MODEL_ROUTER",
    "FORGE_MANAGER",
)
checks = {
    "stable package": 'applicationId = "com.pineapple.sagecommander.stable"' in source["gradle"],
    "version 1.28 code 40": 'versionName = "1.28.0"' in source["gradle"] and "versionCode = 40" in source["gradle"],
    "all internal specialists": all(value in source["coordinator"] for value in specialists),
    "goal planning": all(value in source["coordinator"] for value in (
        "executionRequest", "goal", "intent", "tool", "routeHint", "verification")),
    "safe follow-up": "safeRepeat" in source["coordinator"] and '"do that again"' in source["coordinator"],
    "correction awareness": "correction" in source["coordinator"] and '"no i meant "' in source["coordinator"],
    "outcome verification": "recordOutcome" in source["coordinator"] and "last_verified" in source["coordinator"],
    "visible goal phrase": "I understand what you want to accomplish:" in source["coordinator"] and "I understand what you want to accomplish:" in source["voice"],
    "route integration": "SageIntentCoordinator.understand" in source["voice"] and "SageIntentCoordinator.recordOutcome" in source["voice"],
    "Brain receives goal context": "SageIntentCoordinator.promptContext" in source["brain"],
    "GGUF verification": all(value in source["brain"] for value in (
        "modelFileProblem", "truncated or too small", "not a valid GGUF file", "file size changed")),
    "Memory 2.0 encoding": 'VERSION = "v2"' in source["memory"] and "confidence" in source["memory"] and "source" in source["memory"],
    "legacy memory migration": "legacy_owner_memory" in source["memory"] and "parts.length >= 3" in source["memory"],
    "project memory": 'startsWith("this project is ")' in source["memory"] and 'lower.startsWith("this project is ")' in source["commands"],
    "forget this": '"forget this"' in source["commands"] and "recallLast(context)" in source["commands"],
    "creative commands": all(value in source["commands"] for value in (
        '"surprise me"', '"cure boredom"', '"video idea"', "SageCreativeEngine.respond")),
    "rotating creative engine": all(value in source["creative"] for value in (
        "surprise_rotation", "Creative Planner", "boredom cure", "route=command engine")),
    "creative route visibility": 'case "creative": return "Creative Studio"' in source["coordinator"]
        and 'if (intent.equals("creative")) return "command engine"' in source["coordinator"],
}

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(("PASS" if passed else "FAIL") + ": " + name)
if failed:
    raise SystemExit("Sage 1.27 intelligence failures: " + "; ".join(failed))

java = shutil.which("java") or "/usr/lib/jvm/java-17-openjdk-amd64/bin/java"
javac = shutil.which("javac")
compiler = [javac] if javac else [java, "-m", "jdk.compiler/com.sun.tools.javac.Main"]
with tempfile.TemporaryDirectory(prefix="sage-intelligence-") as target:
    subprocess.run(compiler + ["-d", target,
        str(REPO / "sage_tests/java_stubs/android/content/Context.java"),
        str(REPO / "sage_tests/java_stubs/android/content/SharedPreferences.java"),
        str(REPO / "sage_tests/java_stubs/com/pineapple/sage/SageDiagnostics.java"),
        str(JAVA / "SageCapabilityRegistry.java"),
        str(files["coordinator"]), str(files["memory"]), str(files["creative"]),
        str(REPO / "sage_tests/SageIntelligenceMemoryHarness.java")], check=True)
    subprocess.run([java, "-cp", target,
        "com.pineapple.sage.SageIntelligenceMemoryHarness"], check=True)

print(f"All {len(checks)} Sage 1.27 intelligence source checks passed.")
