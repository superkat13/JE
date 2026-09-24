package com.pineapple.sage;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerKt;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Private, bounded streaming RecognitionService recovered from Sage 1.33.4.
 * It only reads the previously verified private model pack and never installs or mutates it.
 */
public final class SageSherpaRecognitionService extends RecognitionService {
    private static final String TAG = "SageCommandASR";
    private static final int SAMPLE_RATE = 16_000;
    private static final int FEATURE_DIM = 80;
    private static final int READ_SAMPLES = 1_600;
    private static final long MAX_UTTERANCE_MS = 15_000L;
    private static final long COOLDOWN_MS = 60_000L;
    private static volatile long unhealthyUntilMs;
    private static volatile String lastFailure = "";
    private static final Object RECOGNIZER_LOCK = new Object();
    private static volatile OnlineRecognizer sharedRecognizer;
    private static volatile boolean recognizerWarming;
    private static volatile long lastReadyLatencyMs = -1L;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Thread worker;
    private volatile AudioRecord microphone;
    private volatile Callback activeCallback;

    public static ComponentName primaryComponent(Context context) {
        return available(context)
                ? new ComponentName(context, SageSherpaRecognitionService.class) : null;
    }

    /**
     * Load the command recognizer before the owner needs it. The verified model is large enough on
     * the L10_T05 that constructing it on the first Talk press can consume most of an utterance.
     * Keeping one recognizer in-process lets each turn create only a cheap stream.
     */
    public static void prewarm(Context context) {
        if (context == null || !available(context) || sharedRecognizer != null || recognizerWarming) return;
        Context app = context.getApplicationContext();
        synchronized (RECOGNIZER_LOCK) {
            if (sharedRecognizer != null || recognizerWarming) return;
            recognizerWarming = true;
        }
        new Thread(() -> {
            long started = System.currentTimeMillis();
            try {
                obtainRecognizer(app);
                lastReadyLatencyMs = System.currentTimeMillis() - started;
                lastFailure = "";
            } catch (Throwable problem) {
                markUnhealthy("prewarm:" + safeProblem(problem));
            } finally {
                recognizerWarming = false;
            }
        }, "SageSherpaPrewarm").start();
    }

    public static boolean recognizerWarm() {
        return sharedRecognizer != null;
    }

    public static String runtimeDetail() {
        if (sharedRecognizer != null) {
            return "recognizer warm" + (lastReadyLatencyMs >= 0L ? " in " + lastReadyLatencyMs + "ms" : "");
        }
        if (recognizerWarming) return "recognizer warming";
        if (!lastFailure.isEmpty()) return "recognizer not warm: " + lastFailure;
        return "recognizer not warm";
    }

