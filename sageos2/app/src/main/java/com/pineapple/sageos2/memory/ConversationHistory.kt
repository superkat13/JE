package com.pineapple.sageos2.memory

enum class ConversationSpeaker { OWNER, SAGE }
enum class ConversationInput { VOICE, TEXT, SYSTEM }

data class ConversationEntry(
    val id: String,
    val turnId: Long,
    val speaker: ConversationSpeaker,
    val input: ConversationInput,
    val text: String,
    val timestampEpochMs: Long
) {
    init {
        require(id.isNotBlank())
        require(text.isNotBlank())
    }
}

data class ConversationHistorySnapshot(
    val revision: Long,
    val entries: List<ConversationEntry>
)

interface ConversationHistoryProvider {
    fun recent(limit: Int = 24): ConversationHistorySnapshot
}

interface ConversationHistoryStore : ConversationHistoryProvider {
    fun record(entry: ConversationEntry)
    fun clear()
}

object EmptyConversationHistoryProvider : ConversationHistoryProvider {
    override fun recent(limit: Int) = ConversationHistorySnapshot(0L, emptyList())
}
