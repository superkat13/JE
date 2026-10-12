package com.pineapple.sageos2.speech.voicerepair

import org.junit.Assert.*
import org.junit.Test

class VoiceRepairPersistenceTest {
    @Test fun cancellationReleasesControllerEvenWhenPersistenceFails() {
        var failSave = false
        val store = object : VoiceRepairStore {
            override fun load() = emptyList<VoiceRepairSession>()
            override fun save(sessions: List<VoiceRepairSession>) { if (failSave) error("disk full") }
        }
        var released = false
        val port = object : VoiceDiagnosticPort {
            override fun acquire(owner: String) = true
            override fun capture(owner: String, expected: String, result: (VoiceRepairTestResult) -> Unit) = Unit
            override fun reset(owner: String, completed: (Boolean) -> Unit) = Unit
            override fun release(owner: String) { released = true }
        }
        val scheduler = object : com.pineapple.sageos2.runtime.RuntimeScheduler {
            override fun schedule(delayMs: Long, task: () -> Unit) = object : com.pineapple.sageos2.runtime.ScheduledHandle {
                override fun cancel() = Unit
            }
        }
        val controller = VoiceRepairOrchestrator(port, VoiceRepairSessionManager(store = store), scheduler)
        controller.startRepair("hello"); failSave = true
        runCatching { controller.cancel() }
        assertTrue(released)
    }

    @Test fun restartPreservesTestAndMarksUnfinishedRepairInterrupted() {
        val store = MemoryVoiceRepairStore()
        val first = VoiceRepairSessionManager(clockMs = { 100 }, store = store)
        val session = first.startSession("delete nothing")
        first.update(session.copy(state = VoiceRepairState.REPAIRING, attemptCount = 1,
            repairAction = VoiceRepairAction.RECREATE_RECOGNIZER, repairAppliedAtMs = 120))
        val restored = VoiceRepairSessionManager(clockMs = { 200 }, store = store).current()!!
        assertEquals(VoiceRepairState.INTERRUPTED, restored.state)
        assertEquals("delete nothing", restored.testPhrase)
        assertEquals(120L, restored.repairAppliedAtMs)
        assertNull(restored.secondTest)
        assertTrue(VoiceRepairPresentation.render(restored).contains("No repair was verified"))
    }

    @Test fun historyHasTenSessionsAndRetainsTerminalEvidenceAcrossRestart() {
        val store = MemoryVoiceRepairStore()
        val manager = VoiceRepairSessionManager(store = store)
        repeat(15) { manager.startSession("phrase $it"); manager.cancel() }
        val restored = VoiceRepairSessionManager(store = store)
        assertEquals(10, restored.history().size)
        assertEquals("phrase 5", restored.history().first().testPhrase)
        assertEquals(VoiceRepairState.CANCELLED, restored.current()!!.state)
    }
}
