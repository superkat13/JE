package com.pineapple.sageos2.speech

/**
 * Bounded recovery schedule for the isolated native wake process.
 * Repeated native/process failures must not turn into a battery-draining restart loop.
 */
object WakeReconnectPolicy {
    private val delaysMs = longArrayOf(750L, 2_000L, 5_000L)
    const val MAX_ATTEMPTS: Int = 3

    fun delayForAttempt(attempt: Int): Long? =
        if (attempt in 1..MAX_ATTEMPTS) delaysMs[attempt - 1] else null
}
