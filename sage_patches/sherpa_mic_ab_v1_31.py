#!/usr/bin/env python3
"""Add a Speech Lab-only sherpa microphone A/B test without changing Sage command routing."""
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
        raise SystemExit("usage: sherpa_mic_ab_v1_31.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    lab = java / "SageSpeechLabActivity.java"
    if not lab.is_file() or not (java / "SageSherpaModelManager.java").is_file():
        raise SystemExit("Sage model-pack Speech Lab source is missing")

    (java / "SageSherpaMicTester.java").write_text(MIC_TESTER, encoding="utf-8")

    replace_once(lab,
'''    private SageSherpaModelManager modelManager;
    private boolean repairRequested;
''',
'''    private SageSherpaModelManager modelManager;
    private SageSherpaMicTester micTester;
    private Button micAction;
    private TextView micStatus;
    private boolean repairRequested;
''', "Speech Lab mic fields")

    replace_once(lab,
'''        modelManager = new SageSherpaModelManager(this);
        ScrollView scroll = new ScrollView(this);
''',
'''        modelManager = new SageSherpaModelManager(this);
        micTester = new SageSherpaMicTester(this);
        ScrollView scroll = new ScrollView(this);
''', "Speech Lab mic tester init")

    replace_once(lab,
'''        content.addView(cancelModel, matchWrap());

        Button refresh = button("Refresh speech evidence");
''',
'''        content.addView(cancelModel, matchWrap());

        TextView micHeading = text(21f);
        micHeading.setText("Local microphone A / B test");
        content.addView(micHeading, matchWrap());
        micAction = button("Run local STT mic test");
        micAction.setOnClickListener(v -> runMicTest());
        content.addView(micAction, matchWrap());
        micStatus = text(14f);
        micStatus.setText("This test transcribes only. It cannot execute a Sage command.");
        micStatus.setTextIsSelectable(true);
        content.addView(micStatus, matchWrap());

        Button refresh = button("Refresh speech evidence");
''', "Speech Lab mic controls")

    replace_once(lab,
'''        if (modelManager == null || modelAction == null) return;
        boolean running = modelManager.isRunning();
        modelAction.setEnabled(!running);
''',
'''        if (modelManager == null || modelAction == null) return;
        boolean running = modelManager.isRunning();
        boolean micRunning = micTester != null && micTester.isRunning();
        modelAction.setEnabled(!running && !micRunning);
''', "model and mic mutual exclusion")

    replace_once(lab,
'''            if (modelStatus.getText() == null || modelStatus.getText().length() == 0) {
                modelStatus.setText(modelManager.isInstalled()
                        ? "The four-file local speech pack is installed. Verify it any time before the command-routing pass."
                        : "No verified local command-speech model pack is installed. Download starts only when you press Install.");
            }
        }
    }

    private void runModelAction() {
''',
'''            if (modelStatus.getText() == null || modelStatus.getText().length() == 0) {
                modelStatus.setText(modelManager.isInstalled()
                        ? "The four-file local speech pack is installed. Verify it any time before the command-routing pass."
                        : "No verified local command-speech model pack is installed. Download starts only when you press Install.");
            }
        }
        if (micAction != null && micTester != null) {
            micAction.setText(micRunning ? "Stop local STT test" : "Run local STT mic test");
            micAction.setEnabled(!running && (micRunning || SageSpeechBackendState.sherpaReady(this)));
            if (!micRunning && !SageSpeechBackendState.sherpaReady(this)
                    && (micStatus.getText() == null || micStatus.getText().length() == 0)) {
                micStatus.setText("Install and verify the local speech pack before running the sherpa microphone test.");
            }
        }
    }

    private void runModelAction() {
''', "Speech Lab mic readiness refresh")

    replace_once(lab,
'''        refresh();
    }

    private String filteredSpeechEvidence(String report) {
''',
'''        refresh();
    }

    private void runMicTest() {
        if (micTester == null || modelManager == null || modelManager.isRunning()) return;
        if (micTester.isRunning()) {
            micTester.stop();
            micStatus.setText("Stopping local STT test…");
            refresh();
            return;
        }
        if (!SageSpeechBackendState.sherpaReady(this)) {
            micStatus.setText("Sherpa is not physically ready. Install and verify the local speech pack first.");
            refresh();
            return;
        }
        micStatus.setText("Opening the microphone for isolated local transcription…");
        micTester.start(new SageSherpaMicTester.Listener() {
            @Override public void onStarted(String detail) {
                runOnUiThread(() -> { micStatus.setText(detail); refresh(); });
            }
            @Override public void onPartial(String text, long firstPartialLatencyMs) {
                runOnUiThread(() -> micStatus.setText("Partial: " + text
                        + "\\nFirst partial: " + firstPartialLatencyMs + " ms"));
            }
            @Override public void onComplete(String text, long firstPartialLatencyMs,
                                             long totalLatencyMs, long audioMs) {
                runOnUiThread(() -> {
                    micStatus.setText("Local final: " + (text.isEmpty() ? "[no speech decoded]" : text)
                            + "\\nFirst partial: " + (firstPartialLatencyMs < 0 ? "not observed" : firstPartialLatencyMs + " ms")
                            + "\\nTotal test: " + totalLatencyMs + " ms"
                            + "\\nAudio captured: " + audioMs + " ms"
                            + "\\nRoute: Speech Lab only; no command executed.");
                    refresh();
                });
            }
            @Override public void onError(String detail) {
                runOnUiThread(() -> { micStatus.setText(detail); refresh(); });
            }
        });
        refresh();
    }

    private String filteredSpeechEvidence(String report) {
''', "isolated mic test method")

    replace_once(lab,
'''    @Override protected void onDestroy() {
        if (modelManager != null && modelManager.isRunning()) modelManager.cancel();
        super.onDestroy();
    }
''',
'''    @Override protected void onDestroy() {
        if (micTester != null && micTester.isRunning()) micTester.stop();
        if (modelManager != null && modelManager.isRunning()) modelManager.cancel();
        super.onDestroy();
    }
''', "Speech Lab mic cleanup")

    combined = lab.read_text(encoding="utf-8") + (java / "SageSherpaMicTester.java").read_text(encoding="utf-8")
    for marker in (
        "Run local STT mic test",
        "Stop local STT test",
        "Local microphone A / B test",
        "Speech Lab only; no command executed.",
        "AudioRecord",
        "OnlineRecognizer",
        "OnlineRecognizerKt.getModelConfig(10)",
        "acceptWaveform",
        "isEndpoint",
        "first_partial_ms=",
        "route=speech_lab_only",
    ):
        if marker not in combined:
            raise SystemExit("missing sherpa mic A/B marker: " + marker)
    for forbidden in ("SageCommandEngine", "dispatchTypedCommand", "executeCommand("):
        if forbidden in (java / "SageSherpaMicTester.java").read_text(encoding="utf-8"):
            raise SystemExit("isolated sherpa mic tester must not execute commands: " + forbidden)
    print("Applied Sage 1.31 isolated sherpa microphone A/B test")


MIC_TESTER = r'''package com.pineapple.sage;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerKt;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated Speech Lab transcription test. Never dispatches commands or changes the primary recognizer. */
final class SageSherpaMicTester {
    interface Listener {
        void onStarted(String detail);
        void onPartial(String text, long firstPartialLatencyMs);
        void onComplete(String text, long firstPartialLatencyMs, long totalLatencyMs, long audioMs);
        void onError(String detail);
    }

    private static final int SAMPLE_RATE = 16_000;
    private static final int FEATURE_DIM = 80;
    private static final long MAX_TEST_MS = 15_000L;
    private static final int READ_SAMPLES = 1_600;

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile boolean running;

    SageSherpaMicTester(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isRunning() { return running; }

    void start(Listener listener) {
        if (running) return;
        if (!SageSpeechBackendState.sherpaReady(context)) {
            if (listener != null) listener.onError(
                    "Sherpa engine/model are not both verified yet. Install local command speech first.");
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (listener != null) listener.onError(
                    "Microphone permission is not granted to Sage. Open Voice & Wake permissions first.");
            return;
        }
        running = true;
        stopRequested.set(false);
        worker.execute(() -> runTest(listener));
    }

    void stop() { stopRequested.set(true); }

    private void runTest(Listener listener) {
        OnlineRecognizer recognizer = null;
        OnlineStream stream = null;
        AudioRecord audio = null;
        long started = SystemClock.elapsedRealtime();
        long firstPartial = -1L;
        long capturedSamples = 0L;
        String lastText = "";
        try {
            recognizer = buildRecognizer();
            stream = recognizer.createStream("");
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException(
                    "Android reported an invalid microphone buffer size: " + minimum);
            int bufferBytes = Math.max(minimum * 2, READ_SAMPLES * 4);
            audio = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException(
                        "Android could not initialize the Speech Lab microphone. Stop Sage background listening and retry the isolated test.");
            }
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException(
                        "Android did not give Speech Lab the microphone. Stop Sage background listening and retry.");
            }
            if (listener != null) listener.onStarted(
                    "Listening locally with sherpa-onnx. Speak one short command-like sentence. Nothing will execute.");
            short[] buffer = new short[READ_SAMPLES];
            while (!stopRequested.get()
                    && SystemClock.elapsedRealtime() - started < MAX_TEST_MS) {
                int count = audio.read(buffer, 0, buffer.length);
                if (count < 0) throw new IllegalStateException("AudioRecord read failed: " + count);
                if (count == 0) continue;
                capturedSamples += count;
                float[] samples = new float[count];
                for (int i = 0; i < count; i++) samples[i] = buffer[i] / 32768.0f;
                stream.acceptWaveform(samples, SAMPLE_RATE);
                while (recognizer.isReady(stream)) recognizer.decode(stream);
                OnlineRecognizerResult result = recognizer.getResult(stream);
                String text = clean(result == null ? "" : result.getText());
                if (!text.isEmpty() && !text.equals(lastText)) {
                    if (firstPartial < 0L) firstPartial = SystemClock.elapsedRealtime() - started;
                    lastText = text;
                    if (listener != null) listener.onPartial(text, firstPartial);
                }
                if (recognizer.isEndpoint(stream) && !text.isEmpty()) break;
            }
            stream.inputFinished();
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            OnlineRecognizerResult finalResult = recognizer.getResult(stream);
            String finalText = clean(finalResult == null ? "" : finalResult.getText());
            if (finalText.isEmpty()) finalText = lastText;
            long total = SystemClock.elapsedRealtime() - started;
            long audioMs = capturedSamples * 1000L / SAMPLE_RATE;
            SageDiagnostics.appendEvent(context, "SHERPA A/B",
                    "route=speech_lab_only executed=false final=" + sanitize(finalText)
                            + " first_partial_ms=" + firstPartial + " total_ms=" + total
                            + " audio_ms=" + audioMs + " endpoint=true");
            running = false;
            if (listener != null) listener.onComplete(finalText, firstPartial, total, audioMs);
        } catch (Throwable problem) {
            running = false;
            String detail = "Local STT test failed: " + safeProblem(problem);
            SageDiagnostics.recordError(context, detail);
            SageDiagnostics.appendEvent(context, "SHERPA A/B",
                    "route=speech_lab_only executed=false error=" + sanitize(detail));
            if (listener != null) listener.onError(detail);
        } finally {
            if (audio != null) {
                try { if (audio.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audio.stop(); }
                catch (RuntimeException ignored) { }
                try { audio.release(); } catch (RuntimeException ignored) { }
            }
            if (stream != null) try { stream.release(); } catch (Throwable ignored) { }
            if (recognizer != null) try { recognizer.release(); } catch (Throwable ignored) { }
            running = false;
        }
    }

    private OnlineRecognizer buildRecognizer() {
        File dir = SageSpeechBackendState.modelDirectory(context);
        OnlineModelConfig model = OnlineRecognizerKt.getModelConfig(10);
        if (model == null) model = new OnlineModelConfig();
        OnlineTransducerModelConfig transducer = model.getTransducer();
        if (transducer == null) transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(new File(dir, "encoder-epoch-99-avg-1.int8.onnx").getAbsolutePath());
        transducer.setDecoder(new File(dir, "decoder-epoch-99-avg-1.onnx").getAbsolutePath());
        transducer.setJoiner(new File(dir, "joiner-epoch-99-avg-1.int8.onnx").getAbsolutePath());
        model.setTransducer(transducer);
        model.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
        model.setNumThreads(2);
        model.setDebug(false);
        model.setProvider("cpu");
        model.setModelType("zipformer");

        FeatureConfig feature = new FeatureConfig();
        feature.setSampleRate(SAMPLE_RATE);
        feature.setFeatureDim(FEATURE_DIM);
        feature.setDither(0.0f);

        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        config.setFeatConfig(feature);
        config.setModelConfig(model);
        config.setEndpointConfig(OnlineRecognizerKt.getEndpointConfig());
        config.setEnableEndpoint(true);
        config.setDecodingMethod("greedy_search");
        config.setMaxActivePaths(4);
        return new OnlineRecognizer(null, config);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String sanitize(String value) {
        String clean = clean(value).replace('=', ':');
        return clean.length() <= 240 ? clean : clean.substring(0, 240);
    }

    private static String safeProblem(Throwable problem) {
        if (problem == null) return "unknown error";
        String message = problem.getMessage();
        return message == null || message.trim().isEmpty()
                ? problem.getClass().getSimpleName() : clean(message);
    }
}
'''


if __name__ == "__main__":
    main()
