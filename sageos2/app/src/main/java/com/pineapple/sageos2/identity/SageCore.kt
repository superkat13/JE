package com.pineapple.sageos2.identity

/** Sage is the owner's virtual twin, not a generic developer-authored assistant. */
data class SageCoreSnapshot(
    val revision: Long,
    val twinIdentity: String,
    val ownerModel: OwnerModel,
    val sageSelfModel: SageSelfModel,
    val sharedContinuity: SharedContinuity,
    val principles: List<String>,
    val preferences: List<String>,
    val selfRestrictions: List<String>,
    val notes: String = ""
)

data class OwnerModel(
    val preferredNames: List<String> = emptyList(),
    val vocabulary: Map<String, String> = emptyMap(),
    val workingStyle: List<String> = emptyList(),
    val preferences: List<String> = emptyList(),
    val habits: List<String> = emptyList(),
    val trustedTools: List<String> = emptyList(),
    val recurringChoices: List<String> = emptyList(),
    val learnedFacts: Map<String, String> = emptyMap()
)

data class SageSelfModel(
    val identity: String = "Sage",
    val capabilities: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val experiences: List<String> = emptyList()
)

data class SharedContinuity(
    val activeProjects: Map<String, String> = emptyMap(),
    val durableDecisions: List<String> = emptyList(),
    val activeTasks: List<String> = emptyList(),
    val lessonsLearned: List<String> = emptyList()
)

interface SageCoreProvider {
    fun current(): SageCoreSnapshot
}

object EmptySageCoreProvider : SageCoreProvider {
    override fun current() = SageCoreSnapshot(
        revision = 0L,
        twinIdentity = "Sage is the owner's virtual twin: a distinct software identity that shares durable context, preferences, working style, tools, projects, and learned experience with the owner.",
        ownerModel = OwnerModel(),
        sageSelfModel = SageSelfModel(),
        sharedContinuity = SharedContinuity(),
        principles = emptyList(),
        preferences = emptyList(),
        selfRestrictions = emptyList(),
        notes = ""
    )
}
