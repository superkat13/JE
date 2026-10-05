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
     * Why a turn was refused the microphone after being admitted.
     *
     * A turn is admitted before it opens its AudioRecord, so the window between the two is where a
     * cancel can retire it. If the turn resumes there it must not take the device, and the owner is
     * owed a way to tell that refusal apart from a genuine fault: a refused capture opens no
     * cooldown and reports no error, so without this the run looks exactly like a recognizer that
     * failed for no reason.
     *
     * Carried as a name in the diagnostic export only. It names no callback, no audio and no text.
     */
    enum class CaptureRefusal {
        /** The turn had already been stopped, cancelled or superseded. Expected, and silent. */
        SUPERSEDED,

        /**
         * The claim was gone while the turn still looked live. Not reachable from the admission and
         * teardown paths, so it is reported as an anomaly rather than treated as ordinary.
         */
        CLAIM_LOST
    }

    /**
     * True when the refusal is the expected consequence of the turn having been retired, and False
     * when the claim disappeared without one.
     */
    @JvmStatic
    fun isExpectedCaptureRefusal(refusal: CaptureRefusal): Boolean =
        refusal == CaptureRefusal.SUPERSEDED

    /**
     * Classifies a refused claim from what the service still knows about the turn.
     *
     * [stopped] and [ownsTurn] are read after the claim was refused, because that is the only point
     * at which the refusal is known and the only information needed to explain it.
     */
    @JvmStatic
    fun classifyCaptureRefusal(stopped: Boolean, ownsTurn: Boolean): CaptureRefusal =
        if (stopped || !ownsTurn) CaptureRefusal.SUPERSEDED else CaptureRefusal.CLAIM_LOST

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
