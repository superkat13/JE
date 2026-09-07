package com.pineapple.sageos2.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacySageCodecTest {
    @Test
    fun decodesVersionTwoLegacyMemoryWithoutLosingMetadata() {
        val record = LegacySageCodec.parseMemory(
            "v2\ti prefer pineapple\tpreference\t0.95\towner_statement\t1725000000000\tI prefer pineapple"
        )!!
        assertEquals("preference", record.category)
        assertEquals("I prefer pineapple", record.value)
        assertEquals(0.95, record.confidence, 0.0001)
        assertEquals("owner_statement", record.source)
        assertEquals(1725000000000L, record.createdAtMs)
    }

    @Test
    fun decodesOlderMemoryShapesConservatively() {
        val record = LegacySageCodec.parseMemory("anything\tproject\tSageOS")!!
        assertEquals("project", record.category)
        assertEquals("SageOS", record.value)
        assertEquals(1.0, record.confidence, 0.0)
    }

    @Test
    fun roundTripsLegacyWakeProfileEncoding() {
        val encoded = LegacySageCodec.encodeLegacyWake("sage glitch", "red_queen", "red queen mode")
        val profile = LegacySageCodec.parseWakeProfile(encoded)!!
        assertEquals("sage glitch", profile.phrase)
        assertEquals("red_queen", profile.mode)
        assertEquals("red queen mode", profile.command)
    }

    @Test
    fun rejectsMalformedLegacyWakeProfiles() {
        assertNull(LegacySageCodec.parseWakeProfile("not-a-profile"))
    }
}
