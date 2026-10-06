package com.pineapple.sage

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.pineapple.sageos2.speech.CommandSpeechTurnOwnership
import com.pineapple.sageos2.speech.CommandSpeechTurnState
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.io.File
import java.io.RandomAccessFile
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
 *    cancel is its successor's;
 *  - a worker paused during recognizer setup could be cancelled, superseded by a turn that opened its
 *    own microphone, and then resume and publish over the top of it, so two turns contended for one
 *    device while the cancelled turn went on reporting progress to a caller that had moved on.
 *
 * The verified sherpa engine and model are native, so the recognizer is genuinely unavailable here
 * and every turn that runs the worker leaves through the same failure path a device turn takes when
 * the engine cannot load. That is the case under test, not a workaround. No audio is captured or
 * decoded, so these tests make no claim about real microphone behaviour; that still needs a device.
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

    /** Terminal signals the service delivered, across every callback. */
    private val errors = CopyOnWriteArrayList<Int>()
    private val results = CopyOnWriteArrayList<Any>()

    /**
     * The same signals, kept per callback.
     *
     * Two turns are live at once in the reported sequence, so a shared list cannot say which turn
     * reported. That attribution is the whole question in those cases: a cancelled turn that reports
     * is the defect, not the fact that some turn reported.
     */
    private val perCallback = java.util.Collections.synchronizedMap(
        LinkedHashMap<RecognitionService.Callback, MutableList<String>>()
    )

    @Before fun setUp() {
        // These are static, so one test's would otherwise mask every later one.
        setStatic("lastFailure", "")
        setStatic("unhealthyUntilMs", 0L)
        setStatic("lastCompletion", "")
        setStatic("captureRefusals", 0L)
        setStatic("lastCaptureRefusal", "")
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
        val state = admit(callback)

        runRecognition(state)

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
        val state = admit(callback)
        // The production cancel path, ownership guard and all.
        service.onCancel(callback)

        runRecognition(state)

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
        val state = admit(callback)
        service.onStopListening(callback)

        runRecognition(state)

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
        val retired = newCallback(service)
        val live = newCallback(service)
        admit(retired)
        // The real cancel path, so the turn is retired the way a cancel retires it.
        service.onCancel(retired)
        val liveState = admit(live)
        val before = detail()

        service.onCancel(retired)
        service.onStopListening(retired)

        assertSame("the live turn must keep its ownership", live, turns().live)
        assertSame("and keep the turn it admitted", liveState, liveTurnOrNull())
        assertEquals("a late callback must not change service state", before, detail())
    }

    /**
     * A retiring turn must not release its successor's microphone. The turn tears itself down after
     * its turn is gone, by which point a successor can hold the device. Releasing that would leave
     * the successor capturing nothing while it still believes it is recording, and would let a third
     * turn open a second AudioRecord on top of it.
     */
    @Test fun aRetiringTurnCannotReleaseItsSuccessorsMicrophone() {
        val successor = newCallback(service)
        val successorTurn = admit(successor)
        val successorAudio = detachedAudioRecord()
        assertTrue("the successor takes the device", claimMicrophone(successorTurn, successorAudio))

        // A turn that is already gone, holding nothing.
        val gone = CommandSpeechTurnState("already-gone")
        retireTurn(gone)

        assertTrue(
            "a retiring turn must leave the successor's microphone alone",
            liveClaim().holds(successorAudio)
        )
    }

    /** Unconditional teardown, as onDestroy performs, does release the live microphone. */
    @Test fun destroyingTheServiceReleasesTheLiveMicrophone() {
        val state = admit(callback)
        val audio = detachedAudioRecord()
        assertTrue(claimMicrophone(state, audio))

        service.onDestroy()

        assertNull("destroying the service must give the device back", liveTurnOrNull())
        assertFalse("and empty the claim", state.capture.isHeld())
    }

    // ---- the reported race: cancel during setup, then an immediate retry -----------------------

    /**
     * The reported sequence, with every step performed in the order the platform performs it.
     *
     * Turn A is admitted and parks in recognizer setup, so it is live and has opened nothing. The
     * owner cancels and presses Talk again, and turn B is admitted and opens its microphone. Turn A
     * then resumes and reaches the point where it publishes the microphone it opened. It must lose:
     * its claim was emptied by the cancel, and the device belongs to B.
     *
     * Nothing here is timed. Each step is a production method called in sequence, so the test fails
     * on a wrong ordering rule rather than on a slow machine. A worker thread is deliberately not
     * involved: under Robolectric it would die inside obtainRecognizer before reaching the publish
     * step, which is why the reported race is reproduced by calling that step directly.
     */
    @Test fun aTurnCancelledDuringSetupCannotTakeTheMicrophoneFromItsSuccessor() {
        fakeSherpaReady()

        val first = newCallback(service)
        val firstTurn = admit(first)

        // The owner cancels, then immediately asks again.
        service.onCancel(first)
        val second = newCallback(service)
        val secondTurn = admit(second)

        val secondAudio = detachedAudioRecord()
        assertTrue(
            "the successor must be able to take the device",
            claimMicrophone(secondTurn, secondAudio)
        )

        // Turn A resumes, holding the microphone it opened while it was parked.
        val firstAudio = detachedAudioRecord()
        assertFalse(
            "a turn cancelled during setup must not publish over its successor",
            claimMicrophone(firstTurn, firstAudio)
        )

        assertTrue("the successor keeps the device", liveClaim().holds(secondAudio))
        assertTrue("and is still the turn the platform is waiting on", turns().owns(second))
        assertTrue(
            "the cancelled turn stays cancelled whatever its successor did",
            firstTurn.stopped
        )
        assertTrue(
            "the refusal has to be reportable in an export, but runtimeDetail said: " + detail(),
            detail().contains("capture refused 1x (superseded)")
        )
    }

    /**
     * The same sequence with real worker threads, to show the cancelled turn reports nothing while
     * its successor is still refused by a broken engine.
     *
     * The workers are held inside obtainRecognizer by this test's own hold on RECOGNIZER_LOCK, which
     * is the only place they can be parked deterministically: the engine is native, so a worker that
     * got past it would have no recognizer to run. The cancel therefore lands while the worker is
     * provably parked, rather than at a hoped-for moment.
     */
    @Test fun aTurnCancelledWhileItsWorkerIsParkedReportsNothing() {
        fakeSherpaReady()

        val first = newCallback(service)
        val second = newCallback(service)
        var firstWorker: Thread? = null
        var secondWorker: Thread? = null
        // The monitor is taken before either turn is admitted, so both workers are provably parked
        // inside obtainRecognizer while the cancel and the retry happen.
        synchronized(recognizerLock()) {
            onStartListening(first)
            awaitWorkerParked()
            firstWorker = currentWorker()
            service.onCancel(first)
            onStartListening(second)
            secondWorker = currentWorker()
            assertTrue("the successor must be admitted", turns().owns(second))
        }
        shadowOf(Looper.getMainLooper()).idle()
        firstWorker?.join(THREAD_JOIN_MS)
        secondWorker?.join(THREAD_JOIN_MS)

        assertEquals(
            "a cancelled turn must report nothing at all, got " + eventsFor(first),
            emptyList<String>(),
            eventsFor(first)
        )
        assertEquals(
            "and the turn that is still live reports for itself",
            1,
            eventsFor(second).size
        )
    }

    // ---- twenty mixed turns -------------------------------------------------------------------

    /**
     * Twenty turns in the shapes the platform actually produces, in the orders it produces them.
     *
     * Each turn is one of: a plain finish, a stop, a cancel, a cancel that lands during setup and is
     * followed immediately by a retry, and a finish followed by late callbacks. Three invariants are
     * asserted after every turn rather than only at the end, so a failure names the turn that broke
     * it: no turn reports twice, the device is free between turns, and the turn the platform is
     * waiting on is the one that is live.
     *
     * The count of refused captures is asserted too. Four of the twenty are cancelled during setup
     * and each must refuse exactly once, which is what makes the diagnostic note a number that can
     * be checked rather than a word that can be read.
     */
    @Test fun twentyTurnMixedLifecycleKeepsEveryTurnSound() {
        fakeSherpaReady()

        var refused = 0
        repeat(TWENTY_TURNS) { index ->
            val current = newCallback(service)
            when (index % 5) {
                0 -> { // A turn that simply finishes.
                    val state = admit(current)
                    val audio = detachedAudioRecord()
                    assertTrue(claimMicrophone(state, audio))
                    retireTurn(state)
                    turns().retire(current)
                }
                1 -> { // The owner stops the turn and it reports its own ending.
                    val state = admit(current)
                    assertTrue(claimMicrophone(state, detachedAudioRecord()))
                    service.onStopListening(current)
                    runRecognition(state)
                    assertEquals(
                        "a stopped turn reports exactly one outcome, got " + eventsFor(current),
                        1,
                        eventsFor(current).size
                    )
                }
                2 -> { // The owner cancels and says nothing more about it.
                    val state = admit(current)
                    assertTrue(claimMicrophone(state, detachedAudioRecord()))
                    service.onCancel(current)
                    runRecognition(state)
                    assertEquals(
                        "a cancelled turn reports nothing, got " + eventsFor(current),
                        emptyList<String>(),
                        eventsFor(current)
                    )
                }
                3 -> { // The reported race: cancelled during setup, retried immediately.
                    val cancelled = admit(current)
                    service.onCancel(current)
                    val successor = newCallback(service)
                    val successorTurn = admit(successor)
                    val successorAudio = detachedAudioRecord()
                    assertTrue(claimMicrophone(successorTurn, successorAudio))
                    assertFalse(
                        "a turn cancelled during setup must not publish",
                        claimMicrophone(cancelled, detachedAudioRecord())
                    )
                    refused++
                    // The successor finishes normally, and the cancelled turn's late callbacks
                    // arrive afterwards and must be ignored.
                    retireTurn(successorTurn)
                    turns().retire(successor)
                    service.onCancel(current)
                    service.onStopListening(current)
                    assertEquals(
                        "a superseded turn reports nothing, got " + eventsFor(current),
                        emptyList<String>(),
                        eventsFor(current)
                    )
                }
                else -> { // A turn that finished, then late callbacks for it.
                    val state = admit(current)
                    val audio = detachedAudioRecord()
                    assertTrue(claimMicrophone(state, audio))
                    releaseCapture(state)
                    turns().retire(current)
                    service.onCancel(current)
                    service.onStopListening(current)
                    assertFalse(
                        "a late callback must not re-take the device",
                        state.capture.isHeld()
                    )
                    assertFalse(
                        "nor revive a turn that had already finished",
                        state.stopped
                    )
                }
            }

            // Invariant: the device is free, or held only by the turn that is live.
            val live = liveTurnOrNull()
            assertTrue(
                "turn $index left the device held with no live turn behind it",
                live == null || !live.capture.isHeld()
            )
            // Invariant: no turn ever reported twice.
            perCallback.values.forEach { events ->
                assertTrue(
                    "turn $index saw a second terminal outcome: $events",
                    events.size <= 1
                )
            }
        }

        assertEquals(
            "across twenty mixed turns only the four stopped turns report, and each reports once",
            TWENTY_TURNS / 5,
            terminalCount()
        )
        assertEquals(
            "the four cancelled-during-setup turns must each refuse exactly once",
            4,
            refused
        )
        assertTrue(
            "and the export must say so, but runtimeDetail said: " + detail(),
            detail().contains("capture refused 4x (superseded)")
        )
        assertFalse(
            "no turn in this sequence may leave a cooldown behind, but runtimeDetail said: " + detail(),
            detail().startsWith("recognizer not warm:")
        )
    }

    // ---- admission after teardown ------------------------------------------------------------

    /**
     * A turn that has given the microphone back no longer blocks the next one.
     *
     * The owner stops or cancels and immediately asks again, which is the normal retry. The retiring
     * worker may still be unwinding at that moment, so admission has to follow the device rather
     * than the thread: the thread can outlive its own turn for as long as it takes to clear the
     * AudioRecord and the stream, and during that time it holds nothing.
     *
     * This drives onStartListening itself rather than the claim, so it fails against an admission
     * rule that keys on the previous worker thread. The worker thread it starts is held inside
     * obtainRecognizer, which leaves the turn owned and observable instead of racing the assertion.
     */
    @Test fun admissionFollowsTheDeviceRatherThanTheRetiringThread() {
        fakeSherpaReady()

        // A live turn holding the device: the request must be refused, and no turn may begin.
        val holding = admit(callback)
        assertTrue(claimMicrophone(holding, detachedAudioRecord()))

        val refused = newCallback(service)
        onStartListening(refused)
        assertEquals(
            "a held device must refuse the next turn",
            listOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY),
            errors.toList()
        )
        assertSame("a refused request must preserve the current owner", callback, turns().live)

        // The same request once the device is gone, with the turn still unwinding. This is the half
        // that decides which rule admission follows.
        releaseCapture(holding)
        turns().retire(callback)

        val successor = newCallback(service)
        synchronized(recognizerLock()) {
            onStartListening(successor)
            assertSame(
                "a released device must admit the next turn even while its thread unwinds",
                successor,
                turns().live
            )
        }
        joinWorker()
    }

    /**
     * Teardown that finishes quickly admits the next turn too, so the bound is an optimisation
     * rather than a requirement.
     */
    @Test fun promptTeardownAdmitsTheNextTurn() {
        fakeSherpaReady()

        val retiring = admit(callback)
        assertTrue(claimMicrophone(retiring, detachedAudioRecord()))
        retireTurn(retiring)
        turns().retire(callback)

        val successor = newCallback(service)
        synchronized(recognizerLock()) {
            onStartListening(successor)
            assertSame(
                "a finished teardown must admit the next turn",
                successor,
                turns().live
            )
        }
        joinWorker()
    }

    /**
     * A worker that dies holding the device must not wedge the service.
     *
     * The claim names the turn holding it, so a turn whose worker has vanished still has to leave
     * admission open; otherwise one dead worker would refuse every later request for the life of the
     * service.
     */
    @Test fun aDeadOwnerDoesNotWedgeAdmission() {
        fakeSherpaReady()

        val dead = admit(callback)
        assertTrue(claimMicrophone(dead, detachedAudioRecord()))
        // The turn's ownership is gone, as a dead worker's finally block would have left it, but its
        // device claim was never released. That is the state this guards.
        turns().retire(callback)
        val exited = Thread { }
        exited.start()
        exited.join(THREAD_JOIN_MS)
        assertFalse(exited.isAlive)
        dead.worker = exited

        val successor = newCallback(service)
        synchronized(recognizerLock()) {
            onStartListening(successor)
            assertSame(
                "a turn that died holding the device must not keep refusing requests",
                successor,
                turns().live
            )
        }
        joinWorker()
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
        val state = admit(callback)

        fail(state, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, IllegalStateException("first"))
        fail(state, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, IllegalStateException("second"))
        emitTerminalError(state, SpeechRecognizer.ERROR_NO_MATCH)

        assertEquals(
            "a turn must report exactly one terminal signal",
            1,
            terminalCount()
        )
    }

    /**
     * The guard belongs to the turn, so the turn after a delivered outcome is owed one of its own
     * without anything having to reset it.
     *
     * Resetting a shared guard at admission is what made a retiring turn able to claim a second
     * outcome after its successor had been admitted.
     */
    @Test fun eachTurnGetsItsOwnTerminalOutcome() {
        val first = admit(callback)
        fail(first, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, IllegalStateException("first"))
        assertEquals(1, terminalCount())
        retireTurn(first)
        turns().retire(callback) // Match the worker finally block after releasing capture.

        // No reset: the successor's guard was never touched by the first turn's outcome.
        val successor = newCallback(service)
        val second = admit(successor)
        fail(second, SpeechRecognizer.ERROR_NO_MATCH, IllegalStateException("second"))

        assertEquals(
            "a later turn must still report an outcome of its own",
            2,
            terminalCount()
        )
    }

    @Test fun stoppedSetupExitCompletesExactlyOnceWithoutCooldown() {
        val state = admit(callback)
        service.onStopListening(callback)
        method("completeStoppedTurn", CommandSpeechTurnState::class.java).invoke(service, state)
        method("completeStoppedTurn", CommandSpeechTurnState::class.java).invoke(service, state)
        assertEquals(listOf(SpeechRecognizer.ERROR_NO_MATCH), errors.toList())
        assertFalse(detail().startsWith("recognizer not warm:"))
    }

    @Test fun cancelledSetupExitStaysSilentAndStopped() {
        val state = admit(callback)
        service.onCancel(callback)
        assertTrue(state.stopped)
        method("completeStoppedTurn", CommandSpeechTurnState::class.java).invoke(service, state)
        assertEquals(0, terminalCount())
        assertFalse(state.capture.isHeld())
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun terminalCount(): Int = errors.size + results.size

    private fun detail(): String = SageSherpaRecognitionService.runtimeDetail()

    private fun eventsFor(callback: RecognitionService.Callback): MutableList<String> =
        perCallback.getOrPut(callback) { mutableListOf() }

    private fun turns(): CommandSpeechTurnOwnership =
        field("turns").get(service) as CommandSpeechTurnOwnership

    private fun liveTurnOrNull(): CommandSpeechTurnState? =
        (field("liveTurn").get(service) as java.util.concurrent.atomic.AtomicReference<*>).get() as CommandSpeechTurnState?

    /** The claim of whichever turn is live, for asserting what the device is currently held by. */
    private fun liveClaim(): com.pineapple.sageos2.speech.CommandSpeechCaptureClaim =
        liveTurnOrNull()?.capture
            ?: throw AssertionError("no live turn holds a claim")

    /**
     * Admits a turn through the production admission steps, without starting its worker.
     *
     * onStartListening performs exactly these steps and then starts a thread. The thread is left out
     * on purpose: the verified engine is native, so a real worker dies inside obtainRecognizer
     * before it reaches the microphone, and what these tests are about is the order of the steps.
     * Every step called here is production code, in the order onStartListening uses.
     */
    private fun admit(callback: RecognitionService.Callback): CommandSpeechTurnState {
        val state = CommandSpeechTurnState(callback)
        assertTrue("the turn must win the device", claimDeviceFor(state))
        assertTrue("and take ownership", turns().begin(callback))
        return state
    }

    private fun claimDeviceFor(state: CommandSpeechTurnState): Boolean =
        method("claimDeviceFor", CommandSpeechTurnState::class.java).invoke(service, state) as Boolean

    private fun claimMicrophone(state: CommandSpeechTurnState, audio: AudioRecord): Boolean =
        method("claimMicrophone", CommandSpeechTurnState::class.java, AudioRecord::class.java)
            .invoke(service, state, audio) as Boolean

    private fun releaseCapture(state: CommandSpeechTurnState) =
        method("releaseCapture", CommandSpeechTurnState::class.java).invoke(service, state)

    private fun retireTurn(state: CommandSpeechTurnState) =
        method("retireTurn", CommandSpeechTurnState::class.java).invoke(service, state)

    /**
     * Runs the production worker body. The turn cannot obtain a recognizer under Robolectric, so it
     * leaves through the same failure path a device turn takes when the engine is unavailable.
     */
    private fun runRecognition(state: CommandSpeechTurnState) {
        try {
            method("runRecognition", CommandSpeechTurnState::class.java).invoke(service, state)
        } catch (thrown: java.lang.reflect.InvocationTargetException) {
            // The worker catches Throwable internally, so anything escaping is a real fault.
            throw AssertionError("runRecognition propagated ${thrown.targetException}")
        }
    }

    /** The real admission path, including its device, availability and ownership gates. */
    private fun onStartListening(callback: RecognitionService.Callback) =
        method("onStartListening", android.content.Intent::class.java, RecognitionService.Callback::class.java)
            .invoke(service, null, callback)

    private fun recognizerLock(): Any =
        SageSherpaRecognitionService::class.java.getDeclaredField("RECOGNIZER_LOCK")
            .apply { isAccessible = true }.get(null) as Any

    /**
     * Waits for the worker to be parked on this test's hold of RECOGNIZER_LOCK.
     *
     * The thread state is the evidence, not a sleep: BLOCKED on the monitor is the only state a
     * worker can be in while it waits for the lock this test is holding, and the wait is bounded
     * only so that a failure is reported rather than hung on.
     */
    private fun awaitWorkerParked() {
        val deadline = System.currentTimeMillis() + THREAD_JOIN_MS
        while (System.currentTimeMillis() < deadline) {
            val running = currentWorker()
            if (running != null && running.state == Thread.State.BLOCKED) return
            Thread.sleep(1L)
        }
        throw AssertionError("the worker never parked inside obtainRecognizer")
    }

    private fun currentWorker(): Thread? = field("worker").get(service) as Thread?

    /**
     * Stops and joins the worker onStartListening started. It was parked inside obtainRecognizer by
     * the test's own monitor, and this lets it out to fail and retire itself.
     */
    private fun joinWorker() {
        shadowOf(Looper.getMainLooper()).idle()
        val running = field("worker").get(service) as Thread?
        running?.join(THREAD_JOIN_MS)
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
     * Presents a ready backend so onStartListening reaches its device and ownership gates.
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

    private fun emitTerminalError(state: CommandSpeechTurnState, code: Int) =
        method("emitTerminalError", CommandSpeechTurnState::class.java, java.lang.Integer.TYPE)
            .invoke(service, state, code)

    private fun fail(state: CommandSpeechTurnState, code: Int, problem: Throwable) =
        method("fail", CommandSpeechTurnState::class.java, java.lang.Integer.TYPE, Throwable::class.java)
            .invoke(service, state, code, problem)

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
        perCallback[created] = mutableListOf()
        type.getDeclaredField("mListener").apply { isAccessible = true }
            .set(created, recordingListener(created))
        return created
    }

    /**
     * Stands in for the caller's IRecognitionListener, recording only what the service reports as a
     * terminal outcome. IRecognitionListener is a hidden AIDL interface that the SDK stub does not
     * expose, so it is implemented as a proxy.
     */
    private fun recordingListener(callback: RecognitionService.Callback): Any {
        val listenerType = Class.forName("android.speech.IRecognitionListener")
        return Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, args ->
            when (method.name) {
                "onError" -> {
                    errors.add(args!![0] as Int)
                    eventsFor(callback).add("error:" + args!![0])
                }
                "onResults", "onSegmentResults" -> {
                    results.add(method.name)
                    eventsFor(callback).add(method.name)
                }
            }
            null
        }
    }

    /**
     * A standalone AudioRecord, not the one the service is holding.
     *
     * The admission tests need a capture that the service is not already holding, so this is used
     * where the test is about the claim rather than about releasing the live microphone.
     */
    private fun detachedAudioRecord(): AudioRecord = newAudioRecord()

    /**
     * How long to wait for a test thread to reach a state. Generous, because it only ever bounds a
     * failure; the joins are what the assertions depend on.
     */
    private val THREAD_JOIN_MS = 5_000L

    /** The length of the mixed sequence in twentyTurnMixedLifecycleKeepsEveryTurnSound. */
    private val TWENTY_TURNS = 20

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
