#!/usr/bin/env python3
"""Executable source regressions for the bounded Sage 1.29 Brain repair."""

from pathlib import Path
import re
import sys


ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build_129")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"
files = {
    "gradle": ROOT / "app/build.gradle.kts",
    "native": ROOT / "app/src/main/cpp/sage_brain.cpp",
    "brain": JAVA / "SageBrainManager.java",
    "health": JAVA / "SageBrainHealth.java",
    "test": JAVA / "SageBrainTestActivity.java",
    "models": JAVA / "SageModelManager.java",
    "main": JAVA / "MainActivity.java",
}
source = {name: path.read_text(encoding="utf-8") for name, path in files.items()}

exact_prompt = '"Reply with exactly: Brain online."'
checks = {
    "permanent package": 'applicationId = "com.pineapple.sagecommander.stable"' in source["gradle"],
    "1.29 identity": 'versionCode = 41' in source["gradle"] and 'versionName = "1.29.0"' in source["gradle"],
    "1.29 launcher label": "Sage Commander 1.29.0" in
        (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8"),
    "permanent signing config": all(value in source["gradle"] for value in (
        'sagePermanentSigning', 'debug.keystore', 'androiddebugkey')),
    "watchdog remains 30 seconds": 'GENERATION_TIMEOUT_MS = 30000L' in source["test"]
        and 'GENERATION_TIMEOUT_MS = 30000L' not in source["brain"],
    "exact dedicated prompt": exact_prompt in source["brain"]
        and 'testBrainAsync' in source["brain"] and 'brain.testBrainAsync(' in source["test"],
    "test bypasses conversational prompt": 'buildUserPrompt' not in source["test"]
        and 'testBrainAsync' in source["brain"],
    "real tokens required": 'nativeLastGeneratedTokenCount() <= 0' in source["brain"]
        and 'tokens > 0' in source["test"],
    "deterministic native sampler": 'jboolean deterministic' in source["native"]
        and 'llama_sampler_init_greedy()' in source["native"],
    "prefill cancellation": 'context_params.abort_callback' in source["native"]
        and 'g_cancel_requested.load' in source["native"]
        and 'decode_result == 2' in source["native"],
    "prefill diagnostics": all(value in source["native"] + source["health"] for value in (
        'prompt_prefill_duration', 'prompt_token_count')),
    "full health evidence": all(value in source["health"] for value in (
        'Model name:', 'Model file:', 'Quantization:', 'File size:', 'Verification hash:',
        'Active route:', 'Load duration:', 'First-token latency:', 'Generation speed:',
        'Generated token count:', 'Available RAM:', 'Native stage:', 'Exact error:')),
    "immutable Q4 catalog": all(value in source["brain"] for value in (
        'Qwen_Qwen3-1.7B-Q4_K_M.gguf',
        '88c7b586e13a4c53ef5e16e10a4ec1cda921b3c9',
        '72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb',
        '1_282_439_584L')),
    "direct HTTPS download": 'HttpURLConnection' in source["models"]
        and 'RECOMMENDED_MODEL_URL' in source["models"],
    "resume": 'setRequestProperty("Range"' in source["models"]
        and 'HTTP_PARTIAL' in source["models"] and 'partial file retained for resume' in source["models"],
    "progress and cancellation": 'Downloading model:' in source["models"]
        and 'AtomicBoolean' in source["models"] and 'void cancel()' in source["models"],
    "storage requirement": 'STORAGE_HEADROOM' in source["models"]
        and 'freeStorageBytes()' in source["models"],
    "exact size and hash verification": 'partial.length() != SageBrainManager.RECOMMENDED_MODEL_BYTES' in source["models"]
        and 'RECOMMENDED_MODEL_SHA256.equalsIgnoreCase(hash)' in source["models"],
    "verified activation only": 'installVerifiedDownloadAsync' in source["models"]
        and 'Verified model handoff did not match' in source["brain"],
    "manual import retained": 'importModelAsync' in source["brain"]
        and 'chooseBrainModelFile()' in source["main"],
    "no fake Brain response": 'Reply.answer("Brain online.")' not in source["brain"]
        and 'postReply(callback, Reply.answer("Brain online."))' not in source["brain"],
}

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(("PASS" if passed else "FAIL") + ": " + name)
if failed:
    raise SystemExit("Sage 1.29 Brain repair failures: " + "; ".join(failed))
print(f"All {len(checks)} Sage 1.29 Brain repair checks passed.")
