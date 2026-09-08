package com.pineapple.sageos2.core

enum class SageRoute {
    LOCAL_SAGE,
    FAST_DEVICE,
    DEEP_REASONING,
    OWNER_WORKFLOW
}

data class RouteDecision(
    val route: SageRoute,
    val normalizedText: String,
    val workflowId: String? = null,
    val localReply: String? = null
)
