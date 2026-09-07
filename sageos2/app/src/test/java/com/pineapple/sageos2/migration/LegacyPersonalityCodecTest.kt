package com.pineapple.sageos2.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyPersonalityCodecTest {
    @Test
    fun roundTripsOwnerTaughtPersonalityReply() {
        val encoded = LegacyPersonalityCodec.encode("  Hey, Sage!!  ", "Pineapple reporting for duty. 🍍")
        val decoded = LegacyPersonalityCodec.parse(encoded)!!
        assertEquals("hey sage", decoded.phrase)
        assertEquals("Pineapple reporting for duty. 🍍", decoded.response)
    }

    @Test
    fun rejectsMalformedPersonalityReply() {
        assertNull(LegacyPersonalityCodec.parse("not-a-pair"))
        assertNull(LegacyPersonalityCodec.parse("."))
    }
}
