from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"

voice = (JAVA / "SageVoiceService.java").read_text()
commands = (JAVA / "SageCommandEngine.java").read_text()
main = (JAVA / "MainActivity.java").read_text()
store = (JAVA / "SageWakeProfileStore.java").read_text()
gradle = (ROOT / "app/build.gradle.kts").read_text()

checks = {
    "current_version_code_34": "versionCode = 34" in gradle,
    "current_version_name_1_24": 'versionName = "1.24"' in gradle,
    "wake_profile_store_present": "class SageWakeProfileStore" in store,
    "normal_mode_present": 'MODE_NORMAL = "normal"' in store,
    "red_queen_mode_present": 'MODE_RED_QUEEN = "red_queen"' in store,
    "brain_mode_present": 'MODE_BRAIN = "brain"' in store,
    "command_mode_present": 'MODE_COMMAND = "command"' in store,
    "unsafe_single_word_guard": "UNSAFE_SINGLE_WORDS" in store,
    "legacy_aliases_migrated": 'KEY_LEGACY_ALIASES = "wake_aliases"' in store,
    "wake_grammar_reads_profiles": "SageWakeProfileStore.allWakePhrases(this)" in voice,
    "wake_match_dispatches_profile": "respondToWakeProfile(profileMatch)" in voice,
    "wake_profile_diagnostics": '"WAKE PROFILE"' in voice,
    "red_queen_wake_dispatch": 'handleCommand("red queen mode")' in voice,
    "brain_wake_is_one_shot": "forceBrainForNextCommand" in voice,
    "wake_refresh_action": "ACTION_REFRESH_WAKE_PROFILES" in voice,
    "complete_minimum_speech_window": "EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1300L" in voice,
    "complete_silence_window": "EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L" in voice,
    "possible_silence_window": "EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 750L" in voice,
    "final_partial_candidates_combined": "combinedChoices" in voice and "for (String partial : partialChoices)" in voice,
    "incomplete_stem_penalty": "isIncompleteCommandStem" in voice and "score -= 45" in voice,
    "longer_candidate_bonus": "wordCount * 9" in voice,
    "follow_up_escape": "shouldReplaceActionFollowUp" in commands,
    "wake_profile_ui": 'setText("Custom wake profiles")' in main,
    "wake_profile_save_button": 'makeButton("Save wake profile")' in main,
    "wake_profile_remove_button": 'makeButton("Remove typed wake phrase")' in main,
    "wake_profile_clear_button": 'makeButton("Clear custom wake profiles")' in main,
}

failed = [name for name, passed in checks.items() if not passed]
if failed:
    raise SystemExit("Source checks failed: " + ", ".join(failed))


def contains_cue(normalized: str) -> bool:
    cues = {
        "open", "show", "search", "find", "look", "youtube", "home", "back", "recent",
        "scroll", "tap", "click", "press", "pick", "choose", "select", "type", "number",
        "numbers", "go", "write", "volume", "louder", "quieter", "mute", "pause", "play",
        "next", "previous", "notification", "remember", "memory", "red", "queen", "sleep",
        "help", "first", "second", "third", "fourth", "fifth", "sixth", "seventh",
        "eighth", "ninth", "tenth", "video", "link", "result", "website", "settings",
        "translate", "language", "spanish", "french", "german", "japanese", "chinese",
        "teach", "taught", "when", "mean", "means", "phrase", "voice", "audio", "clip",
    }
    return any(word in cues for word in normalized.split())


incomplete = {
    "open", "show", "search", "find", "look", "tap", "click", "type", "write", "go",
    "play", "scroll", "turn", "number", "select", "choose",
}


def score(choice: str, index: int) -> int:
    normalized = " ".join(choice.lower().split())
    value = 100 - index * 5
    if contains_cue(normalized):
        value += 40
    if normalized.startswith("sage ") or normalized.startswith("hey sage "):
        value += 15
    words = normalized.split() if normalized else []
    value += min(36, len(words) * 9)
    value += min(12, len(normalized) // 4)
    if len(words) == 1 and normalized in incomplete:
        value -= 45
    return value


def choose(choices):
    return max(enumerate(choices), key=lambda item: score(item[1], item[0]))[1]


behavior = {
    "show_numbers_beats_show": choose(["show", "show numbers"]) == "show numbers",
    "open_youtube_beats_open": choose(["open", "open youtube"]) == "open youtube",
    "go_back_beats_go": choose(["go", "go back"]) == "go back",
    "number_two_beats_two": choose(["two", "number two"]) == "number two",
    "show_video_numbers_beats_show": choose(["show", "show video numbers"]) == "show video numbers",
}


def normalize(value: str) -> str:
    return " ".join(re.sub(r"[^a-z0-9']+", " ", value.lower()).split())


def match(profile_phrase: str, recognized: str):
    phrase = normalize(profile_phrase)
    text = normalize(recognized)
    for variant in ("okay " + phrase, "hey " + phrase, "ok " + phrase, phrase):
        if text == variant:
            return ""
        if text.startswith(variant + " "):
            return text[len(variant):].strip()
    return None


behavior.update({
    "custom_exact_wake": match("computer", "computer") == "",
    "custom_prefixed_wake": match("computer", "hey computer") == "",
    "custom_wake_with_command": match("computer", "computer open youtube") == "open youtube",
    "red_queen_phrase": match("red queen", "red queen") == "",
    "brain_phrase_with_question": match("oracle", "oracle why is the sky blue") == "why is the sky blue",
})

fresh_starts = (
    "open ", "show ", "search ", "find ", "look for ", "tap ", "click ", "type ",
    "write ", "go back", "go home", "scroll ", "number ", "play ", "pause ", "turn ",
    "read ", "translate ", "remember ", "red queen", "clear numbers",
)


def replaces_follow_up(pending: str, lower: str) -> bool:
    if pending not in {"open", "tap", "type", "youtube_search", "web_search", "say"}:
        return False
    return lower.startswith(fresh_starts) or lower in {
        "back", "home", "recent apps", "notifications", "help", "sleep"
    }


behavior.update({
    "open_target_stays_follow_up": not replaces_follow_up("open", "youtube"),
    "new_show_command_escapes_open_follow_up": replaces_follow_up("open", "show numbers"),
    "new_navigation_escapes_open_follow_up": replaces_follow_up("open", "go back"),
    "teaching_follow_up_not_replaced": not replaces_follow_up("teach_meaning", "open youtube"),
})

failed_behavior = [name for name, passed in behavior.items() if not passed]
if failed_behavior:
    raise SystemExit("Behavior checks failed: " + ", ".join(failed_behavior))

print(f"Sage 1.23 source checks passed: {len(checks)}")
print(f"Sage 1.23 behavior checks passed: {len(behavior)}")
