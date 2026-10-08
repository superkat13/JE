package com.pineapple.sageos2.speech

/** Reset a retry budget only after sustained, observed audio health, never on configuration. */
class WakeRecoveryBudget {
    @Volatile var attempts: Int = 0
        private set
    @Volatile var paused: Boolean = false
        private set
    @Volatile var lastFailure: String = ""
        private set
    private var listeningSince: Long? = null

    fun listening(nowMs: Long) {
        if (paused) return
        val since = listeningSince
        if (since == null) listeningSince = nowMs
        else if (nowMs - since >= STABLE_MS) attempts = 0
    }

    fun interrupted() { listeningSince = null }

    fun failed(reason: String): Long? {
        interrupted()
        lastFailure = reason
        if (paused) return null
        val delay = WakeReconnectPolicy.delayForAttempt(attempts + 1)
        if (delay == null) paused = true else attempts++
        return delay
    }

    fun reset() {
        attempts = 0
        paused = false
        listeningSince = null
        // Retain evidence even after an explicit retry/reset.
    }

    companion object { const val STABLE_MS = 30_000L }
}
