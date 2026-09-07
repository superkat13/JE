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
        assertEquals("Waking up my local Brain", ui.presence)
        assertTrue(ui.activity.orEmpty().contains("first reply"))
        assertTrue(ui.activity.orEmpty().contains("few minutes"))
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

    @Test fun idleHomeHasNoDebugActivityBanner() {
        val ui = SageChatPresentation.present(snapshot(SageRuntimeState.IDLE_WAKE))
        assertEquals("Here with you", ui.presence)
        assertNull(ui.activity)
        assertNull(ui.waitingMessage)
    }

    @Test fun queuedMessagesAreExplainedInOwnerLanguage() {
        val ui = SageChatPresentation.present(snapshot(SageRuntimeState.THINKING_DEEP, queued = 2))
        assertEquals("2 messages are waiting", ui.waitingMessage)
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
