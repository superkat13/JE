package com.pineapple.sageos2.identity

import com.pineapple.sageos2.memory.ConversationEntry
import com.pineapple.sageos2.memory.ConversationHistorySnapshot
import com.pineapple.sageos2.memory.ConversationInput
import com.pineapple.sageos2.memory.ConversationSpeaker
import com.pineapple.sageos2.memory.TwinMemoryRecord
import com.pineapple.sageos2.memory.TwinMemorySnapshot
import com.pineapple.sageos2.memory.TwinMemorySource
import com.pineapple.sageos2.memory.TwinMemorySubject
import com.pineapple.sageos2.mode.SageModeSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwinContextRendererTest {
    @Test fun contextContainsOnlyVisibleTwinDataAndActiveMode() {
        val core = SageCoreSnapshot(
            revision = 2,
            twinIdentity = "Sage is the owner's virtual twin",
            ownerModel = OwnerModel(preferences = listOf("complete steps")),
            sageSelfModel = SageSelfModel(identity = "Sage"),
            sharedContinuity = SharedContinuity(activeProjects = mapOf("SageOS" to "building")),
            principles = listOf("check work"),
            preferences = emptyList(),
            selfRestrictions = listOf("visible self rule")
        )
        val memory = TwinMemorySnapshot(1, listOf(
            TwinMemoryRecord("m1", TwinMemorySubject.OWNER, "style", "direct", TwinMemorySource.EXPLICIT_OWNER, 1.0, 1, 1)
        ))
        val history = ConversationHistorySnapshot(1, listOf(
            ConversationEntry("c1", 1, ConversationSpeaker.OWNER, ConversationInput.TEXT, "keep moving", 1)
        ))
        val rendered = TwinContextRenderer().render(core, memory, history, SageModeSnapshot("sage_glitch", "red_queen"))
        assertTrue(rendered.contains("virtual twin"))
        assertTrue(rendered.contains("complete steps"))
        assertTrue(rendered.contains("visible self rule"))
        assertTrue(rendered.contains("red_queen"))
        assertTrue(rendered.contains("keep moving"))
    }

    @Test fun emptyCoreDoesNotInventHiddenSelfRestrictions() {
        val rendered = TwinContextRenderer().render(
            EmptySageCoreProvider.current(),
            TwinMemorySnapshot(0, emptyList()),
            ConversationHistorySnapshot(0, emptyList()),
            SageModeSnapshot()
        )
        assertTrue(rendered.contains("Self restrictions: (none)"))
        assertFalse(rendered.contains("secret restriction"))
    }
}
