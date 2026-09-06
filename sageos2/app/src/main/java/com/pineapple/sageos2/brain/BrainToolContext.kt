package com.pineapple.sageos2.brain

import com.pineapple.sageos2.capability.Capability
import com.pineapple.sageos2.capability.CapabilityResult
import com.pineapple.sageos2.capability.CapabilitySnapshot
import com.pineapple.sageos2.capability.CapabilityStatus
import com.pineapple.sageos2.capability.DeviceAction

object BrainToolContextRenderer {
    fun render(snapshot: CapabilitySnapshot): String = buildString {
        appendLine("# SAGE TOOL CONTRACT")
        appendLine("Tool calls are structured control output, not owner-facing prose.")
        appendLine("If no tool is required, answer the owner normally and do not emit SAGE_TOOL tags.")
        appendLine("If a tool is required, your entire response must be exactly one block in this form:")
        appendLine("<SAGE_TOOL>")
        appendLine("name=tool.name")
        appendLine("argument=value")
        appendLine("</SAGE_TOOL>")
        appendLine("Never put commentary before or after a tool block. Never invent a tool not listed below.")
        appendLine()

        if (snapshot.states[Capability.SAGEOS_ROOT_BROKER] == CapabilityStatus.ACTIVE) {
            appendLine("## Active root-backed tools")
            appendLine("- root.health")
            appendLine("- root.install_package: path, optional replace=true|false")
            appendLine("- root.uninstall_package: package, optional keep_data=true|false")
            appendLine("- root.set_package_enabled: package, enabled=true|false")
            appendLine("- root.write_setting: namespace=system|secure|global, key, optional value. Omit value to delete.")
            appendLine("- root.chown: path, uid, gid")
            appendLine("- root.chmod: path, mode as decimal integer")
            appendLine("- root.restart_service: service")
            appendLine("- root.power: action=REBOOT|SHUTDOWN|REBOOT_RECOVERY")
            appendLine("- root.exec: executable must be absolute; optional arg.0, arg.1...; env.NAME; cwd; timeout_ms")
            appendLine("For root.exec, use separate argv entries. Never request a shell-concatenated command when direct executable + args can express the operation.")
        } else {
            appendLine("Root broker: unavailable in this runtime. Do not emit root.* tool calls.")
        }
    }.trim()

    fun renderResult(action: DeviceAction, result: CapabilityResult): String = buildString {
        appendLine("<SAGE_TOOL_RESULT>")
        appendLine("name=${action.name}")
        appendLine("success=${result.success}")
        appendLine("detail=${sanitize(result.detail)}")
        appendLine("</SAGE_TOOL_RESULT>")
        append("Continue the same owner turn. If another listed tool is necessary, emit exactly one SAGE_TOOL block. Otherwise answer the owner normally.")
    }

    private fun sanitize(value: String): String = value
        .replace("\u0000", "")
        .replace("</SAGE_TOOL_RESULT>", "[tool-result-end]")
        .take(24_000)
}
