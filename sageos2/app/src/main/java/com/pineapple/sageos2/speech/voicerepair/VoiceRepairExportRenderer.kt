package com.pineapple.sageos2.speech.voicerepair

object VoiceRepairExportRenderer {
    fun render(export: VoiceRepairExport): String = buildString {
        appendLine("Voice repair test export")
        appendLine("Session: ${export.sessionId}")
        appendLine("Started: ${export.startedAtMs}")
        appendLine("Ended: ${export.endedAtMs ?: "in progress"}")
        appendLine("State: ${export.state}")
        appendLine("Cause: ${export.cause}")
        appendLine("Test phrase: ${export.testPhrase}")
        appendLine("Expected: ${export.expected}")
        appendLine("Recognized before: ${export.recognizedBefore ?: "none"}")
        appendLine("Recognized after: ${export.recognizedAfter ?: "none"}")
        appendLine("Repair action: ${export.repairAction}")
        appendLine("Repair applied: ${export.repairApplied}")
        appendLine("Verified: ${export.verified}")
        if (export.steps.isNotEmpty()) {
            appendLine("Steps:")
            export.steps.forEach { appendLine("- ${it.name} @${it.timestampMs}: ${it.detail}") }
        }
        if (export.notes.isNotBlank()) {
            appendLine("Notes: ${export.notes}")
        }
    }
}
