package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.memory.ConversationEntry
import com.pineapple.sageos2.memory.ConversationHistoryStore
import com.pineapple.sageos2.memory.ConversationInput
import com.pineapple.sageos2.memory.ConversationSpeaker
import java.util.UUID

/** Persists the "speak now" readiness prompt as exactly one owner-visible Sage conversation entry
 *  and then notifies the UI. Persisting before notifying is what makes the prompt appear for a
 *  backgrounded or freshly-foregrounded activity, because the message list renders stored history.
 *  Delivery is exactly-once per capture: each capture stamps its own "ready" step, and only that
 *  stamp is delivered, so a retest prompts again while duplicate readiness callbacks do not.
 */
class VoiceRepairReadinessNotifier(
    private val history: ConversationHistoryStore,
    private val notify: (String) -> Unit
) {
    @Volatile private var lastDeliveredStamp: Pair<String, Long>? = null

    fun onReady(session: VoiceRepairSession) {
        val text = VoiceRepairReadinessPresenter.ownerText(session) ?: return
        val stamp = session.steps.lastOrNull { it.name == "ready" }?.timestampMs ?: return
        val key = session.id to stamp
        if (lastDeliveredStamp == key) return
        lastDeliveredStamp = key
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
