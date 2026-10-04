package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.capability.DeviceAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCompletionPolicyTest {
    @Test fun recoveryPromptForcesCurrentStateVerificationBeforeReplay() {
        val prompt = RecoveryCompletionPolicy.recoveryPrompt(
            recoveredTaskId = "runtime:turn:9",
            ownerGoal = "restart printing and make sure it works",
            priorPhase = "capability",
            lastAction = "root.restart_service",
            lastActionSignature = "root.restart_service|service=print",
            lastActionSuccess = null,
            completedToolCalls = 1,
            recoveryDepth = 1
        )

        assertTrue(prompt.startsWith(RecoveryCompletionPolicy.RECOVERY_MARKER))
        assertTrue(prompt.contains("owner_goal=restart printing and make sure it works"))
        assertTrue(prompt.contains("verify current state"))
        assertTrue(prompt.contains("Never blindly replay"))
    }

    @Test fun exactMutatingReplayIsBlockedButReadOnlyEvidenceIsAllowed() {
        val original = DeviceAction("root.restart_service", mapOf("service" to "print"))
        val guard = RecoveryCompletionPolicy.replayGuard(
            original.name,
            RecoveryCompletionPolicy.actionSignature(original)
        )!!

        assertTrue(RecoveryCompletionPolicy.shouldBlockReplay(guard, original))
        assertFalse(
            RecoveryCompletionPolicy.shouldBlockReplay(
                guard,
                DeviceAction("root.health", emptyMap())
            )
        )
        assertFalse(
            RecoveryCompletionPolicy.shouldBlockReplay(
                guard,
                DeviceAction("root.restart_service", mapOf("service" to "other"))
            )
        )
    }

    @Test fun oldCheckpointWithoutSignatureStillBlocksSameMutatingToolName() {
        val guard = RecoveryCompletionPolicy.replayGuard("root.restart_service", null)!!
        assertTrue(
            RecoveryCompletionPolicy.shouldBlockReplay(
                guard,
                DeviceAction("root.restart_service", mapOf("service" to "print"))
            )
        )
        assertFalse(RecoveryCompletionPolicy.isMutating("root.health"))
        assertTrue(RecoveryCompletionPolicy.isMutating("forge.start_job"))
    }
}
