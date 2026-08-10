#!/usr/bin/env python3
"""Apply the physical-tablet media boundary and installed TTS voice studio slice."""

from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor, found {count}: {old[:80]!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: media_voice_repair_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"

    engine = java / "SageCommandEngine.java"
    replace_once(engine, """        public final boolean matched;

        public Result(String message) {
            this(message, false, true, true);
        }

        public Result(String message, boolean stopListening) {
            this(message, stopListening, true, true);
        }

        public Result(String message, boolean stopListening, boolean speak) {
            this(message, stopListening, speak, true);
        }

        private Result(
                String message,
                boolean stopListening,
                boolean speak,
                boolean matched
        ) {
            this.message = message;
            this.stopListening = stopListening;
            this.speak = speak;
            this.matched = matched;
        }

        public static Result quiet(String message) {
            return new Result(message, false, false);
        }

        public static Result unmatched(String message) {
            return new Result(message, false, true, false);
        }
""", """        public final boolean matched;
        /** Close natural conversation after an external media action. */
        public final boolean freshWakeAfterAction;

        public Result(String message) {
            this(message, false, true, true, false);
        }

        public Result(String message, boolean stopListening) {
            this(message, stopListening, true, true, false);
        }

        public Result(String message, boolean stopListening, boolean speak) {
            this(message, stopListening, speak, true, false);
        }

        private Result(
                String message,
                boolean stopListening,
                boolean speak,
                boolean matched,
                boolean freshWakeAfterAction
        ) {
            this.message = message;
            this.stopListening = stopListening;
            this.speak = speak;
            this.matched = matched;
            this.freshWakeAfterAction = freshWakeAfterAction;
        }

        public static Result quiet(String message) {
            return new Result(message, false, false, true, false);
        }

        public static Result media(String message) {
            return new Result(message, false, true, true, true);
        }

        public static Result quietMedia(String message) {
            return new Result(message, false, false, true, true);
        }

        public static Result unmatched(String message) {
            return new Result(message, false, true, false, false);
        }
""")
    replace_once(engine, """            return surprise.quiet?Result.quiet(surprise.message):new Result(surprise.message);
""", """            if (surprise.opened || surprise.pending) {
                return surprise.quiet ? Result.quietMedia(surprise.message)
                        : Result.media(surprise.message);
            }
            return surprise.quiet ? Result.quiet(surprise.message) : new Result(surprise.message);
""")
    replace_once(engine, """                result = verified ? Result.quiet("Playing.")
""", """                result = verified ? Result.quietMedia("Playing.")
""")
    replace_once(engine, """                result = verified ? Result.quiet("Opening the second item.")
""", """                result = verified ? (SageAccessibilityService.isMediaAppForeground()
                        ? Result.quietMedia("Opening the second item.")
                        : Result.quiet("Opening the second item."))
""")
    replace_once(engine, """                context.startActivity(deepLink);
                return new Result("Opening YouTube.");
""", """                context.startActivity(deepLink);
                return Result.media("Opening YouTube.");
""")
    replace_once(engine, """        if (launchPackage("com.google.android.youtube")) return new Result("Opening YouTube.");
        return openUrl("https://www.youtube.com", "Opening YouTube in the browser.");
""", """        if (launchPackage("com.google.android.youtube")) return Result.media("Opening YouTube.");
        return asMediaBoundary(openUrl("https://www.youtube.com", "Opening YouTube in the browser."));
""")
    replace_once(engine, """            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            return Result.quiet("Okay.");
""", """            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            return Result.quietMedia("Okay.");
""")
    replace_once(engine, """            mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
            return Result.quiet("Next.");
""", """            mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
            return Result.quietMedia("Next.");
""")
    replace_once(engine, """            mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            return Result.quiet("Previous.");
""", """            mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            return Result.quietMedia("Previous.");
""")
    replace_once(engine, """                return Result.quiet("Opening number " + numberedChoice + ".");
""", """                return SageAccessibilityService.isMediaAppForeground()
                        ? Result.quietMedia("Opening number " + numberedChoice + ".")
                        : Result.quiet("Opening number " + numberedChoice + ".");
""")
    replace_once(engine, """            return Result.quiet("Opening number " + number + ".");
""", """            return SageAccessibilityService.isMediaAppForeground()
                    ? Result.quietMedia("Opening number " + number + ".")
                    : Result.quiet("Opening number " + number + ".");
""")
    replace_once(engine, """        return SageAccessibilityService.tapText(target)
                ? Result.quiet("Tapped " + target + ".")
                : new Result("I could not find a visible button or word named " + target + ". Say show numbers if you want me to label the clickable things.");
""", """        return SageAccessibilityService.tapText(target)
                ? (SageAccessibilityService.isMediaAppForeground()
                ? Result.quietMedia("Tapped " + target + ".")
                : Result.quiet("Tapped " + target + "."))
                : new Result("I could not find a visible button or word named " + target + ". Say show numbers if you want me to label the clickable things.");
""")
    replace_once(engine, """            if (launchPackage("com.google.android.youtube")) {
                return new Result("Opening YouTube.");
            }
            return openUrl("https://www.youtube.com", "Opening YouTube.");
""", """            if (launchPackage("com.google.android.youtube")) {
                return Result.media("Opening YouTube.");
            }
            return asMediaBoundary(openUrl("https://www.youtube.com", "Opening YouTube."));
""")
    replace_once(engine, """        return openUrl(url, "Searching YouTube for " + q + ".");
    }

    private Result searchWeb(String query) {
""", """        return asMediaBoundary(openUrl(url, "Searching YouTube for " + q + "."));
    }

    private Result asMediaBoundary(Result result) {
        if (result == null || !result.matched
                || result.message.toLowerCase(Locale.US).startsWith("i could not")) {
            return result;
        }
        return result.speak ? Result.media(result.message) : Result.quietMedia(result.message);
    }

    private Result searchWeb(String query) {
""")

    access = java / "SageAccessibilityService.java"
    replace_once(access, """    public static boolean isReady() {
        return instance != null;
    }
""", """    public static boolean isReady() {
        return instance != null;
    }

    public static String activePackageName() {
        SageAccessibilityService service = instance;
        if (service == null) return "";
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return "";
        return root.getPackageName().toString();
    }

    public static boolean isMediaAppForeground() {
        String value = activePackageName().toLowerCase(Locale.US);
        return value.contains("youtube") || value.contains("spotify")
                || value.contains("music") || value.contains("podcast")
                || value.contains("vlc") || value.contains("netflix")
                || value.contains("hulu") || value.contains("disney")
                || value.contains("primevideo") || value.contains("twitch");
    }
""")

    bridge = java / "SageMediaSessionBridge.java"
    replace_once(bridge, """        MediaController controller = controllers.get(0);
        PlaybackState playback = controller.getPlaybackState();
""", """        MediaController controller = preferredController(controllers);
        PlaybackState playback = controller.getPlaybackState();
""")
    replace_once(bridge, """        return new Snapshot(controller.getPackageName(), title, stateName(code), actions,
                isPlayingState(code), "");
""", """        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        boolean musicActive = audio != null && audio.isMusicActive();
        return new Snapshot(controller.getPackageName(), title, stateName(code), actions,
                isPlayingState(code) || musicActive, "");
""")
    replace_once(bridge, """    boolean isPlaybackActive() {
        Snapshot current = snapshot();
        return current.activePlayback;
    }
""", """    boolean isPlaybackActive() {
        Snapshot current = snapshot();
        return current.activePlayback;
    }

    void logSnapshot(String reason) {
        Snapshot current = snapshot();
        SageDiagnostics.appendEvent(context, "MEDIA SNAPSHOT",
                "reason=" + reason + " package=" + current.packageName
                        + " state=" + current.state + " active=" + current.activePlayback
                        + " actions=" + current.actions + " title=" + current.title
                        + (current.error.isEmpty() ? "" : " note=" + current.error));
    }
""")
    replace_once(bridge, """            MediaController.TransportControls controls = controllers.get(0).getTransportControls();
""", """            MediaController.TransportControls controls = preferredController(controllers).getTransportControls();
""")
    replace_once(bridge, """            MediaController controller=active.get(0);PlaybackState state=controller.getPlaybackState();
""", """            MediaController controller=preferredController(active);PlaybackState state=controller.getPlaybackState();
""")
    replace_once(bridge, """    private List<MediaController> controllers() {
""", """    private static MediaController preferredController(List<MediaController> controllers) {
        for (MediaController controller : controllers) {
            PlaybackState state = controller.getPlaybackState();
            if (state != null && isPlayingState(state.getState())) return controller;
        }
        return controllers.get(0);
    }

    private List<MediaController> controllers() {
""")

    service = java / "SageVoiceService.java"
    replace_once(service, """            final boolean mediaActiveAtStart = mediaSessionBridge != null
                    && mediaSessionBridge.isPlaybackActive();
""", """            final boolean mediaActiveAtStart = (mediaSessionBridge != null
                    && mediaSessionBridge.isPlaybackActive())
                    || SageAccessibilityService.isMediaAppForeground();
            if (mediaSessionBridge != null) mediaSessionBridge.logSnapshot(
                    SageAccessibilityService.isMediaAppForeground()
                            ? "foreground_media_app" : "recognizer_start");
""")
    replace_once(service, """    private void deliverCommandResult(SageCommandEngine.Result result, String routeLabel) {
        if (commandEngine.isAwaitingFollowUp()) {
""", """    private void deliverCommandResult(SageCommandEngine.Result result, String routeLabel) {
        if (result.freshWakeAfterAction) {
            closeConversationWindow();
            commandEngine.cancelFollowUp();
            listenForCommandAfterSpeech = false;
            SageDiagnostics.appendEvent(this, "MEDIA BOUNDARY",
                    "fresh_wake_required=true result=" + result.message);
            if (mediaSessionBridge != null) {
                handler.postDelayed(() -> mediaSessionBridge.logSnapshot("after_media_action_350ms"), 350L);
                handler.postDelayed(() -> mediaSessionBridge.logSnapshot("after_media_action_1200ms"), 1200L);
            }
        }
        if (commandEngine.isAwaitingFollowUp()) {
""")
    replace_once(service, """        textToSpeech.setLanguage(defaultSpeechLocale);
        textToSpeech.setSpeechRate(1.0f);
""", """        SageVoiceProfile.Snapshot voiceSnapshot = SageVoiceProfile.apply(this, textToSpeech);
        defaultSpeechLocale = voiceSnapshot.locale;
        SageDiagnostics.appendEvent(this, "TTS PROFILE", voiceSnapshot.diagnostic());
""")
    replace_once(service, """        textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
""", """        if (!restoreDefaultSpeechLanguage) {
            SageVoiceProfile.apply(this, textToSpeech);
        }
        textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
""")
    replace_once(service, """        restoreDefaultSpeechLanguage = false;
        textToSpeech.setLanguage(defaultSpeechLocale);
""", """        restoreDefaultSpeechLanguage = false;
        SageVoiceProfile.apply(this, textToSpeech);
""")

    main_activity = java / "MainActivity.java"
    replace_once(main_activity, """        Button voiceSettings = makeButton("Choose Sage voice");
""", """        Button voiceSettings = makeButton("Voice Studio — choose & preview Sage");
""")
    replace_once(main_activity, """        voiceHelp.setText("Sage uses Android’s installed text-to-speech voices. Install the matching language voice if you want translated phrases pronounced aloud.");
""", """        voiceHelp.setText("Choose a real installed Android voice, preview it immediately, and save Sage’s rate and pitch. Voice Studio reports the exact engine, voice, quality, locale, and network requirement.");
""")
    replace_once(main_activity, """    private void openVoiceSettings() {
        Intent voiceSettings = new Intent("com.android.settings.TTS_SETTINGS");
        try {
            startActivity(voiceSettings);
            Toast.makeText(this, "Choose an installed text-to-speech engine and voice. Restart Sage afterward.", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Android could not open its voice settings on this tablet.", Toast.LENGTH_LONG).show();
        }
    }
""", """    private void openVoiceSettings() {
        startActivity(new Intent(this, SageVoiceSettingsActivity.class));
    }
""")

    manifest = root / "app/src/main/AndroidManifest.xml"
    replace_once(manifest, """        <activity android:name=".SageVoiceCommandTesterActivity" android:exported="false" />
""", """        <activity android:name=".SageVoiceCommandTesterActivity" android:exported="false" />
        <activity android:name=".SageVoiceSettingsActivity" android:exported="false" />
""")

    repair = java / "SageRepairManager.java"
    replace_once(repair, """        packet.put("wake_status", SageDiagnostics.buildSummary(context));
""", """        packet.put("wake_status", SageDiagnostics.buildSummary(context));
        packet.put("tts_profile", SageVoiceProfile.storedDiagnostic(context));
""")
    replace_once(repair, """        if (events.contains("ERROR")) values.put("Recent error diagnostics are present.");
""", """        if (events.contains("ERROR")) values.put("Recent error diagnostics are present.");
        if (events.contains("MEDIA SNAPSHOT") || events.contains("MEDIA BOUNDARY"))
            values.put("Media boundary and playback diagnostics are present.");
        if (events.contains("TTS PROFILE")) values.put("Exact TTS profile diagnostics are present.");
""")
    replace_once(repair, """                + "\\n## Brain\\n\\n" + sanitize(packet.optString("brain_status"))
                + "\\n\\n## Recent sanitized diagnostics\\n\\n```text\\n"
""", """                + "\\n## Brain\\n\\n" + sanitize(packet.optString("brain_status"))
                + "\\n\\n## Voice output\\n\\n" + sanitize(packet.optString("tts_profile"))
                + "\\n\\n## Recent sanitized diagnostics\\n\\n```text\\n"
""")

    (java / "SageVoiceProfile.java").write_text(VOICE_PROFILE, encoding="utf-8")
    (java / "SageVoiceSettingsActivity.java").write_text(VOICE_ACTIVITY, encoding="utf-8")


VOICE_PROFILE = r'''package com.pineapple.sage;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Persistent owner-selected Android TTS voice with honest engine/quality telemetry. */
final class SageVoiceProfile {
    private static final String PREFS = "sage_voice_profile";
    private static final String VOICE = "voice_name";
    private static final String RATE = "speech_rate";
    private static final String PITCH = "speech_pitch";
    private static final String PRESET = "preset";
    private static final String DIAGNOSTIC = "last_diagnostic";

    static final class Snapshot {
        final String engine;
        final String voiceName;
        final Locale locale;
        final int quality;
        final int latency;
        final boolean networkRequired;
        final float rate;
        final float pitch;
        final String preset;

        Snapshot(String engine, String voiceName, Locale locale, int quality, int latency,
                 boolean networkRequired, float rate, float pitch, String preset) {
            this.engine = engine;
            this.voiceName = voiceName;
            this.locale = locale == null ? Locale.getDefault() : locale;
            this.quality = quality;
            this.latency = latency;
            this.networkRequired = networkRequired;
            this.rate = rate;
            this.pitch = pitch;
            this.preset = preset;
        }

        String diagnostic() {
            return "engine=" + engine + " voice=" + voiceName + " locale="
                    + locale.toLanguageTag() + " quality=" + qualityName(quality)
                    + " latency=" + latencyName(latency) + " network_required="
                    + networkRequired + " rate=" + rate + " pitch=" + pitch
                    + " preset=" + preset;
        }
    }

    private SageVoiceProfile() { }

    static Snapshot apply(Context context, TextToSpeech tts) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        float rate = clamp(prefs.getFloat(RATE, 0.94f), 0.65f, 1.35f);
        float pitch = clamp(prefs.getFloat(PITCH, 1.0f), 0.75f, 1.25f);
        String preset = prefs.getString(PRESET, "NATURAL");
        String requested = prefs.getString(VOICE, "");
        Voice selected = findVoice(tts.getVoices(), requested);
        if (selected == null) selected = bestVoice(tts.getVoices(),
                "CHEEKY_BRITISH".equals(preset) ? Locale.UK : Locale.getDefault());
        if (selected != null) tts.setVoice(selected);
        else tts.setLanguage(Locale.getDefault());
        tts.setSpeechRate(rate);
        tts.setPitch(pitch);
        Voice active = tts.getVoice();
        Snapshot snapshot = active == null
                ? new Snapshot(tts.getDefaultEngine(), "default", Locale.getDefault(),
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, rate, pitch, preset)
                : new Snapshot(tts.getDefaultEngine(), active.getName(), active.getLocale(),
                active.getQuality(), active.getLatency(), active.isNetworkConnectionRequired(),
                rate, pitch, preset);
        prefs.edit().putString(DIAGNOSTIC, snapshot.diagnostic()).apply();
        return snapshot;
    }

    static List<Voice> voices(TextToSpeech tts) {
        Set<Voice> source = tts == null ? null : tts.getVoices();
        List<Voice> result = new ArrayList<>();
        if (source != null) result.addAll(source);
        Collections.sort(result, Comparator
                .comparing((Voice v) -> v.getLocale().getDisplayName())
                .thenComparing(Voice::getName));
        return result;
    }

    static String label(Voice voice) {
        return voice.getLocale().getDisplayName() + " — " + voice.getName()
                + " — " + qualityName(voice.getQuality())
                + (voice.isNetworkConnectionRequired() ? " — network" : " — offline");
    }

    static void save(Context context, Voice voice, float rate, float pitch, String preset) {
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putFloat(RATE, clamp(rate, 0.65f, 1.35f))
                .putFloat(PITCH, clamp(pitch, 0.75f, 1.25f))
                .putString(PRESET, preset == null ? "CUSTOM" : preset);
        if (voice != null) edit.putString(VOICE, voice.getName());
        edit.commit();
    }

    static String storedVoiceName(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(VOICE, "");
    }

    static float storedRate(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(RATE, 0.94f);
    }

    static float storedPitch(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(PITCH, 1.0f);
    }

    static String storedDiagnostic(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(DIAGNOSTIC, "TTS profile has not been applied yet.");
    }

    static Voice bestVoice(Set<Voice> voices, Locale locale) {
        if (voices == null || voices.isEmpty()) return null;
        Voice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Voice voice : voices) {
            int score = voice.getQuality() * 10 - voice.getLatency();
            if (!voice.isNetworkConnectionRequired()) score += 5000;
            if (sameLanguage(voice.getLocale(), locale)) score += 10000;
            if (voice.getLocale().equals(locale)) score += 2000;
            if (best == null || score > bestScore) {
                best = voice;
                bestScore = score;
            }
        }
        return best;
    }

    private static Voice findVoice(Set<Voice> voices, String name) {
        if (voices == null || name == null || name.isEmpty()) return null;
        for (Voice voice : voices) if (name.equals(voice.getName())) return voice;
        return null;
    }

    private static boolean sameLanguage(Locale left, Locale right) {
        return left != null && right != null && left.getLanguage().equals(right.getLanguage());
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static String qualityName(int value) {
        if (value >= Voice.QUALITY_VERY_HIGH) return "VERY_HIGH";
        if (value >= Voice.QUALITY_HIGH) return "HIGH";
        if (value <= Voice.QUALITY_VERY_LOW) return "VERY_LOW";
        if (value <= Voice.QUALITY_LOW) return "LOW";
        return "NORMAL";
    }

    static String latencyName(int value) {
        if (value >= Voice.LATENCY_VERY_HIGH) return "VERY_HIGH";
        if (value >= Voice.LATENCY_HIGH) return "HIGH";
        if (value <= Voice.LATENCY_VERY_LOW) return "VERY_LOW";
        if (value <= Voice.LATENCY_LOW) return "LOW";
        return "NORMAL";
    }
}
'''


VOICE_ACTIVITY = r'''package com.pineapple.sage;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Working owner voice selector and immediate TTS preview for installed Android voices. */
public class SageVoiceSettingsActivity extends Activity implements TextToSpeech.OnInitListener {
    private TextToSpeech tts;
    private Spinner voiceSpinner;
    private SeekBar rateBar;
    private SeekBar pitchBar;
    private TextView details;
    private final List<Voice> voices = new ArrayList<>();
    private long previewStartedAt;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Sage Voice Studio");
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 24, 24, 36);
        scroll.addView(content);

        TextView intro = new TextView(this);
        intro.setText("Choose from voices actually installed on this tablet. Preview is real Android TTS; quality and network requirements are reported honestly.");
        intro.setTextSize(17f);
        intro.setTextColor(Color.DKGRAY);
        content.addView(intro, matchWrap());

        voiceSpinner = new Spinner(this);
        content.addView(voiceSpinner, matchWrap());
        rateBar = addSlider(content, "Speech rate", SageVoiceProfile.storedRate(this), 0.65f, 1.35f);
        pitchBar = addSlider(content, "Pitch", SageVoiceProfile.storedPitch(this), 0.75f, 1.25f);

        Button preview = button("Preview selected voice");
        preview.setOnClickListener(v -> preview());
        content.addView(preview, matchWrap());
        Button save = button("Save for Sage");
        save.setOnClickListener(v -> save("CUSTOM", true));
        content.addView(save, matchWrap());
        Button natural = button("Natural preset");
        natural.setOnClickListener(v -> preset("NATURAL", Locale.getDefault(), 0.94f, 1.0f));
        content.addView(natural, matchWrap());
        Button british = button("Cheeky British preset");
        british.setOnClickListener(v -> preset("CHEEKY_BRITISH", Locale.UK, 0.96f, 1.02f));
        content.addView(british, matchWrap());
        Button clear = button("Clear and steady preset");
        clear.setOnClickListener(v -> preset("CLEAR", Locale.getDefault(), 0.86f, 1.0f));
        content.addView(clear, matchWrap());
        Button androidSettings = button("Install or manage Android voices");
        androidSettings.setOnClickListener(v -> openAndroidVoiceSettings());
        content.addView(androidSettings, matchWrap());

        details = new TextView(this);
        details.setText("Loading installed voices…");
        details.setTextSize(15f);
        details.setTextColor(Color.DKGRAY);
        details.setPadding(8, 20, 8, 8);
        content.addView(details, matchWrap());
        setContentView(scroll);
        tts = new TextToSpeech(this, this);
    }

    @Override public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            details.setText("Android TTS could not start. Use Manage Android voices to repair or install an engine.");
            return;
        }
        voices.clear();
        voices.addAll(SageVoiceProfile.voices(tts));
        List<String> labels = new ArrayList<>();
        for (Voice voice : voices) labels.add(SageVoiceProfile.label(voice));
        voiceSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        String stored = SageVoiceProfile.storedVoiceName(this);
        for (int i = 0; i < voices.size(); i++) {
            if (voices.get(i).getName().equals(stored)) voiceSpinner.setSelection(i);
        }
        SageVoiceProfile.Snapshot snapshot = SageVoiceProfile.apply(this, tts);
        details.setText(snapshot.diagnostic() + "\nInstalled voices: " + voices.size());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {
                long latency = System.currentTimeMillis() - previewStartedAt;
                runOnUiThread(() -> details.append("\nPreview first audio callback: " + latency + " ms"));
            }
            @Override public void onDone(String id) {
                runOnUiThread(() -> details.append("\nPreview completed."));
            }
            @Override public void onError(String id) {
                runOnUiThread(() -> details.append("\nPreview failed in Android TTS."));
            }
        });
    }

    private void preset(String name, Locale locale, float rate, float pitch) {
        Voice best = SageVoiceProfile.bestVoice(tts == null ? null : tts.getVoices(), locale);
        if (best != null) {
            int index = voices.indexOf(best);
            if (index >= 0) voiceSpinner.setSelection(index);
        }
        rateBar.setProgress(toProgress(rate, 0.65f, 1.35f));
        pitchBar.setProgress(toProgress(pitch, 0.75f, 1.25f));
        save(name, false);
        preview();
    }

    private void save(String preset, boolean notify) {
        Voice selected = selectedVoice();
        SageVoiceProfile.save(this, selected, rate(), pitch(), preset);
        if (tts != null) details.setText(SageVoiceProfile.apply(this, tts).diagnostic()
                + "\nSaved. Sage applies this profile before her next spoken response.");
        SageDiagnostics.appendEvent(this, "TTS PROFILE",
                "owner_saved=true " + SageVoiceProfile.storedDiagnostic(this));
        if (notify) Toast.makeText(this, "Sage voice saved.", Toast.LENGTH_SHORT).show();
    }

    private void preview() {
        if (tts == null) return;
        save("CUSTOM", false);
        previewStartedAt = System.currentTimeMillis();
        tts.speak("All right. This is Sage, and yes, I can sound less robotic.",
                TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
    }

    private Voice selectedVoice() {
        int index = voiceSpinner.getSelectedItemPosition();
        return index >= 0 && index < voices.size() ? voices.get(index) : null;
    }

    private float rate() { return fromProgress(rateBar.getProgress(), 0.65f, 1.35f); }
    private float pitch() { return fromProgress(pitchBar.getProgress(), 0.75f, 1.25f); }

    private SeekBar addSlider(LinearLayout parent, String label, float value, float min, float max) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(16f);
        title.setPadding(4, 18, 4, 0);
        parent.addView(title, matchWrap());
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(toProgress(value, min, max));
        parent.addView(bar, matchWrap());
        return bar;
    }

    private static int toProgress(float value, float min, float max) {
        return Math.round((Math.max(min, Math.min(max, value)) - min) * 100f / (max - min));
    }
    private static float fromProgress(int progress, float min, float max) {
        return min + (max - min) * progress / 100f;
    }
    private Button button(String text) {
        Button value = new Button(this);
        value.setText(text);
        value.setAllCaps(false);
        value.setMinHeight(56);
        return value;
    }
    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        value.setMargins(0, 10, 0, 0);
        return value;
    }
    private void openAndroidVoiceSettings() {
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (Exception error) {
            Toast.makeText(this, "Android voice settings are unavailable.", Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
'''


if __name__ == "__main__":
    main()
