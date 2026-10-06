package com.pineapple.sageos2.speech

import java.util.concurrent.atomic.AtomicReference

/**
 * Turn ownership for the private command recognizer.
 *
 * Extracted from SageSherpaRecognitionService so the onStartListening / onStopListening / onCancel
 * path is testable off-device, following the same convention as [RecognitionSessionGate] and
 * [CommandEndpointPolicy].
 *
 * The service owns the microphone. This only answers one question: does the callback that just
 * arrived still address the live turn? [RecognitionService] delivers per-session callbacks across
 * a binder, so a callback belonging to a turn that already reached a terminal outcome can arrive
 * after the next turn has already started.
 *
 * Identity is by reference, matching the `activeCallback == callback` comparison the service
 * already performed at the emit boundary.
 *
 * [requestStop] and [retire] are the guards the service's onStopListening and onCancel depend on.
 * Acting on a callback for a retired turn releases the *live* turn's microphone and clears the
 * *live* turn's ownership, after which that turn's own terminal outcome is unreachable: it ends
 * with no transcript, no error and no end of speech, and the owner's next request has nothing to
 * resolve against. Rejecting the late callback keeps that from happening.
 */
class CommandSpeechTurnOwnership {

    // Service callbacks and the recognition worker both access ownership.
    // CAS preserves visibility and prevents a retired turn from clearing its successor.
    private val owner = AtomicReference<Any?>(null)

    /** The live turn's callback, or null when no turn is live. */
    val live: Any? get() = owner.get()

    /** Opens a turn for [callback]. False when a turn is already live, which the caller reports as busy. */
    fun begin(callback: Any): Boolean = owner.compareAndSet(null, callback)

    /** True only for the live turn's own callback. */
    fun owns(callback: Any?): Boolean = callback != null && live === callback

    /**
     * Stops capture for [callback]. False when the callback belongs to a turn that already
     * retired, in which case the caller must leave live state alone.
     */
    fun requestStop(callback: Any?): Boolean = owns(callback)

    /** Releases the live turn. False when [callback] belongs to a turn that already retired. */
    fun retire(callback: Any?): Boolean = callback != null && owner.compareAndSet(callback, null)

    /** Unconditional teardown, used when the service itself is destroyed. */
    fun clear() {
        owner.set(null)
    }
}
