package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.capability.DeviceAction

/**
 * Safe restart policy for an owner goal that was interrupted while Sage was working.
 *
 * Recovery never assumes that the last side effect failed just because Sage did not record its
 * result. The current device/Forge state is the source of truth. Mutating actions are guarded
 * against blind replay until Sage obtains fresh read-only evidence.
 */
object RecoveryCompletionPolicy {
    const val RECOVERY_MARKER = "<SAGE_GOAL_RECOVER>"
    const val MAX_AUTO_RESUME_DEPTH = 3
    const val MAX_REPLAY_BLOCKS = 2

    data class ReplayGuard(
        val actionName: String,
        val actionSignature: String?
    )

    fun recoveryPrompt(
        recoveredTaskId: String,
        ownerGoal: String,
        priorPhase: String?,
        lastAction: String?,
        lastActionSignature: String?,
        lastActionSuccess: String?,
        completedToolCalls: Int,
        recoveryDepth: Int
    ): String {
        val goal = sanitize(ownerGoal).take(4_000)
        return buildString {
            appendLine(RECOVERY_MARKER)
            appendLine("recovered_task_id=${sanitize(recoveredTaskId)}")
            appendLine("owner_goal=$goal")
            appendLine("prior_phase=${sanitize(priorPhase.orEmpty()).ifBlank { \"unknown\" }}")
            appendLine("completed_tool_calls=$completedToolCalls")
            appendLine("recovery_depth=$recoveryDepth")
            lastAction?.takeIf { it.isNotBlank() }?.let { appendLine("last_action=${sanitize(it)}") }
            lastActionSignature?.takeIf { it.isNotBlank() }?.let {
                appendLine("last_action_signature=${sanitize(it).take(2_000)}")
            }
            lastActionSuccess?.takeIf { it.isNotBlank() }?.let {
                appendLine("last_action_recorded_success=${sanitize(it)}")
            }
            appendLine("</SAGE_GOAL_RECOVER>")
            appendLine("Resume the same owner goal after an interrupted Sage runtime.")
            appendLine("Treat the current real state as authoritative. Do not assume the last action failed or succeeded merely because Sage was interrupted.")
            appendLine("If a side effect may already have happened, verify current state with a read-only listed tool before repeating that side effect.")
            appendLine("Never blindly replay a mutating action. Prefer health, status, screenshot, notification, job-status, or other direct evidence when available.")
            appendLine("Continue toward the owner\'s original goal. Use exactly one SAGE_TOOL block when another tool is needed.")
            append("If no tool is needed, state only what the current evidence supports; Sage\'s normal goal-verification loop will decide whether completion is verified.")
        }.trim()
    }

    fun replayBlockedVerificationPrompt(
        ownerGoal: String,
        blockedAction: DeviceAction,
        completedToolCalls: Int,
        verificationRound: Int
    ): String = buildString {
        appendLine(GoalCompletionPolicy.verificationPrompt(ownerGoal, completedToolCalls, verificationRound))
        appendLine()
        appendLine("<SAGE_RECOVERY_REPLAY_BLOCKED>")
        appendLine("blocked_action=${actionSignature(blockedAction)}")
        appendLine("</SAGE_RECOVERY_REPLAY_BLOCKED>")
        appendLine("The recovered task attempted to repeat a mutating action whose prior completion is unknown.")
        appendLine("That replay was NOT executed.")
        appendLine("Use a different read-only verification tool to establish current state.")
        append("If no safe evidence path exists, return ${GoalCompletionPolicy.UNVERIFIED_MARKER} and explain what remains unknown.")
    }.trim()

    fun replayGuard(
        actionName: String?,
        actionSignature: String?
    ): ReplayGuard? {
        val name = actionName?.trim().orEmpty()
        if (name.isBlank() || !isMutating(name)) return null
        return ReplayGuard(name, actionSignature?.trim()?.takeIf { it.isNotBlank() })
    }

    fun shouldBlockReplay(guard: ReplayGuard, action: DeviceAction): Boolean {
        if (!isMutating(action.name)) return false
        val signature = actionSignature(action)
        return if (guard.actionSignature != null) {
            signature == guard.actionSignature
        } else {
            action.name == guard.actionName
        }
    }

    fun actionSignature(action: DeviceAction): String = buildString {
        append(action.name.trim())
        action.arguments.toSortedMap().forEach { (key, value) ->
            append(\'|\').append(sanitize(key)).append(\'=\').append(sanitize(value))
        }
    }.take(4_000)

    fun isMutating(actionName: String): Boolean = actionName !in READ_ONLY_ACTIONS

    private fun sanitize(value: String): String = value
        .replace("\\u0000", "")
        .replace("\\r", " ")
        .replace("\\n", " ")
        .trim()

    private val READ_ONLY_ACTIONS = setOf(
        "root.health",
        "forge.health",
        "forge.tools",
        "forge.job",
        "device.screenshot",
        "device.read_notifications"
    )
}
