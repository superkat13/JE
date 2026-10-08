package com.pineapple.sageos2.apps

import com.pineapple.sageos2.capability.DeviceAction

/** Exact turn-local instructions and completed actions; never replayed after restart. */
class OwnerAppStartupSession(val ownerRequest: String, val app: OwnerAppRecord) {
    private val completed = mutableListOf<String>()
    fun supports(action: DeviceAction): Boolean = action.name in TOOLS
    fun recordCompleted(action: DeviceAction) {
        completed += "${action.name} ${action.arguments.entries.joinToString { "${it.key}=${it.value}" }}"
    }
    fun render(): String = buildString {
        appendLine("# OWNER APP STARTUP")
        appendLine("Request: $ownerRequest")
        appendLine("App: ${app.displayName} [${app.packageName}]")
        appendLine("Saved steps:")
        appendLine(app.startupProcedure)
        if (completed.isNotEmpty()) {
            appendLine("Completed; do not replay unless a later step explicitly repeats it:")
            completed.forEach { appendLine(it) }
        }
        appendLine("# SAGE TOOL CONTRACT")
        appendLine("Follow steps in order. Only listed tools; report unsupported steps honestly.")
        appendLine("One tool per reply, no surrounding prose:")
        appendLine("<SAGE_TOOL>\nname=tool.name\nargument=value\n</SAGE_TOOL>")
        appendLine("device.open_app: app")
        appendLine("device.tap_label: label")
        appendLine("device.back: no arguments")
        appendLine("device.scroll: direction=UP|DOWN|LEFT|RIGHT")
    }.trim()
    companion object {
        private val TOOLS = setOf("device.open_app", "device.tap_label", "device.back", "device.scroll")
    }
}
