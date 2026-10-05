package com.pineapple.sageos2.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device claim, and the interleavings it has to survive.
 *
 * The rule under test is that a turn which was stopped, cancelled or superseded can never open the
 * microphone, and can never take the microphone a successor is already holding. Every case here is
 * driven by hand in the order the service performs the steps, because the defect was an ordering
 * defect: no single step is wrong, only the order of two of them.
 *
 * Off-device and deterministic. No threads, no timing, no sherpa. What this cannot cover is stated
 * rather than implied: whether the microphone itself behaves correctly is device behaviour, and the
 * service-level consequences of these rules are covered by SageSherpaRecognitionServiceLifecycleTest.
 */
class CommandSpeechCaptureClaimTest {

    /** Stands in for an AudioRecord. The claim is identity-based, so any object will do. */
    private val record = Any()

    @Test
    fun aFreshClaimIsHeldButPending() {
        val claim = CommandSpeechCaptureClaim()
        assertTrue("an admitted turn holds the device from the moment it is admitted", claim.isHeld())
        assertTrue("but it has not opened its microphone yet", claim.isPending())
    }

    @Test
    fun publishingReplacesThePendingPlaceholder() {
        val claim = CommandSpeechCaptureClaim()
        assertTrue(claim.publish(record))
        assertFalse(claim.isPending())
        assertTrue(claim.isHeld())
        assertTrue(claim.holds(record))
    }

    @Test
    fun releaseHandsBackTheRecordsOwnMicrophone() {
        val claim = CommandSpeechCaptureClaim()
        claim.publish(record)
        assertSame("the releasing turn gets its own record back", record, claim.release())
        assertFalse(claim.isHeld())
    }

    @Test
    fun releasingAPendingClaimYieldsNothingToRelease() {
        val claim = CommandSpeechCaptureClaim()
        assertNull(
            "a turn cancelled before it opened its microphone has nothing to release",
            claim.release()
        )
        assertFalse(claim.isHeld())
    }

    /**
     * The defect. A turn is admitted, so its claim is pending; it is cancelled while still setting
     * up, which empties the claim; a successor is admitted and opens its own microphone; then the
     * first turn resumes. It must lose, because the cancel already emptied its claim and the
     * successor's microphone is not its to take.
     */
    @Test
    fun aCancelledTurnCannotPublishAfterItsSuccessorOpenedTheMicrophone() {
        val cancelled = CommandSpeechCaptureClaim()
        val successor = CommandSpeechCaptureClaim()

        // Admitted, so pending: the turn is live but has no microphone yet.
        assertTrue(cancelled.isPending())

        // Cancelled while still setting up. This is what the cancel does to the claim.
        cancelled.release()

        // The successor is admitted and opens the microphone.
        assertTrue(successor.publish(record))

        // The cancelled turn resumes. Its claim was emptied, so there is nothing to publish into.
        assertFalse(
            "a turn cancelled during setup must not take the microphone",
            cancelled.publish(Any())
        )
        assertFalse(cancelled.isHeld())
        assertTrue("the successor keeps the microphone", successor.holds(record))
    }

    /**
     * The other order. The worker wins the publish and the cancel arrives afterwards, so the cancel
     * has to find the record in the claim and release it. Nothing may be left behind for a third turn
     * to walk into.
     */
    @Test
    fun aCancelAfterPublishStillRecoversTheMicrophone() {
        val claim = CommandSpeechCaptureClaim()
        assertTrue(claim.publish(record))

        assertSame(claim.release(), record)
        assertFalse("the device is free once the cancel has run", claim.isHeld())
        assertFalse("and it stays free", claim.publish(Any()))
    }

    /** Two turns contending for one device: exactly one may hold it. */
    @Test
    fun onlyOneTurnCanHoldTheDevice() {
        val first = CommandSpeechCaptureClaim()
        val second = CommandSpeechCaptureClaim()

        assertTrue(first.publish(record))
        assertFalse(
            "a second turn must not take a device that is already held",
            second.publish(Any())
        )
        assertTrue(first.holds(record))
    }

    /**
     * A claim is not reusable. A turn that gave the device up cannot come back and re-take it, which
     * is what stops a very late worker from re-entering a device its successor now holds.
     */
    @Test
    fun aReleasedClaimCannotBeReused() {
        val claim = CommandSpeechCaptureClaim()
        claim.publish(record)
        claim.release()
        assertFalse(claim.publish(Any()))
        assertFalse(claim.isHeld())
    }

    @Test
    fun releasingTwiceIsHarmless() {
        val claim = CommandSpeechCaptureClaim()
        claim.publish(record)
        assertSame(record, claim.release())
        assertNull("a second release has nothing left to hand back", claim.release())
    }

    /** Per-turn stop and terminal-outcome ownership, which is the other half of the fix. */
    @Test
    fun aSuccessorCannotClearItsPredecessorsStopFlag() {
        val cancelled = CommandSpeechTurnState("first")
        val successor = CommandSpeechTurnState("second")

        assertTrue("the first stop is accepted", cancelled.requestStop())
        assertFalse("a second stop for the same turn is a late callback", cancelled.requestStop())
        assertTrue(cancelled.stopped)

        assertFalse("a successor starts unstopped", successor.stopped)
        successor.requestStop()
        assertTrue("and stopping it does not touch the first turn", cancelled.stopped)
    }

    @Test
    fun eachTurnHasItsOwnTerminalOutcomeGuard() {
        val first = CommandSpeechTurnState("first")
        val second = CommandSpeechTurnState("second")

        assertTrue(first.claimTerminalOutcome())
        assertFalse("a turn delivers exactly one terminal outcome", first.claimTerminalOutcome())
        assertTrue(
            "and admitting a successor must not re-arm the retired turn",
            second.claimTerminalOutcome()
        )
    }

    @Test
    fun ownershipIsByIdentity() {
        val turn = CommandSpeechTurnState("token")
        assertTrue(turn.owns("token"))
        assertFalse("an equal but distinct token is a different turn", turn.owns(String(charArrayOf('t', 'o', 'k', 'e', 'n'))))
        assertFalse(turn.owns(null))
    }

    @Test
    fun eachTurnHasItsOwnClaim() {
        val first = CommandSpeechTurnState("first")
        val second = CommandSpeechTurnState("second")
        assertNotSame(
            "a successor must not inherit the retired turn's claim",
            first.capture,
            second.capture
        )
    }
}
