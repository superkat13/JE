package com.pineapple.sageos2.action

import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticTargetSelectorTest {
    @Test fun exactMatchWinsOverFuzzyCandidates() {
        assertEquals(
            SemanticSelection.Match(1),
            SemanticTargetSelector.choose(listOf("Submit form", "Submit", "Submit later"), "submit")
        )
    }

    @Test fun oneFuzzyMatchCanBeChosen() {
        assertEquals(
            SemanticSelection.Match(0),
            SemanticTargetSelector.choose(listOf("Continue setup", "Cancel"), "continue")
        )
    }

    @Test fun ambiguousMatchesAreNotGuessed() {
        assertEquals(
            SemanticSelection.Ambiguous(2),
            SemanticTargetSelector.choose(listOf("Allow", "Allow"), "allow")
        )
    }

    @Test fun missingTargetStaysMissing() {
        assertEquals(
            SemanticSelection.None,
            SemanticTargetSelector.choose(listOf("Save", "Cancel"), "delete")
        )
    }
}
