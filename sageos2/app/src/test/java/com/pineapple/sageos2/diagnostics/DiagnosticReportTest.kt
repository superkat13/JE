package com.pineapple.sageos2.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    @Test
    fun reportIncludesOperationalTruthWithoutPrivatePayloads() {
        val report = DiagnosticReportRenderer.render(
            DiagnosticReportSnapshot(
                createdAtMs = 1234L,
                appVersion = "2.0.0 (200)",
                packageName = "com.pineapple.sagecommander.stable",
                device = "VASOUN L10_T05",
                android = "13 (API 33)",
                runtimeState = "IDLE",
                listeningMode = "WAKE",
                brainReady = true,
                brainDetail = "local model ready",
                brainLastLatencyMs = 1200L,
                wakeReady = true,
                wakeEngine = "Sherpa",
                wakeDetail = "offline wake ready",
                capabilities = mapOf("SAGEOS_ROOT_BROKER" to "UNAVAILABLE", "FORGE" to "ACTIVE"),
                sageCoreRevision = 7L,
                profileId = "sage",
                modeId = null,
                ownerAppsRevision = 3L,
                ownerAppsCount = 4,
                recoverableTasks = listOf(DiagnosticTaskSummary("task-1", "Recovered turn", "WAITING", "Continue safely")),
                chickenTonightScopeStatus = "READY",
                traces = listOf(TraceEvent("trace-1", 1200L, 9L, "brain", "generation completed", TraceLevel.INFO))
            )
        )

        assertTrue(report.contains("SageOS 2 diagnostic report"))
        assertTrue(report.contains("Brain: ready"))
        assertTrue(report.contains("SAGEOS_ROOT_BROKER: UNAVAILABLE"))
        assertTrue(report.contains("Chicken Tonight scope: READY"))
        assertTrue(report.contains("generation completed"))
        assertTrue(report.contains("conversation text"))
        assertFalse(report.contains("authorizationReference"))
        assertFalse(report.contains("ownerPrompt"))
    }
}
