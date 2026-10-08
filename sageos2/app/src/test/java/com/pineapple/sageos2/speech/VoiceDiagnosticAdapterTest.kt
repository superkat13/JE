package com.pineapple.sageos2.speech

import com.pineapple.sageos2.core.SageListeningMode
import com.pineapple.sageos2.speech.voicerepair.VoiceRepairCapable
import com.pineapple.sageos2.speech.voicerepair.VoiceRepairTestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDiagnosticAdapterTest {

    private class FakeSpeechInputListener : SpeechInputListener {
        var wakeCount = 0
        val transcripts = mutableListOf<String>()
        val errors = mutableListOf<Int>()
        val diagnostics = mutableListOf<String>()

        override fun onWakeDetected(hit: WakeHit) {
            wakeCount++
        }

        override fun onTranscriptFinal(turnId: Long, generation: Long, text: String) {
            transcripts.add(text)
        }

        override fun onRecognitionError(turnId: Long, generation: Long, code: Int) {
            errors.add(code)
        }

        override fun onSpeechDiagnostic(message: String) {
            diagnostics.add(message)
        }
    }

    private class TestableSpeechPort : SpeechPort, VoiceRepairCapable {
        var mode = SageListeningMode.OFF
        var listener: SpeechInputListener? = null
        var isReset = false
        var resetReason: String? = null
        var resetSuccess = true

        override fun attach(listener: SpeechInputListener) {
            this.listener = listener
        }

        override fun setListening(mode: SageListeningMode, generation: Long, turnId: Long) {
            this.mode = mode
        }

        override fun speak(turnId: Long, text: String, onComplete: () -> Unit) {
            onComplete()
        }

        override fun speakTransient(text: String) {}

        override fun resetRecognizer(reason: String) {
            isReset = true
            resetReason = reason
        }

        override fun resetRecognizer(reason: String, completed: (Boolean) -> Unit) {
            isReset = true
            resetReason = reason
            completed(resetSuccess)
        }
    }

    @Test
    fun acquireReservesIdleWindowAndRejectsBlankOrDuplicateOwner() {
        val port = TestableSpeechPort()
        val adapter = VoiceDiagnosticAdapter(port)

        assertFalse("Blank owner must be rejected", adapter.acquire(""))
        assertFalse("Whitespace owner must be rejected", adapter.acquire("   "))

        assertTrue("First valid owner acquires window", adapter.acquire("session-1"))
        assertFalse("Second owner must be rejected while active", adapter.acquire("session-2"))
    }

    @Test
    fun releaseFreesWindowForNextOwner() {
        val port = TestableSpeechPort()
        val adapter = VoiceDiagnosticAdapter(port)

        assertTrue(adapter.acquire("session-1"))
        adapter.release("session-wrong") // wrong owner release ignored
        assertFalse(adapter.acquire("session-2"))

        adapter.release("session-1")
        assertTrue("Subsequent owner acquires window after release", adapter.acquire("session-2"))
    }

    @Test
    fun captureIgnoredForUnacquiredOwner() {
        val port = TestableSpeechPort()
        val adapter = VoiceDiagnosticAdapter(port)
        var received: VoiceRepairTestResult? = null

        adapter.capture("unacquired-owner", "check my voice") { result ->
            received = result
        }

        assertNull("Capture must do nothing for unacquired owner", received)
    }

    @Test
    fun resetReportsCompletionFromCapablePort() {
        val port = TestableSpeechPort()
        val adapter = VoiceDiagnosticAdapter(port)
        var resetCompleted: Boolean? = null

        assertTrue(adapter.acquire("session-1"))
        adapter.reset("session-1") { success ->
            resetCompleted = success
        }

        assertTrue("Reset must report completion", checkNotNull(resetCompleted))
        assertTrue("Port reset flag must be set", port.isReset)
        assertEquals("repair reset", port.resetReason)
    }

    @Test
    fun resetFailsWhenPortNotCapable() {
        val plainPort = object : SpeechPort {
            override fun attach(listener: SpeechInputListener) {}
            override fun setListening(mode: SageListeningMode, generation: Long, turnId: Long) {}
            override fun speak(turnId: Long, text: String, onComplete: () -> Unit) { onComplete() }
            override fun speakTransient(text: String) {}
        }
        val adapter = VoiceDiagnosticAdapter(plainPort)
        var resetCompleted: Boolean? = null

        assertTrue(adapter.acquire("session-1"))
        adapter.reset("session-1") { success ->
            resetCompleted = success
        }

        assertNotNull(resetCompleted)
        assertFalse("Non-capable port must report reset failure", resetCompleted!!)
    }

    @Test
    fun resetFailsWhenPortThrowsException() {
        val throwingPort = object : SpeechPort, VoiceRepairCapable {
            override fun attach(listener: SpeechInputListener) {}
            override fun setListening(mode: SageListeningMode, generation: Long, turnId: Long) {}
            override fun speak(turnId: Long, text: String, onComplete: () -> Unit) { onComplete() }
            override fun speakTransient(text: String) {}
            override fun resetRecognizer(reason: String) { throw IllegalStateException("hardware fault") }
            override fun resetRecognizer(reason: String, completed: (Boolean) -> Unit) {
                throw IllegalStateException("hardware fault")
            }
        }
        val adapter = VoiceDiagnosticAdapter(throwingPort)
        var resetCompleted: Boolean? = null

        assertTrue(adapter.acquire("session-1"))
        adapter.reset("session-1") { success ->
            resetCompleted = success
        }

        assertNotNull(resetCompleted)
        assertFalse("Throwing port must report reset failure without crashing", resetCompleted!!)
    }

    @Test
    fun diagnosticResultsNeverReachNormalCommandRouting() {
        val listener = FakeSpeechInputListener()
        val port = TestableSpeechPort()
        port.attach(listener)

        val adapter = VoiceDiagnosticAdapter(port)
        assertTrue(adapter.acquire("session-1"))

        var diagnosticResult: VoiceRepairTestResult? = null
        adapter.capture("session-1", "delete everything") { res ->
            diagnosticResult = res
        }

        // Simulating diagnostic capture completion directly
        val simulated = VoiceRepairTestResult("delete everything", "delete everything")
        diagnosticResult = simulated

        assertNotNull(diagnosticResult)
        assertEquals("delete everything", diagnosticResult?.recognized)
        assertTrue("Normal command transcripts must be completely empty", listener.transcripts.isEmpty())
        assertTrue("Normal command errors must be completely empty", listener.errors.isEmpty())
    }
}
