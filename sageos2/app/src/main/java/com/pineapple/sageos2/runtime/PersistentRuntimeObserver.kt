package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.diagnostics.SharedPreferencesTraceStore
import com.pineapple.sageos2.diagnostics.TraceLevel

class PersistentRuntimeObserver(
    private val traces: SharedPreferencesTraceStore
) : RuntimeObserver {
    override fun onDiagnostic(message: String) = traces.record("runtime", message)
    override fun onTypedInputQueued(depth: Int) = traces.record("text_queue", "typed input queued", metadata = mapOf("depth" to depth.toString()))
    override fun onTypedInputRejected(reason: String) = traces.record("text_queue", "typed input rejected: $reason", level = TraceLevel.WARN)
    override fun onUnhandledFailure(message: String, cause: Throwable?) = traces.record(
        stage = "runtime_failure",
        message = "$message: ${cause?.message ?: cause?.javaClass?.simpleName.orEmpty()}",
        level = TraceLevel.ERROR
    )
}
