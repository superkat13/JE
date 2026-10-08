package com.pineapple.sageos2.runtime

import android.os.Handler
import android.os.Looper

class AndroidRuntimeScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper())
) : RuntimeScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): ScheduledHandle {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
        return object : ScheduledHandle {
            override fun cancel() { handler.removeCallbacks(runnable) }
        }
    }
}
