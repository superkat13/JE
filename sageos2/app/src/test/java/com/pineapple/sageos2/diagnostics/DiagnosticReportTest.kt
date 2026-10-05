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
                commandSpeechReady = true,
                commandSpeechDetail = "local sherpa command speech ready",
                capabilities = mapOf("SAGEOS_ROOT_BROKER" to "UNAVAILABLE", "FORGE" to "ACTIVE"),
                sageCoreRevision = 7L,
                profileId = "sage",
                modeId = null,
                ownerAppsRevision = 3L,
                ownerAppsCount = 4,
                continuityMigration = "legacy migration already complete; core=true source[core=true,memory=true,apps=true,wake=true,autonomy=true]",
                recoverableTasks = listOf(DiagnosticTaskSummary("task-1", "Recovered turn", "WAITING", "Continue safely")),
                chickenTonightScopeStatus = "READY",
                traces = listOf(TraceEvent("trace-1", 1200L, 9L, "brain", "generation completed", TraceLevel.INFO)),
                brainEvidence = mapOf(
                    "native stage" to "complete",
                    "prompt tokens" to "812",
                    "first token" to "7100 ms",
                    "model sha256" to "abc123",
                    "model architecture" to "\"qwen2\""
                )
            )
        )

        assertTrue(report.contains("SageOS 2 diagnostic report"))
        assertTrue(report.contains("Brain: ready"))
        assertTrue(report.contains("SAGEOS_ROOT_BROKER: UNAVAILABLE"))
        assertTrue(report.contains("Command speech: ready"))
        assertTrue(report.contains("local sherpa command speech ready"))
        assertTrue(report.contains("Continuity migration: legacy migration already complete"))
        assertTrue(report.contains("source[core=true,memory=true,apps=true,wake=true,autonomy=true]"))
        assertTrue(report.contains("Chicken Tonight scope: READY"))
        assertTrue(report.contains("generation completed"))
        assertTrue(report.contains("Brain native stage: complete"))
        assertTrue(report.contains("Brain prompt tokens: 812"))
        assertTrue(report.contains("Brain first token: 7100 ms"))
        assertTrue(report.contains("Brain model sha256: abc123"))
        assertTrue(report.contains("Brain model architecture: \"qwen2\""))
        assertTrue(report.contains("conversation text"))
        assertFalse(report.contains("authorizationReference"))
        assertFalse(report.contains("ownerPrompt"))
    }

    /**
     * A turn refused the microphone because it was superseded has to be distinguishable in an export
     * from a turn refused because the recognizer is broken.
     *
     * Both are silent and neither opens a cooldown, so without the refusal count the two are
     * indistinguishable in the field and a device with a working recognizer reads exactly like a
     * device with a broken one. This is the line that tells them apart, so it is asserted here rather
     * than only in the service test that produces it.
     */
    @Test
    fun exportSeparatesASupersededTurnFromABrokenRecognizer() {
        val superseded = DiagnosticReportRenderer.render(
            snapshot(commandSpeechDetail = "recognizer warm in 40ms, capture refused 4x (superseded)")
        )
        assertTrue(superseded.contains("Command speech: ready"))
        assertTrue(
            "a superseded turn must be visible in the export",
            superseded.contains("capture refused 4x (superseded)")
        )

        val broken = DiagnosticReportRenderer.render(
            snapshot(commandSpeechDetail = "recognizer not warm: verified sherpa engine/model unavailable")
        )
        assertTrue(broken.contains("Command speech: fallback"))
        assertTrue(broken.contains("verified sherpa engine/model unavailable"))
        assertFalse(
            "a genuine fault must not be reported as a refused capture",
            broken.contains("capture refused")
        )
    }

    /** A turn that was never refused must not carry the note at all. */
    @Test
    fun exportOmitsTheRefusalNoteWhenNothingWasRefused() {
        val report = DiagnosticReportRenderer.render(
            snapshot(commandSpeechDetail = "recognizer warm in 40ms, last turn ended by endpoint")
        )
        assertFalse(report.contains("capture refused"))
    }

    /** The whole snapshot is rebuilt per case; only the command speech line varies. */
    private fun snapshot(commandSpeechDetail: String) = DiagnosticReportSnapshot(
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
        commandSpeechReady = !commandSpeechDetail.contains("not warm"),
        commandSpeechDetail = commandSpeechDetail,
        capabilities = mapOf("SAGEOS_ROOT_BROKER" to "UNAVAILABLE"),
        sageCoreRevision = 7L,
        profileId = "sage",
        modeId = null,
        ownerAppsRevision = 3L,
        ownerAppsCount = 4,
        chickenTonightScopeStatus = "READY",
        traces = emptyList()
    )
}
