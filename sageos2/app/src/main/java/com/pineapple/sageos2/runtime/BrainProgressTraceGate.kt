package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.brain.BrainProgress
import com.pineapple.sageos2.brain.BrainProgressStage

/** Bound persistent trace traffic without throttling live UI or Brain health updates. */
internal class BrainProgressTraceGate(
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private var turnId: Long? = null
    private var stage: BrainProgressStage? = null
    private var lastRecordedMs = 0L

    @Synchronized fun shouldRecord(progress: BrainProgress): Boolean {
        val now = monotonicMs()
        // Keep every transition, including PREPARING for a follow-up using the same turn ID.
        if (turnId == progress.turnId && stage == progress.stage && now - lastRecordedMs < 10_000L) {
            return false
        }
        turnId = progress.turnId
        stage = progress.stage
        lastRecordedMs = now
        return true
    }
}
