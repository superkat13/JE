package com.pineapple.sageos2.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BrainToolDirectiveTest {
    @Test
    fun normalProseIsNeverAToolCall() {
        assertNull(BrainToolDirectiveParser.parse("You could use root.exec to inspect that."))
    }

    @Test
    fun proseWrappedToolBlockIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BrainToolDirectiveParser.parse(
                """
                I will do that now.
                <SAGE_TOOL>
                name=root.health
                </SAGE_TOOL>
                """.trimIndent()
            )
        }
    }

    @Test
    fun exactToolBlockParsesArgumentsWithoutShellInterpretation() {
        val directive = BrainToolDirectiveParser.parse(
            """
            <SAGE_TOOL>
            name=root.exec
            executable=/system/bin/id
            arg.0=-u
            timeout_ms=5000
            </SAGE_TOOL>
            """.trimIndent()
        )!!
        assertEquals("root.exec", directive.name)
        assertEquals("/system/bin/id", directive.arguments["executable"])
        assertEquals("-u", directive.arguments["arg.0"])
        assertEquals("5000", directive.arguments["timeout_ms"])
    }

    @Test
    fun duplicateKeysAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BrainToolDirectiveParser.parse(
                """
                <SAGE_TOOL>
                name=root.exec
                executable=/system/bin/id
                executable=/system/bin/whoami
                </SAGE_TOOL>
                """.trimIndent()
            )
        }
    }
}
