package com.pineapple.sageos2.speech

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import com.pineapple.sageos2.core.SageListeningMode
import com.pineapple.sageos2.runtime.RuntimeScheduler
import com.pineapple.sageos2.runtime.ScheduledHandle
import com.pineapple.sageos2.speech.voicerepair.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration

/** Real AndroidSpeechPort -> RecognitionListener -> VoiceDiagnosticAdapter -> orchestrator.
 * Only the framework recognizer and remote service IPC peer are simulated; no audio claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, shadows = [VoiceDiagnosticAdapterTest.RecognizerShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceDiagnosticAdapterTest {
    private lateinit var context: WakeContext
    private lateinit var wake: RemoteWakeWordEngine
    private lateinit var port: AndroidSpeechPort
    private lateinit var adapter: VoiceDiagnosticAdapter
    private val normal = mutableListOf<String>()
    private val errors = mutableListOf<Int>()
    private var wakeHits = 0

    @Before fun setup() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        context = WakeContext(RuntimeEnvironment.getApplication())
        wake = RemoteWakeWordEngine(context)
        port = AndroidSpeechPort(context, wake)
        port.attach(object : SpeechInputListener {
            override fun onWakeDetected(hit: WakeHit) { wakeHits++ }
            override fun onTranscriptFinal(turnId: Long, generation: Long, text: String) { normal.add(text) }
            override fun onRecognitionError(turnId: Long, generation: Long, code: Int) { errors.add(code) }
        })
        adapter = VoiceDiagnosticAdapter(port)
        idle()
        port.setListening(SageListeningMode.WAKE_ONLY, 1, 1)
        idle()
    }
    @After fun cleanup() { port.shutdown(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun recognizer(): RecognizerShadow = Shadow.extract(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    private fun bundle(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }
    private fun acquire(owner: String = "one") {
        assertTrue(adapter.acquire(owner)); idle(); context.acknowledgeStop(); idle()
    }
    private fun capture(owner: String = "one", result: (VoiceRepairTestResult) -> Unit = {}) {
        adapter.capture(owner, "delete everything", result); idle()
    }
    private fun assertIsolated() {
        assertTrue(normal.isEmpty()); assertTrue(errors.isEmpty()); assertEquals(0, wakeHits)
    }

    @Test fun captureWaitsForRemoteStopAcknowledgement() {
        assertTrue(adapter.acquire("one")); capture()
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        context.acknowledgeStop("remote wake profiles configured"); idle()
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        context.acknowledgeStop(); idle()
        assertEquals(1, recognizer().starts)
        assertEquals(1, context.unbinds)
    }
    @Test fun diagnosticResultsNeverReachNormalCommandRouting() {
        acquire()
        var received: VoiceRepairTestResult? = null
        capture { received = it }
        // Listener installed by production captureDiagnosticPhrase, not a test variable assignment.
        recognizer().callback!!.onResults(bundle("delete everything"))
        assertEquals("delete everything", received?.recognized)
        assertEquals("delete everything", received?.expected)
        assertIsolated()
    }
    @Test fun modeAndSpeechChangesCannotCancelOrReplaceDiagnostic() {
        acquire(); capture()
        val active = recognizer(); val callback = active.callback; val cancels = active.cancels
        var spoken = 0
        port.setListening(SageListeningMode.OFF, 2, 2)
        port.setListening(SageListeningMode.COMMAND, 3, 3)
        port.speak(3, "normal response") { spoken++ }
        port.speakTransient("thinking"); idle()
        assertSame(callback, active.callback); assertEquals(cancels, active.cancels)
        assertEquals(0, spoken); assertEquals(1, active.starts)
        adapter.release("one"); idle()
        // TTS is deliberately not initialized; shutdown must complete queued speech.
        port.shutdown(); idle()
        assertEquals(1, spoken); assertIsolated()
    }
    @Test fun releaseRestoresLatestRequestedModeAndGeneration() {
        acquire(); capture(); val old = recognizer().callback!!
        port.setListening(SageListeningMode.COMMAND, 99, 77)
        adapter.release("one"); idle()
        assertNotSame(old, recognizer().callback)
        old.onResults(bundle("stale diagnostic")); assertIsolated()
        recognizer().callback!!.onResults(bundle("current normal command"))
        assertEquals(listOf("current normal command"), normal)
    }
    @Test fun offModeIsPreservedAfterRelease() {
        acquire(); capture(); val starts = context.starts
        port.setListening(SageListeningMode.OFF, 2, 2)
        adapter.release("one"); idle()
        assertEquals(starts, context.starts); assertEquals(1, recognizer().starts)
    }
    @Test fun blankDuplicateBusyAndOffMainAdmissionAreRejected() {
        assertFalse(adapter.acquire(" "))
        port.setListening(SageListeningMode.COMMAND, 2, 2); idle()
        val command = recognizer(); val cancels = command.cancels
        assertFalse(adapter.acquire("one")); assertEquals(cancels, command.cancels)
        port.setListening(SageListeningMode.OFF, 3, 3); idle()
        var backgroundResult = true
        Thread { backgroundResult = adapter.acquire("background") }.apply { start(); join() }
        assertFalse(backgroundResult)
        acquire(); assertFalse(adapter.acquire("two"))
        adapter.release("wrong"); assertFalse(adapter.acquire("two"))
    }
    @Test fun queuedSpeechBlocksAdmission() {
        port.speak(2, "hello") {}; idle(); assertFalse(adapter.acquire("one"))
    }
    @Test fun unsupportedSpeechPortCannotPretendToReserveMicrophone() {
        val plain = object : SpeechPort {
            override fun attach(listener: SpeechInputListener) {}
            override fun setListening(mode: SageListeningMode, generation: Long, turnId: Long) {}
            override fun speak(turnId: Long, text: String, onComplete: () -> Unit) = onComplete()
            override fun speakTransient(text: String) {}
        }
        assertFalse(VoiceDiagnosticAdapter(plain).acquire("one"))
    }
    @Test fun legacyResetCannotReportUncorrelatedSuccess() {
        var invoked = false
        val legacy = object : VoiceRepairCapable {
            override fun resetRecognizer(reason: String) { invoked = true }
        }
        var result: Boolean? = null
        legacy.resetRecognizer("test") { result = it }
        assertEquals(false, result); assertFalse(invoked)
    }
    @Test fun cancelAndDestroyFailuresAreReportedSeparately() {
        for (cancelFails in listOf(true, false)) {
            acquire(); capture(); val old = recognizer()
            old.failCancel = cancelFails; old.failDestroy = !cancelFails
            var result: Boolean? = null
            adapter.reset("one") { result = it }
            assertNull(result); idle(); assertEquals(false, result)
            assertTrue(old.destroys > 0) // still attempts destroy when cancel throws
            old.failCancel = false; old.failDestroy = false
            adapter.release("one"); idle()
        }
    }
    @Test fun faultResetAndSamePhraseRetestUseNewRecognizer() {
        val manager = VoiceRepairSessionManager(); val clock = TestScheduler()
        val controller = VoiceRepairOrchestrator(adapter, manager, clock)
        assertTrue(controller.startRepair("delete everything")); idle(); context.acknowledgeStop(); idle()
        val first = recognizer()
        first.callback!!.onError(SpeechRecognizer.ERROR_CLIENT)
        assertFalse(controller.resetCompleted()); idle()
        val second = recognizer()
        assertNotSame(first, second); assertEquals(1, first.destroys)
        assertTrue(controller.resetCompleted()); assertEquals(VoiceRepairState.RETESTING, manager.current()!!.state)
        first.callback!!.onResults(bundle("late old phrase"))
        second.callback!!.onResults(bundle("delete everything")); idle()
        assertEquals(VoiceRepairState.SUCCESS, manager.current()!!.state)
        assertEquals("delete everything", manager.current()!!.secondTest!!.expected)
        assertEquals(1, manager.current()!!.attemptCount); assertTrue(clock.cancelled); assertIsolated()
    }
    @Test fun failedResetNeverStartsRetestOrClaimsRepairApplied() {
        val manager = VoiceRepairSessionManager()
        val controller = VoiceRepairOrchestrator(adapter, manager, TestScheduler())
        assertTrue(controller.startRepair("hello")); idle(); context.acknowledgeStop(); idle()
        val first = recognizer(); first.failDestroy = true
        first.callback!!.onError(SpeechRecognizer.ERROR_CLIENT); idle()
        assertEquals(VoiceRepairState.FAILED, manager.current()!!.state)
        assertNull(manager.current()!!.repairAppliedAtMs); assertNull(manager.current()!!.secondTest)
        assertSame(first, recognizer()); first.failDestroy = false
    }
    @Test fun cancelBeforeStopAckPreventsCaptureAndRejectsOldAckForSuccessor() {
        assertTrue(adapter.acquire("same")); capture("same"); val oldReply = context.stopReplies.last()
        adapter.release("same"); idle()
        assertTrue(adapter.acquire("same")); capture("same")
        context.acknowledgeStop(reply = oldReply); idle()
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        context.acknowledgeStop(); idle(); assertEquals(1, recognizer().starts)
    }
    @Test fun cancelDuringCaptureRejectsLateResultsErrorsAndDuplicateCompletion() {
        acquire(); var count = 0; capture { count++ }; val old = recognizer().callback!!
        adapter.release("one"); idle(); acquire("one"); capture("one") { count++ }
        old.onResults(bundle("delete everything")); old.onError(5); assertEquals(0, count)
        recognizer().callback!!.onResults(bundle("delete everything"))
        recognizer().callback!!.onResults(bundle("duplicate"))
        assertEquals(1, count); assertIsolated()
    }
    @Test fun cancelQueuedResetCannotTearDownSuccessor() {
        acquire(); capture(); val old = recognizer(); var completions = 0
        adapter.reset("one") { completions++ }; adapter.release("one")
        assertTrue(adapter.acquire("one")); idle(); context.acknowledgeStop(); capture()
        assertEquals(0, completions); assertEquals(0, old.destroys)
    }
    @Test fun orchestratorCancelAndTimeoutReleaseCaptureAndRejectLateCallbacks() {
        for (timeout in listOf(false, true)) {
            val manager = VoiceRepairSessionManager(); val clock = TestScheduler()
            val controller = VoiceRepairOrchestrator(adapter, manager, clock)
            assertTrue(controller.startRepair("hello")); idle(); context.acknowledgeStop(); idle()
            val old = recognizer().callback!!
            if (timeout) clock.task!!.invoke() else controller.cancel()
            idle()
            assertEquals(if (timeout) VoiceRepairState.FAILED else VoiceRepairState.CANCELLED, manager.current()!!.state)
            if (timeout) assertEquals(VoiceRepairCause.DEADLINE_EXCEEDED, manager.current()!!.cause)
            old.onResults(bundle("hello")); old.onError(5); assertTrue(clock.cancelled)
            assertTrue(adapter.acquire("next")); adapter.release("next"); idle(); assertIsolated()
        }
    }
    @Test fun missingStopAcknowledgementTimesOutWithoutStartingRecognizer() {
        val manager = VoiceRepairSessionManager()
        val controller = VoiceRepairOrchestrator(adapter, manager, TestScheduler())
        assertTrue(controller.startRepair("hello")); idle(); advance(5_001)
        assertEquals(VoiceRepairState.FAILED, manager.current()!!.state)
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()); assertNull(manager.current()!!.repairAppliedAtMs)
        assertTrue(adapter.acquire("next")); adapter.release("next")
    }
    @Test fun shutdownInvalidatesCapture() {
        acquire(); capture(); val old = recognizer().callback!!
        port.shutdown(); idle(); old.onResults(bundle("delete everything")); old.onError(5)
        adapter.release("one"); assertFalse(adapter.acquire("next")); assertIsolated()
    }
    @Test fun shutdownBeforeStopAcknowledgementCancelsBindingAndCapture() {
        assertTrue(adapter.acquire("one")); capture()
        val reply = context.stopReplies.last()
        port.shutdown(); idle()
        val unbinds = context.unbinds
        context.acknowledgeStop(reply = reply); idle(); advance(5_001)
        assertEquals(unbinds, context.unbinds); assertTrue(unbinds >= 1)
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()); assertIsolated()
    }
    @Test fun queuedCaptureCannotRunForReusedOwner() {
        acquire(); var oldResults = 0
        adapter.capture("one", "old") { oldResults++ }; adapter.release("one")
        assertTrue(adapter.acquire("one")); idle(); context.acknowledgeStop(); idle()
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()); assertEquals(0, oldResults)
        capture(); assertEquals(1, recognizer().starts)
    }
    @Test fun deferredCommandModeDoesNotPreventOwnedReset() {
        acquire(); capture(); port.setListening(SageListeningMode.COMMAND, 9, 9)
        var result: Boolean? = null
        adapter.reset("one") { result = it }; idle(); assertEquals(true, result)
        capture(); assertEquals(1, recognizer().starts); assertIsolated()
    }
    private class TestScheduler : RuntimeScheduler {
        var task: (() -> Unit)? = null
        var cancelled = false
        override fun schedule(delayMs: Long, task: () -> Unit): ScheduledHandle {
            this.task = task
            return object : ScheduledHandle { override fun cancel() { cancelled = true } }
        }
    }
    @Implements(SpeechRecognizer::class)
    class RecognizerShadow : ShadowSpeechRecognizer() {
        var callback: RecognitionListener? = null
        var starts = 0; var cancels = 0; var destroys = 0
        var failCancel = false; var failDestroy = false
        @Implementation fun setRecognitionListener(listener: RecognitionListener) { callback = listener }
        @Implementation public override fun startListening(intent: Intent) { starts++ }
        @Implementation fun cancel() { cancels++; check(!failCancel) { "injected cancel failure" } }
        @Implementation public override fun destroy() { destroys++; check(!failDestroy) { "injected destroy failure" } }
        companion object {
            @JvmStatic @Implementation fun isRecognitionAvailable(context: Context) = true
        }
    }
    private class WakeContext(base: Context) : ContextWrapper(base) {
        val stopReplies = mutableListOf<Messenger>()
        var starts = 0
        var unbinds = 0
        override fun getApplicationContext(): Context = this
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        override fun startForegroundService(intent: Intent) = intent.component
        override fun stopService(intent: Intent) = true
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            if (intent.component?.className != "com.pineapple.sageos2.speech.SageWakeRemoteService") return false
            val service = Messenger(Handler(Looper.getMainLooper()) { message ->
                when (message.what) {
                    RemoteWakeProtocol.MSG_STOP -> stopReplies.add(message.replyTo)
                    RemoteWakeProtocol.MSG_START -> starts++
                }
                true
            })
            Handler(Looper.getMainLooper()).post { connection.onServiceConnected(intent.component, service.binder) }
            return true
        }
        fun acknowledgeStop(detail: String = "remote wake engine stopped", reply: Messenger = stopReplies.last()) {
            reply.send(Message.obtain(null, RemoteWakeProtocol.MSG_ACKNOWLEDGED).apply {
                data = Bundle().apply { putString(RemoteWakeProtocol.KEY_DETAIL, detail) }
            })
        }
    }
}
