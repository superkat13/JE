package com.pineapple.sageos2.brain

import com.pineapple.sageos2.capability.Capability
import com.pineapple.sageos2.capability.CapabilitySnapshot
import com.pineapple.sageos2.capability.CapabilityStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainToolContextTest {
    @Test
    fun unavailableCapabilitiesDoNotExposeActionSchemas() {
        val text = BrainToolContextRenderer.render(
            CapabilitySnapshot(
                mapOf(
                    Capability.SAGEOS_ROOT_BROKER to CapabilityStatus.UNAVAILABLE,
                    Capability.FORGE to CapabilityStatus.AVAILABLE
                )
            )
        )
        assertTrue(text.contains("Do not emit root.*"))
        assertTrue(text.contains("Do not emit forge.*"))
        assertFalse(text.contains("root.exec:"))
        assertFalse(text.contains("forge.start_job:"))
    }

    @Test
    fun activeCapabilitiesExposeOnlyVerifiedSchemas() {
        val text = BrainToolContextRenderer.render(
            CapabilitySnapshot(
                mapOf(
                    Capability.SAGEOS_ROOT_BROKER to CapabilityStatus.ACTIVE,
                    Capability.FORGE to CapabilityStatus.ACTIVE
                )
            )
        )
        assertTrue(text.contains("root.exec:"))
        assertTrue(text.contains("forge.start_job:"))
        assertTrue(text.contains("owner_approved=false"))
        assertFalse(text.contains("forge.revoke"))
        assertFalse(text.contains("forge.pair"))
    }
}
