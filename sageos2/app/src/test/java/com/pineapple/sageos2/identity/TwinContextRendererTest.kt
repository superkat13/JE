package com.pineapple.sageos2.identity

import com.pineapple.sageos2.apps.OwnerAppRecord
import com.pineapple.sageos2.apps.OwnerAppSnapshot
import com.pineapple.sageos2.memory.*
import com.pineapple.sageos2.mode.SageModeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwinContextRendererTest {
    @Test fun contextContainsTwinContinuityAppsToneAndCurrentFacetInNaturalLanguage() {
        val core = SageCoreSnapshot(
            2,
            "Sage is the owner's virtual twin",
            OwnerModel(preferences = listOf("complete steps")),
            SageSelfModel(identity = "Sage"),
            SharedContinuity(activeProjects = mapOf("SageOS" to "building")),
            listOf("check work"),
            emptyList(),
            listOf("visible self rule")
        )
        val memory = TwinMemorySnapshot(1, listOf(TwinMemoryRecord("m1", TwinMemorySubject.OWNER, "style", "direct", TwinMemorySource.EXPLICIT_OWNER, 1.0, 1, 1)))
        val history = ConversationHistorySnapshot(1, listOf(ConversationEntry("c1", 1, ConversationSpeaker.OWNER, ConversationInput.TEXT, "keep moving", 1)))
        val apps = OwnerAppSnapshot(1, listOf(OwnerAppRecord("com.example.browser", "Firefox", listOf("browser"), "web")))
        val rendered = TwinContextRenderer().render(core, memory, history, apps, SageModeSnapshot("sage_glitch", "red_queen"))

        assertTrue(rendered.contains("# WHO I AM"))
        assertTrue(rendered.contains("virtual twin"))
        assertTrue(rendered.contains("complete steps"))
        assertTrue(rendered.contains("visible self rule"))
        assertTrue(rendered.contains("red_queen"))
        assertTrue(rendered.contains("mirror the owner's language"))
        assertTrue(rendered.contains("keep moving"))
        assertTrue(rendered.contains("Firefox"))
        assertTrue(rendered.contains("browser"))
        assertFalse(rendered.contains("com.example.browser"))
        assertFalse(rendered.contains("Owner model"))
        assertFalse(rendered.contains("Durable twin memory"))
        assertFalse(rendered.contains("SAGE TOOL CONTRACT"))
    }

    @Test fun emptyCoreDoesNotPrintOrInventRestrictionLanguage() {
        val rendered = TwinContextRenderer().render(
            EmptySageCoreProvider.current(),
            TwinMemorySnapshot(0, emptyList()),
            ConversationHistorySnapshot(0, emptyList()),
            OwnerAppSnapshot(0, emptyList()),
            SageModeSnapshot()
        )
        assertFalse(rendered.contains("Self restrictions"))
        assertFalse(rendered.contains("Boundary I have chosen"))
        assertFalse(rendered.contains("secret restriction"))
        assertFalse(rendered.contains("hidden provider"))
    }

    @Test fun ordinaryConversationOmitsOperationalPackageCapabilityAndStartupDetails() {
        val core = EmptySageCoreProvider.current().copy(
            sageSelfModel = SageSelfModel(capabilities = listOf("root.exec"), limitations = listOf("broker unavailable"))
        )
        val apps = OwnerAppSnapshot(
            1,
            listOf(OwnerAppRecord("com.example.browser", "Firefox", listOf("browser"), "web", startupProcedure = "tap private profile"))
        )
        val ordinary = TwinContextRenderer().render(
            core,
            TwinMemorySnapshot(0, emptyList()),
            ConversationHistorySnapshot(0, emptyList()),
            apps,
            SageModeSnapshot()
        )
        assertFalse(ordinary.contains("com.example.browser"))
        assertFalse(ordinary.contains("root.exec"))
        assertFalse(ordinary.contains("broker unavailable"))
        assertFalse(ordinary.contains("tap private profile"))

        val operational = TwinContextRenderer().render(
            core,
            TwinMemorySnapshot(0, emptyList()),
            ConversationHistorySnapshot(0, emptyList()),
            apps,
            SageModeSnapshot(),
            includeOperationalDetails = true
        )
        assertTrue(operational.contains("com.example.browser"))
        assertTrue(operational.contains("root.exec"))
        assertTrue(operational.contains("broker unavailable"))
        assertTrue(operational.contains("tap private profile"))
    }

    @Test fun migratedOwnerCoreIsEarlyExplicitAndNotDuplicatedAsANote() {
        val core = EmptySageCoreProvider.current().copy(
            notes = "A retained note.\n\nImported Sage 1.33.3 owner instructions:\nCall me Kat. Stay warm and finish the job."
        )
        val rendered = TwinContextRenderer().render(
            core,
            TwinMemorySnapshot(0, emptyList()),
            ConversationHistorySnapshot(0, emptyList()),
            OwnerAppSnapshot(0, emptyList()),
            SageModeSnapshot()
        )

        assertTrue(rendered.indexOf("# OWNER CORE") < rendered.indexOf("# ME"))
        assertTrue(rendered.contains("Call me Kat. Stay warm and finish the job."))
        assertTrue(rendered.contains("Note: A retained note."))
        assertFalse(rendered.contains("Note: A retained note.\n\nImported Sage"))
        assertEquals(1, Regex("Call me Kat").findAll(rendered).count())
    }

    @Test fun importedOwnerCoreUsesHistoricalPromptBoundWithoutMutatingStoredText() {
        val ownerCore = "begin:" + "x".repeat(3_000) + ":stored-end"
        val notes = "Imported Sage 1.33.3 owner instructions:\n$ownerCore"
        val core = EmptySageCoreProvider.current().copy(notes = notes)
        val rendered = TwinContextRenderer().render(
            core,
            TwinMemorySnapshot(0, emptyList()),
            ConversationHistorySnapshot(0, emptyList()),
            OwnerAppSnapshot(0, emptyList()),
            SageModeSnapshot()
        )

        assertTrue(rendered.contains("begin:"))
        assertFalse(rendered.contains(":stored-end"))
        assertEquals(notes, core.notes)
    }

    @Test fun currentRequestMovesRelevantMemoryAheadOfHigherConfidenceNoise() {
        val memory = TwinMemorySnapshot(
            1,
            listOf(
                TwinMemoryRecord("noise", TwinMemorySubject.SHARED, "weather", "Clouds are white", TwinMemorySource.IMPORTED, 1.0, 1, 20),
                TwinMemoryRecord("relevant", TwinMemorySubject.OWNER, "coffee", "Kat takes coffee with cinnamon", TwinMemorySource.EXPLICIT_OWNER, 0.7, 1, 10)
            )
        )
        val rendered = TwinContextRenderer(maxMemories = 1).render(
            EmptySageCoreProvider.current(),
            memory,
            ConversationHistorySnapshot(0, emptyList()),
            OwnerAppSnapshot(0, emptyList()),
            SageModeSnapshot(),
            currentRequest = "How does Kat take coffee?"
        )

        assertTrue(rendered.contains("Kat takes coffee with cinnamon"))
        assertFalse(rendered.contains("Clouds are white"))
    }
}
