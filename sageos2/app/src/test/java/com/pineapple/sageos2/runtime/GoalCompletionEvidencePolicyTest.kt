package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.brain.BrainToolExecutionEvidence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalCompletionEvidencePolicyTest {
    private fun evidence(name: String, success: Boolean, detail: String) =
        BrainToolExecutionEvidence(1, name, "", success, detail)

    @Test fun deniedMutationCannotBeVerifiedFromModelMarker() {
        val result = GoalCompletionPolicy.assessEvidence(
            "lower my volume one step",
            listOf(evidence("device.volume", false, "permission denied; unchanged"))
        )
        assertFalse(result.independentlyVerified)
    }

    @Test fun successfulCommandAcknowledgementIsNotAnObservedPostcondition() {
        val result = GoalCompletionPolicy.assessEvidence(
            "lower my volume one step",
            listOf(evidence("device.volume", true, "request accepted; final state unknown"))
        )
        assertFalse(result.independentlyVerified)
    }

    @Test fun readOnlyRootHealthResultCanVerifyItsOwnObservedResult() {
        val result = GoalCompletionPolicy.assessEvidence(
            "check root health and make sure it responds",
            listOf(evidence("root.health", true, "root service answered"))
        )
        assertTrue(result.independentlyVerified)
    }

    @Test fun ReadOnlyHealthDoesNotProveRequestedRestart() {
        val result = GoalCompletionPolicy.assessEvidence(
            "restart printing, then check root health",
            listOf(evidence("root.health", true, "root service answered"))
        )
        assertFalse(result.independentlyVerified)
    }

    @Test fun runningForgeJobIsNotCompletedJustBecauseStatusRequestSucceeded() {
        val result = GoalCompletionPolicy.assessEvidence(
            "check whether the forge job has completed",
            listOf(evidence("forge.job", true, "status=RUNNING; result unavailable"))
        )
        assertFalse(result.independentlyVerified)
    }

    @Test fun explicitGenerationLimitDiffersFromProviderUnavailability() {
        assertTrue(GoalCompletionPolicy.isIncompleteGeneration("TOKEN_LIMIT: no EOS"))
        assertTrue(GoalCompletionPolicy.isIncompleteGeneration("BYTE_LIMIT: exhausted"))
        assertTrue(GoalCompletionPolicy.isIncompleteGeneration("CONTEXT_EXHAUSTED: KV cache full"))
        assertFalse(GoalCompletionPolicy.isIncompleteGeneration("Live web access unavailable"))
        assertFalse(GoalCompletionPolicy.isIncompleteGeneration(null))
    }
}
