#!/usr/bin/env python3
"""Make owner confirmation intercept uncertain speech and add a one-tap natural voice repair."""

from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str) -> None:
    source = path.read_text(encoding="utf-8")
    if old not in source:
        raise SystemExit(f"expected source block missing in {path}: {old[:90]!r}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: owner_conversation_finish_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1]).resolve()
    java = root / "app/src/main/java/com/pineapple/sage"

    machine = java / "SageConversationStateMachine.java"
    replace_once(machine, """        CONVERSATION_LISTENING,
        CLOSED,
""", """        CONVERSATION_LISTENING,
        AWAITING_CONFIRMATION,
        CLOSED,
""")
    replace_once(machine, """    synchronized Decision finalTranscript(String rawCandidates, String selected,
                                          float confidence, long callbackGeneration,
                                          long nowMs, long deduplicationWindowMs,
                                          String recentTtsFingerprint,
                                          long recentTtsFinishedAtMs,
                                          long echoFingerprintWindowMs,
                                          boolean activeMedia) {
        String normalized = normalize(selected);
""", """    synchronized Decision finalTranscript(String rawCandidates, String selected,
                                          float confidence, long callbackGeneration,
                                          long nowMs, long deduplicationWindowMs,
                                          String recentTtsFingerprint,
                                          long recentTtsFinishedAtMs,
                                          long echoFingerprintWindowMs,
                                          boolean activeMedia) {
        return finalTranscript(rawCandidates, selected, confidence, callbackGeneration,
                nowMs, deduplicationWindowMs, recentTtsFingerprint, recentTtsFinishedAtMs,
                echoFingerprintWindowMs, activeMedia, false);
    }

    synchronized Decision finalTranscript(String rawCandidates, String selected,
                                          float confidence, long callbackGeneration,
                                          long nowMs, long deduplicationWindowMs,
                                          String recentTtsFingerprint,
                                          long recentTtsFinishedAtMs,
                                          long echoFingerprintWindowMs,
                                          boolean activeMedia,
                                          boolean requireOwnerConfirmation) {
        String normalized = normalize(selected);
""")
    replace_once(machine, """        if (activeMedia && !authorizedByWakeOrPushToTalk) {
            return rejectAndClose(rawCandidates, selected, confidence, normalized,
                    callbackGeneration, "active_media", "", "media_without_authorized_wake",
                    SpeechSource.ACTIVE_MEDIA, TranscriptType.COMMAND_FINAL, nowMs);
        }
        completed = true;
""", """        if (activeMedia && !authorizedByWakeOrPushToTalk) {
            return rejectAndClose(rawCandidates, selected, confidence, normalized,
                    callbackGeneration, "active_media", "", "media_without_authorized_wake",
                    SpeechSource.ACTIVE_MEDIA, TranscriptType.COMMAND_FINAL, nowMs);
        }
        if (requireOwnerConfirmation) {
            completed = true;
            state = State.AWAITING_CONFIRMATION;
            return reject(rawCandidates, selected, confidence, normalized,
                    callbackGeneration, "owner_confirmation", "confirmation",
                    "confirmation_required", SpeechSource.OWNER_SPEECH,
                    TranscriptType.COMMAND_FINAL, nowMs);
        }
        completed = true;
""")

    service = java / "SageVoiceService.java"
    replace_once(service, """                    SageConversationStateMachine.Decision decision = conversationMachine
                            .finalTranscript(finalChoices.toString(), candidate, confidence,
                                    sessionId, System.currentTimeMillis(), deduplicationWindowMs(),
                                    lastSpokenForEcho, lastSpokenFinishedAtMs,
                                    ECHO_TEXT_WINDOW_MS,
                                    mediaActiveAtStart || (mediaSessionBridge != null
                                            && mediaSessionBridge.isPlaybackActive()));
                    recordSpeechDecision(decision);
""", """                    boolean answeringConfirmation = confirmationLearning.pending(
                            System.currentTimeMillis()) != null;
                    boolean requireOwnerConfirmation = "low_confidence".equals(rejection)
                            && !candidate.isEmpty() && !answeringConfirmation;
                    SageConversationStateMachine.Decision decision = conversationMachine
                            .finalTranscript(finalChoices.toString(), candidate, confidence,
                                    sessionId, System.currentTimeMillis(), deduplicationWindowMs(),
                                    lastSpokenForEcho, lastSpokenFinishedAtMs,
                                    ECHO_TEXT_WINDOW_MS,
                                    mediaActiveAtStart || (mediaSessionBridge != null
                                            && mediaSessionBridge.isPlaybackActive()),
                                    requireOwnerConfirmation);
                    recordSpeechDecision(decision);
""")
    replace_once(service, """                    if (decision.executable) {
                        commandQualityRetries = 0;
""", """                    if ("confirmation_required".equals(decision.rejectionReason)) {
                        commandQualityRetries = 0;
                        askSpeechConfirmation(decision, finalChoices, confidence);
                    } else if (decision.executable) {
                        commandQualityRetries = 0;
""")
    replace_once(service, """                    } else if ("low_confidence".equals(rejection) && !candidate.isEmpty()) {
                        askSpeechConfirmation(decision, finalChoices, confidence);
                    } else if ("speaker_echo".equals(decision.rejectionReason)) {
""", """                    } else if ("speaker_echo".equals(decision.rejectionReason)) {
""")

    activity = java / "SageVoiceSettingsActivity.java"
    replace_once(activity, """    private TextView details;
    private final List<Voice> voices = new ArrayList<>();
""", """    private TextView details;
    private TextView controlValues;
    private final List<Voice> voices = new ArrayList<>();
""")
    replace_once(activity, """        rateBar = addSlider(content, "Speech rate", SageVoiceProfile.storedRate(this), 0.65f, 1.35f);
        pitchBar = addSlider(content, "Pitch", SageVoiceProfile.storedPitch(this), 0.75f, 1.25f);

        Button preview = button("Preview selected voice");
""", """        rateBar = addSlider(content, "Speech rate", SageVoiceProfile.storedRate(this), 0.65f, 1.35f);
        pitchBar = addSlider(content, "Pitch", SageVoiceProfile.storedPitch(this), 0.75f, 1.25f);
        controlValues = new TextView(this);
        controlValues.setTextSize(15f);
        controlValues.setTextColor(Color.DKGRAY);
        content.addView(controlValues, matchWrap());
        SeekBar.OnSeekBarChangeListener valuesListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { updateControlValues(); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        };
        rateBar.setOnSeekBarChangeListener(valuesListener);
        pitchBar.setOnSeekBarChangeListener(valuesListener);
        updateControlValues();

        Button repair = button("Fix robotic voice — preview & save");
        repair.setOnClickListener(v -> preset("LESS_ROBOTIC", Locale.UK, 0.88f, 0.96f));
        content.addView(repair, matchWrap());
        Button preview = button("Preview selected voice");
""")
    replace_once(activity, """        Button natural = button("Natural preset");
        natural.setOnClickListener(v -> preset("NATURAL", Locale.getDefault(), 0.94f, 1.0f));
""", """        Button natural = button("Natural preset");
        natural.setOnClickListener(v -> preset("NATURAL", Locale.getDefault(), 0.90f, 0.98f));
""")
    replace_once(activity, """        Button british = button("Cheeky British preset");
        british.setOnClickListener(v -> preset("CHEEKY_BRITISH", Locale.UK, 0.96f, 1.02f));
""", """        Button british = button("Cheeky British preset");
        british.setOnClickListener(v -> preset("CHEEKY_BRITISH", Locale.UK, 0.92f, 0.98f));
""")
    replace_once(activity, """        rateBar.setProgress(toProgress(rate, 0.65f, 1.35f));
        pitchBar.setProgress(toProgress(pitch, 0.75f, 1.25f));
        save(name, false);
        preview();
""", """        rateBar.setProgress(toProgress(rate, 0.65f, 1.35f));
        pitchBar.setProgress(toProgress(pitch, 0.75f, 1.25f));
        updateControlValues();
        save(name, false);
        preview();
""")
    replace_once(activity, """    private void preview() {
        if (tts == null) return;
        save("CUSTOM", false);
        previewStartedAt = System.currentTimeMillis();
""", """    private void preview() {
        if (tts == null) return;
        Voice selected = selectedVoice();
        if (selected != null) tts.setVoice(selected);
        tts.setSpeechRate(rate());
        tts.setPitch(pitch());
        updateControlValues();
        previewStartedAt = System.currentTimeMillis();
""")
    replace_once(activity, """    private float rate() { return fromProgress(rateBar.getProgress(), 0.65f, 1.35f); }
    private float pitch() { return fromProgress(pitchBar.getProgress(), 0.75f, 1.25f); }

    private SeekBar addSlider""", """    private float rate() { return fromProgress(rateBar.getProgress(), 0.65f, 1.35f); }
    private float pitch() { return fromProgress(pitchBar.getProgress(), 0.75f, 1.25f); }
    private void updateControlValues() {
        if (controlValues != null && rateBar != null && pitchBar != null) {
            controlValues.setText(String.format(Locale.US, "Rate %.2f  •  Pitch %.2f", rate(), pitch()));
        }
    }

    private SeekBar addSlider""")

    tone = java / "SageTonePolicy.java"
    replace_once(tone, """    static String response(Tone tone){return "Language setting: "+tone.name()+".";}
""", """    static String response(Tone tone){if(tone==Tone.UNFILTERED)return "Unfiltered mode. Hell yes—I can cuss with you.";if(tone==Tone.CASUAL)return "Casual mode. Sass is on.";return "Clean mode. I’ll keep it polite.";}
""")


if __name__ == "__main__":
    main()
