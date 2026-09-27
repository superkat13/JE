package com.pineapple.sageos2.speech

/**
 * One recognizer session may produce at most one terminal result/error.
 * Starting/stopping recognition invalidates every older listener immediately.
 */
class RecognitionSessionGate {
    private var current = 0L

    fun begin(): Long = ++current

    fun invalidate() {
        current += 1
    }

    fun isCurrent(session: Long): Boolean = session == current

    fun consumeTerminal(session: Long): Boolean {
        if (session != current) return false
        current += 1
        return true
    }
}
