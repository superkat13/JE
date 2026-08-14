#!/usr/bin/env python3
from pathlib import Path
import re
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: sherpa_primary_v1_31.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    gradle = root / "app/build.gradle.kts"
    manifest = root / "app/src/main/AndroidManifest.xml"
    service = java / "SageVoiceService.java"

    gradle_text = gradle.read_text(encoding="utf-8")
    if 'sherpa-onnx-1.13.4.aar' not in gradle_text:
        match = re.search(r"dependencies\s*\{", gradle_text)
        if not match:
            raise SystemExit("app Gradle dependencies block missing")
        insertion = ('dependencies {\n'
                     '    implementation(files("libs/sherpa-onnx-1.13.4.aar"))\n'
                     '    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")')
        gradle_text = gradle_text[:match.start()] + insertion + gradle_text[match.end():]
        gradle.write_text(gradle_text, encoding="utf-8")

    replace_once(
        service,
        '''            try {\n                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);\n            } catch (RuntimeException error) {''',
        '''            try {\n                android.content.ComponentName sherpa =\n                        SageSherpaRecognitionService.primaryComponent(this);\n                if (sherpa != null) {\n                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this, sherpa);\n                    SageDiagnostics.appendEvent(this, "RECOGNIZER BACKEND",\n                            "backend=sherpa-onnx primary=true model="\n                                    + SageSherpaRecognitionService.MODEL_ID);\n                } else {\n                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);\n                    SageDiagnostics.appendEvent(this, "RECOGNIZER BACKEND",\n                            "backend=android fallback=true reason="\n                                    + SageSherpaRecognitionService.unavailableReason(this));\n                }\n            } catch (RuntimeException error) {''',
        "primary recognizer selection",
    )

    manifest_text = manifest.read_text(encoding="utf-8")
    service_decl = '''\n        <service\n            android:name=".SageSherpaRecognitionService"\n            android:exported="false">\n            <intent-filter>\n                <action android:name="android.speech.RecognitionService" />\n            </intent-filter>\n        </service>\n'''
    if '.SageSherpaRecognitionService' not in manifest_text:
        marker = '</application>'
        if manifest_text.count(marker) != 1:
            raise SystemExit("manifest application closing tag missing")
        manifest.write_text(manifest_text.replace(marker, service_decl + marker, 1), encoding="utf-8")

    (java / "SageSherpaRecognitionService.java").write_text(SHERPA_SERVICE, encoding="utf-8")
    print("Added sherpa-onnx primary command recognition with Android SpeechRecognizer fallback")


