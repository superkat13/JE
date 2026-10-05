package com.pineapple.sageos2.speech

/**
 * Separates a real command-recognition fault from the failure a cancelled turn is guaranteed to see.
 *
 * Extracted from SageSherpaRecognitionService for the same reason as [CommandSpeechTurnOwnership]:
 * the failure and stop path has to be checkable off-device, because it is the part that decides
 * whether the owner's next request is accepted or rejected.
 *
 * Releasing the microphone unblocks the worker's pending read with an error, so a cancelled turn
 * always ends in a failure. That failure is a consequence of the cancel, not a fault in the
 * recognizer, and the service used to open a [SageSherpaRecognitionService] cooldown for it
 * regardless of who owned the turn. The owner's next request was then rejected as busy for the
 * whole cooldown even though nothing was wrong with the backend.
 */
object CommandSpeechFailurePolicy {

    /**
     * True when a failed turn represents a real backend fault and should open a cooldown.
     *
     * Only the live, uncancelled turn qualifies. A cancelled turn's read error is expected, and a
     * superseded turn's error belongs to a turn the caller has already moved on from; in both cases
     * poisoning availability would reject a request that has not had its chance yet.
     */
    @JvmStatic
    fun shouldMarkUnhealthy(ownsTurn: Boolean, stopRequested: Boolean): Boolean =
        ownsTurn && !stopRequested

    /** True when the failed turn still owns the session and must report a terminal error. */
    @JvmStatic
    fun shouldEmitError(ownsTurn: Boolean): Boolean = ownsTurn

    /**
     * How long onStopListening and onCancel wait for the worker they are retiring.
     *
     * The worker clears the microphone, retires the turn and nulls its own thread reference on the
     * way out, and the next request is refused while that thread is still alive. Releasing the
     * microphone does not stop the worker, so without a wait the caller's immediate retry races the
     * teardown it just asked for and is answered ERROR_RECOGNIZER_BUSY. SherpaWakeWordEngine already
     * joins its worker this way.
     *
     * Small on purpose. RecognitionService dispatches these callbacks on the service's main thread,
     * so this is a main-thread wait and has to stay well clear of an ANR. Joining a thread that has
     * already exited returns immediately, which is the usual case; the bound only matters in the
     * race, and the worker's remaining work after its read is unblocked is a few field writes plus
     * the stream release.
     */
    const val STOP_JOIN_MS: Long = 250L
}
