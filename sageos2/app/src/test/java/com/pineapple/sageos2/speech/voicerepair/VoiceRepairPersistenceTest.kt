package com.pineapple.sageos2.speech.voicerepair

import org.junit.Assert.*
import org.junit.Test

class VoiceRepairPersistenceTest {
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
