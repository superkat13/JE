package com.pineapple.sageos2.diagnostics

data class DiagnosticTaskSummary(
    val id: String,
    val title: String,
    val state: String,
    val nextStep: String
)

data class DiagnosticReportSnapshot(
    val createdAtMs: Long,
    val appVersion: String,
    val packageName: String,
    val device: String,
    val android: String,
    val runtimeState: String,
    val listeningMode: String,
    val brainReady: Boolean,
    val brainDetail: String,
    val brainLastLatencyMs: Long?,
    val wakeReady: Boolean,
    val wakeEngine: String,
    val wakeDetail: String,
    val capabilities: Map<String, String>,
    val sageCoreRevision: Long,
    val profileId: String,
    val modeId: String?,
    val ownerAppsRevision: Long,
    val ownerAppsCount: Int,
    val recoverableTasks: List<DiagnosticTaskSummary>,
    val chickenTonightScopeStatus: String,
    val traces: List<TraceEvent>
)

object DiagnosticReportRenderer {
    fun render(snapshot: DiagnosticReportSnapshot): String = buildString {
        appendLine("SageOS 2 diagnostic report")
        appendLine("Created: ${snapshot.createdAtMs}")
        appendLine("Version: ${oneLine(snapshot.appVersion)}")
        appendLine("Package: ${oneLine(snapshot.packageName)}")
        appendLine("Device: ${oneLine(snapshot.device)}")
        appendLine("Android: ${oneLine(snapshot.android)}")
        appendLine()
        appendLine("Runtime")
        appendLine("State: ${oneLine(snapshot.runtimeState)}")
        appendLine("Input: ${oneLine(snapshot.listeningMode)}")
        appendLine("Brain: ${if (snapshot.brainReady) "ready" else "not ready"} • ${oneLine(snapshot.brainDetail)}")
        snapshot.brainLastLatencyMs?.let { appendLine("Brain last latency: ${it} ms") }
        appendLine("Wake: ${if (snapshot.wakeReady) "ready" else "not ready"} • ${oneLine(snapshot.wakeEngine)} • ${oneLine(snapshot.wakeDetail)}")
        appendLine()
        appendLine("Capabilities")
        if (snapshot.capabilities.isEmpty()) appendLine("(none reported)")
        snapshot.capabilities.toSortedMap().forEach { (name, state) -> appendLine("$name: $state") }
        appendLine()
        appendLine("Owner state")
        appendLine("Sage Core revision: ${snapshot.sageCoreRevision}")
        appendLine("Profile: ${oneLine(snapshot.profileId)}")
        appendLine("Mode: ${oneLine(snapshot.modeId ?: "normal")}")
        appendLine("Owner Apps: ${snapshot.ownerAppsCount} • revision ${snapshot.ownerAppsRevision}")
        appendLine("Chicken Tonight scope: ${oneLine(snapshot.chickenTonightScopeStatus)}")
        appendLine()
        appendLine("Active / recoverable tasks")
        if (snapshot.recoverableTasks.isEmpty()) appendLine("(none)")
        snapshot.recoverableTasks.take(20).forEach { task ->
            appendLine("- ${oneLine(task.title.ifBlank { task.id })} [${oneLine(task.state)}]")
            if (task.nextStep.isNotBlank()) appendLine("  next: ${oneLine(task.nextStep).take(500)}")
        }
        appendLine()
        appendLine("Recent trace")
        if (snapshot.traces.isEmpty()) appendLine("(none)")
        snapshot.traces.takeLast(100).forEach { event ->
            append(event.timestampMs)
            append(" • ").append(event.level)
            append(" • ").append(oneLine(event.stage))
            event.turnId?.let { append(" • turn=").append(it) }
            append(" • ").append(oneLine(event.message).take(800))
            appendLine()
        }
        appendLine()
        append("Privacy: conversation text, Sage Core contents, Owner App details, scope authorization details, and trace metadata are intentionally omitted from this report.")
    }.trim()

    private fun oneLine(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}
