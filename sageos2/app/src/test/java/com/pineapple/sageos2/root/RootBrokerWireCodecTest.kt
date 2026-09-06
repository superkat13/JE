package com.pineapple.sageos2.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class RootBrokerWireCodecTest {
    @Test
    fun healthRequestUsesVersionedProtocol() {
        val wire = String(RootBrokerWireCodec.encode(RootBrokerRequest("r1", RootOperation.Health)))
        assertTrue(wire.startsWith("SAGE_ROOTD 1\n"))
        assertTrue(wire.contains("id 7231\n"))
        assertTrue(wire.contains("op HEALTH\n"))
        assertTrue(wire.endsWith("END\n"))
    }

    @Test
    fun execRequestNeverBuildsAShellString() {
        val request = RootBrokerRequest(
            "exec-1",
            RootOperation.ExecuteProcess(
                executable = "/system/bin/id",
                args = listOf("-u", "owner value"),
                environment = mapOf("SAGE_TEST" to "yes"),
                workingDirectory = "/data/local/tmp",
                timeoutMs = 4321
            )
        )
        val wire = String(RootBrokerWireCodec.encode(request))
        assertTrue(wire.contains("op EXEC\n"))
        assertTrue(wire.contains("a 2d75\n"))
        assertTrue(wire.contains("a 6f776e65722076616c7565\n"))
        assertTrue(wire.contains("e 534147455f54455354 796573\n"))
        assertFalse(wire.contains("/system/bin/sh"))
        assertFalse(wire.contains("sh -c"))
    }

    @Test
    fun responseRoundTripsAuditAndProcessOutput() {
        val response = """
            SAGE_ROOTD 1
            id 7231
            success 1
            code 4f4b
            detail 6f7065726174696f6e20636f6d706c65746564
            audit 6131
            exit 0
            stdout 313030300a
            END
        """.trimIndent() + "\n"
        val decoded = RootBrokerWireCodec.decode(ByteArrayInputStream(response.toByteArray()))
        assertEquals("r1", decoded.requestId)
        assertTrue(decoded.success)
        assertEquals("OK", decoded.code)
        assertEquals("operation completed", decoded.detail)
        assertEquals("a1", decoded.auditId)
        assertEquals(0, decoded.exitCode)
        assertEquals("1000\n", decoded.stdout)
    }
}
