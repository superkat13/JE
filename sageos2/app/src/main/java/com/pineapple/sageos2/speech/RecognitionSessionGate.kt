package com.pineapple.sageos2.speech

/**
 * Pure gate deciding whether a recognizer callback still belongs to the live turn.
 *
 * Extracted from AndroidSpeechPort so stale-callback rejection is testable off-device.
 * The mutation order is identical to the previous inline `recognitionSession` counter, so
 * this is a behaviour-preserving refactor rather than a behavioural change.
 *
 * Invariants:
 *  - [next] opens a new turn and returns its token.
 *  - [invalidate] retires the live turn so every in-flight callback becomes stale.
 *  - [isCurrent] is the only thing that decides whether a result, error or diagnostic is
 *    delivered to the listener.
 */
class RecognitionSessionGate {
    var current: Long = 0L
        private set

    fun next(): Long {
        current += 1L
        return current
    }

    fun invalidate() {
        next()
    }

    fun isCurrent(session: Long): Boolean = session == current
}
