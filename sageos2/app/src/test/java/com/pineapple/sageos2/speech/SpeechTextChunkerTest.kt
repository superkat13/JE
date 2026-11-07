package com.pineapple.sageos2.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextChunkerTest {
    @Test fun shortReplyRemainsOneUtterance() {
        assertEquals(listOf("Sage is here."), SpeechTextChunker.chunk("Sage is here.", 40))
    }

    @Test fun emptyReplyProducesNoUtterance() {
        assertTrue(SpeechTextChunker.chunk("", 40).isEmpty())
    }

    @Test fun longReplyPreservesEveryCharacterAndStaysWithinTheLimit() {
        val text = buildString {
            repeat(40) { append("Sage keeps the complete answer intact across every spoken sentence. ") }
        }
        val chunks = SpeechTextChunker.chunk(text, 180)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 180 })
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.dropLast(1).all { it.endsWith(".") || it.endsWith(" ") })
    }

    @Test fun longReplyUsesNaturalSentenceBoundariesWhenAvailable() {
        val text = "First sentence. Second sentence. Third sentence. Fourth sentence."
        val chunks = SpeechTextChunker.chunk(text, 34)
        assertEquals("First sentence. Second sentence.", chunks.first())
        assertEquals(text, chunks.joinToString(""))
    }

    @Test fun doesNotSplitASurrogatePairAcrossUtterances() {
        val text = "a".repeat(39) + "😀" + "b".repeat(20)
        val chunks = SpeechTextChunker.chunk(text, 40)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.none { it.last().isHighSurrogate() })
        assertTrue(chunks.none { it.first().isLowSurrogate() })
    }
}
