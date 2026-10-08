package com.pineapple.sageos2.workflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChickenTonightScopeTest {
    @Test
    fun completeUnexpiredScopeIsUsable() {
        val now = 1_000L
        val scope = ChickenTonightScope(
            scopeId = "authorized-lab",
            authorizationReference = "written scope reference",
            targetSummary = "authorized training environment",
            expiresAtMs = now + 10_000L,
            allowedModules = setOf(
                ChickenTonightModule.LOCAL_DEVICE_POSTURE,
                ChickenTonightModule.EVIDENCE_REPORT
            )
        )
        assertTrue(scope.isUsable(now))
        assertFalse(scope.isExpired(now))
    }

    @Test
    fun expiredOrIncompleteScopeCannotActivate() {
        val now = 10_000L
        assertFalse(
            ChickenTonightScope(
                scopeId = "expired",
                authorizationReference = "scope ref",
                targetSummary = "authorized target",
                expiresAtMs = now,
                allowedModules = setOf(ChickenTonightModule.EVIDENCE_REPORT)
            ).isUsable(now)
        )
        assertFalse(
            ChickenTonightScope(
                scopeId = "missing-auth",
                authorizationReference = "",
                targetSummary = "authorized target",
                allowedModules = setOf(ChickenTonightModule.EVIDENCE_REPORT)
            ).isUsable(now)
        )
    }

    @Test
    fun exclusionContractNamesHighRiskActions() {
        val exclusions = SharedPreferencesChickenTonightScopeStore.NON_NEGOTIABLE_EXCLUSIONS.joinToString(" ").lowercase()
        assertTrue("credential" in exclusions)
        assertTrue("exploit" in exclusions)
        assertTrue("persistence" in exclusions)
        assertTrue("destructive" in exclusions)
    }
}
