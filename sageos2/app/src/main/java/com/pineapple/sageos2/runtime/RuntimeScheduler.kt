package com.pineapple.sageos2.runtime

interface ScheduledHandle {
    fun cancel()
}

interface RuntimeScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): ScheduledHandle
}
