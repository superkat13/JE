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

        if (normalized in setOf(
                "share diagnostic report",
                "share sage diagnostic report",
                "send diagnostic report",
                "import brain model",
                "choose brain model",
                "load brain model"
            )
        ) {
            return RouteDecision(SageRoute.FAST_DEVICE, normalized)
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

        // Normalization is only for deterministic command matching. Sage's Brain must receive the
        // owner's actual wording, punctuation, capitalization, names, and code unchanged.
        return RouteDecision(SageRoute.DEEP_REASONING, rawText.trim())
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
