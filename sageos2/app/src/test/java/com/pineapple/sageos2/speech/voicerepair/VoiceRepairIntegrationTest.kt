package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.personal.EmptySagePersonalResponder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRepairIntegrationTest {
    @Test fun orchestratorTracksTestFlow() {
        val manager = VoiceRepairSessionManager()
        val orchestrator = VoiceRepairOrchestrator(object : com.pineapple.sageos2.speech.SpeechPort {
            override fun attach(listener: com.pineapple.sageos2.speech.SpeechInputListener) = Unit
            override fun setListening(mode: com.pineapple.sageos2.core.SageListeningMode, generation: Long, turnId: Long) = Unit
            override fun speak(turnId: Long, text: String, onComplete: () -> Unit) = Unit
            override fun speakTransient(text: String) = Unit
        }, manager)
        orchestrator.startRepair("hello")
        assertNotNull(manager.current())
        assertEquals(VoiceRepairState.TESTING_EXPECTED, manager.current()?.state)
    }

    @Test fun exportContainsExplicitTestEvidence() {
        val manager = VoiceRepairSessionManager()
        val orchestrator = VoiceRepairOrchestrator(object : com.pineapple.sageos2.speech.SpeechPort {
            override fun attach(listener: com.pineapple.sageos2.speech.SpeechInputListener) = Unit
            override fun setListening(mode: com.pineapple.sageos2.core.SageListeningMode, generation: Long, turnId: Long) = Unit
            override fun speak(turnId: Long, text: String, onComplete: () -> Unit) = Unit
            override fun speakTransient(text: String) = Unit
        }, manager)
        orchestrator.startRepair("hello")
        val export = orchestrator.createExport()
        assertNotNull(export)
        assertEquals("hello", export?.expected)
        assertEquals("hello", export?.testPhrase)
    }
}
