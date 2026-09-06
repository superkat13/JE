package com.pineapple.sageos2.core

class SageCommandRouter {
    fun route(rawText: String): RouteDecision {
        val normalized = normalize(rawText)

        if (normalized == "do you feel like chicken tonight") {
            return RouteDecision(
                route = SageRoute.OWNER_WORKFLOW,
                normalizedText = normalized,
                workflowId = "chicken_tonight"
            )
        }

        val fastPrefixes = listOf(
            "open ", "launch ", "close ", "go back", "go home",
            "scroll ", "swipe ", "tap ", "press ", "volume ",
            "turn on ", "turn off ", "set timer", "set alarm",
            "take screenshot", "show notifications"
        )
        if (fastPrefixes.any { normalized == it.trim() || normalized.startsWith(it) }) {
            return RouteDecision(SageRoute.FAST_DEVICE, normalized)
        }

        return RouteDecision(SageRoute.DEEP_REASONING, normalized)
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
