package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.brain.BrainProgress
import com.pineapple.sageos2.brain.BrainProgressStage.*
import com.pineapple.sageos2.brain.BrainTelemetry
import org.junit.Assert.*
import org.junit.Test

class BrainProgressTraceGateTest {
    @Test fun frequentTokenUpdatesLeaveRoomForOtherDiagnostics() {
        var now = 0L
        val gate = BrainProgressTraceGate { now }
        val recorded = (0..200).mapNotNull { tick ->
            now = tick * 350L
            val progress = BrainProgress(13, GENERATING, BrainTelemetry(generatedTokens = tick))
            progress.takeIf { gate.shouldRecord(it) }
        }
        // Seventy seconds of 350 ms polling becomes seven entries, with fresh telemetry.
        assertEquals(7, recorded.size)
        assertEquals(0, recorded.first().telemetry?.generatedTokens)
        assertEquals(174, recorded.last().telemetry?.generatedTokens)
    }

    @Test fun stageChangesAndSameTurnFollowUpAreNeverDelayed() {
        val gate = BrainProgressTraceGate { 0L }
        listOf(PREPARING, LOADING_MODEL, READING_CONTEXT, GENERATING,
            PREPARING, READING_CONTEXT, GENERATING).forEach { stage ->
            assertTrue(gate.shouldRecord(BrainProgress(13, stage)))
            assertFalse(gate.shouldRecord(BrainProgress(13, stage)))
        }
        assertTrue(gate.shouldRecord(BrainProgress(14, GENERATING)))
    }

    @Test fun longPrefillKeepsHeartbeatAtTenSecondBoundary() {
        var now = 100L
        val gate = BrainProgressTraceGate { now }
        val progress = BrainProgress(1, READING_CONTEXT)
        assertTrue(gate.shouldRecord(progress))
        now = 10_099L
        assertFalse(gate.shouldRecord(progress))
        now = 10_100L
        assertTrue(gate.shouldRecord(progress))
        now = 10_101L
        assertFalse(gate.shouldRecord(progress))
    }
}
