#!/usr/bin/env python3
"""Add Sage 1.27's rotating offline Creative Planner vertical slice."""

from pathlib import Path
import sys


ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"


def replace_once(path: Path, old: str, new: str) -> None:
    value = path.read_text()
    if new in value:
        return
    if value.count(old) != 1:
        raise SystemExit(f"expected one creative insertion marker in {path}")
    path.write_text(value.replace(old, new, 1))


command_path = JAVA / "SageCommandEngine.java"
command_marker = '''        if (learningResult != null) {
            preferences.edit().putString("last_heard", raw).apply();
            return learningResult;
        }
'''
command_insert = command_marker + '''
        if (isAny(lower, "surprise me", "sage surprise me", "i am bored", "im bored",
                "cure boredom", "sage cure boredom")
                || lower.contains("video idea") || lower.contains("image prompt")
                || lower.contains("music idea") || lower.contains("project idea")) {
            return new Result(SageCreativeEngine.respond(context, raw));
        }
'''
replace_once(command_path, command_marker, command_insert)

coordinator_path = JAVA / "SageIntentCoordinator.java"
replace_once(
    coordinator_path,
    '''            case "quality": return "QA diagnostics";
            case "device_action": return "Android command engine";''',
    '''            case "quality": return "QA diagnostics";
            case "creative": return "Creative Studio";
            case "device_action": return "Android command engine";''',
)
replace_once(
    coordinator_path,
    '''        if (intent.equals("knowledge") || intent.equals("conversation") || intent.equals("creative")) {
            return brainAvailable ? "tablet Brain" : "fallback";
        }''',
    '''        if (intent.equals("creative")) return "command engine";
        if (intent.equals("knowledge") || intent.equals("conversation")) {
            return brainAvailable ? "tablet Brain" : "fallback";
        }''',
)

(JAVA / "SageCreativeEngine.java").write_text(r'''package com.pineapple.sage;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Offline rotating creative challenges with an explicit route and no network dependency. */
final class SageCreativeEngine {
    private static final String PREFS = "sage_creative_v1";

    private static final String[] VIDEO = {
            "Film an eight-second POV walk where rain reflections begin moving one step ahead of the camera, then freeze when noticed.",
            "Start on an ordinary doorway, push through a veil of water, and reveal the same room rebuilt as a bioluminescent forest.",
            "Make a three-shot transformation: calm portrait, one impossible environmental change, then a final reveal held long enough to enjoy.",
            "Shoot a harmless object like a coffee mug as if it is the artifact that decides the fate of a gothic kingdom."
    };
    private static final String[] IMAGE = {
            "A moonlit poisonous-mushroom cathedral, hard psychedelic color separation, wet black stone, cinematic fog, no text.",
            "A black-and-crimson engineering sanctuary hidden inside an old mansion, practical screens, candlelight, elegant and believable.",
            "Four fantasy guardians reflected in broken mirror shards, each shard showing a different season and transformation energy.",
            "A tiny neon storm trapped inside a glass pineapple on a workbench, macro photography, dramatic rim light."
    };
    private static final String[] MUSIC = {
            "Build a 90-second track that starts with one dirty bass pulse, adds industrial percussion, then detonates into a deathcore half-time drop.",
            "Write a sinister surf-punk riff that mutates into heavy electronic bass without changing tempo.",
            "Make a cinematic cue from forest ambience, distant metallic knocks, one whispered synth note, and a final enormous drum hit.",
            "Create a cheeky villain theme using a music-box melody, distorted bass, and drums that keep interrupting the melody."
    };
    private static final String[] PROJECT = {
            "Create a one-minute trailer for an imaginary tool that solves a ridiculous problem with completely serious cinematography.",
            "Pick one unfinished clip and improve only its final two seconds: cleaner motion, stronger reveal, and an intentional audio landing.",
            "Build a four-image continuity chain where each ending image becomes the next scene's beginning image.",
            "Design a tiny before-and-after portfolio piece showing raw generation, repaired transition, sound pass, and final grade."
    };
    private static final String[] BORED = {
            "Ten-minute mission: photograph the strangest shadow nearby and turn it into a creature concept with a name and one rule.",
            "Pick any object within reach. Give yourself eight minutes to shoot it as a luxury commercial, then add one absurd reveal.",
            "Open one abandoned project, change exactly one thing that bothered you, export it, and stop before perfectionism wakes up.",
            "Record three ordinary sounds, arrange them into a fifteen-second beat, and name the imaginary band that made it."
    };

    private SageCreativeEngine() {}

    static String respond(Context context, String request) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String normalized = normalize(request);
        String mode;
        if (normalized.contains("video")) mode = "video";
        else if (normalized.contains("image")) mode = "image";
        else if (normalized.contains("music")) mode = "music";
        else if (normalized.contains("project")) mode = "project";
        else if (normalized.contains("bored") || normalized.contains("boredom")) mode = "boredom";
        else {
            String[] rotation = {"video", "image", "music", "project", "boredom"};
            long index = preferences.getLong("surprise_rotation", 0L);
            mode = rotation[(int) Math.floorMod(index, rotation.length)];
            preferences.edit().putLong("surprise_rotation", index + 1L).apply();
        }

        String[] choices = choices(mode);
        String key = "next_" + mode;
        long index = preferences.getLong(key, 0L);
        String idea = choices[(int) Math.floorMod(index, choices.length)];
        preferences.edit().putLong(key, index + 1L).apply();
        SageDiagnostics.appendEvent(context, "CREATIVE",
                "mode=" + mode + " route=command engine index=" + index);
        return "Command engine • Creative Planner • " + label(mode) + ": " + idea;
    }

    private static String[] choices(String mode) {
        if (mode.equals("video")) return VIDEO;
        if (mode.equals("image")) return IMAGE;
        if (mode.equals("music")) return MUSIC;
        if (mode.equals("project")) return PROJECT;
        return BORED;
    }

    private static String label(String mode) {
        if (mode.equals("video")) return "video idea";
        if (mode.equals("image")) return "image prompt";
        if (mode.equals("music")) return "music idea";
        if (mode.equals("project")) return "project idea";
        return "boredom cure";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
''')

print("Applied Sage 1.27 offline Creative Studio")
