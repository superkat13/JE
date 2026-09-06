package com.pineapple.sageos2.forge

import org.junit.Assert.assertEquals
import org.junit.Test

class ForgeProtocolTest {
    @Test fun donorJobIdsRemainStrictlyValidated() {
        assertEquals("job_0123456789abcdef01234567", ForgeProtocol.safeJobId("job_0123456789abcdef01234567"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun arbitraryJobPathIsRejected() {
        ForgeProtocol.safeJobId("../../etc/passwd")
    }

    @Test fun originIsHttpsAndPathless() {
        assertEquals("https://forge.example.test", ForgeProtocol.normalizeOrigin("https://forge.example.test/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun forgeOriginCannotUseHttp() {
        ForgeProtocol.normalizeOrigin("http://forge.example.test")
    }
}
