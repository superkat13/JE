package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.brain.BrainProgress
import com.pineapple.sageos2.core.SageRuntimeSnapshot
import com.pineapple.sageos2.diagnostics.SharedPreferencesTraceStore
import com.pineapple.sageos2.diagnostics.TraceLevel

class PersistentRuntimeObserver(private val traces: SharedPreferencesTraceStore) : RuntimeObserver {
    override fun onDiagnostic(message: String) = traces.record("runtime", message)
    override fun onTypedInputQueued(depth: Int) = traces.record("text_queue", "typed input queued", metadata = mapOf("depth" to depth.toString()))
    override fun onTypedInputRejected(reason: String) = traces.record("text_queue", "typed input rejected: $reason", level = TraceLevel.WARN)
    override fun onUnhandledFailure(message: String, cause: Throwable?) = traces.record(
        "runtime_failure",
        "$message: ${cause?.message ?: cause?.javaClass?.simpleName.orEmpty()}",
        level = TraceLevel.ERROR
    )
    override fun onStateChanged(snapshot: SageRuntimeSnapshot) = traces.record(
        "state",
        "${snapshot.state}/${snapshot.listeningMode}",
        turnId = snapshot.activeTurnId.takeIf { it != 0L },
        metadata = mapOf("origin" to snapshot.activeTurnOrigin.name, "queue" to snapshot.queuedTextCount.toString())
    )
    override fun onBrainProgress(progress: BrainProgress) = traces.record(
        "brain_progress",
        progress.stage.name,
        turnId = progress.turnId
    )
    override fun onTextResponse(turnId: Long, text: String) = traces.record("text_response", "response emitted", turnId, metadata = mapOf("chars" to text.length.toString()))
}
