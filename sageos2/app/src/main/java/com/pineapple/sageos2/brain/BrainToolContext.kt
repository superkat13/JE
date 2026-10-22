package com.pineapple.sageos2.brain

import com.pineapple.sageos2.capability.Capability
import com.pineapple.sageos2.capability.CapabilityResult
import com.pineapple.sageos2.capability.CapabilitySnapshot
import com.pineapple.sageos2.capability.CapabilityStatus
import com.pineapple.sageos2.capability.DeviceAction

/** One observed result in this live turn; never a claim that the complete owner goal succeeded. */
data class BrainToolExecutionEvidence(
    val step: Int,
    val name: String,
    val arguments: String,
    val success: Boolean,
    val detail: String
)

object BrainToolContextRenderer {
    const val MAX_EVIDENCE_RECORDS = 16
    const val MAX_RENDERED_EVIDENCE_CHARACTERS = 1_200
    private const val OMITTED = "[...omitted...]"

    fun render(snapshot: CapabilitySnapshot): String = buildString {
        appendLine("# SAGE TOOL CONTRACT")
        appendLine("Use only listed tools. Tool response = one block, no prose:")
        appendLine("<SAGE_TOOL>\nname=tool.name\nargument=value\n</SAGE_TOOL>")
        appendLine("Otherwise reply normally. Prefer labels. Bracketed args are optional.")
        appendLine("device.open_app: app; device.tap_label: label")
        appendLine("device.back; device.home; device.recents; device.notifications; device.quick_settings")
        appendLine("device.scroll: direction=UP|DOWN|LEFT|RIGHT; device.swipe: direction=UP|DOWN|LEFT|RIGHT")
        appendLine("device.tap: x,y; device.screenshot; device.read_notifications")
        appendLine("device.volume: direction=UP|DOWN|MUTE|UNMUTE; device.media: action=PLAY|PAUSE|NEXT|PREVIOUS")
        appendLine("device.set_timer: seconds; device.set_alarm: hour24,minute")
        if (snapshot.states[Capability.SAGEOS_ROOT_BROKER] == CapabilityStatus.ACTIVE) {
            appendLine("root.health; root.install_package: path,[replace=true|false]")
            appendLine("root.uninstall_package: package,[keep_data=true|false]; root.set_package_enabled: package,enabled=true|false")
            appendLine("root.write_setting: namespace=system|secure|global,key,[value] (omit value to delete)")
            appendLine("root.chown: path,uid,gid; root.chmod: path,mode=decimal_integer; root.restart_service: service")
            appendLine("root.power: action=REBOOT|SHUTDOWN|REBOOT_RECOVERY")
            appendLine("root.exec: executable=absolute_path,[arg.0,arg.1...],[env.NAME],[cwd],[timeout_ms]; separate argv, no shell concatenation.")
        } else appendLine("Do not emit root.* (unavailable).")
        if (snapshot.states[Capability.FORGE] == CapabilityStatus.ACTIVE) {
            appendLine("forge.health; forge.tools; forge.start_job: tool_id,[input.<key>=value]")
            appendLine("forge.job: job_id; forge.cancel: job_id")
            appendLine("Forge submissions always owner_approved=false; pairing/revocation are not Brain tools.")
        } else appendLine("Do not emit forge.* (unavailable).")
    }.trim()

    fun captureResult(step: Int, action: DeviceAction, result: CapabilityResult): BrainToolExecutionEvidence {
        require(step in 1..MAX_EVIDENCE_RECORDS)
        return BrainToolExecutionEvidence(
            step = step,
            name = bounded(data(action.name), 96),
            arguments = bounded(action.arguments.entries.sortedBy { it.key }
                .joinToString(", ") { "${data(it.key)}=${data(it.value)}" }, 256),
            success = result.success,
            detail = bounded(data(result.detail), 2_048)
        )
    }

    fun renderContinuation(ownerGoal: String, evidence: List<BrainToolExecutionEvidence>): String = buildString {
        appendLine("<SAGE_TOOL_RESULT>")
        appendLine("owner_goal=${data(ownerGoal)}")
        appendLine(renderEvidence(evidence))
        appendLine("</SAGE_TOOL_RESULT>")
        append("Continue this original goal using the observed results, not unrelated history. " +
            "Do not repeat successful mutations. If more work is needed, emit one listed SAGE_TOOL block; otherwise answer from evidence.")
    }

    // Retained for callers that only have one result; the runtime uses renderContinuation.
    fun renderResult(action: DeviceAction, result: CapabilityResult): String =
        renderContinuation("", listOf(captureResult(1, action, result)))

    /** Keep the newest facts, then earlier results in execution order, with explicit omissions. */
    fun renderEvidence(evidence: List<BrainToolExecutionEvidence>): String {
        require(evidence.size <= MAX_EVIDENCE_RECORDS)
        val note = "Omitted output is unknown; use read-only evidence when needed. Never replay successful mutations."
        val selected = mutableListOf<String>()
        var remaining = MAX_RENDERED_EVIDENCE_CHARACTERS - note.length - 90
        var omitted = 0
        for (item in evidence.asReversed()) {
            val header = "step=${item.step} name=${item.name} success=${item.success} arguments=${item.arguments}\n"
            if (remaining < header.length + 60) { omitted++; continue }
            // Reserve useful room for older facts rather than allowing one large payload to erase them.
            val allowance = minOf(remaining, if (selected.isEmpty()) 650 else 350)
            val detailRoom = allowance - header.length - "detail=\n".length
            val record = header + "detail=" + bounded(item.detail, detailRoom.coerceAtLeast(OMITTED.length))
            selected += record
            remaining -= record.length + 1
        }
        return buildString {
            appendLine("observed_tool_results=${evidence.size}; earlier_result_records_omitted=$omitted")
            selected.asReversed().forEach { appendLine(it) }
            append(note)
        }
    }

    private fun data(value: String): String = value.replace("\u0000", "")
        .replace("<", "‹").replace(">", "›")
        .replace("\r", "\\r").replace("\n", "\\n")

    private fun bounded(value: String, limit: Int): String {
        if (value.length <= limit) return value
        val available = (limit - OMITTED.length).coerceAtLeast(0)
        val head = (available * 2 / 3)
        return value.take(head) + OMITTED + value.takeLast(available - head)
    }
}
