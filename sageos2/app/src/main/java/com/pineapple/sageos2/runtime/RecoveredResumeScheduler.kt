package com.pineapple.sageos2.runtime

/** Bounded readiness polling only. Task eligibility and replay policy remain with the caller. */
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

    fun schedule(attempt: () -> Unit) = synchronized(synchronizationLock) {
        cancelLocked()
        val token = generation
        if (isStarted()) scheduleCheck(token, maxChecks, attempt)
    }

    fun cancel() = synchronized(synchronizationLock) { cancelLocked() }

    private fun cancelLocked() {
        generation++
        pending?.cancel()
        pending = null
    }

    private fun scheduleCheck(token: Long, remainingChecks: Int, attempt: () -> Unit) {
        pending = scheduler.schedule(delayMs) {
            synchronized(synchronizationLock) {
                if (token != generation) return@synchronized
                pending = null
                // Consume each callback, including busy polls, before scheduling or invoking code.
                generation++
                if (!isStarted()) {
                    return@synchronized
                } else if (isIdle()) {
                    attempt()
                } else if (remainingChecks > 1) {
                    scheduleCheck(generation, remainingChecks - 1, attempt)
                } else {
                    onExhausted()
                }
            }
        }
    }
}
