package com.pineapple.sageos2.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainReplyCompletionTest {
    @Test fun completeSentenceIsPreserved() {
        assertEquals("Sage is here.", BrainReplyCompletion.completePrefix("Sage is here."))
        assertTrue(BrainReplyCompletion.endsAtSentenceBoundary("She said, \"Ready.\""))
    }

    @Test fun unfinishedTrailingSentenceIsRemovedAtTheBoundary() {
        assertEquals(
            "The first thought is complete.",
            BrainReplyCompletion.completePrefix("The first thought is complete. The second thought is not")
        )
    }

    @Test fun decimalPointsAreNotTreatedAsSentenceEndings() {
        assertNull(BrainReplyCompletion.completePrefix("The value is 3.14 and it keeps going"))
    }

    @Test fun toolBlocksMustBeCompleteBeforeTheyCanBeActedOn() {
        assertTrue(BrainReplyCompletion.startsToolBlock("<SAGE_TOOL>\nname=root.health"))
        assertFalse(BrainReplyCompletion.isCompleteToolBlock("<SAGE_TOOL>\nname=root.health"))
        assertTrue(BrainReplyCompletion.isCompleteToolBlock("<SAGE_TOOL>\nname=root.health\n</SAGE_TOOL>"))
    }
}
