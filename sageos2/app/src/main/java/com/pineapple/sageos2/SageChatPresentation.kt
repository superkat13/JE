package com.pineapple.sageos2

import com.pineapple.sageos2.brain.BrainProgressStage
import com.pineapple.sageos2.core.SageRuntimeSnapshot
import com.pineapple.sageos2.core.SageRuntimeState

data class SageChatUiState(
    val presence: String,
    val activity: String? = null,
    val waitingMessage: String? = null
)

/** Converts runtime machinery into the small amount of language that belongs on Sage's home. */
object SageChatPresentation {
    fun present(
        snapshot: SageRuntimeSnapshot,
        brainProgress: BrainProgressStage? = null
    ): SageChatUiState {
        val waiting = when (snapshot.queuedTextCount) {
            0 -> null
            1 -> "One message is waiting"
            else -> "${snapshot.queuedTextCount} messages are waiting"
        }
        val (presence, activity) = when (snapshot.state) {
            SageRuntimeState.STOPPED -> "Getting ready" to "I'm getting everything ready"
            SageRuntimeState.IDLE_WAKE -> "Here with you" to null
            SageRuntimeState.ACKNOWLEDGING_WAKE -> "Right here" to null
            SageRuntimeState.COMMAND_LISTENING,
            SageRuntimeState.FOLLOW_UP_LISTENING -> "Listening" to "I'm listening"
            SageRuntimeState.THINKING_FAST -> "On it" to "I'm taking care of that"
            SageRuntimeState.THINKING_DEEP -> when (brainProgress) {
                BrainProgressStage.LOADING_MODEL ->
                    "Waking up my local Brain" to "The first reply can take a few minutes while I load my local Brain"
                BrainProgressStage.GENERATING -> "Thinking" to "I'm putting my answer together"
                BrainProgressStage.PREPARING, null -> "Thinking" to "I'm thinking"
            }
            SageRuntimeState.SPEAKING -> "Replying" to null
            SageRuntimeState.ECHO_GUARD -> "Here with you" to null
            SageRuntimeState.ERROR -> "Still here" to "I need a moment before I try that again"
        }
        return SageChatUiState(presence, activity, waiting)
    }
}
