package com.pineapple.sage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SageWakeProfileStore {
    public static final String MODE_NORMAL = "normal";
    public static final String MODE_RED_QUEEN = "red_queen";
    public static final String MODE_BRAIN = "brain";
    public static final String MODE_COMMAND = "command";

    private static final String PREFERENCES = "sage_state";
    private static final String KEY_PROFILES = "wake_profiles_v1";
    private static final String KEY_LEGACY_ALIASES = "wake_aliases";
    private static final String SEPARATOR = ".";

    private static final Set<String> RESERVED = new HashSet<>();
    private static final Set<String> UNSAFE_SINGLE_WORDS = new HashSet<>();

    static {
        Collections.addAll(
                RESERVED,
                "sage", "hey sage", "okay sage", "ok sage",
                "stage", "hey stage", "okay stage", "ok stage",
                "say age", "hey say age", "okay say age", "ok say age",
                "safe", "save", "say", "age", "page"
        );
        Collections.addAll(
                UNSAFE_SINGLE_WORDS,
                "hey", "okay", "ok", "yes", "no", "open", "show", "go",
                "play", "pause", "stop", "phone", "video", "back", "home",
                "search", "find", "tap", "click", "help", "sleep"
        );
    }

    public static final class Profile {
        public final String phrase;
        public final String mode;
        public final String command;

        Profile(String phrase, String mode, String command) {
            this.phrase = phrase;
            this.mode = mode;
            this.command = command;
        }
    }

    public static final class Match {
        public final Profile profile;
        public final String remainder;

        Match(Profile profile, String remainder) {
            this.profile = profile;
            this.remainder = remainder;
        }
    }

    private SageWakeProfileStore() {
    }

    public static List<Profile> load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        Map<String, Profile> byPhrase = new HashMap<>();
        Set<String> encoded = preferences.getStringSet(KEY_PROFILES, null);
        if (encoded != null) {
            for (String value : encoded) {
                Profile profile = decodeProfile(value);
                if (profile != null) {
                    byPhrase.put(profile.phrase, profile);
                }
            }
        }

        Set<String> legacy = preferences.getStringSet(KEY_LEGACY_ALIASES, null);
        if (legacy != null) {
            for (String value : legacy) {
                String phrase = normalizePhrase(value);
                if (!phrase.isEmpty() && !RESERVED.contains(phrase) && !byPhrase.containsKey(phrase)) {
                    byPhrase.put(phrase, new Profile(phrase, MODE_NORMAL, ""));
                }
            }
        }

        ArrayList<Profile> profiles = new ArrayList<>(byPhrase.values());
        profiles.sort((left, right) -> {
            int length = Integer.compare(right.phrase.length(), left.phrase.length());
            return length != 0 ? length : left.phrase.compareTo(right.phrase);
        });
        return profiles;
    }

    public static String save(
            Context context,
            String phraseInput,
            String modeInput,
            String commandInput
    ) {
        String phrase = normalizePhrase(phraseInput);
        String mode = normalizeMode(modeInput);
        String command = commandInput == null ? "" : commandInput.trim();

        String validation = validatePhrase(phrase);
        if (!validation.isEmpty()) {
            return validation;
        }
        if (mode.isEmpty()) {
            return "Choose a wake mode.";
        }
        if (MODE_COMMAND.equals(mode) && command.isEmpty()) {
            return "Type the command this wake word should run.";
        }
        if (MODE_RED_QUEEN.equals(mode)) {
            command = "red queen mode";
        } else if (!MODE_COMMAND.equals(mode)) {
            command = "";
        }

        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        LinkedHashSet<String> updated = new LinkedHashSet<>();
        Set<String> current = preferences.getStringSet(KEY_PROFILES, null);
        if (current != null) {
            for (String value : current) {
                Profile existing = decodeProfile(value);
                if (existing != null && !existing.phrase.equals(phrase)) {
                    updated.add(value);
                }
            }
        }
        updated.add(encodeProfile(new Profile(phrase, mode, command)));
        return preferences.edit().putStringSet(KEY_PROFILES, updated).commit()
                ? ""
                : "Android could not save that wake profile.";
    }

    public static boolean remove(Context context, String phraseInput) {
        String phrase = normalizePhrase(phraseInput);
        if (phrase.isEmpty()) {
            return false;
        }
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        LinkedHashSet<String> updated = new LinkedHashSet<>();
        boolean removed = false;
        Set<String> current = preferences.getStringSet(KEY_PROFILES, null);
        if (current != null) {
            for (String value : current) {
                Profile existing = decodeProfile(value);
                if (existing != null && existing.phrase.equals(phrase)) {
                    removed = true;
                } else {
                    updated.add(value);
                }
            }
        }
        if (!removed) {
            return false;
        }
        return preferences.edit().putStringSet(KEY_PROFILES, updated).commit();
    }

    public static boolean clear(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PROFILES)
                .remove(KEY_LEGACY_ALIASES)
                .commit();
    }

    public static List<String> allWakePhrases(Context context) {
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        for (Profile profile : load(context)) {
            phrases.add(profile.phrase);
            phrases.add("hey " + profile.phrase);
            phrases.add("okay " + profile.phrase);
            phrases.add("ok " + profile.phrase);
        }
        return new ArrayList<>(phrases);
    }

    public static Match match(Context context, String recognizedText) {
        String normalized = normalizePhrase(recognizedText);
        if (normalized.isEmpty()) {
            return null;
        }
        for (Profile profile : load(context)) {
            String[] variants = {
                    "okay " + profile.phrase,
                    "hey " + profile.phrase,
                    "ok " + profile.phrase,
                    profile.phrase
            };
            for (String variant : variants) {
                if (normalized.equals(variant)) {
                    return new Match(profile, "");
                }
                if (normalized.startsWith(variant + " ")) {
                    return new Match(
                            profile,
                            normalized.substring(variant.length()).trim()
                    );
                }
            }
        }
        return null;
    }

    public static String summary(Context context) {
        ArrayList<Profile> profiles = new ArrayList<>(load(context));
        profiles.sort(Comparator.comparing(profile -> profile.phrase));
        if (profiles.isEmpty()) {
            return "No custom wake profiles yet. Sage and her built-in sound-alikes still work.";
        }
        StringBuilder summary = new StringBuilder("Saved wake profiles:");
        for (Profile profile : profiles) {
            summary.append("\n• ")
                    .append(profile.phrase)
                    .append(" → ")
                    .append(modeLabel(profile));
        }
        return summary.toString();
    }

    public static String modeLabel(Profile profile) {
        if (profile == null) {
            return "Normal Sage";
        }
        if (MODE_RED_QUEEN.equals(profile.mode)) {
            return "Red Queen";
        }
        if (MODE_BRAIN.equals(profile.mode)) {
            return "Sage Brain";
        }
        if (MODE_COMMAND.equals(profile.mode)) {
            return "Run: " + profile.command;
        }
        return "Normal Sage";
    }

    public static String normalizePhrase(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9']+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String validatePhrase(String phrase) {
        if (phrase.isEmpty()) {
            return "Type the wake word or phrase.";
        }
        if (phrase.length() < 3 || phrase.length() > 40) {
            return "Use a wake phrase between 3 and 40 characters.";
        }
        int words = phrase.split(" ").length;
        if (words > 4) {
            return "Use four words or fewer so the offline listener can recognize it.";
        }
        if (RESERVED.contains(phrase)) {
            return "That phrase is already part of Sage's built-in wake listener.";
        }
        if (words == 1 && (phrase.length() < 4 || UNSAFE_SINGLE_WORDS.contains(phrase))) {
            return "That single word is too common and would wake Sage by accident. Use a more distinctive word or a two-word phrase.";
        }
        return "";
    }

    private static String normalizeMode(String mode) {
        if (MODE_NORMAL.equals(mode)
                || MODE_RED_QUEEN.equals(mode)
                || MODE_BRAIN.equals(mode)
                || MODE_COMMAND.equals(mode)) {
            return mode;
        }
        return "";
    }

    private static String encodeProfile(Profile profile) {
        return encode(profile.phrase)
                + SEPARATOR
                + encode(profile.mode)
                + SEPARATOR
                + encode(profile.command);
    }

    private static Profile decodeProfile(String encoded) {
        if (encoded == null) {
            return null;
        }
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            String phrase = normalizePhrase(decode(parts[0]));
            String mode = normalizeMode(decode(parts[1]));
            String command = decode(parts[2]).trim();
            if (phrase.isEmpty() || mode.isEmpty()) {
                return null;
            }
            if (MODE_RED_QUEEN.equals(mode)) {
                command = "red queen mode";
            }
            if (MODE_COMMAND.equals(mode) && command.isEmpty()) {
                return null;
            }
            return new Profile(phrase, mode, command);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String encode(String value) {
        return Base64.encodeToString(
                value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP
        );
    }

    private static String decode(String value) {
        return new String(
                Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP),
                StandardCharsets.UTF_8
        );
    }
}
