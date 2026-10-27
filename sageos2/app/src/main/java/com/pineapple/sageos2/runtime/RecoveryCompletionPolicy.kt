package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.capability.DeviceAction

/**
 * Safe restart policy for an owner goal that was interrupted while Sage was working.
 *
 * Recovery never assumes that the last side effect failed just because Sage did not record its
 * result. The current device/Forge state is the source of truth. Mutating actions are guarded
 * against replay throughout the recovered turn. A successful read-only call is not proof that an
 * interrupted mutation did not happen. Guards survive read-only work and subsequent restarts.
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
            appendLine("prior_phase=${sanitize(priorPhase.orEmpty()).ifBlank { "unknown" }}")
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
            appendLine("A healthy broker or successful read-only call does not authorize replay. Protected mutations remain blocked throughout recovery; verify completion, take a different safe action, or leave the goal waiting.")
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
        appendLine("Use a read-only verification tool to establish current state. Its success does not clear the replay guard.")
        appendLine("Do not retry the blocked action in this recovery turn. Verify completion, choose a different safe action, or leave the goal waiting.")
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

    /** Keep unresolved mutations separate from lastAction, which read-only checks overwrite. */
    fun replayGuards(metadata: Map<String, String>): List<ReplayGuard> = buildList {
        val count = (metadata["recoveryGuardCount"]?.toIntOrNull() ?: 0).coerceIn(0, 64)
        repeat(count) { index ->
            replayGuard(metadata["recoveryGuard${index}Action"], metadata["recoveryGuard${index}Signature"])
                ?.let { add(it) }
        }
        // Build 216 checkpoints have only lastAction; migrate them without losing protection.
        replayGuard(metadata["lastAction"], metadata["lastActionSignature"])?.let { add(it) }
    }.distinct()

    fun replayGuardMetadata(guards: List<ReplayGuard>): Map<String, String> = buildMap {
        val unique = guards.distinct()
        put("recoveryGuardCount", unique.size.toString())
        unique.forEachIndexed { index, guard ->
            put("recoveryGuard${index}Action", guard.actionName)
            guard.actionSignature?.let { put("recoveryGuard${index}Signature", it) }
        }
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
            append('|').append(sanitize(key)).append('=').append(sanitize(value))
        }
    }.take(4_000)

    fun isMutating(actionName: String): Boolean = actionName !in READ_ONLY_ACTIONS

    private fun sanitize(value: String): String = value
        .replace("\u0000", "")
        .replace("\r", " ")
        .replace("\n", " ")
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
