package com.pineapple.sageos2.identity

import com.pineapple.sageos2.brain.BrainRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SageCoreTest {
    @Test fun defaultCoreDeclaresVirtualTwinIdentity() {
        val core = EmptySageCoreProvider.current()
        assertTrue(core.twinIdentity.contains("virtual twin"))
        assertEquals("Sage", core.sageSelfModel.identity)
    }

    @Test fun twinModelSeparatesOwnerSelfAndSharedContinuity() {
        val core = SageCoreSnapshot(
            revision = 7,
            twinIdentity = "virtual twin",
            ownerModel = OwnerModel(preferences = listOf("prefers direct answers")),
            sageSelfModel = SageSelfModel(identity = "Sage", experiences = listOf("learned a tool workflow")),
            sharedContinuity = SharedContinuity(activeProjects = mapOf("SageOS" to "active")),
            principles = listOf("Think before acting"),
            preferences = listOf("Be concise"),
            selfRestrictions = listOf("one visible self-rule")
        )
        val request = BrainRequest(42, "test", core)
        assertEquals("prefers direct answers", request.sageCore?.ownerModel?.preferences?.single())
        assertEquals("active", request.sageCore?.sharedContinuity?.activeProjects?.get("SageOS"))
        assertTrue(request.sageCore?.selfRestrictions?.contains("one visible self-rule") == true)
    }

    @Test fun defaultCoreContainsNoHiddenBehavioralRules() {
        val core = EmptySageCoreProvider.current()
        assertTrue(core.principles.isEmpty())
        assertTrue(core.selfRestrictions.isEmpty())
    }
}
