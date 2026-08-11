#!/usr/bin/env python3
"""Checkpoint 1: make Sage the only normal user-facing assistant.

This is additive and compatibility-preserving. Existing saved wake profiles keep their
serialized mode values and continue to load. New wake-profile configuration no longer
presents Sage Brain as a separate assistant/mode. Red Queen remains the deliberate
hidden elevated workspace, and command shortcuts remain available as automation.
"""
from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1))


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: one_sage_routing_v1_29.py <reconstructed-source>")

    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    main_activity = java / "MainActivity.java"
    store = java / "SageWakeProfileStore.java"
    voice = java / "SageVoiceService.java"

    for required in (main_activity, store, voice):
        if not required.is_file():
            raise SystemExit(f"Checkpoint 1 missing reconstructed source: {required.name}")

    replace_once(
        main_activity,
        '''    private static final String[] WAKE_MODE_KEYS = {
            SageWakeProfileStore.MODE_NORMAL,
            SageWakeProfileStore.MODE_RED_QUEEN,
            SageWakeProfileStore.MODE_BRAIN,
            SageWakeProfileStore.MODE_COMMAND
    };
    private static final String[] WAKE_MODE_LABELS = {
            "Normal Sage",
            "Red Queen",
            "Sage Brain",
            "Run a command"
    };''',
        '''    private static final String[] WAKE_MODE_KEYS = {
            SageWakeProfileStore.MODE_NORMAL,
            SageWakeProfileStore.MODE_RED_QUEEN,
            SageWakeProfileStore.MODE_COMMAND
    };
    private static final String[] WAKE_MODE_LABELS = {
            "Sage",
            "Red Queen phrase",
            "Run a command"
    };''',
        "one-Sage wake-mode choices",
    )

    replace_once(
        main_activity,
        '''        wakeProfilesHelp.setText("Add a distinctive wake word or short phrase, then choose what it activates. A profile can open normal Sage, trigger Red Queen, open direct Sage Brain mode, or run any saved command.");''',
        '''        wakeProfilesHelp.setText("Add a distinctive wake word or short phrase. Normal phrases wake Sage, a Red Queen phrase opens the elevated workspace, and command phrases run an automation. Sage chooses Brain and other specialists internally.");''',
        "one-Sage wake-profile help",
    )

    replace_once(
        main_activity,
        '''            } else if (SageWakeProfileStore.MODE_BRAIN.equals(mode)) {
                wakeProfileModeHelp.setText("Saying this phrase opens a one-question direct local-brain turn. Built-in commands are bypassed for that one question.");
            } else if (commandMode) {''',
        '''            } else if (commandMode) {''',
        "hide direct Brain mode from new profile UI",
    )

    replace_once(
        main_activity,
        '''                wakeProfileModeHelp.setText("Saying this phrase works like saying Sage and opens normal conversation.");''',
        '''                wakeProfileModeHelp.setText("Saying this phrase wakes Sage. Sage decides whether the request belongs to Brain, device control, Forge, creative tools, or another installed specialist.");''',
        "one-Sage normal wake description",
    )

    # Preserve old MODE_BRAIN data and runtime dispatch so existing saved profiles are
    # not destroyed. Relabel them as compatibility shortcuts rather than a second Sage.
    replace_once(
        store,
        '''        if (MODE_BRAIN.equals(profile.mode)) {
            return "Sage Brain";
        }''',
        '''        if (MODE_BRAIN.equals(profile.mode)) {
            return "Sage (legacy direct-Brain shortcut)";
        }''',
        "legacy Brain profile compatibility label",
    )

    # Runtime compatibility guard: old profiles must still dispatch; do not delete the
    # proven one-shot path until a later migration is explicitly tested against data.
    voice_text = voice.read_text()
    required_runtime = (
        "SageWakeProfileStore.MODE_BRAIN.equals(profile.mode)",
        "forceBrainForNextCommand = true;",
        "SageWakeProfileStore.MODE_RED_QUEEN.equals(profile.mode)",
    )
    missing = [token for token in required_runtime if token not in voice_text]
    if missing:
        raise SystemExit("Checkpoint 1 would break saved wake-profile compatibility: " + ", ".join(missing))

    print("Applied Checkpoint 1: one-Sage user-facing routing with legacy wake-profile compatibility")


if __name__ == "__main__":
    main()
