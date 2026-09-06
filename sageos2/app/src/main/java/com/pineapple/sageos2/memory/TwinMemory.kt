package com.pineapple.sageos2.memory

enum class TwinMemorySubject {
    OWNER,
    SAGE,
    SHARED,
    DEVICE,
    APP,
    PROJECT
}

enum class TwinMemorySource {
    EXPLICIT_OWNER,
    OBSERVED_CHOICE,
    SHARED_DECISION,
    SAGE_EXPERIENCE,
    DEVICE_OBSERVATION,
    IMPORTED
}

data class TwinMemoryRecord(
    val id: String,
    val subject: TwinMemorySubject,
    val key: String,
    val value: String,
    val source: TwinMemorySource,
    val confidence: Double,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val active: Boolean = true
) {
    init {
        require(id.isNotBlank())
        require(key.isNotBlank())
        require(value.isNotBlank())
        require(confidence in 0.0..1.0)
    }
}

data class TwinMemorySnapshot(
    val revision: Long,
    val records: List<TwinMemoryRecord>
) {
    fun activeFor(subject: TwinMemorySubject): List<TwinMemoryRecord> =
        records.filter { it.active && it.subject == subject }
}

interface TwinMemoryProvider {
    fun snapshot(): TwinMemorySnapshot
}

object EmptyTwinMemoryProvider : TwinMemoryProvider {
    override fun snapshot() = TwinMemorySnapshot(0L, emptyList())
}
