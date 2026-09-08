package com.pineapple.sageos2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SageProductPresentationTest {
    @Test fun chatHomeExplainsItselfInSageLanguage() {
        val visible = buildString {
            appendLine(SageProductPresentation.HOME_GREETING)
            SageProductPresentation.starters.forEach { appendLine("${it.label} ${it.message}") }
        }.lowercase()

        assertTrue(visible.contains("talk to me"))
        assertTrue(visible.contains("type anything"))
        assertTrue(visible.contains("what do you remember"))
        listOf("diagnostic", "capability", "scope", "policy", "rule", "runtime", "gguf", "model").forEach {
            assertFalse("home leaked engineering term: $it", visible.contains(it))
        }
    }

    @Test fun ordinarySettingsContainOnlyPersonalSageAreasAndOneAdvancedDoor() {
        assertEquals(
            listOf(
                SageDestination.MEMORIES,
                SageDestination.APPS,
                SageDestination.VOICE,
                SageDestination.APPEARANCE,
                SageDestination.ADVANCED
            ),
            SageProductPresentation.settings.map { it.destination }
        )
        val visible = SageProductPresentation.settings.joinToString(" ") { "${it.title} ${it.description}" }.lowercase()
        listOf("core", "diagnostic", "capability", "workflow", "scope", "policy", "rule", "authority", "gguf", "model").forEach {
            assertFalse("ordinary Settings leaked engineering term: $it", visible.contains(it))
        }
    }

    @Test fun engineeringMachineryExistsOnlyAfterOpeningAdvanced() {
        val destinations = SageProductPresentation.advancedSettings.map { it.destination }.toSet()
        assertTrue(SageDestination.CORE in destinations)
        assertTrue(SageDestination.TASKS in destinations)
        assertTrue(SageDestination.WORKFLOWS in destinations)
        assertTrue(SageDestination.LOCAL_REPLIES in destinations)
        assertTrue(SageDestination.DIAGNOSTICS in destinations)
    }
}
