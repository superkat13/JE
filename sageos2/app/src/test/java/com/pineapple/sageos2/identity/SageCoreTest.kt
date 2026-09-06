package com.pineapple.sageos2.identity

import com.pineapple.sageos2.brain.BrainRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SageCoreTest {
    @Test fun coreKeepsSelfRestrictionsVisibleAsData() {
        val core = SageCoreSnapshot(
            revision = 7,
            identity = "Sage",
            principles = listOf("Think before acting"),
            preferences = listOf("Be concise"),
            selfRestrictions = listOf("One rule Sage chose herself")
        )
        val request = BrainRequest(42, "test", core)
        assertEquals(7, request.sageCore?.revision)
        assertTrue(request.sageCore?.selfRestrictions?.contains("One rule Sage chose herself") == true)
    }

    @Test fun emptyCoreContainsNoHiddenBehavioralRules() {
        val core = EmptySageCoreProvider.current()
        assertTrue(core.principles.isEmpty())
        assertTrue(core.selfRestrictions.isEmpty())
    }
}
