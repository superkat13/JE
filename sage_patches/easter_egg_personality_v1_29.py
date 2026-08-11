#!/usr/bin/env python3
"""Checkpoint 2: persistent normal-Sage Easter egg text replies.

Adds phrase -> text-response personality hooks without creating another mode or
altering Red Queen/capability authority. Custom replies use Sage's existing voice
output path and are intentionally separate from executable commands and privileges.
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
        raise SystemExit("usage: easter_egg_personality_v1_29.py <reconstructed-source>")

    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    main_activity = java / "MainActivity.java"
    voice = java / "SageVoiceService.java"
    for required in (main_activity, voice):
        if not required.is_file():
            raise SystemExit(f"Checkpoint 2 missing reconstructed source: {required.name}")

    store_source = r'''package com.pineapple.sage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Owner-defined phrase -> text personality replies for normal Sage.
 *
 * This store contains no executable action, authority, permission, package, network,
 * shell, Forge, or Red Queen dispatch. It is deliberately a personality layer only.
 */
public final class SageEasterEggStore {
    private static final String PREFS = "sage_state";
    private static final String KEY = "easter_egg_replies_v1";
    private static final String SEP = ".";
    private static final int MAX_PHRASE = 80;
    private static final int MAX_RESPONSE = 600;

    public static final class Entry {
        public final String phrase;
        public final String response;
        Entry(String phrase, String response) {
            this.phrase = phrase;
            this.response = response;
        }
    }

    private SageEasterEggStore() {}

    public static String save(Context context, String phraseInput, String responseInput) {
        String phrase = normalize(phraseInput);
        String response = responseInput == null ? "" : responseInput.trim();
        if (phrase.isEmpty()) return "Type the phrase Sage should react to.";
        if (phrase.length() < 2 || phrase.length() > MAX_PHRASE) {
            return "Use a phrase between 2 and 80 characters.";
        }
        if (response.isEmpty()) return "Type what Sage should say back.";
        if (response.length() > MAX_RESPONSE) return "Keep the reply under 600 characters.";
        if (phrase.equals("red queen mode")) {
            return "Red Queen mode is reserved for Sage's elevated workspace.";
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        LinkedHashSet<String> updated = new LinkedHashSet<>();
        Set<String> current = prefs.getStringSet(KEY, null);
        if (current != null) {
            for (String encoded : current) {
                Entry existing = decode(encoded);
                if (existing != null && !existing.phrase.equals(phrase)) updated.add(encoded);
            }
        }
        updated.add(encode(new Entry(phrase, response)));
        return prefs.edit().putStringSet(KEY, updated).commit()
                ? "" : "Android could not save that Easter egg.";
    }

    public static Entry find(Context context, String spoken) {
        String normalized = normalize(spoken);
        if (normalized.isEmpty()) return null;
        for (Entry entry : list(context)) {
            if (entry.phrase.equals(normalized)) return entry;
        }
        return null;
    }

    public static boolean remove(Context context, String phraseInput) {
        String phrase = normalize(phraseInput);
        if (phrase.isEmpty()) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> current = prefs.getStringSet(KEY, null);
        if (current == null || current.isEmpty()) return false;
        LinkedHashSet<String> updated = new LinkedHashSet<>();
        boolean removed = false;
        for (String encoded : current) {
            Entry entry = decode(encoded);
            if (entry != null && entry.phrase.equals(phrase)) removed = true;
            else if (entry != null) updated.add(encoded);
        }
        return removed && prefs.edit().putStringSet(KEY, updated).commit();
    }

    public static boolean clear(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).commit();
    }

    public static List<Entry> list(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> current = prefs.getStringSet(KEY, null);
        if (current == null || current.isEmpty()) return Collections.emptyList();
        Map<String, Entry> byPhrase = new LinkedHashMap<>();
        for (String encoded : current) {
            Entry entry = decode(encoded);
            if (entry != null) byPhrase.put(entry.phrase, entry);
        }
        ArrayList<Entry> entries = new ArrayList<>(byPhrase.values());
        entries.sort(Comparator.comparing(entry -> entry.phrase));
        return entries;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9']+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String encode(Entry entry) {
        return b64(entry.phrase) + SEP + b64(entry.response);
    }

    private static Entry decode(String encoded) {
        if (encoded == null) return null;
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != 2) return null;
        try {
            String phrase = normalize(unb64(parts[0]));
            String response = unb64(parts[1]).trim();
            if (phrase.isEmpty() || response.isEmpty()) return null;
            return new Entry(phrase, response);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String b64(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP);
    }

    private static String unb64(String value) {
        return new String(Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP),
                StandardCharsets.UTF_8);
    }
}
'''
    (java / "SageEasterEggStore.java").write_text(store_source)

    replace_once(
        main_activity,
        '''    private EditText voiceResponsePhrase;\n    private TextView voiceResponseSummary;''',
        '''    private EditText voiceResponsePhrase;\n    private TextView voiceResponseSummary;\n    private EditText easterEggPhrase;\n    private EditText easterEggReply;\n    private TextView easterEggSummary;''',
        "Easter egg UI fields",
    )

    marker = '''        TextView voiceResponseTitle = new TextView(this);'''
    ui = '''        TextView easterEggTitle = new TextView(this);\n        easterEggTitle.setText("Sage Easter eggs");\n        easterEggTitle.setTextSize(23);\n        easterEggTitle.setTextColor(Color.rgb(31, 41, 55));\n        easterEggTitle.setPadding(4, 18, 4, 4);\n        root.addView(easterEggTitle, spaced());\n\n        TextView easterEggHelp = new TextView(this);\n        easterEggHelp.setText("Add a phrase and exactly what Sage should say back. This is normal Sage personality, not a mode or privileged action. Red Queen mode remains reserved for the elevated workspace.");\n        easterEggHelp.setTextSize(15);\n        easterEggHelp.setTextColor(Color.DKGRAY);\n        easterEggHelp.setPadding(8, 2, 8, 8);\n        root.addView(easterEggHelp, matchWrap());\n\n        easterEggPhrase = new EditText(this);\n        easterEggPhrase.setHint("Phrase, for example: who's the boss");\n        easterEggPhrase.setSingleLine(false);\n        easterEggPhrase.setTextSize(17);\n        root.addView(easterEggPhrase, spacedSmall());\n\n        easterEggReply = new EditText(this);\n        easterEggReply.setHint("Sage's exact reply");\n        easterEggReply.setSingleLine(false);\n        easterEggReply.setTextSize(17);\n        root.addView(easterEggReply, spacedSmall());\n\n        Button saveEasterEgg = makeButton("Save Easter egg");\n        saveEasterEgg.setOnClickListener(v -> saveEasterEgg());\n        root.addView(saveEasterEgg, spacedSmall());\n\n        Button removeEasterEgg = makeButton("Remove typed Easter egg");\n        removeEasterEgg.setOnClickListener(v -> removeEasterEgg());\n        root.addView(removeEasterEgg, spacedSmall());\n\n        easterEggSummary = new TextView(this);\n        easterEggSummary.setTextSize(15);\n        easterEggSummary.setTextColor(Color.rgb(55, 65, 81));\n        easterEggSummary.setPadding(8, 10, 8, 4);\n        root.addView(easterEggSummary, matchWrap());\n        refreshEasterEggSummary();\n\n'''
    replace_once(main_activity, marker, ui + marker, "Easter egg controls")

    methods_marker = '''    private void chooseVoiceResponseFile() {'''
    methods = '''    private void saveEasterEgg() {\n        String problem = SageEasterEggStore.save(\n                this,\n                easterEggPhrase.getText().toString(),\n                easterEggReply.getText().toString()\n        );\n        if (!problem.isEmpty()) {\n            Toast.makeText(this, problem, Toast.LENGTH_LONG).show();\n            return;\n        }\n        refreshEasterEggSummary();\n        Toast.makeText(this, "Easter egg saved.", Toast.LENGTH_LONG).show();\n    }\n\n    private void removeEasterEgg() {\n        if (!SageEasterEggStore.remove(this, easterEggPhrase.getText().toString())) {\n            Toast.makeText(this, "I could not find that saved Easter egg.", Toast.LENGTH_LONG).show();\n            return;\n        }\n        refreshEasterEggSummary();\n        Toast.makeText(this, "Easter egg removed.", Toast.LENGTH_LONG).show();\n    }\n\n    private void refreshEasterEggSummary() {\n        if (easterEggSummary == null) return;\n        java.util.List<SageEasterEggStore.Entry> entries = SageEasterEggStore.list(this);\n        StringBuilder text = new StringBuilder("Saved: ").append(entries.size())\n                .append(entries.size() == 1 ? " Easter egg" : " Easter eggs");\n        for (SageEasterEggStore.Entry entry : entries) {\n            text.append("\\n• ").append(entry.phrase).append(" → ").append(entry.response);\n        }\n        easterEggSummary.setText(text.toString());\n    }\n\n'''
    replace_once(main_activity, methods_marker, methods + methods_marker, "Easter egg UI methods")

    voice_text = voice.read_text()
    media_call = voice_text.find("SageMediaResponseStore.find(")
    if media_call < 0:
        raise SystemExit("Easter egg voice dispatch: existing media-response route not found")
    media_return = voice_text.find("return;", media_call)
    if media_return < 0:
        raise SystemExit("Easter egg voice dispatch: media-response return not found")
    media_close = voice_text.find("\n        }", media_return)
    if media_close < 0:
        raise SystemExit("Easter egg voice dispatch: media-response block end not found")
    insert_at = media_close + len("\n        }")
    voice_insert = '''\n        SageEasterEggStore.Entry easterEgg = SageEasterEggStore.find(this, cleaned);\n        if (easterEgg != null) {\n            SageDiagnostics.appendEvent(this, "EASTER EGG", easterEgg.phrase);\n            broadcastLine("Sage", easterEgg.response);\n            speak(easterEgg.response);\n            return;\n        }'''
    if "SageEasterEggStore.Entry easterEgg" in voice_text:
        raise SystemExit("Easter egg voice dispatch already present before Checkpoint 2")
    voice.write_text(voice_text[:insert_at] + voice_insert + voice_text[insert_at:])

    print("Applied Checkpoint 2: persistent normal-Sage Easter egg personality replies")


if __name__ == "__main__":
    main()
