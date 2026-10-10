package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.memory.ConversationEntry
import com.pineapple.sageos2.memory.ConversationHistoryStore
import com.pineapple.sageos2.memory.ConversationInput
import com.pineapple.sageos2.memory.ConversationSpeaker
import java.util.UUID

/** Persists a finished voice-repair result as exactly one owner-visible Sage conversation entry
 *  and then notifies the UI, so backgrounded or freshly-foregrounded activities still show it.
 *  Delivery is exactly-once per terminal result state per session. Interruption and cancellation
 *  are never pushed; they are surfaced through the status command and startup notices instead.
 */
class VoiceRepairOutcomeNotifier(
    private val history: ConversationHistoryStore,
    private val notify: (String) -> Unit
) {
    @Volatile private var lastDelivered: Pair<String, VoiceRepairState>? = null

    fun onTerminal(session: VoiceRepairSession) {
        val text = VoiceRepairCompletionPresenter.ownerText(session) ?: return
        val key = session.id to session.state
        if (lastDelivered == key) return
        lastDelivered = key
        history.record(
            ConversationEntry(
                id = UUID.randomUUID().toString(),
                turnId = 0L,
                speaker = ConversationSpeaker.SAGE,
                input = ConversationInput.SYSTEM,
                text = text,
                timestampEpochMs = System.currentTimeMillis()
            )
        )
        notify(text)
    }
}