SHERPA_SERVICE = r'''package com.pineapple.sage;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sage-private streaming ASR service. It deliberately implements Android's RecognitionService
 * boundary so the existing Sage conversation state machine receives the same callback contract
 * regardless of whether local sherpa-onnx or Android SpeechRecognizer is providing the words.
 */
public final class SageSherpaRecognitionService extends RecognitionService {
    static final String MODEL_ID = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-int8";
    private static final String MODEL_DIR = "sherpa-asr";
    private static final String ENCODER = MODEL_DIR + "/encoder-epoch-99-avg-1.int8.onnx";
    private static final String DECODER = MODEL_DIR + "/decoder-epoch-99-avg-1.onnx";
    private static final String JOINER = MODEL_DIR + "/joiner-epoch-99-avg-1.int8.onnx";
    private static final String TOKENS = MODEL_DIR + "/tokens.txt";
    private static final int SAMPLE_RATE = 16000;
    private static final long MAX_UTTERANCE_MS = 14_000L;
    private static volatile long unhealthyUntilMs;
    private static volatile String lastFailure = "";

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Thread worker;
    private volatile AudioRecord audioRecord;
    private volatile OnlineRecognizer recognizer;
    private volatile Callback activeCallback;

    static ComponentName primaryComponent(Context context) {
        return available(context)
                ? new ComponentName(context, SageSherpaRecognitionService.class)
                : null;
    }

    static boolean available(Context context) {
        if (System.currentTimeMillis() < unhealthyUntilMs) return false;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return false;
        try {
            Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer", false,
                    context.getClassLoader());
            for (String path : new String[]{ENCODER, DECODER, JOINER, TOKENS}) {
                try (java.io.InputStream ignored = context.getAssets().open(path)) { }
            }
            return true;
        } catch (Throwable problem) {
            lastFailure = problem.getClass().getSimpleName();
            return false;
        }
    }

    static String unavailableReason(Context context) {
        if (System.currentTimeMillis() < unhealthyUntilMs) {
            return lastFailure.isEmpty() ? "sherpa cooldown" : lastFailure;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return "microphone permission";
        return lastFailure.isEmpty() ? "runtime or model unavailable" : lastFailure;
    }

    @Override protected void onStartListening(android.content.Intent intent, Callback callback) {
        if (worker != null && worker.isAlive()) {
            callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
            return;
        }
        if (!available(this)) {
            markUnhealthy("sherpa unavailable at start");
            callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
            return;
        }
        stopRequested.set(false);
        activeCallback = callback;
        worker = new Thread(() -> runRecognition(callback), "SageSherpaASR");
        worker.start();
    }

    @Override protected void onStopListening(Callback callback) {
        stopRequested.set(true);
        stopAudio();
    }

    @Override protected void onCancel(Callback callback) {
        activeCallback = null;
        stopRequested.set(true);
        stopAudio();
    }

    @Override public void onDestroy() {
        activeCallback = null;
        stopRequested.set(true);
        stopAudio();
        OnlineRecognizer value = recognizer;
        recognizer = null;
        if (value != null) {
            try { value.release(); } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }

    private void runRecognition(Callback callback) {
        OnlineStream stream = null;
        try {
            OnlineRecognizer local = ensureRecognizer();
            AudioRecord microphone = createMicrophone();
            audioRecord = microphone;
            if (microphone.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord not initialized");
            }
            microphone.startRecording();
            Bundle ready = new Bundle();
            ready.putString("sage_recognizer_backend", "sherpa-onnx");
            callback.readyForSpeech(ready);
            stream = local.createStream("");
            final int chunkSamples = 1600; // 100 ms at 16 kHz
            short[] pcm = new short[chunkSamples];
            long started = System.currentTimeMillis();
            boolean began = false;
            String lastPartial = "";
            String finalText = "";
            while (!stopRequested.get()
                    && System.currentTimeMillis() - started < MAX_UTTERANCE_MS) {
                int count = microphone.read(pcm, 0, pcm.length);
                if (count <= 0) continue;
                if (!began && hasSpeechEnergy(pcm, count)) {
                    began = true;
                    callback.beginningOfSpeech();
                }
                float[] samples = new float[count];
                for (int i = 0; i < count; i++) samples[i] = pcm[i] / 32768.0f;
                stream.acceptWaveform(samples, SAMPLE_RATE);
                while (local.isReady(stream)) local.decode(stream);
                OnlineRecognizerResult result = local.getResult(stream);
                String text = result == null || result.getText() == null
                        ? "" : result.getText().trim();
                if (!text.isEmpty() && !text.equals(lastPartial)) {
                    lastPartial = text;
                    callback.partialResults(resultBundle(text));
                }
                if (local.isEndpoint(stream) && !text.isEmpty()) {
                    finalText = text;
                    break;
                }
            }
            try { stream.inputFinished(); } catch (Throwable ignored) { }
            while (local.isReady(stream)) local.decode(stream);
            OnlineRecognizerResult tail = local.getResult(stream);
            if (tail != null && tail.getText() != null && !tail.getText().trim().isEmpty()) {
                finalText = tail.getText().trim();
            }
            stopAudio();
            if (activeCallback != callback) return;
            callback.endOfSpeech();
            if (!finalText.isEmpty()) {
                lastFailure = "";
                SageDiagnostics.appendEvent(this, "SHERPA ASR",
                        "outcome=success chars=" + finalText.length()
                                + " elapsed_ms=" + (System.currentTimeMillis() - started)
                                + " model=" + MODEL_ID);
                callback.results(resultBundle(finalText));
            } else if (!stopRequested.get()) {
                callback.error(SpeechRecognizer.ERROR_NO_MATCH);
            }
        } catch (SecurityException problem) {
            fail(callback, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, problem);
        } catch (Throwable problem) {
            fail(callback, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, problem);
        } finally {
            if (stream != null) {
                try { stream.release(); } catch (Throwable ignored) { }
            }
            stopAudio();
            if (activeCallback == callback) activeCallback = null;
            worker = null;
        }
    }

    private synchronized OnlineRecognizer ensureRecognizer() {
        if (recognizer != null) return recognizer;
        FeatureConfig feature = new FeatureConfig();
        feature.setSampleRate(SAMPLE_RATE);
        feature.setFeatureDim(80);
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(ENCODER);
        transducer.setDecoder(DECODER);
        transducer.setJoiner(JOINER);
        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(TOKENS);
        model.setNumThreads(2);
        model.setProvider("cpu");
        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        config.setFeatConfig(feature);
        config.setModelConfig(model);
        config.setEnableEndpoint(true);
        config.setDecodingMethod("greedy_search");
        config.setMaxActivePaths(4);
        recognizer = new OnlineRecognizer(getAssets(), config);
        SageDiagnostics.appendEvent(this, "SHERPA ASR",
                "outcome=model_ready runtime=1.13.4 model=" + MODEL_ID
                        + " threads=2 hotwords=false");
        return recognizer;
    }

    private AudioRecord createMicrophone() {
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(minimum * 2, 6400);
        return new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes);
    }

    private void stopAudio() {
        AudioRecord value = audioRecord;
        audioRecord = null;
        if (value == null) return;
        try { value.stop(); } catch (IllegalStateException ignored) { }
        value.release();
    }

    private void fail(Callback callback, int code, Throwable problem) {
        String reason = problem == null ? "unknown" : problem.getClass().getSimpleName()
                + ":" + String.valueOf(problem.getMessage());
        markUnhealthy(reason);
        SageDiagnostics.recordError(this, "Sherpa command recognition failed: " + reason);
        if (activeCallback == callback) callback.error(code);
    }

    private static void markUnhealthy(String reason) {
        lastFailure = reason == null ? "unknown" : reason.replace('\n', ' ').trim();
        unhealthyUntilMs = System.currentTimeMillis() + 60_000L;
    }

    private static boolean hasSpeechEnergy(short[] pcm, int count) {
        long energy = 0L;
        for (int i = 0; i < count; i += 4) energy += Math.abs((int) pcm[i]);
        return energy / Math.max(1, count / 4) > 180;
    }

    private static Bundle resultBundle(String text) {
        Bundle result = new Bundle();
        ArrayList<String> values = new ArrayList<>();
        values.add(text);
        result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, values);
        result.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, new float[]{-1.0f});
        result.putString("sage_recognizer_backend", "sherpa-onnx");
        return result;
    }
}
'''


if __name__ == "__main__":
    main()
