package com.pineapple.sageos2.brain

/**
 * Keeps each local turn inside limits already exercised by the inherited Sage tablet Brain.
 * Ordinary conversation is Sage + twin context only. Engineering contracts are opt-in per turn:
 * capability/tool context is supplied only for action-shaped requests, and task-recovery context
 * only when the owner is actually asking to continue or resume work.
 */
object BrainRequestPolicy {
    const val SELF_CHECK_PROMPT = "Reply with exactly: Brain online."

    private val exactRequest = Regex("(?i)^\\s*reply\\s+with\\s+exactly\\s*:\\s*(.+?)\\s*$")
    private val conversationalCue = Regex(
        "(?i)\\b(based on our conversation|what did i|earlier|continue our|do you remember)\\b"
    )
    private val taskCue = Regex(
        "(?i)\\b(continue|resume|pick up|where were we|unfinished|recoverable task|previous task)\\b"
    )
    private val actionCue = Regex(
        "(?i)^\\s*(?:(?:can|could|would)\\s+you\\s+|please\\s+)?" +
            "(open|launch|tap|click|choose|select|scroll|search|find|type|play|pause|share|copy|edit|" +
            "install|uninstall|send|move|delete|rename|turn|set|change)\\b"
    )

    data class Profile(
        val systemGuide: String,
        val combinedCharacterBudget: Int,
        val outputTokens: Int,
        val deterministic: Boolean,
        val expectedLiteral: String? = null,
        val includeTwinContext: Boolean = true,
        val includeToolContext: Boolean = false,
        val includeTaskContext: Boolean = false
    )

    fun forPrompt(prompt: String): Profile {
        val cleaned = prompt.trim().replace(Regex("\\s+"), " ")
        val literal = exactRequest.matchEntire(cleaned)?.groupValues?.get(1)?.let(::unwrapLiteral)
        if (!literal.isNullOrBlank()) {
            val estimatedTokens = ((literal.length + 3) / 4).coerceAtLeast(1)
            return Profile(
                systemGuide = "Output only the requested literal. No explanation. /no_think",
                combinedCharacterBudget = 320,
                outputTokens = (estimatedTokens + 3).coerceIn(4, 12),
                deterministic = true,
                expectedLiteral = literal,
                includeTwinContext = false
            )
        }

        val wantsTaskContext = taskCue.containsMatchIn(cleaned)
        val wantsToolContext = actionCue.containsMatchIn(cleaned)

        if (conversationalCue.containsMatchIn(cleaned) || wantsTaskContext) {
            return Profile(
                systemGuide = BrainPromptBudget.LOCAL_RESPONSE_GUIDE,
                combinedCharacterBudget = 4_800,
                outputTokens = 24,
                deterministic = false,
                includeTaskContext = wantsTaskContext,
                includeToolContext = wantsToolContext
            )
        }

        if (wantsToolContext) {
            return Profile(
                systemGuide = BrainPromptBudget.LOCAL_RESPONSE_GUIDE,
                combinedCharacterBudget = 3_900,
                outputTokens = 20,
                deterministic = false,
                includeToolContext = true
            )
        }

        return Profile(
            systemGuide = BrainPromptBudget.LOCAL_RESPONSE_GUIDE,
            combinedCharacterBudget = 3_600,
            outputTokens = 16,
            deterministic = true
        )
    }

    fun literalMatches(generated: String, expected: String): Boolean =
        unwrapLiteral(generated) == unwrapLiteral(expected)

    private fun unwrapLiteral(value: String): String {
        val cleaned = value.trim().replace(Regex("\\s+"), " ")
        return if (
            cleaned.length >= 2 &&
            ((cleaned.startsWith('"') && cleaned.endsWith('"')) ||
                (cleaned.startsWith('\'') && cleaned.endsWith('\'')))
        ) {
            cleaned.substring(1, cleaned.length - 1)
        } else {
            cleaned
        }
    }
}
