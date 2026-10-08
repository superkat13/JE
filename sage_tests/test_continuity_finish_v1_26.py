#!/usr/bin/env python3
"""Source-level regressions for the continuity-safe 1.26 finish patch."""

from pathlib import Path
import sys


ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"

files = {
    "brain": JAVA / "SageBrainManager.java",
    "health": JAVA / "SageBrainHealth.java",
    "test": JAVA / "SageBrainTestActivity.java",
    "native": ROOT / "app/src/main/cpp/sage_brain.cpp",
    "voice": JAVA / "SageVoiceService.java",
    "memory": JAVA / "SageMemoryStore.java",
    "commands": JAVA / "SageCommandEngine.java",
    "repair": JAVA / "SageRepairManager.java",
}
source = {name: path.read_text() for name, path in files.items()}

stages = (
    "model_verification",
    "model_load",
    "context_creation",
    "prompt_formatting",
    "inference_start",
    "token_sampling",
    "first_token",
    "generation",
    "cancellation",
    "complete",
)
categories = (
    "project",
    "person",
    "device",
    "alias",
    "preference",
    "vocabulary",
    "routine",
    "factual_memory",
    "temporary_context",
    "skill_instruction",
)

checks = {
    "all native Brain stages": all(stage in source["native"] for stage in stages),
    "native stage JNI": "nativeLastStage" in source["native"] and "nativeLastStage" in source["brain"],
    "one active generation": "generationScheduled" in source["brain"] and "duplicate generation rejected" in source["brain"],
    "cancel clears inference memory": "llama_memory_clear(llama_get_memory(g_context), true);" in source["native"],
    "zero-token failure records exact stage": "generated_tokens=" in source["brain"] and "lastNativeStage()" in source["brain"],
    "exact deterministic prompt": 'askAsync("Reply with exactly: Brain online."' in source["test"],
    "real token acceptance": "tokens > 0" in source["test"] and "Generated tokens:" in source["test"],
    "displayed deterministic response": "displayed_response" in source["health"] and "Deterministic result:" in source["test"],
    "timeout captures native stage": "lastNativeStage()" in source["voice"] and "lastNativeStage()" in source["test"],
    "all memory categories": all(category in source["memory"] for category in categories),
    "versioned memory entries": 'VERSION = "v2"' in source["memory"] and "encode(" in source["memory"],
    "legacy memory decode": "legacy_owner_memory" in source["memory"] and "factual_memory" in source["memory"],
    "deterministic memory recall": "items.sort(String.CASE_INSENSITIVE_ORDER)" in source["memory"],
    "memory inspect edit delete": all(marker in source["commands"] for marker in (
        "inspectMemory()", "deleteMemory(", "editMemory(")),
    "preference device skill teaching": all(marker in source["commands"] for marker in (
        'lower.startsWith("i prefer ")',
        'lower.startsWith("use this app for ")',
        'lower.startsWith("this device is ")',
    )),
    "duplicate teaching rejection": "I already know that lesson." in source["commands"],
    "recovery repair branch": "agent/sage-1-27-unified-20260801" in source["repair"],
}

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(("PASS" if passed else "FAIL") + ": " + name)
if failed:
    raise SystemExit("Sage 1.26 continuity-finish failures: " + "; ".join(failed))

print(f"All {len(checks)} Sage 1.26 continuity-finish source checks passed.")
