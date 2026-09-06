package com.pineapple.sageos2.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeProfilesTest {
    @Test fun defaultSageProfileIsNormalTwinMode() {
        val profile = SharedPreferencesWakeProfileStore.defaults().first { it.id == "sage" }
        assertEquals(listOf("sage"), profile.phrases)
        assertEquals("Yes", profile.acknowledgement)
        assertNull(profile.modeId)
    }

    @Test fun sageGlitchActivatesRedQueenAsModeNotIdentity() {
        val profile = SharedPreferencesWakeProfileStore.defaults().first { it.id == "sage_glitch" }
        assertEquals("red_queen", profile.modeId)
        assertEquals("Yes", profile.acknowledgement)
    }
}
