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
import com.pineapple.sageos2.speech.CommandSpeechFailurePolicy;
import com.pineapple.sageos2.speech.CommandSpeechTurnOwnership;
import com.pineapple.sageos2.speech.CommandSpeechTurnState;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

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
    /**
     * How long a single failed turn keeps the command recognizer unavailable.
     *
     * This was 60,000 ms, which meant one quiet turn, one revoked-then-restored microphone
     * permission, or one transient mic error left Sage deaf to voice for a full minute while the
     * owner was still speaking to her. Nothing in the recognizer is expensive to rebuild:
     * [prewarm] already holds one [OnlineRecognizer] in process, and [obtainRecognizer] returns that
     * same instance rather than rebuilding. The cooldown only exists to avoid a hot retry loop
     * against a genuinely broken model or permission, which a couple of seconds already covers.
     */
    private static final long COOLDOWN_MS = 2_000L;
    /**
     * Reads discarded immediately after the microphone opens, to drop the wake phrase's tail.
     *
     * [READ_SAMPLES] is 1,600 samples at 16 kHz, so each read is 100 ms and four reads are 400 ms.
     * That is longer than the 200-300 ms a wake-spotter frame needs to drain on this device, and it
     * is short enough to sit well inside the 1.0 s endpoint floor this recognizer already enforces.
     */
    private static final int WAKE_TAIL_DISCARD_CHUNKS = 4;
    private static volatile long unhealthyUntilMs;
    private static volatile String lastFailure = "";
    private static final Object RECOGNIZER_LOCK = new Object();
    private static volatile OnlineRecognizer sharedRecognizer;
    private static volatile boolean recognizerWarming;
    private static volatile long lastReadyLatencyMs = -1L;
    /** Why the last turn stopped: endpoint | budget | cancelled. Carries no audio or text. */
    private static volatile String lastCompletion = "";
    /**
     * How many turns were refused the microphone after being admitted, and why the last one was.
     *
     * A refused capture is silent and opens no cooldown, which is correct, but it also makes a run
     * look exactly like a recognizer that failed for no reason. Counting it here is what lets the
     * diagnostic export separate "this device has a broken recognizer" from "a turn was superseded
     * while it was still setting up". Names only: no callback, no audio, no text.
     */
    private static volatile long captureRefusals;
    private static volatile String lastCaptureRefusal = "";

    private volatile Thread worker;
    /**
     * The live turn, or null while none is.
     *
     * Admission claims this and the microphone together, because they have to be won by the same
     * atomic step. A turn's capture claim lives on the turn itself, so retiring this reference is
     * what hands the device back; see [releaseCapture].
     */
    private final AtomicReference<CommandSpeechTurnState> liveTurn = new AtomicReference<>(null);
    private final CommandSpeechTurnOwnership turns = new CommandSpeechTurnOwnership();

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
                    + (lastCompletion.isEmpty() ? "" : ", last turn ended by " + lastCompletion)
                    + captureRefusalNote();
        }
        if (recognizerWarming) return "recognizer warming";
        if (!lastFailure.isEmpty()) return "recognizer not warm: " + lastFailure + captureRefusalNote();
        return "recognizer not warm" + captureRefusalNote();
    }

    /**
     * Reports turns that were refused the microphone, so an export distinguishes a superseded turn
     * from a genuine recognizer fault. Both look identical without it: neither reports an error and
     * neither opens a cooldown.
     */
    private static String captureRefusalNote() {
        if (captureRefusals <= 0L) return "";
        return ", capture refused " + captureRefusals + "x (" + lastCaptureRefusal + ")";
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

    /**
     * Admits a turn by installing it as the live turn, which is what wins the microphone.
     *
     * Only the live turn can hold the microphone, so installing this one is what takes the device,
     * and it is the same atomic step. Ownership is taken afterwards and can still fail: a previous
     * turn may have given the device back while it is still retiring and still owns its session. In
     * that case the device is handed straight back and the request is refused as busy, rather than
     * the previous turn's ownership being overwritten.
     *
     * No stop flag and no terminal-outcome flag is reset here. Both belong to the turn that was just
     * created, so there is nothing shared left for this admission to disturb. Resetting service-wide
     * flags is exactly what let a successor un-cancel its predecessor.
     */
    @Override protected void onStartListening(android.content.Intent intent, Callback callback) {
        // A voice-repair diagnostic capture requests this recognizer directly, after the wake engine
        // has been stopped and acknowledged, so it must not discard the wake tail below.
        boolean diagnosticCapture = intent != null
                && intent.getBooleanExtra(com.pineapple.sageos2.speech.SageSpeechIntents.EXTRA_DIAGNOSTIC_CAPTURE, false);
        // A normal recognizer intent must declare whether there was a wake-word handoff.
        // Unmarked/legacy intents retain the previous tail discard; diagnosed captures never do.
        boolean wakeTailPresent = intent == null || intent.getBooleanExtra(
                com.pineapple.sageos2.speech.SageSpeechIntents.EXTRA_WAKE_TAIL_PRESENT, true);
        CommandSpeechTurnState state = new CommandSpeechTurnState(callback, diagnosticCapture, wakeTailPresent);
        if (!claimDeviceFor(state)) {
            emitError(callback, SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
            return;
        }
        if (!available(this)) {
            retireTurn(state);
            markUnhealthy(lastFailure);
            emitError(callback, SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
            return;
        }
        if (!turns.begin(callback)) {
            retireTurn(state);
            emitError(callback, SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
            return;
        }
        Thread thread = new Thread(() -> runRecognition(state), "SageSherpaPrimaryASR");
        // Registered before it starts, so the claim can never look ownerless while it is running.
        state.setWorker(thread);
        worker = thread;
        thread.start();
    }

    /**
     * Installs [state] as the live turn, unless another turn is still holding the microphone.
     *
     * Admission follows the device rather than whether a worker thread is still alive. A turn that
     * has already given the microphone back no longer holds it, so the next request may take over
     * while that thread finishes unwinding; waiting for the thread to die instead made admission
     * depend on how quickly teardown happened to finish.
     *
     * A turn whose worker died while still holding the device is cleared first. Its own teardown
     * normally does this, so a holder with no live thread has no way left to do it, and without the
     * escape one dead worker would refuse every later request for the life of the service.
     */
    private boolean claimDeviceFor(CommandSpeechTurnState state) {
        CommandSpeechTurnState held = liveTurn.get();
        if (held == null) return liveTurn.compareAndSet(null, state);
        if (!held.getCapture().isHeld()) {
            // It gave the device back and is only still finishing. Taking its place is safe because
            // every teardown path releases by identity, so the retiring turn cannot clear this one.
            return liveTurn.compareAndSet(held, state);
        }
        Thread owner = held.getWorker();
        if (owner != null && !owner.isAlive() && liveTurn.compareAndSet(held, null)) {
            releaseCapture(held);
            return liveTurn.compareAndSet(null, state);
        }
        return false;
    }

    /**
     * Both platform callbacks below are guarded by turn ownership.
     *
     * RecognitionService delivers these per session across a binder, so the stop or cancel for a
     * turn that already reached a terminal outcome can arrive after the next turn has started.
     * Applying it unconditionally released the live turn's AudioRecord, raised its stop flag and
     * dropped its ownership, after which that turn's own terminal outcome was unreachable: it ended
     * with no results, no error and no end of speech. A late callback is now ignored. See
     * CommandSpeechTurnOwnership.
     */
    @Override protected void onStopListening(Callback callback) {
        // Stopping keeps the turn: the caller asked for the utterance to end, not for the session to
        // be abandoned, so the platform is still owed a terminal outcome and the turn stays live.
        if (!turns.requestStop(callback)) return;
        stopTurn(turnFor(callback));
        awaitRetiredWorker();
    }

    @Override protected void onCancel(Callback callback) {
        if (!turns.retire(callback)) return;
        CommandSpeechTurnState state = turnFor(callback);
        if (state != null) {
            state.requestStop();
            retireTurn(state);
        }
        awaitRetiredWorker();
    }

    @Override public void onDestroy() {
        turns.clear();
        CommandSpeechTurnState state = liveTurn.getAndSet(null);
        if (state != null) {
            state.requestStop();
            releaseCapture(state);
        }
        awaitRetiredWorker();
        super.onDestroy();
    }

    /**
     * The turn [callback] addresses, or null when it is not the live turn's.
     *
     * A late callback for a turn that already retired, or one whose successor has taken over, matches
     * nothing here and so cannot reach live state.
     */
    private CommandSpeechTurnState turnFor(Callback callback) {
        CommandSpeechTurnState state = liveTurn.get();
        return state != null && state.owns(callback) ? state : null;
    }

    /**
     * Stops [state]'s turn and gives the microphone back, leaving the turn live.
     *
     * The stop flag is raised on the turn itself, so a successor admitted afterwards cannot clear it
     * and cannot turn this turn's cancelled read back into a genuine fault. The microphone is
     * released through the turn's own claim, so a turn that has already been superseded releases
     * nothing and cannot take the device from the successor holding it.
     */
    private void stopTurn(CommandSpeechTurnState state) {
        if (state == null) return;
        state.requestStop();
        releaseCapture(state);
    }

    /**
     * Ends [state]'s turn: gives the microphone back and clears it as the live turn, but only while it
     * is still the live one. A turn that finishes after a successor took over releases nothing and
     * clears nothing, which is what keeps it from touching the turn now holding the device.
     */
    private void retireTurn(CommandSpeechTurnState state) {
        if (state == null) return;
        releaseCapture(state);
        liveTurn.compareAndSet(state, null);
    }

    /**
     * Hands this turn's microphone back, if it has one.
     *
     * The claim is the turn's own, so this cannot release a successor's AudioRecord: a successor can
     * only exist once the live turn changed hands, and its claim is a different object.
     */
    private void releaseCapture(CommandSpeechTurnState state) {
        releaseRecord(state.getCapture().release());
    }

    private static void releaseRecord(Object held) {
        if (held instanceof AudioRecord) releaseAudioRecord((AudioRecord) held);
    }

    private void runRecognition(CommandSpeechTurnState state) {
        Callback callback = (Callback) state.getCallback();
        OnlineRecognizer recognizer = null;
        OnlineStream stream = null;
        // Held outside the try so the finally block can release exactly this turn's microphone
        // rather than whatever the claim holds by then. See retireTurn.
        AudioRecord audio = null;
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
            audio = createMicrophone();
            // Atomic with cancellation. This is the point the cancelled-during-setup turn loses: it
            // was admitted, so its claim was pending, and a cancel that has already run emptied that
            // slot. It gives up the microphone it just opened instead of taking the device from the
            // successor that was admitted in the meantime.
            if (!claimMicrophone(state, audio)) {
                releaseAudioRecord(audio);
                return;
            }
            // Cancelled between the claim and here: the cancel already released this microphone, so
            // starting it would capture audio for a turn the caller walked away from. Bailing out
            // rather than relying on the released AudioRecord to throw keeps that path off the
            // failure reporter entirely.
            if (state.getStopped()) return;
            if (audio.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("AudioRecord not initialized");
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("microphone did not enter recording state");
            // The audio source can change input gain on this chipset, which moves the amplitude
            // distribution SPEECH_ONSET_PEAK_ABS is calibrated against. Recording which source a
            // turn actually used alongside the per-chunk peaks is what makes that checkable on the
            // device instead of a guess. Carries no audio and no text.
            Log.i(DIAG_TAG, "turn[audio] source=" + audioSourceName(COMMAND_AUDIO_SOURCE)
                    + " resolved=" + audioSourceName(audio.getAudioSource())
                    + " tailDiscardChunks=" + WAKE_TAIL_DISCARD_CHUNKS);

            // The wake engine's AudioRecord is released, then a new one is opened here, but the
            // platform audio path still holds the tail of whatever the wake spotter last decoded.
            // Feeding that tail to the command recognizer makes the first chunks of a turn the
            // residue of the owner's own wake phrase, which is consistent with the device trace
            // where onset latches as early as 600 ms and with the short one- and two-character
            // results. Discarding the first few reads removes it. This is deliberately not fed to
            // the recognizer at all, so it cannot consume the utterance budget.
            //
            // A diagnostic, manual Talk, or follow-up capture has no wake phrase to remove.
            // Their intents suppress this discard so the owner's very first audio frame is kept.
            // A wake-triggered command keeps the tail removal, and an unmarked legacy intent
            // retains the prior behaviour until the caller supplies explicit provenance.
            int tailDiscardChunks = state.tailDiscardChunks(WAKE_TAIL_DISCARD_CHUNKS);
            Log.i(DIAG_TAG, "turn[tailDiscard] diagnostic=" + state.getDiagnosticCapture()
                    + " wakeTailPresent=" + state.getWakeTailPresent()
                    + " chunks=" + tailDiscardChunks);
            short[] discard = new short[READ_SAMPLES];
            int discarded = 0;
            while (discarded < tailDiscardChunks && !state.getStopped()) {
                int dropped = audio.read(discard, 0, discard.length);
                if (dropped <= 0) break;
                discarded++;
            }

            long started = System.currentTimeMillis();
            // The first thing a turn tells the framework is that it is ready for speech, so a turn
            // that has been stopped or superseded between claiming the microphone and here must not
            // send it: the caller has already moved on and is waiting on a different turn.
            if (!ownsLiveTurn(state)) return;
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
            while (!state.getStopped()
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
                if (ownsLiveTurn(state)) emitRms(callback, rmsDb(pcm, count));
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
                    if (ownsLiveTurn(state)) emitBeginning(callback);
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
                    if (ownsLiveTurn(state)) emitPartial(callback, resultBundle(text));
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
            if (state.getStopped()) return;
            // A turn that lost ownership while it was draining is finished as far as the caller is
            // concerned. Checked again here because the drain is long enough for a cancel to land.
            if (!ownsLiveTurn(state)) return;
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
            // The microphone is handed back here, before the window outcome is emitted, so the
            // owner's next request is not refused while this turn finishes reporting. The turn
            // itself stays live: it is still the turn the platform is owed an outcome from, and a
            // stop arriving in this window must still be able to raise its stop flag.
            releaseCapture(state);
            if (!turns.owns(callback)) return;
            CommandEndpointPolicy.WindowEnd outcome = CommandEndpointPolicy.onWindowEnd(
                    state.getStopped(), totalSamples, peakAbs, finalText);
            if (outcome == CommandEndpointPolicy.WindowEnd.SUPPRESSED) return;
            // Record the outcome before emitting. The emits below are synchronous binder calls, so
            // an observer woken by this turn can read runtimeDetail() immediately; writing
            // lastCompletion afterwards would briefly report the previous turn's value, which for
            // the endpoint check is a false positive. The finally block still recomputes the same
            // string for the early-return and exception paths.
            lastCompletion = lastCompletionFor(endpointReached, state);
            emitEnd(callback);
            if (outcome == CommandEndpointPolicy.WindowEnd.AUDIO_ERROR) {
                markUnhealthy("microphone produced no usable PCM energy");
                emitTerminalError(state, SpeechRecognizer.ERROR_AUDIO);
            } else if (outcome == CommandEndpointPolicy.WindowEnd.NO_MATCH) {
                emitTerminalError(state, SpeechRecognizer.ERROR_NO_MATCH);
            } else {
                lastFailure = "";
                emitTerminalResults(state, resultBundle(finalText));
            }
        } catch (SecurityException problem) {
            fail(state, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, problem);
        } catch (Throwable problem) {
            fail(state, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, problem);
        } finally {
            // Read before the claim is released, because releasing it is not what decides this and
            // the reason has to describe the turn rather than its teardown.
            completeStoppedTurn(state);
            String completion = lastCompletionFor(endpointReached, state);
            retireTurn(state);
            if (stream != null) try { stream.release(); } catch (Throwable ignored) { }
            turns.retire(callback);
            // Only the worker that still owns the field may clear it. A turn that retires while a
            // successor is already running would otherwise null the successor's reference, and
            // awaitRetiredWorker would wait on the wrong thread. SherpaWakeWordEngine guards this
            // way.
            if (worker == Thread.currentThread()) worker = null;
            lastCompletion = completion;
        }
    }

    /** Why a turn ended, read from the turn's own stop flag rather than a service-wide one. */
    private static String lastCompletionFor(boolean endpointReached, CommandSpeechTurnState state) {
        return endpointReached ? "endpoint" : (state.getStopped() ? "cancelled" : "budget");
    }

    /**
     * True while [state] is still the turn the caller is waiting on.
     *
     * Progress is only reported to a turn that both still owns the session and has not been asked
     * to stop. A superseded turn reporting ready, levels or partials to its own callback after the
     * caller moved on is how a cancelled turn looks like a live one in the framework's eyes.
     */
    private boolean ownsLiveTurn(CommandSpeechTurnState state) {
        return !state.getStopped() && turns.owns((Callback) state.getCallback());
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

    /**
     * Audio source for the command recognizer.
     *
     * This was [MediaRecorder.AudioSource.MIC], which is the raw path: no acoustic echo
     * cancellation and no noise suppression. On a tablet that both plays Sage's answers through
     * its own speaker and listens to her, that means TTS bleed is captured uncancelled and room
     * noise is not suppressed.
     *
     * [MediaRecorder.AudioSource.VOICE_RECOGNITION] requests the platform's voice-communication
     * processing instead. The important caveat, and the reason this logs its own level rather than
     * being trusted blindly: on some Unisoc builds that source changes input gain and frequency
     * response as well as adding processing, which moves the amplitude distribution this service
     * measures. That interacts with [CommandEndpointPolicy.SPEECH_ONSET_PEAK_ABS] and with the
     * wake-tail discard, so the first device run after this change must re-read the onset and
     * per-chunk peak telemetry in [DIAG_TAG] before the threshold is considered still valid.
     */
    private static final int COMMAND_AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION;

    private AudioRecord createMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("RECORD_AUDIO was revoked before microphone creation");
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("invalid microphone buffer: " + minimum);
        int bufferBytes = Math.max(minimum * 2, READ_SAMPLES * 4);
        // A device without the voice-recognition path must still be able to run commands, so fall
        // back to the raw source rather than failing the turn. The fallback is logged because a
        // silent drop back to MIC is exactly the kind of thing that would otherwise look like a
        // regression in recognition quality several turns later.
        AudioRecord record = openAudio(COMMAND_AUDIO_SOURCE, bufferBytes);
        if (record != null && record.getState() == AudioRecord.STATE_INITIALIZED) return record;
        if (record != null) {
            try { record.release(); } catch (Throwable ignored) { }
        }
        AudioRecord fallback = openAudio(MediaRecorder.AudioSource.MIC, bufferBytes);
        if (fallback != null && fallback.getState() == AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "voice-recognition source unavailable, using raw MIC for this device");
            return fallback;
        }
        if (fallback != null) {
            try { fallback.release(); } catch (Throwable ignored) { }
        }
        throw new IllegalStateException("AudioRecord not initialized");
    }

    /** Names the constant actually requested, not the fallback, so a run is never ambiguous. */
    private static String audioSourceName(int source) {
        if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) return "VOICE_RECOGNITION";
        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) return "VOICE_COMMUNICATION";
        if (source == MediaRecorder.AudioSource.MIC) return "MIC";
        return "source" + source;
    }

    /**
     * Opens one source, returning null instead of throwing so [createMicrophone] can try the next.
     *
     * The permission check is repeated here rather than relied on in the caller. This method is
     * where the [AudioRecord] is constructed, so this is where the check has to be provably
     * adjacent to it: Android permission can be revoked between the caller's check and the actual
     * open, and a revoked permission surfaces as a [SecurityException] from the constructor, which
     * is exactly the case that must not take down the whole turn. Kept adjacent deliberately: lint
     * rejects a guard that is not in the same method as the call it protects.
     */
    private AudioRecord openAudio(int source, int bufferBytes) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "audio source " + source + " skipped, RECORD_AUDIO not granted");
            return null;
        }
        try {
            return new AudioRecord(source, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
        } catch (Throwable problem) {
            Log.w(TAG, "audio source " + source + " unavailable: " + problem);
            return null;
        }
    }

    /**
     * Publishes [audio] as this turn's microphone, atomically with cancellation.
     *
     * The single compareAndSet inside the claim is the whole guarantee, and the two reads above it
     * are only fast paths. A worker paused in recognizer setup can be cancelled, superseded by a turn
     * that has already opened its own microphone, and then resume here; its claim was emptied by the
     * cancel, so the compareAndSet loses and it never touches the device. When the two sides race
     * the other way the cancel reads the record back out of the claim and releases it, so there is
     * no interleaving in which a cancelled turn holds the microphone and no other turn does.
     *
     * Refusals are counted rather than reported to the caller: the turn has been retired, so it is
     * owed nothing, and the count is what lets the diagnostic export tell this apart from a broken
     * recognizer.
     */
    private boolean claimMicrophone(CommandSpeechTurnState state, AudioRecord audio) {
        boolean ownsTurn = turns.owns((Callback) state.getCallback());
        if (state.getCapture().publish(audio)) return true;
        CommandSpeechFailurePolicy.CaptureRefusal refusal =
                CommandSpeechFailurePolicy.classifyCaptureRefusal(state.getStopped(), ownsTurn);
        captureRefusals++;
        lastCaptureRefusal = refusal.name().toLowerCase(java.util.Locale.ROOT);
        // An expected refusal is the reported race doing its job. An unexpected one means the claim
        // disappeared while the turn still looked live, which nothing in the admission or teardown
        // paths should be able to cause, so it is logged louder rather than counted as ordinary.
        if (CommandSpeechFailurePolicy.isExpectedCaptureRefusal(refusal)) {
            Log.i(DIAG_TAG, "capture[refused] reason=" + lastCaptureRefusal
                    + " stopped=" + state.getStopped() + " ownsTurn=" + ownsTurn);
        } else {
            Log.w(TAG, "Microphone claim lost by a turn that still looked live");
            Log.w(DIAG_TAG, "capture[refused] reason=" + lastCaptureRefusal
                    + " stopped=" + state.getStopped() + " ownsTurn=" + ownsTurn);
        }
        return false;
    }

    private static void releaseAudioRecord(AudioRecord value) {
        if (value == null) return;
        try { if (value.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) value.stop(); }
        catch (RuntimeException ignored) { }
        try { value.release(); } catch (RuntimeException ignored) { }
    }

    /**
     * Ends the turn's teardown before returning, so the owner's next request is not refused while
     * this worker is still clearing the AudioRecord it was asked to release.
     *
     * Releasing the microphone unblocks the pending read with an error; the worker then unwinds,
     * retires the turn and nulls its own thread reference. The wait is an optimisation that usually
     * finishes teardown before the next request arrives. It is not what admits that request: a stop
     * or cancel releases the claim before it waits, so a teardown that outlives the bound still lets
     * the next turn open its own microphone instead of being answered ERROR_RECOGNIZER_BUSY.
     * Bounded, because the worker is not guaranteed to return promptly and these callbacks run on
     * the main thread, so a longer bound would be charged to the caller on every stop.
     *
     * The wait also does not have to keep the claim honest. The claim is emptied by the release that
     * stops the microphone, not by the thread's exit, so a worker that unwinds slowly no longer
     * holds admission shut after it has given the device back.
     */
    private void awaitRetiredWorker() {
        Thread retiring = worker;
        if (retiring == null || retiring == Thread.currentThread()) return;
        try { retiring.join(CommandSpeechFailurePolicy.STOP_JOIN_MS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (RuntimeException ignored) { }
    }

    private void fail(CommandSpeechTurnState state, int code, Throwable problem) {
        String reason = safeProblem(problem);
        Callback callback = (Callback) state.getCallback();
        boolean ownsTurn = turns.owns(callback);
        // A cancelled turn's read fails by construction, because cancelling released its microphone.
        // Marking that unhealthy opened a cooldown that rejected the caller's next request even
        // though the backend was fine, so the cooldown is limited to the live, uncancelled turn.
        // The stop flag is the turn's own, so a successor admitted in the meantime cannot clear it
        // and make a cancelled turn's failure look like a genuine fault.
        if (CommandSpeechFailurePolicy.shouldMarkUnhealthy(ownsTurn, state.getStopped())) {
            markUnhealthy(reason);
        }
        Log.w(TAG, "Local command recognition failed; Android fallback may be used: " + reason);
        if (CommandSpeechFailurePolicy.shouldEmitError(ownsTurn)) emitTerminalError(state, code);
    }

    /**
     * Delivers the turn's terminal outcome unless one was already delivered.
     *
     * A cancelled turn owns nothing by the time its failure arrives, so it stays silent; a stopped
     * turn still owns the session and is owed exactly one outcome. The guard is the turn's own, so
     * admitting a successor cannot re-arm a retired turn's second outcome.
     */
    // Stop keeps session ownership even when setup or capture exits early. Cancel does not.
    private void completeStoppedTurn(CommandSpeechTurnState state) {
        if (state.getStopped() && turns.owns((Callback) state.getCallback())) {
            emitTerminalError(state, SpeechRecognizer.ERROR_NO_MATCH);
        }
    }

    private void emitTerminalError(CommandSpeechTurnState state, int code) {
        if (state.claimTerminalOutcome()) emitError((Callback) state.getCallback(), code);
    }

    private void emitTerminalResults(CommandSpeechTurnState state, Bundle value) {
        if (state.claimTerminalOutcome()) emitResults((Callback) state.getCallback(), value);
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
