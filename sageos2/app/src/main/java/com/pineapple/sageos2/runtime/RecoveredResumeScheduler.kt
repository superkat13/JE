package com.pineapple.sageos2.runtime

/** Bounded readiness polling. An owner diagnostic pauses, not consumes, the remaining budget. */
class RecoveredResumeScheduler(
    private val scheduler: RuntimeScheduler,
    private val isStarted: () -> Boolean,
    private val isIdle: () -> Boolean,
    private val onExhausted: () -> Unit,
    private val delayMs: Long = 1_200L,
    private val maxChecks: Int = 25,
    // The host supplies its runtime monitor so idle-check + attempt cannot race an owner turn.
    private val synchronizationLock: Any = Any()
) {
    init {
        require(delayMs > 0L)
        require(maxChecks > 0)
    }

    private var generation = 0L
    private var pending: ScheduledHandle? = null
    private var paused = false
    private var remainingChecks = 0
    private var pendingAttempt: (() -> Unit)? = null

    fun schedule(attempt: () -> Unit) = synchronized(synchronizationLock) {
        cancelLocked()
        pendingAttempt = attempt
        remainingChecks = maxChecks
        if (isStarted() && !paused) scheduleCheck(generation)
    }

    /** Invalidates a scheduled poll without consuming the owner's recovery opportunity. */
    fun pause() = synchronized(synchronizationLock) {
        if (!paused) {
            paused = true
            generation++
            pending?.cancel()
            pending = null
        }
    }

    /** Re-arm the remaining budget after the hearing-test gate is fully closed. */
    fun resume() = synchronized(synchronizationLock) {
        if (paused) {
            paused = false
            if (isStarted() && pendingAttempt != null) scheduleCheck(generation)
        }
    }

    fun cancel() = synchronized(synchronizationLock) { cancelLocked() }

    private fun cancelLocked() {
        generation++
        pending?.cancel()
        pending = null
        pendingAttempt = null
        remainingChecks = 0
    }

    private fun scheduleCheck(token: Long) {
        pending = scheduler.schedule(delayMs) {
            synchronized(synchronizationLock) {
                if (token != generation) return@synchronized
                pending = null
                generation++
                when {
                    !isStarted() -> {
                        pendingAttempt = null
                        remainingChecks = 0
                    }
                    paused -> Unit // Never spend the budget on a diagnostic-gated poll.
                    isIdle() -> {
                        val attempt = pendingAttempt
                        pendingAttempt = null // Exactly once, even with duplicate callback delivery.
                        remainingChecks = 0
                        attempt?.invoke()
                    }
                    remainingChecks > 1 -> {
                        remainingChecks--
                        scheduleCheck(generation)
                    }
                    else -> {
                        pendingAttempt = null
                        remainingChecks = 0
                        onExhausted()
                    }
                }
            }
        }
    }
}
