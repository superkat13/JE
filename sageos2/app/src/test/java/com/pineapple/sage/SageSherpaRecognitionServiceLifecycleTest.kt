package com.pineapple.sage

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.pineapple.sageos2.speech.CommandSpeechTurnOwnership
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Drives the real command recognition service's failure, cancel and microphone-release paths.
 *
 * These tests call the production methods, so what they cover is the shipped logic rather than a
 * stand-in for it. The decisions pinned down here were previously only reachable through the
 * service's own teardown:
 *
 *  - a cancelled turn's failure opened a cooldown that refused the owner's next request;
 *  - a retiring worker released whichever AudioRecord the field held at the time, which after a
 *    cancel is its successor's.
 *
 * The verified sherpa engine and model are native, so the recognizer is genuinely unavailable here
 * and every turn leaves through the same failure path a device turn takes when the engine cannot
 * load. That is the case under test, not a workaround. No audio is captured or decoded, so these
 * tests make no claim about real microphone behaviour; that still needs a device.
 *
 * RecognitionService.Callback cannot be subclassed from Kotlin because the SDK stub exposes no
 * accessible constructor, so a real one is built reflectively and given a recording listener. The
 * alternative, a fake callback, would test a stand-in rather than the service's own dispatch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SageSherpaRecognitionServiceLifecycleTest {

    private lateinit var service: SageSherpaRecognitionService
    private lateinit var callback: RecognitionService.Callback

    /** Terminal signals the service delivered to the framework listener. */
    private val errors = CopyOnWriteArrayList<Int>()
    private val results = CopyOnWriteArrayList<Any>()

    @Before fun setUp() {
        // The cooldown is static, so one test's would otherwise mask every later one.
        setStatic("lastFailure", "")
        setStatic("unhealthyUntilMs", 0L)
        setStatic("lastCompletion", "")
        // RuntimeEnvironment, not androidx.test:core: the app declares only junit and robolectric as
        // test dependencies.
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        service = Robolectric.buildService(SageSherpaRecognitionService::class.java).create().get()
        callback = newCallback(service)
    }

    @After fun drainMainLooper() {
        // The service posts its dispatch through a handler; Robolectric's paused looper fails the
        // test if work is left queued.
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * A turn that was never cancelled still fails, and it still has to say so exactly once. This is
     * what keeps the fix below from swallowing genuine faults, or reporting them twice.
     */
    @Test fun liveTurnFailureReportsOneTerminalErrorAndOpensACooldown() {
        turns().begin(callback)

        runRecognition()

        assertEquals("a failed live turn must report exactly one terminal signal", 1, terminalCount())
        assertEquals(listOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY), errors.toList())
        assertTrue(results.isEmpty())
        assertTrue(
            "a real fault must still open a cooldown, but runtimeDetail said: " + detail(),
            detail().startsWith("recognizer not warm:")
        )
        assertNull(
            "the failed turn must retire itself so the next request is not treated as busy",
            turns().live
        )
    }

    /**
     * Cancelling releases the microphone, which makes the turn's pending read fail. That failure is
     * the cancel taking effect, so it must stay silent and must not open a cooldown: the owner's
     * next request arrives immediately and would otherwise be refused as busy for the cooldown's
     * remainder.
     */
    @Test fun cancelledTurnFailureIsSilentAndOpensNoCooldown() {
        turns().begin(callback)
        // The production cancel path, ownership guard and all.
        service.onCancel(callback)

        runRecognition()

        assertEquals("a cancelled turn must not deliver a terminal signal", 0, terminalCount())
        assertFalse(
            "cancelling must not leave a cooldown behind, but runtimeDetail said: " + detail(),
            detail().startsWith("recognizer not warm:")
        )
    }

    /**
     * Stopping is not cancelling, and the difference is the whole point of this test.
     *
     * onStopListening keeps the turn's ownership on purpose: the caller asked for the utterance to
     * end, not for the session to be abandoned, so the platform is still owed a terminal outcome.
     * A stopped turn that fails must therefore report that failure, or the session never ends. It
     * must still not open a cooldown, because the failure is the stop taking effect.
     */
    @Test fun stoppedTurnFailureReportsTheErrorButOpensNoCooldown() {
        turns().begin(callback)
        service.onStopListening(callback)

        runRecognition()

        assertEquals(
            "a stopped turn still owns the session and must end it, got " + errors.toList(),
            1,
            terminalCount()
        )
        assertEquals(listOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY), errors.toList())
        assertFalse(
            "stopping must not leave a cooldown behind, but runtimeDetail said: " + detail(),
            detail().startsWith("recognizer not warm:")
        )
    }

    /** A late callback for a turn that already retired must not disturb the live turn. */
    @Test fun lateCallbacksCannotDisturbTheLiveTurn() {
        val live = newCallback(service)
        turns().begin(callback)
        turns().retire(callback)
        turns().begin(live)
        val before = detail()

        service.onCancel(callback)
        service.onStopListening(callback)

        assertSame("the live turn must keep its ownership", live, turns().live)
        assertEquals("a late callback must not change service state", before, detail())
    }

    /**
     * A retiring worker must not release its successor's microphone. The worker tears itself down
     * after its turn is gone, by which point the field can already hold the next turn's
     * AudioRecord. Releasing that would leave the successor capturing nothing while it still
     * believes it is recording.
     */
    @Test fun retiredWorkerCannotReleaseTheSuccessorMicrophone() {
        val retired = newAudioRecord()
        val successor = newAudioRecord()
        field("microphone").set(service, successor)

        releaseMicrophone(retired)

        assertSame(
            "a retiring worker must leave the successor's microphone alone",
            successor,
            field("microphone").get(service)
        )

        // The successor does own it, so its own worker still releases it.
        releaseMicrophone(successor)
        assertNull(field("microphone").get(service))
    }

    /** Unconditional teardown, as onDestroy performs, does release the live microphone. */
    @Test fun unconditionalTeardownReleasesTheLiveMicrophone() {
        field("microphone").set(service, newAudioRecord())

        method("stopMicrophone").invoke(service)

        assertNull(field("microphone").get(service))
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun terminalCount(): Int = errors.size + results.size

    private fun detail(): String = SageSherpaRecognitionService.runtimeDetail()

    /**
     * Runs the production worker body. The turn cannot obtain a recognizer under Robolectric, so it
     * leaves through the same failure path a device turn takes when the engine is unavailable.
     */
    private fun runRecognition() {
        try {
            method("runRecognition", arrayOf(RecognitionService.Callback::class.java))
                .invoke(service, callback)
        } catch (thrown: java.lang.reflect.InvocationTargetException) {
            // The worker catches Throwable internally, so anything escaping is a real fault.
            throw AssertionError("runRecognition propagated ${thrown.targetException}")
        }
    }

    private fun turns(): CommandSpeechTurnOwnership =
        field("turns").get(service) as CommandSpeechTurnOwnership

    private fun releaseMicrophone(audio: AudioRecord) =
        method("releaseMicrophone", arrayOf(AudioRecord::class.java)).invoke(service, audio)

    private fun method(name: String, types: Array<Class<*>> = emptyArray()): Method =
        SageSherpaRecognitionService::class.java
            .getDeclaredMethod(name, *types)
            .apply { isAccessible = true }

    private fun field(name: String): Field =
        SageSherpaRecognitionService::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }

    private fun setStatic(name: String, value: Any) =
        field(name).apply { isAccessible = true }.set(null, value)

    /**
     * Builds a real Callback wired to this service and a recording listener.
     *
     * The framework only ever constructs a Callback around its own service, through a constructor
     * the SDK stub hides, so both links are restored reflectively. Everything below them is the
     * framework's own dispatch.
     */
    private fun newCallback(owner: RecognitionService): RecognitionService.Callback {
        val type = RecognitionService.Callback::class.java
        val constructor = type.declaredConstructors.first { it.parameterCount == 0 }
        constructor.isAccessible = true
        val created = constructor.newInstance() as RecognitionService.Callback
        type.declaredFields
            .first { RecognitionService::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }
            .set(created, owner)
        type.getDeclaredField("mListener").apply { isAccessible = true }
            .set(created, recordingListener())
        return created
    }

    /**
     * Stands in for the caller's IRecognitionListener, recording only what the service reports as a
     * terminal outcome. IRecognitionListener is a hidden AIDL interface that the SDK stub does not
     * expose, so it is implemented as a proxy.
     */
    private fun recordingListener(): Any {
        val listenerType = Class.forName("android.speech.IRecognitionListener")
        return Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, args ->
            when (method.name) {
                "onError" -> errors.add(args!![0] as Int)
                "onResults", "onSegmentResults" -> results.add(method.name)
            }
            null
        }
    }

    /** Robolectric supplies the AudioRecord object; no host device is opened. */
    private fun newAudioRecord(): AudioRecord =
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            16_000 * 2
        )
}
