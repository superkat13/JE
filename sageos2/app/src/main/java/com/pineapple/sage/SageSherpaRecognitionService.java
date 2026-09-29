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

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerKt;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.pineapple.sageos2.speech.CommandEndpointPolicy;

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
    /** Why the last turn stopped: endpoint | budget | cancelled. Carries no audio or text. */
    private static volatile String lastCompletion = "";

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
            return "recognizer warm" + (lastReadyLatencyMs >= 0L ? " in " + lastReadyLatencyMs + "ms" : "")
                    + (lastCompletion.isEmpty() ? "" : ", last turn ended by " + lastCompletion);
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
        boolean endpointReached = false;
        boolean speechBegan = false;
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
            int peakAbs = 0;
            long totalSamples = 0L;
            String lastText = "";
            String finalText = "";
            // Diagnostic-only counters. They exist to separate "the decoder never produced text"
            // from "the decoder produced text that was dropped before inputFinished()".
            int chunkIndex = 0;
            int lastRead = 0;
            int decodeSteps = 0;
            int nonEmptyTextChunks = 0;
            int runningMeanAbs = 0;
            long speechBeganAtMs = -1L;
            while (!stopRequested.get()
                    && System.currentTimeMillis() - started < MAX_UTTERANCE_MS) {
                int count = audio.read(pcm, 0, pcm.length);
                if (count < 0) throw new IllegalStateException("AudioRecord read failed: " + count);
                if (count == 0) continue;
                chunkIndex++;
                lastRead = count;
                totalSamples += count;
                int chunkPeakAbs = peakAbsolute(pcm, count);
                int chunkMeanAbs = meanAbsolute(pcm, count);
                peakAbs = Math.max(peakAbs, chunkPeakAbs);
                runningMeanAbs = (int) (((long) runningMeanAbs * (totalSamples - count)
                        + (long) chunkMeanAbs * count) / Math.max(1, totalSamples));
                // Diagnostic-only. The previous onset threshold was fitted to a subsampled mean that
                // was never recorded, which is why it could not be recalibrated when it proved wrong
                // on the device. Logging the running mean and the chunk peak makes a real fit possible.
                emitRms(callback, rmsDb(pcm, count));
                if (!speechBegan
                        && CommandEndpointPolicy.hasSpeechEnergy(peakAbs)) {
                    speechBegan = true;
                    speechBeganAtMs = System.currentTimeMillis() - started;
                    Log.i(DIAG_TAG, "onset[latched] chunk=" + chunkIndex
                            + " audioMs=" + (totalSamples * 1000L / SAMPLE_RATE)
                            + " chunkPeakAbs=" + chunkPeakAbs
                            + " chunkMeanAbs=" + chunkMeanAbs
                            + " runningMeanAbs=" + runningMeanAbs
                            + " turnPeakAbs=" + peakAbs);
                    emitBeginning(callback);
                }
                float[] samples = new float[count];
                for (int index = 0; index < count; index++) samples[index] = pcm[index] / 32768.0f;
                stream.acceptWaveform(samples, SAMPLE_RATE);
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream);
                    decodeSteps++;
                }
                OnlineRecognizerResult result = recognizer.getResult(stream);
                String text = clean(result == null ? "" : result.getText());
                if (!text.isEmpty()) nonEmptyTextChunks++;
                if (!text.isEmpty() && !text.equals(lastText)) {
                    lastText = text;
                    emitPartial(callback, resultBundle(text));
                }
                boolean endpointFlag = recognizer.isEndpoint(stream);
                CommandEndpointPolicy.ChunkAction action = CommandEndpointPolicy.onChunk(
                        endpointFlag, text, speechBegan,
                        totalSamples * 1000L / SAMPLE_RATE);
                if (action != CommandEndpointPolicy.ChunkAction.CONTINUE) {
                    // Capture the state the endpoint decision was made on, before anything is
                    // flushed. If text was empty here and stays empty after the drain below, the
                    // endpoint genuinely ended the turn with no decodable audio.
                    logTurnState("endpoint", action.name(), chunkIndex, lastRead, totalSamples,
                            decodeSteps, nonEmptyTextChunks, text.length(),
                            System.currentTimeMillis() - started, speechBegan, endpointFlag, false,
                            speechBeganAtMs, peakAbs, runningMeanAbs);
                    if (action == CommandEndpointPolicy.ChunkAction.FINISH_WITH_TEXT) finalText = text;
                    endpointReached = true;
                    break;
                }
            }
            // An explicit stop must not keep decoding or emit afterwards. The drain below only
            // re-derives the text the loop would have produced anyway, so bailing out here cannot
            // change the outcome of a turn the caller still wanted.
            if (stopRequested.get()) return;
            // Capture the state immediately before inputFinished(): this is the boundary where
            // "recognizer reset prematurely" and "final chunks not flushed" become distinguishable.
            // totalSamples here versus the endpoint log above shows whether any audio was read after
            // the endpoint fired; decodeSteps versus audioMs shows whether sherpa was keeping up.
            logTurnState("preInputFinished", endpointReached ? "endpoint" : "budget", chunkIndex,
                    lastRead, totalSamples, decodeSteps, nonEmptyTextChunks, lastText.length(),
                    System.currentTimeMillis() - started, speechBegan, false, false,
                    speechBeganAtMs, peakAbs, runningMeanAbs);
            stream.inputFinished();
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
                decodeSteps++;
            }
            OnlineRecognizerResult tail = recognizer.getResult(stream);
            String tailText = clean(tail == null ? "" : tail.getText());
            // Post-drain capture. tailText empty while lastText was non-empty would mean the drain
            // destroyed text; both empty means the model decoded nothing for the whole window.
            logTurnState("postInputFinished", "drain", chunkIndex, lastRead, totalSamples,
                    decodeSteps, nonEmptyTextChunks, tailText.length(),
                    System.currentTimeMillis() - started, speechBegan, false, true,
                    speechBeganAtMs, peakAbs, runningMeanAbs);
            if (!tailText.isEmpty()) finalText = tailText;
            if (finalText.isEmpty()) finalText = lastText;
            stopMicrophone();
            if (activeCallback != callback) return;
            CommandEndpointPolicy.WindowEnd outcome = CommandEndpointPolicy.onWindowEnd(
                    stopRequested.get(), totalSamples, peakAbs, finalText);
            if (outcome == CommandEndpointPolicy.WindowEnd.SUPPRESSED) return;
            // Record the outcome before emitting. The emits below are synchronous binder calls, so
            // an observer woken by this turn can read runtimeDetail() immediately; writing
            // lastCompletion afterwards would briefly report the previous turn's value, which for
            // the endpoint check is a false positive. The finally block still recomputes the same
            // string for the early-return and exception paths.
            lastCompletion = endpointReached ? "endpoint" : (stopRequested.get() ? "cancelled" : "budget");
            emitEnd(callback);
            if (outcome == CommandEndpointPolicy.WindowEnd.AUDIO_ERROR) {
                markUnhealthy("microphone produced no usable PCM energy");
                emitError(callback, SpeechRecognizer.ERROR_AUDIO);
            } else if (outcome == CommandEndpointPolicy.WindowEnd.NO_MATCH) {
                emitError(callback, SpeechRecognizer.ERROR_NO_MATCH);
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
            // Only the worker that still owns the field may clear it. A turn that retires while a
            // successor is already running would otherwise null the successor's reference, the
            // ERROR_RECOGNIZER_BUSY guard in onStartListening would pass, and two AudioRecords
            // would contend for the microphone. SherpaWakeWordEngine already guards this way.
            if (worker == Thread.currentThread()) worker = null;
            lastCompletion = endpointReached ? "endpoint" : (stopRequested.get() ? "cancelled" : "budget");
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
        config.setEndpointConfig(commandEndpointConfig());
        logEndpointConfig("build", config.getEndpointConfig());
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

    /**
     * Endpoint rules for command speech, replacing sherpa's stock [OnlineRecognizerKt.getEndpointConfig].
     *
     * The stock config, read out of the AAR bytecode rather than assumed, is:
     * rule1 = (mustContainNonSilence=false, minTrailingSilence=2.4, minUtteranceLength=0)
     * rule2 = (mustContainNonSilence=true,  minTrailingSilence=1.4, minUtteranceLength=0)
     * rule3 = (mustContainNonSilence=false, minTrailingSilence=0,   minUtteranceLength=20)
     *
     * sherpa ORs the three rules, so the stock set is permissive in two ways that both truncate
     * commands on the L10_T05: rule1 can fire on 2.4s of pure silence with no speech at all, and
     * rule2 ends a turn after only 1.4s of trailing silence, which is shorter than a natural pause
     * inside a spoken command. Device evidence: every empty turn finished at 2,844 / 2,997 / 2,959 ms
     * after the recognizer was ready, while the one turn that produced text ran 5,573 ms.
     *
     * Every rule here therefore requires observed non-silence, the trailing silence is widened to
     * 2.6s, and a 1.0s floor plus a 15s hard cap (matching MAX_UTTERANCE_MS) bound the window. The
     * extra trailing silence costs up to ~1.2s of added latency on a genuine end-of-utterance; that
     * is the deliberate trade, because the alternative was returning nothing at all.
     */
    private static EndpointConfig commandEndpointConfig() {
        return new EndpointConfig(
                new EndpointRule(true, 2.6f, 1.0f),
                new EndpointRule(true, 2.6f, 1.0f),
                new EndpointRule(true, 0.0f, 15.0f));
    }

    private static final String DIAG_TAG = "SageSherpaDiag";

    /**
     * Diagnostic-only. Records endpoint rule parameters so a device log can prove which rule is
     * capable of ending a turn. Carries no audio and no transcript text.
     */
    private static void logEndpointConfig(String phase, EndpointConfig config) {
        if (config == null) {
            Log.w(DIAG_TAG, "endpointConfig[" + phase + "]=null");
            return;
        }
        StringBuilder out = new StringBuilder("endpointConfig[" + phase + "]");
        EndpointRule[] rules = {config.getRule1(), config.getRule2(), config.getRule3()};
        String[] names = {"rule1", "rule2", "rule3"};
        for (int index = 0; index < rules.length; index++) {
            EndpointRule rule = rules[index];
            if (rule == null) {
                out.append(' ').append(names[index]).append("=null");
                continue;
            }
            out.append(' ').append(names[index])
                    .append("(mustContainNonSilence=").append(rule.getMustContainNonSilence())
                    .append(",minTrailingSilence=").append(rule.getMinTrailingSilence())
                    .append(",minUtteranceLength=").append(rule.getMinUtteranceLength())
                    .append(')');
        }
        Log.i(DIAG_TAG, out.toString());
    }

    /**
     * Diagnostic-only. Emits the state of a turn at one of the three points where a decode can be
     * lost. Reports sizes, counts and elapsed times only: never samples, never transcript text, so
     * the diagnostic output stays free of both.
     */
    private static void logTurnState(String phase, String reason, int chunkIndex, int lastRead,
                                     long totalSamples, int decodeSteps, int nonEmptyTextChunks,
                                     int lastTextLength, long elapsedMs, boolean speechBegan,
                                     boolean endpointFlag, boolean inputFinished,
                                     long speechBeganAtMs, int peakAbs, int runningMeanAbs) {
        Log.i(DIAG_TAG, "turn[" + phase + "]"
                + " reason=" + reason
                + " chunk=" + chunkIndex
                + " lastReadSamples=" + lastRead
                + " totalSamples=" + totalSamples
                + " audioMs=" + (totalSamples * 1000L / SAMPLE_RATE)
                + " decodeSteps=" + decodeSteps
                + " nonEmptyTextChunks=" + nonEmptyTextChunks
                + " lastTextLen=" + lastTextLength
                + " speechBegan=" + speechBegan
                + " speechBeganAtMs=" + speechBeganAtMs
                + " peakAbs=" + peakAbs
                + " runningMeanAbs=" + runningMeanAbs
                + " isEndpoint=" + endpointFlag
                + " inputFinished=" + inputFinished
                + " elapsedMs=" + elapsedMs);
    }

    /**
     * The old 1-in-4 subsample mean-absolute gate is gone; the onset test is now peak-based in
     * {@link CommandEndpointPolicy#hasSpeechEnergy(int)}. This helper exists only to report the
     * mean in diagnostics, so the threshold can be fitted on recorded data rather than guessed.
     */
    private static int meanAbsolute(short[] pcm, int count) {
        if (count <= 0) return 0;
        long sum = 0L;
        for (int index = 0; index < count; index++) sum += Math.abs((int) pcm[index]);
        return (int) (sum / count);
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
