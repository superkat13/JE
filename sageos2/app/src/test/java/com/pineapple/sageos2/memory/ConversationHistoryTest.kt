package com.pineapple.sageos2.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationHistoryTest {
    @Test fun recentSnapshotPreservesSpeakerAndInputType() {
        val entries = listOf(
            ConversationEntry("1", 10, ConversationSpeaker.OWNER, ConversationInput.VOICE, "hello", 1),
            ConversationEntry("2", 10, ConversationSpeaker.SAGE, ConversationInput.SYSTEM, "hi", 2)
        )
        val snapshot = ConversationHistorySnapshot(2, entries)
        assertEquals(ConversationSpeaker.OWNER, snapshot.entries.first().speaker)
        assertEquals(ConversationInput.VOICE, snapshot.entries.first().input)
        assertEquals(ConversationSpeaker.SAGE, snapshot.entries.last().speaker)
    }
}
