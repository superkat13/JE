#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build_1334")

def read(relative):
    path = root / relative
    if not path.is_file():
        raise SystemExit(f"missing {relative}")
    return path.read_text(encoding="utf-8")

gradle = read("app/build.gradle.kts")
strings = read("app/src/main/res/values/strings.xml")
voice = read("app/src/main/java/com/pineapple/sage/SageVoiceService.java")
wake = read("app/src/main/java/com/pineapple/sage/SageWakeFragmentTracker.java")
wake_profiles = read("app/src/main/java/com/pineapple/sage/SageWakeProfileStore.java")
conversation = read("app/src/main/java/com/pineapple/sage/SageConversationRepairPolicy.java")
brain = read("app/src/main/java/com/pineapple/sage/SageBrainRequestPolicy.java")
native = read("app/src/main/cpp/sage_brain.cpp")

exact_aliases = voice[voice.index("private static final String[] EXACT_WAKE_ALIASES"):
                      voice.index("private static final String[] SAFE_PREFIXED_WAKE_SOUNDALIKES")]

checks = {
    "1.33.4 version identity": "versionCode = 50" in gradle and 'versionName = "1.33.4"' in gradle,
    "stable package continuity": 'applicationId = "com.pineapple.sagecommander.stable"' in gradle,
    "follow-up provenance branch": "repair/normal-tablet-v1-33-4" in gradle,
    "visible 1.33.4 label": "Sage Commander 1.33.4 · Tablet Repair" in strings,
    "rotated signing remains external": "SAGE_SIGNING_STORE_PASSWORD" in gradle and 'storePassword = "android"' not in gradle,
    "observed say-page wake alias": '"say page"' in exact_aliases,
    "unsafe one-word aliases remain absent": all(f'"{word}"' not in exact_aliases for word in ("stage", "safe", "save", "say", "age", "page")),
    "bounded prefix fragment window": "FRAGMENT_WINDOW_MS = 2500L" in wake and 'Arrays.asList("hey", "okay", "ok")' in wake,
    "only Sage-like suffixes can combine": "SAGE_SUFFIXES.contains(value)" in wake and 'heldPrefix + " " + value' in wake,
    "standalone suffix passes through without wake promotion": "reset();\n        return value;" in wake,
    "wake service resolves fragments before matching": "wakeFragmentTracker.resolve(" in voice and voice.index("wakeFragmentTracker.resolve(") < voice.index("startsWithWakePhrase(normalized, finalResult)"),
    "wake fragment state resets with listener": "wakeFragmentTracker.reset();" in voice,
    "physical fragment telemetry": '"WAKE FRAGMENT"' in voice and '"combined=" + normalized' in voice,
    "owner profile survives dropped Sage token": "recoverySuffix(profile.phrase)" in wake_profiles and 'String[] prefixes = {"okay sage ", "hey sage ", "ok sage ", "sage "}' in wake_profiles,
    "unsafe custom suffixes remain blocked": "UNSAFE_SINGLE_WORDS.contains(suffix)" in wake_profiles,
    "natural capability questions route deterministically": "SageConversationRepairPolicy.isCapabilityQuestion(cleaned)" in voice,
    "observed capability wording covered": all(text in conversation for text in ("what all can you do", "what all capabilities do you have", "show your capabilities")),
    "finish-thought recognized as continuation": 'v.equals("finish your thought")' in brain and "selectRecent(" in brain,
    "recent turn carried without keyword overlap": 'for(int i=start;i<values.size();i++)' in brain,
    "complete sentence instruction": "Never stop mid-sentence" in brain,
    "expanded adaptive Brain response budget": "CONCISE_ANSWER?32" in brain and ":48" in brain,
    "native response ceiling matches policy": "std::min(48, static_cast<int>(requested_tokens))" in native,
    "typed busy queue preserved": "pendingTypedCommands.offer(cleaned)" in voice and "dispatchNextTypedCommandIfReady" in voice,
    "progress-aware Brain watchdog preserved": "BRAIN_FIRST_TOKEN_TIMEOUT_MS = 60000L" in voice and "BRAIN_ABSOLUTE_TIMEOUT_MS = 120000L" in voice,
    "repeated wake acknowledgement preserved": '"I\'m thinking."' in voice,
}

failed = []
for name, passed in checks.items():
    print(("PASS" if passed else "FAIL") + " - " + name)
    if not passed:
        failed.append(name)
if failed:
    raise SystemExit("1.33.4 physical follow-up gate failed: " + ", ".join(failed))
print(f"1.33.4 physical follow-up gate: all {len(checks)} checks passed")