    public static boolean available(Context context) {
        if (context == null) return false;
        if (System.currentTimeMillis() < unhealthyUntilMs) return false;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            lastFailure = "microphone permission";
            return false;
        }
        try {
            boolean ready = SageSpeechBackendState.sherpaReady(context);
            if (!ready) lastFailure = "verified sherpa engine/model unavailable";
            return ready;
        } catch (Throwable problem) {
            lastFailure = safeProblem(problem);
            return false;
        }
    }

    @Override protected void onStartListening(android.content.Intent intent, Callback callback) {
        if (worker != null && worker.isAlive()) {
            emitError(callback, SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
            return;
        }
        if (!available(this)) {
            markUnhealthy(lastFailure);
            emitError(callback, SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
            return;
        }
        stopRequested.set(false);
        activeCallback = callback;
        worker = new Thread(() -> runRecognition(callback), "SageSherpaPrimaryASR");
        worker.start();
    }

    @Override protected void onStopListening(Callback callback) {
        stopRequested.set(true);
        stopMicrophone();
    }

    @Override protected void onCancel(Callback callback) {
        activeCallback = null;
        stopRequested.set(true);
        stopMicrophone();
    }

    @Override public void onDestroy() {
        activeCallback = null;
        stopRequested.set(true);
        stopMicrophone();
        super.onDestroy();
    }

    private void runRecognition(Callback callback) {
        OnlineRecognizer recognizer = null;
        OnlineStream stream = null;
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("RECORD_AUDIO was revoked before worker start");
            if (!SageSpeechBackendState.sherpaReady(this))
                throw new IllegalStateException("verified sherpa engine/model became unavailable");
            recognizer = obtainRecognizer(this);
            stream = recognizer.createStream("");
            AudioRecord audio = createMicrophone();
            microphone = audio;
            if (audio.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("AudioRecord not initialized");
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("microphone did not enter recording state");

            long started = System.currentTimeMillis();
            Bundle ready = new Bundle();
            ready.putString("sage_recognizer_backend", "sherpa-onnx");
            emitReady(callback, ready);
            short[] pcm = new short[READ_SAMPLES];
            boolean began = false;
            int peakAbs = 0;
            long totalSamples = 0L;
            String lastText = "";
            String finalText = "";
            while (!stopRequested.get()
                    && System.currentTimeMillis() - started < MAX_UTTERANCE_MS) {
                int count = audio.read(pcm, 0, pcm.length);
                if (count < 0) throw new IllegalStateException("AudioRecord read failed: " + count);
                if (count == 0) continue;
                totalSamples += count;
                peakAbs = Math.max(peakAbs, peakAbsolute(pcm, count));
                emitRms(callback, rmsDb(pcm, count));
                if (!began && hasSpeechEnergy(pcm, count)) {
                    began = true;
                    emitBeginning(callback);
                }
                float[] samples = new float[count];
                for (int index = 0; index < count; index++) samples[index] = pcm[index] / 32768.0f;
                stream.acceptWaveform(samples, SAMPLE_RATE);
                while (recognizer.isReady(stream)) recognizer.decode(stream);
                OnlineRecognizerResult result = recognizer.getResult(stream);
                String text = clean(result == null ? "" : result.getText());
                if (!text.isEmpty() && !text.equals(lastText)) {
                    lastText = text;
                    emitPartial(callback, resultBundle(text));
                }
                if (recognizer.isEndpoint(stream) && !text.isEmpty()) {
                    finalText = text;
                    break;
                }
            }
            stream.inputFinished();
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            OnlineRecognizerResult tail = recognizer.getResult(stream);
            String tailText = clean(tail == null ? "" : tail.getText());
            if (!tailText.isEmpty()) finalText = tailText;
            if (finalText.isEmpty()) finalText = lastText;
            stopMicrophone();
            if (activeCallback != callback) return;
            emitEnd(callback);
            if (finalText.isEmpty()) {
                if (totalSamples == 0L || peakAbs < 32) {
                    markUnhealthy("microphone produced no usable PCM energy");
                    emitError(callback, SpeechRecognizer.ERROR_AUDIO);
                } else {
                    emitError(callback, SpeechRecognizer.ERROR_NO_MATCH);
                }
            } else {
                lastFailure = "";
                emitResults(callback, resultBundle(finalText));
            }
        } catch (SecurityException problem) {
            fail(callback, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, problem);
        } catch (Throwable problem) {
            fail(callback, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, problem);
        } finally {
            stopMicrophone();
            if (stream != null) try { stream.release(); } catch (Throwable ignored) { }
            if (activeCallback == callback) activeCallback = null;
            worker = null;
        }
    }

    private static OnlineRecognizer obtainRecognizer(Context context) {
        OnlineRecognizer existing = sharedRecognizer;
        if (existing != null) return existing;
        synchronized (RECOGNIZER_LOCK) {
            existing = sharedRecognizer;
            if (existing != null) return existing;
            long started = System.currentTimeMillis();
            OnlineRecognizer built = buildRecognizer(context);
            sharedRecognizer = built;
            lastReadyLatencyMs = System.currentTimeMillis() - started;
            return built;
        }
    }

    private static OnlineRecognizer buildRecognizer(Context context) {
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

    private AudioRecord createMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("RECORD_AUDIO was revoked before microphone creation");
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("invalid microphone buffer: " + minimum);
        return new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minimum * 2, READ_SAMPLES * 4));
    }

    private void stopMicrophone() {
        AudioRecord value = microphone;
        microphone = null;
        if (value == null) return;
        try { if (value.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) value.stop(); }
        catch (RuntimeException ignored) { }
        try { value.release(); } catch (RuntimeException ignored) { }
    }

    private void fail(Callback callback, int code, Throwable problem) {
        String reason = safeProblem(problem);
        markUnhealthy(reason);
        Log.w(TAG, "Local command recognition failed; Android fallback may be used: " + reason);
        if (activeCallback == callback) emitError(callback, code);
    }

    private static void markUnhealthy(String reason) {
        lastFailure = clean(reason);
        unhealthyUntilMs = System.currentTimeMillis() + COOLDOWN_MS;
    }

    private static boolean hasSpeechEnergy(short[] pcm, int count) {
        long energy = 0L;
        for (int index = 0; index < count; index += 4) energy += Math.abs((int) pcm[index]);
        return energy / Math.max(1, count / 4) > 180L;
    }

    private static int peakAbsolute(short[] pcm, int count) {
        int peak = 0;
        for (int index = 0; index < count; index++)
            peak = Math.max(peak, Math.abs((int) pcm[index]));
        return peak;
    }

    private static float rmsDb(short[] pcm, int count) {
        if (count <= 0) return -120.0f;
        double sumSquares = 0.0;
        for (int index = 0; index < count; index++) {
            double value = pcm[index] / 32768.0;
            sumSquares += value * value;
        }
        double rms = Math.sqrt(sumSquares / count);
        return rms <= 0.000001 ? -120.0f : (float) (20.0 * Math.log10(rms));
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

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').trim().replaceAll("\\s+", " ");
    }

    private static String safeProblem(Throwable problem) {
        if (problem == null) return "unknown";
        String message = problem.getMessage();
        return problem.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ":" + clean(message));
    }

    private static void emitReady(Callback callback, Bundle value) {
        try { callback.readyForSpeech(value); } catch (RemoteException ignored) { }
    }
    private static void emitBeginning(Callback callback) {
        try { callback.beginningOfSpeech(); } catch (RemoteException ignored) { }
    }
    private static void emitPartial(Callback callback, Bundle value) {
        try { callback.partialResults(value); } catch (RemoteException ignored) { }
    }
    private static void emitRms(Callback callback, float value) {
        try { callback.rmsChanged(value); } catch (RemoteException ignored) { }
    }
    private static void emitEnd(Callback callback) {
        try { callback.endOfSpeech(); } catch (RemoteException ignored) { }
    }
    private static void emitResults(Callback callback, Bundle value) {
        try { callback.results(value); } catch (RemoteException ignored) { }
    }
    private static void emitError(Callback callback, int code) {
        try { callback.error(code); } catch (RemoteException ignored) { }
    }
}
