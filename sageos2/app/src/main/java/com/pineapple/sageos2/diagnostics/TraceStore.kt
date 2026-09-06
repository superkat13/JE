package com.pineapple.sageos2.diagnostics

enum class TraceLevel { DEBUG, INFO, WARN, ERROR }

data class TraceEvent(
    val id: String,
    val timestampMs: Long,
    val turnId: Long? = null,
    val stage: String,
    val message: String,
    val level: TraceLevel = TraceLevel.INFO,
    val metadata: Map<String, String> = emptyMap()
)

interface TraceStore {
    fun append(event: TraceEvent)
    fun recent(limit: Int = 200): List<TraceEvent>
    fun clear()
}
