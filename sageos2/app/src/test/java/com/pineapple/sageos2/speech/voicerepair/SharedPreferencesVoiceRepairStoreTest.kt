package com.pineapple.sageos2.speech.voicerepair

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPreferencesVoiceRepairStoreTest {
    @Test fun roundTripsExplicitEvidenceAndRestoresInterruptedWithoutReplay() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("sage_voice_repair_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val manager = VoiceRepairSessionManager(clockMs = { 100 }, store = SharedPreferencesVoiceRepairStore(context))
        val session = manager.startSession("hello “Sage”")
        manager.update(session.copy(state = VoiceRepairState.RETESTING, attemptCount = 1,
            repairAction = VoiceRepairAction.RECREATE_RECOGNIZER, repairAppliedAtMs = 120,
            firstTest = VoiceRepairTestResult(session.testPhrase, "yellow", errorCode = 5, elapsedMs = 12)))
        val restored = VoiceRepairSessionManager(clockMs = { 200 }, store = SharedPreferencesVoiceRepairStore(context)).current()!!
        assertEquals(session.testPhrase, restored.testPhrase)
        assertEquals("yellow", restored.firstTest!!.recognized)
        assertEquals(5, restored.firstTest!!.errorCode)
        assertEquals(VoiceRepairState.INTERRUPTED, restored.state)
        assertEquals(120L, restored.repairAppliedAtMs)
        assertNull(restored.secondTest)
    }
}
