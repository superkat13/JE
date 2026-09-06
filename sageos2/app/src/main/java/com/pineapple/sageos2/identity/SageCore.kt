package com.pineapple.sageos2.identity

/**
 * Sage Core is readable behavioral identity data, not hidden application policy.
 * A Brain adapter receives this snapshot with every reasoning request.
 */
data class SageCoreSnapshot(
    val revision: Long,
    val identity: String,
    val principles: List<String>,
    val preferences: List<String>,
    val selfRestrictions: List<String>,
    val notes: String = ""
)

interface SageCoreProvider {
    fun current(): SageCoreSnapshot
}

object EmptySageCoreProvider : SageCoreProvider {
    override fun current() = SageCoreSnapshot(
        revision = 0L,
        identity = "",
        principles = emptyList(),
        preferences = emptyList(),
        selfRestrictions = emptyList(),
        notes = ""
    )
}
