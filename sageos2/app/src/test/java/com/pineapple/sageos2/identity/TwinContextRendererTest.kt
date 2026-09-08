package com.pineapple.sageos2.identity

import com.pineapple.sageos2.apps.OwnerAppRecord
import com.pineapple.sageos2.apps.OwnerAppSnapshot
import com.pineapple.sageos2.memory.*
import com.pineapple.sageos2.mode.SageModeSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwinContextRendererTest {
    @Test fun contextContainsTwinContinuityAppsAndCurrentFacetInNaturalLanguage() {
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

        assertTrue(rendered.contains("virtual twin"))
        assertTrue(rendered.contains("complete steps"))
        assertTrue(rendered.contains("visible self rule"))
        assertTrue(rendered.contains("red_queen"))
        assertTrue(rendered.contains("keep moving"))
        assertTrue(rendered.contains("Firefox"))
        assertTrue(rendered.contains("browser"))
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
}
