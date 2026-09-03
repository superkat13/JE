#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "sage_build_1333_new")


def read(relative):
    path = root / relative
    if not path.is_file():
        raise SystemExit(f"missing {relative}")
    return path.read_text(errors="replace")


gradle = read("app/build.gradle.kts")
strings = read("app/src/main/res/values/strings.xml")
voice = read("app/src/main/java/com/pineapple/sage/SageVoiceService.java")
queue = read("app/src/main/java/com/pineapple/sage/SageTypedCommandQueue.java")
queue_test = read("app/src/test/java/com/pineapple/sage/SageTypedCommandQueueTest.java")

typed_entry = voice[voice.index("if (ACTION_TYPED_COMMAND.equals(action))"):
                    voice.index("} else if (ACTION_REFRESH_LISTENING_MODE.equals(action))")]
typed_accept = voice[voice.index("private void acceptTypedCommand"):
                     voice.index("private void dispatchTypedCommand")]
typed_dispatch = voice[voice.index("private void dispatchTypedCommand"):
                       voice.index("private boolean dispatchNextTypedCommandIfReady")]
wake_aliases = voice[voice.index("private static final String[] SAFE_PREFIXED_WAKE_SOUNDALIKES"):
                     voice.index("private static volatile boolean running")]

checks = {
    "1.33.3 version identity": "versionCode = 49" in gradle and 'versionName = "1.33.3"' in gradle,
    "stable package continuity": 'applicationId = "com.pineapple.sagecommander.stable"' in gradle,
    "repair provenance branch": "repair/normal-tablet-v1-33-3" in gradle,
    "visible 1.33.3 label": "Sage Commander 1.33.3 · Tablet Repair" in strings,
    "signing secrets are external to source": (
        "SAGE_SIGNING_STORE_PASSWORD" in gradle
        and "SAGE_SIGNING_KEY_PASSWORD" in gradle
        and 'storePassword = "android"' not in gradle
        and 'keyPassword = "android"' not in gradle
    ),
    "okay age wake recovery restored": '"okay age"' in wake_aliases,
    "all bounded prefixed soundalikes restored": all(
        f'"{prefix} {word}"' in wake_aliases
        for prefix in ("hey", "okay", "ok")
        for word in ("safe", "save", "say", "age", "page")
    ),
    "unsafe standalone soundalikes remain blocked": all(
        f'"{word}"' not in wake_aliases
        for word in ("safe", "save", "say", "age", "page")
    ),
    "typed entry delegates before changing turn mode": (
        "acceptTypedCommand(command)" in typed_entry and "textOnlyTurn = true" not in typed_entry
    ),
    "busy typed messages queue instead of mutating active response": (
        "brainInProgress || translationInProgress || speaking" in typed_accept
        and "pendingTypedCommands.offer(cleaned)" in typed_accept
        and "textOnlyTurn = true" not in typed_accept
        and "handleCommand" not in typed_accept
    ),
    "typed mode begins only at dispatch": (
        "textOnlyTurn = true" in typed_dispatch and "handleCommand(command)" in typed_dispatch
    ),
    "typed input cannot be rejected as speaker echo": "if (!textOnlyTurn && isLikelySelfEcho(cleaned))" in voice,
    "queued messages dispatch after text and spoken completions": (
        voice.count("dispatchNextTypedCommandIfReady()") >= 4
        and "String next = pendingTypedCommands.poll();" in voice
    ),
    "queue has explicit capacity and no overwrite": (
        "commands.size() >= capacity" in queue
        and "OfferResult.FULL" in queue
        and "commands.addLast(cleaned)" in queue
    ),
    "queue preserves arrival order": "return commands.pollFirst();" in queue,
    "queue has executable unit tests": (
        "preservesTypedMessagesInArrivalOrder" in queue_test
        and "neverSilentlyOverwritesAQueuedMessage" in queue_test
        and "ignoresBlankInputAndCanBeCleared" in queue_test
    ),
    "1.33.2 Brain progress watchdog preserved": (
        "BRAIN_FIRST_TOKEN_TIMEOUT_MS = 60000L" in voice
        and "BRAIN_PROGRESS_STALL_TIMEOUT_MS = 30000L" in voice
        and "BRAIN_ABSOLUTE_TIMEOUT_MS = 120000L" in voice
    ),
    "repeated wake acknowledgement preserved": (
        '"I\'m thinking."' in voice
        and "wake heard while generation active; request preserved" in voice
    ),
}

failed = []
for name, passed in checks.items():
    print(("PASS" if passed else "FAIL") + " - " + name)
    if not passed:
        failed.append(name)

if failed:
    raise SystemExit("1.33.3 normal-tablet gate failed: " + ", ".join(failed))
print(f"1.33.3 normal-tablet gate: all {len(checks)} checks passed")
