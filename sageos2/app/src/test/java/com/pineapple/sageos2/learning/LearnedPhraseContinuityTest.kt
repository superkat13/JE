package com.pineapple.sageos2.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearnedPhraseContinuityTest {
    @Test fun readsTheExactSage1333PhraseAliasFormatInPlace() {
        assertEquals(
            LearnedPhrase("movie time", "open YouTube"),
            SharedPreferencesLearnedPhraseStore.decode("movie time\topen YouTube")
        )
        assertEquals("movie time", SharedPreferencesLearnedPhraseStore.normalize(" Movie-Time! "))
        assertNull(SharedPreferencesLearnedPhraseStore.decode("not-a-valid-lesson"))
    }
}
