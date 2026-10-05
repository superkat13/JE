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
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
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

    // ---- admission after teardown ------------------------------------------------------------

    /**
     * A turn that has given the microphone back no longer blocks the next one.
     *
     * The owner stops or cancels and immediately asks again, which is the normal retry. The retiring
     * worker may still be unwinding at that moment, so admission has to follow the capture rather
     * than the thread: the thread can outlive its own turn for as long as it takes to clear the
     * AudioRecord and the stream, and during that time it holds nothing.
     *
     * This drives onStartListening itself rather than the marker, so it fails against an admission
     * rule that keys on the previous worker thread. The worker thread it starts is held inside
     * obtainRecognizer, which leaves the turn owned and observable instead of racing the assertion.
     */
    @Test fun admissionFollowsTheCaptureRatherThanTheRetiringThread() {
        fakeSherpaReady()

        // A live capture held by a running worker: the request must be refused, and no turn may
        // begin. The worker field is set as well, so this half holds under either admission rule.
        val holding = CountDownLatch(1)
        val live = Thread { takeMicrophone(detachedAudioRecord()); holding.countDown(); Thread.sleep(1_000) }
        field("worker").set(service, live)
        live.start()
        assertTrue("the worker should have taken the microphone", holding.await(5, SECONDS))

        val refused = newCallback(service)
        onStartListening(refused)
        assertEquals(
            "a live capture must refuse the next turn",
            listOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY),
            errors.toList()
        )
        assertNull("a refused request must not begin a turn", turns().live)
        live.join(5_000)

        // The same request once the capture is gone, with a retiring thread still running. This is
        // the half that decides which rule admission follows.
        val slow = retiringThreadAfterRelease()
        assertTrue("the retiring thread should still be running", slow.isAlive)

        val successor = newCallback(service)
        synchronized(recognizerLock()) {
            onStartListening(successor)
            assertSame(
                "a released capture must admit the next turn even while its thread unwinds",
                successor,
                turns().live
            )
        }
        stopWorker()
    }

    /**
     * Teardown that finishes quickly admits the next turn too, so the bound is an optimisation
     * rather than a requirement.
     */
    @Test fun promptTeardownAdmitsTheNextTurn() {
        fakeSherpaReady()

        val audio = detachedAudioRecord()
        val finished = CountDownLatch(1)
        Thread {
            takeMicrophone(audio)
            releaseMicrophone(audio)
            finished.countDown()
        }.start()
        assertTrue(finished.await(5, SECONDS))

        val successor = newCallback(service)
        synchronized(recognizerLock()) {
            onStartListening(successor)
            assertSame(
                "a finished teardown must admit the next turn",
                successor,
                turns().live
            )
        }
        stopWorker()
    }

    /**
     * A worker that dies without releasing must not wedge the service.
     *
     * The marker names the owning thread, so a thread that vanishes still has to leave admission
     * open; otherwise one dead worker would refuse every later request.
     */
    @Test fun aDeadOwnerDoesNotWedgeAdmission() {
        fakeSherpaReady()

        val dead = CountDownLatch(1)
        Thread { takeMicrophone(detachedAudioRecord()); dead.countDown() }.start()
        assertTrue(dead.await(5, SECONDS))

        val successor = newCallback(service)
        synchronized(recognizerLock()) {
            onStartListening(successor)
            assertSame(
                "an owner that died without releasing must not keep refusing requests",
                successor,
                turns().live
            )
        }
        stopWorker()
    }

    // ---- exactly one terminal outcome --------------------------------------------------------

    /**
     * A turn gets one terminal outcome, never a second one after it.
     *
     * A stopped turn still owns the session and is owed an outcome. The finished window and the
     * failure path are both reachable for one turn, because emitting the window outcome is followed
     * by more work inside the same try, and anything that throws there falls into the catch that
     * reports an error. Without the guard the caller could receive an error after the transcript it
     * already had.
     */
    @Test fun aTurnReportsOnlyOneTerminalOutcome() {
        turns().begin(callback)

        fail(callback, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, IllegalStateException("first"))
        fail(callback, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, IllegalStateException("second"))
        emitTerminalError(callback, SpeechRecognizer.ERROR_NO_MATCH)

        assertEquals(
            "a turn must report exactly one terminal signal",
            1,
            terminalCount()
        )
    }

    /**
     * The guard is per turn, so the turn after a delivered outcome is owed one of its own.
     */
    @Test fun eachTurnGetsItsOwnTerminalOutcome() {
        turns().begin(callback)
        fail(callback, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, IllegalStateException("first"))
        assertEquals(1, terminalCount())

        // The first turn ends the way a worker's finally block ends it, and onStartListening clears
        // the guard as it admits the successor. An unavailable engine never reaches that point here,
        // so the reset is applied the way admission applies it.
        turns().retire(callback)
        terminalEmitted().set(false)

        val successor = newCallback(service)
        assertTrue(turns().begin(successor))
        fail(successor, SpeechRecognizer.ERROR_NO_MATCH, IllegalStateException("second"))

        assertEquals(
            "a later turn must still report an outcome of its own",
            2,
            terminalCount()
        )
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
            method("runRecognition", RecognitionService.Callback::class.java)
                .invoke(service, callback)
        } catch (thrown: java.lang.reflect.InvocationTargetException) {
            // The worker catches Throwable internally, so anything escaping is a real fault.
            throw AssertionError("runRecognition propagated ${thrown.targetException}")
        }
    }

    private fun turns(): CommandSpeechTurnOwnership =
        field("turns").get(service) as CommandSpeechTurnOwnership

    private fun releaseMicrophone(audio: AudioRecord) =
        method("releaseMicrophone", AudioRecord::class.java).invoke(service, audio)

    private fun takeMicrophone(audio: AudioRecord) =
        method("takeMicrophone", AudioRecord::class.java).invoke(service, audio)

    private fun microphoneCaptured(): Boolean =
        method("microphoneCaptured").invoke(service) as Boolean

    /** The real admission path, including its capture, availability and ownership gates. */
    private fun onStartListening(callback: RecognitionService.Callback) =
        method("onStartListening", android.content.Intent::class.java, RecognitionService.Callback::class.java)
            .invoke(service, null, callback)

    private fun stopMicrophone() = method("stopMicrophone").invoke(service)

    private fun recognizerLock(): Any =
        SageSherpaRecognitionService::class.java.getDeclaredField("RECOGNIZER_LOCK")
            .apply { isAccessible = true }.get(null) as Any

    /**
     * A thread that has released its capture and is still running, which is what a slow teardown
     * looks like from admission's point of view.
     */
    private fun retiringThreadAfterRelease(): Thread {
        val released = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val thread = Thread {
            takeMicrophone(detachedAudioRecord())
            releaseMicrophone(microphoneField())
            released.countDown()
            // Unwinding after the capture is gone, which is what a slow teardown looks like: the
            // thread is alive and still registered as the worker, but it holds nothing.
            finish.await()
        }
        // Installed as the worker, so an admission rule that reads thread liveness sees a live one.
        field("worker").set(service, thread)
        thread.start()
        assertTrue("the worker should have released its capture", released.await(5, SECONDS))
        finish.countDownAfter(thread)
        return thread
    }

    /**
     * Lets a retiring test thread finish on a timer, so it outlives the assertion without leaking.
     */
    private fun CountDownLatch.countDownAfter(thread: Thread) {
        Thread {
            Thread.sleep(1_000)
            countDown()
            thread.join(5_000)
        }.apply { isDaemon = true }.start()
    }

    private fun microphoneField(): AudioRecord =
        field("microphone").get(service) as AudioRecord

    /**
     * Stops the worker onStartListening started. It is parked inside obtainRecognizer by the test's
     * own monitor, and this lets it out to fail and retire itself.
     */
    private fun stopWorker() {
        shadowOf(Looper.getMainLooper()).idle()
        val worker = field("worker").get(service) as Thread?
        worker?.join(5_000)
    }

    /**
     * Robolectric leaves nativeLibraryDir unset, so it is filled in first: the readiness check looks
     * for the two native libraries there before falling back to scanning the APK.
     */
    private fun nativeLibraryDirectory(): File {
        val info = RuntimeEnvironment.getApplication().applicationInfo
        if (info.nativeLibraryDir != null) return File(info.nativeLibraryDir)
        val created = File(RuntimeEnvironment.getApplication().filesDir, "native-libs").apply { mkdirs() }
        info.nativeLibraryDir = created.absolutePath
        return created
    }

    /**
     * Presents a ready backend so onStartListening reaches its capture and ownership gates.
     *
     * The engine and model are native, so without this every request is refused at the availability
     * gate and the admission rules below would never be reached. Only the readiness files are
     * staged: the worker that runs once admitted still fails to build a recognizer and leaves
     * through the real failure path.
     */
    private fun fakeSherpaReady() {
        val model = SageSpeechBackendState.modelDirectory(RuntimeEnvironment.getApplication())
        model.mkdirs()
        File(model, "verified.properties").writeText("ok")
        // exactFile compares lengths, so the weights are staged at their verified sizes as sparse
        // files rather than being written out.
        mapOf(
            "tokens.txt" to 5_048L,
            "encoder-epoch-99-avg-1.int8.onnx" to 42_845_182L,
            "decoder-epoch-99-avg-1.onnx" to 2_092_272L,
            "joiner-epoch-99-avg-1.int8.onnx" to 259_572L
        ).forEach { (name, size) ->
            RandomAccessFile(File(model, name), "rw").apply { setLength(size) }.close()
        }
        // The java API comes from the real AAR on the test classpath; only the native libraries
        // have to be staged, and only their presence is checked.
        val nativeDir = nativeLibraryDirectory()
        nativeDir.mkdirs()
        File(nativeDir, "libsherpa-onnx-jni.so").writeText("")
        File(nativeDir, "libonnxruntime.so").writeText("")
    }

    private fun terminalEmitted(): AtomicBoolean =
        field("terminalEmitted").get(service) as AtomicBoolean

    private fun emitTerminalError(callback: RecognitionService.Callback, code: Int) =
        method("emitTerminalError", RecognitionService.Callback::class.java, java.lang.Integer.TYPE)
            .invoke(service, callback, code)

    private fun fail(callback: RecognitionService.Callback, code: Int, problem: Throwable) =
        method("fail", RecognitionService.Callback::class.java, java.lang.Integer.TYPE, Throwable::class.java)
            .invoke(service, callback, code, problem)

    private fun method(name: String, vararg types: Class<*>): Method =
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

    /**
     * A standalone AudioRecord, not the service's live field.
     *
     * The admission tests need a capture that the service is not already holding, so this is used
     * where the test is about the marker rather than about releasing the live microphone.
     */
    private fun detachedAudioRecord(): AudioRecord = newAudioRecord()

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
