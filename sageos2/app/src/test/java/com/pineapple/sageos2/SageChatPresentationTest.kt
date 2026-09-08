package com.pineapple.sageos2

import com.pineapple.sageos2.brain.BrainProgressStage
import com.pineapple.sageos2.core.SageListeningMode
import com.pineapple.sageos2.core.SageRuntimeSnapshot
import com.pineapple.sageos2.core.SageRuntimeState
import com.pineapple.sageos2.core.TurnOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SageChatPresentationTest {
    @Test fun firstLocalTurnExplainsModelLoadingImmediately() {
        val ui = SageChatPresentation.present(
            snapshot(SageRuntimeState.THINKING_DEEP),
            BrainProgressStage.LOADING_MODEL
        )
        assertEquals("Waking up", ui.presence)
        assertEquals("I'm getting ready for our first reply", ui.activity)
        assertTrue(ui.toString().contains("model").not())
    }

    @Test fun ordinaryDeepGenerationFeelsLikeSageNotAnEngineConsole() {
        val ui = SageChatPresentation.present(
            snapshot(SageRuntimeState.THINKING_DEEP),
            BrainProgressStage.GENERATING
        )
        assertEquals("Thinking", ui.presence)
        assertEquals("I'm putting my answer together", ui.activity)
        assertTrue(ui.toString().contains("THINKING_DEEP").not())
    }

    @Test fun promptReadingHasImmediateHumanVisibleActivity() {
        val ui = SageChatPresentation.present(
            snapshot(SageRuntimeState.THINKING_DEEP),
            BrainProgressStage.READING_CONTEXT
        )
        assertEquals("Thinking", ui.presence)
        assertEquals("I'm gathering what matters for this reply", ui.activity)
    }

    @Test fun idleHomeHasNoDebugActivityBanner() {
        val ui = SageChatPresentation.present(snapshot(SageRuntimeState.IDLE_WAKE))
        assertEquals("Here with you", ui.presence)
        assertNull(ui.activity)
        assertNull(ui.waitingMessage)
    }

    @Test fun queuedMessagesAreExplainedInOwnerLanguage() {
        val ui = SageChatPresentation.present(snapshot(SageRuntimeState.THINKING_DEEP, queued = 2))
        assertEquals("Sent — 2 messages are waiting", ui.waitingMessage)
    }

    @Test fun oneQueuedMessageIsExplicitlyConfirmedAsSent() {
        val ui = SageChatPresentation.present(snapshot(SageRuntimeState.THINKING_DEEP, queued = 1))
        assertEquals("Sent — I'll answer that next", ui.waitingMessage)
    }

    private fun snapshot(state: SageRuntimeState, queued: Int = 0) = SageRuntimeSnapshot(
        state = state,
        listeningMode = SageListeningMode.WAKE_ONLY,
        activeTurnId = if (state == SageRuntimeState.IDLE_WAKE) 0L else 7L,
        activeTurnOrigin = if (state == SageRuntimeState.IDLE_WAKE) TurnOrigin.NONE else TurnOrigin.TEXT,
        recognizerGeneration = 3L,
        queuedTextCount = queued
    )
}
