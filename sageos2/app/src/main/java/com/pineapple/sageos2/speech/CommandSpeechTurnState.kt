package com.pineapple.sageos2.speech

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The per-turn state a command recognition worker needs, kept on the turn rather than on the service.
 *
 * Stop intent and terminal-outcome ownership were service-wide flags. That is what made a successor
 * able to un-cancel its predecessor: onStartListening cleared the shared flags for the turn it was
 * admitting, so a worker that was cancelled while it was still setting up found its own cancellation
 * erased and carried on capturing and reporting for a turn the caller had walked away from.
 *
 * A fresh instance is created per admitted turn, so there is nothing to reset and no window in which
 * one turn's admission can re-arm another turn's flags. Identity is by reference, matching
 * [CommandSpeechTurnOwnership]'s callback convention; [callback] is typed as [Any] so this stays
 * testable without the SDK's hidden Callback constructor.
 */
class CommandSpeechTurnState(
    /** The live turn's callback, or the token standing in for it off-device. */
    val callback: Any
) {

    /**
     * This turn's claim on the microphone.
     *
     * Held for the whole life of the turn, from admission until teardown, rather than only while an
     * AudioRecord exists. See [CommandSpeechCaptureClaim] for why the claim has to exist before the
     * record does.
     */
    val capture = CommandSpeechCaptureClaim()

    private val stopRequested = AtomicBoolean(false)

    private val terminalEmitted = AtomicBoolean(false)

    /**
     * The worker running this turn, or null before it starts.
     *
     * Admission reads it to recognise a claim whose owner died without releasing, which would
     * otherwise refuse every later request. It is set before the thread starts, so the window in
     * which a live worker looks unregistered does not exist.
     */
    @Volatile
    var worker: Thread? = null

    /** True once a stop or cancel has been accepted for this turn. Never cleared again. */
    val stopped: Boolean get() = stopRequested.get()

    /**
     * Asks this turn to stop.
     *
     * Returns true only for the first caller. A second stop or cancel for the same turn is a late
     * platform callback and has nothing left to do, which is reported so the service can leave live
     * state alone rather than repeating the teardown.
     */
    fun requestStop(): Boolean = stopRequested.compareAndSet(false, true)

    /**
     * Claims the right to deliver this turn's terminal outcome. False once one has been delivered.
     *
     * A stopped turn is still owed exactly one outcome and a cancelled turn is owed none, and both
     * facts are decided by [CommandSpeechFailurePolicy] at the emit site. This only guarantees that
     * whichever decision is taken, it is taken once: the finished window and the failure path are
     * both reachable for a single turn, so without it a caller could receive an error after the
     * transcript it already had.
     */
    fun claimTerminalOutcome(): Boolean = terminalEmitted.compareAndSet(false, true)

    /** True when [callback] is this turn's own callback. */
    fun owns(callback: Any?): Boolean = callback != null && this.callback === callback
}
