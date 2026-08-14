#!/usr/bin/env python3
"""Make Sage Voice Studio simpler and choose the strongest installed natural voice by default."""
from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: natural_voice_v1_31.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    profile = java / "SageVoiceProfile.java"
    activity = java / "SageVoiceSettingsActivity.java"
    if not profile.is_file() or not activity.is_file():
        raise SystemExit("Voice Studio reconstructed source is missing")

    replace_once(profile,
'''        float rate = clamp(prefs.getFloat(RATE, 0.94f), 0.65f, 1.35f);
        float pitch = clamp(prefs.getFloat(PITCH, 1.0f), 0.75f, 1.25f);
        String preset = prefs.getString(PRESET, "NATURAL");
''',
'''        float rate = clamp(prefs.getFloat(RATE, 0.90f), 0.65f, 1.35f);
        float pitch = clamp(prefs.getFloat(PITCH, 0.98f), 0.75f, 1.25f);
        String preset = prefs.getString(PRESET, "SAGE_NATURAL");
''', "natural voice defaults")

    replace_once(profile,
'''        if (selected == null) selected = bestVoice(tts.getVoices(),
                "CHEEKY_BRITISH".equals(preset) ? Locale.UK : Locale.getDefault());
''',
'''        if (selected == null) selected = bestVoice(tts.getVoices(), preferredLocale(preset));
''', "recommended locale route")

    replace_once(profile,
'''    static float storedRate(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(RATE, 0.94f);
    }

    static float storedPitch(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(PITCH, 1.0f);
    }
''',
'''    static float storedRate(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(RATE, 0.90f);
    }

    static float storedPitch(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(PITCH, 0.98f);
    }

    static Voice recommendedVoice(TextToSpeech tts) {
        return bestVoice(tts == null ? null : tts.getVoices(), Locale.UK);
    }

    private static Locale preferredLocale(String preset) {
        if (preset == null || preset.isEmpty() || "SAGE_NATURAL".equals(preset)
                || "CHEEKY_BRITISH".equals(preset) || "LESS_ROBOTIC".equals(preset)) {
            return Locale.UK;
        }
        return Locale.getDefault();
    }
''', "stored defaults and recommendation")

    replace_once(activity,
'''        intro.setText("Choose from voices actually installed on this tablet. Preview is real Android TTS; quality and network requirements are reported honestly.");
''',
'''        intro.setText("Sage ranks voices actually installed on this tablet, preferring strong offline English voices and British character when the installed quality supports it. You can still choose any voice and tune rate or pitch yourself.");
''', "Voice Studio intro")

    replace_once(activity,
'''        Button repair = button("Fix robotic voice — preview & save");
        repair.setOnClickListener(v -> preset("LESS_ROBOTIC", Locale.UK, 0.88f, 0.96f));
        content.addView(repair, matchWrap());
        Button preview = button("Preview selected voice");
        preview.setOnClickListener(v -> preview());
        content.addView(preview, matchWrap());
        Button save = button("Save for Sage");
        save.setOnClickListener(v -> save("CUSTOM", true));
        content.addView(save, matchWrap());
        Button natural = button("Natural preset");
        natural.setOnClickListener(v -> preset("NATURAL", Locale.getDefault(), 0.90f, 0.98f));
        content.addView(natural, matchWrap());
        Button british = button("Cheeky British preset");
        british.setOnClickListener(v -> preset("CHEEKY_BRITISH", Locale.UK, 0.92f, 0.98f));
        content.addView(british, matchWrap());
        Button clear = button("Clear and steady preset");
        clear.setOnClickListener(v -> preset("CLEAR", Locale.getDefault(), 0.86f, 1.0f));
        content.addView(clear, matchWrap());
''',
'''        Button natural = button("Make Sage sound natural");
        natural.setOnClickListener(v -> useRecommendedVoice());
        content.addView(natural, matchWrap());
        Button preview = button("Preview selected voice");
        preview.setOnClickListener(v -> preview());
        content.addView(preview, matchWrap());
        Button save = button("Save this voice for Sage");
        save.setOnClickListener(v -> save("CUSTOM", true));
        content.addView(save, matchWrap());
''', "consolidated voice controls")

    replace_once(activity,
'''        String stored = SageVoiceProfile.storedVoiceName(this);
        for (int i = 0; i < voices.size(); i++) {
            if (voices.get(i).getName().equals(stored)) voiceSpinner.setSelection(i);
        }
        SageVoiceProfile.Snapshot snapshot = SageVoiceProfile.apply(this, tts);
''',
'''        String stored = SageVoiceProfile.storedVoiceName(this);
        Voice recommendation = SageVoiceProfile.recommendedVoice(tts);
        for (int i = 0; i < voices.size(); i++) {
            if ((!stored.isEmpty() && voices.get(i).getName().equals(stored))
                    || (stored.isEmpty() && recommendation != null
                    && voices.get(i).getName().equals(recommendation.getName()))) {
                voiceSpinner.setSelection(i);
                break;
            }
        }
        SageVoiceProfile.Snapshot snapshot = SageVoiceProfile.apply(this, tts);
''', "recommended spinner selection")

    preset_anchor = '''    private void preset(String name, Locale locale, float rate, float pitch) {
'''
    helper = r'''    private void useRecommendedVoice() {
        Voice best = SageVoiceProfile.recommendedVoice(tts);
        if (best != null) {
            int index = voices.indexOf(best);
            if (index >= 0) voiceSpinner.setSelection(index);
        }
        rateBar.setProgress(toProgress(0.90f, 0.65f, 1.35f));
        pitchBar.setProgress(toProgress(0.98f, 0.75f, 1.25f));
        updateControlValues();
        save("SAGE_NATURAL", false);
        preview();
        if (details != null) details.append("\nAuto-picked Sage's strongest installed natural voice. Manual controls remain available.");
    }

'''
    replace_once(activity, preset_anchor, helper + preset_anchor, "natural voice helper")

    profile_text = profile.read_text(encoding="utf-8")
    activity_text = activity.read_text(encoding="utf-8")
    required = (
        "recommendedVoice(TextToSpeech tts)", "Locale.UK", "SAGE_NATURAL",
        "Make Sage sound natural", "Save this voice for Sage", "Preview selected voice",
        "updateControlValues()", "Installed voices:",
    )
    combined = profile_text + activity_text
    for marker in required:
        if marker not in combined:
            raise SystemExit("missing natural-voice marker: " + marker)
    for removed in ("Fix robotic voice — preview & save", "Natural preset",
                    "Cheeky British preset", "Clear and steady preset"):
        if removed in activity_text:
            raise SystemExit("old overlapping Voice Studio control remains: " + removed)
    preview = activity_text.split("private void preview()", 1)[1].split("private Voice selectedVoice()", 1)[0]
    if 'save("CUSTOM"' in preview:
        raise SystemExit("preview must not silently persist a voice")
    print("Applied Sage 1.31 natural voice auto-ranking and consolidated Voice Studio controls")


if __name__ == "__main__":
    main()
