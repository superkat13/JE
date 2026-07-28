from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"
voice = (JAVA / "SageVoiceService.java").read_text()
brain = (JAVA / "SageBrainManager.java").read_text()
cpp = (ROOT / "app/src/main/cpp/sage_brain.cpp").read_text()
gradle = (ROOT / "app/build.gradle.kts").read_text()

checks = {
    "30-second brain watchdog": "BRAIN_REQUEST_TIMEOUT_MS = 30000L" in voice,
    "request generation prevents stale callbacks": "requestGeneration != brainRequestGeneration" in voice,
    "timeout restores listening through fallback delivery": (
        "Sage Brain timed out — listening restored" in voice
        and "deliverCommandResult(fallbackResult);" in voice
    ),
    "timeout cancels native generation": "brainManager.cancelCurrentRequest();" in voice,
    "service shutdown cancels brain": (
        "++brainRequestGeneration;" in voice
        and "handler.removeCallbacksAndMessages(null);" in voice
    ),
    "shorter brain replies": "MAX_REPLY_TOKENS = 48" in brain,
    "Java cancellation API exists": (
        "public void cancelCurrentRequest()" in brain
        and "nativeCancelGeneration();" in brain
    ),
    "JNI cancellation declaration exists": "private static native void nativeCancelGeneration();" in brain,
    "native cancellation is atomic": (
        "#include <atomic>" in cpp
        and "std::atomic<bool> g_cancel_requested{false};" in cpp
    ),
    "native loop checks cancellation before decode": (
        cpp.index("g_cancel_requested.load(std::memory_order_acquire)")
        < cpp.index("int decode_result = llama_decode")
    ),
    "native cancellation JNI exists": (
        "Java_com_pineapple_sage_SageBrainManager_nativeCancelGeneration" in cpp
    ),
    "cancelled generation returns an error": "Brain request cancelled" in cpp,
    "permanent package remains unchanged": (
        'applicationId = "com.pineapple.sagecommander.stable"' in gradle
    ),
    "version advanced to 1.22": (
        "versionCode = 32" in gradle and 'versionName = "1.22"' in gradle
    ),
}

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(("PASS" if passed else "FAIL") + ": " + name)

# The timeout callback must invalidate its request before delivering the fallback.
timeout_block = re.search(
    r"final Runnable timeout = \(\) -> \{(?P<body>.*?)\n        \};",
    voice,
    re.DOTALL,
)
if timeout_block is None:
    failed.append("timeout runnable is structurally present")
else:
    body = timeout_block.group("body")
    order = [
        body.find("brainInProgress = false;"),
        body.find("++brainRequestGeneration;"),
        body.find("brainManager.cancelCurrentRequest();"),
        body.find("deliverCommandResult(fallbackResult);")
    ]
    if any(index < 0 for index in order) or order != sorted(order):
        failed.append("timeout invalidates and cancels before fallback")
    else:
        print("PASS: timeout invalidates and cancels before fallback")

if failed:
    raise SystemExit("Sage 1.22 watchdog regression failures: " + "; ".join(failed))

print(f"All {len(checks) + 1} Sage 1.22 brain-watchdog checks passed.")
