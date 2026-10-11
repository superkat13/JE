package com.pineapple.sageos2.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wake-tail discard is a command-turn remedy; a diagnostic capture starts on an idle
 *  microphone and must feed the owner's phrase from its very first frame. */
class CommandSpeechTurnStateTest {
    private companion object { const val COMMAND_TAIL = 4 }

    @Test fun commandTurnDiscardsTheWakeTail() {
        val turn = CommandSpeechTurnState(Any())
        assertFalse(turn.diagnosticCapture)
        assertEquals(COMMAND_TAIL, turn.tailDiscardChunks(COMMAND_TAIL))
    }

    @Test fun explicitlyWakeTriggeredCommandDiscardsResidue() {
        val turn = CommandSpeechTurnState(Any(), wakeTailPresent = true)
        assertEquals(COMMAND_TAIL, turn.tailDiscardChunks(COMMAND_TAIL))
    }

    @Test fun manualPushToTalkKeepsFirstAudioFrame() {
        val turn = CommandSpeechTurnState(Any(), wakeTailPresent = false)
        assertFalse(turn.diagnosticCapture)
        assertFalse(turn.wakeTailPresent)
        assertEquals(0, turn.tailDiscardChunks(COMMAND_TAIL))
    }

    @Test fun followUpListeningKeepsFirstAudioFrame() {
        val turn = CommandSpeechTurnState(Any(), diagnosticCapture = false, wakeTailPresent = false)
        assertEquals(0, turn.tailDiscardChunks(COMMAND_TAIL))
    }

    @Test fun diagnosticAlwaysKeepsOnsetEvenIfMisMarkedAsWakeTail() {
        val turn = CommandSpeechTurnState(Any(), diagnosticCapture = true, wakeTailPresent = true)
        assertEquals(0, turn.tailDiscardChunks(COMMAND_TAIL))
    }

    @Test fun diagnosticCaptureNeverDropsPhraseOnset() {
        val turn = CommandSpeechTurnState(Any(), diagnosticCapture = true)
        assertTrue(turn.diagnosticCapture)
        assertEquals(0, turn.tailDiscardChunks(COMMAND_TAIL))
    }
}
