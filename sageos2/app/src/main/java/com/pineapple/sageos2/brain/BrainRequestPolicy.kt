package com.pineapple.sageos2.brain

/**
 * Keeps each local turn inside limits exercised by the inherited Sage tablet Brain.
 * Candidate 208 measured prompt prefill at about 7.75 tokens/second on the L10_T05, so the
 * historical 3,600-4,800 character ceilings are preserved only as an upper compatibility bound
 * in BrainPromptBudget. Runtime profiles use smaller working sets so Sage can reach a first token
 * before the absolute turn watchdog while still keeping identity and relevant continuity.
 * Engineering context is added only for a turn that actually needs it.
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
            "install|uninstall|send|move|delete|rename|turn|set|change|check|inspect|run)\\b"
    )
    private val toolResultCue = Regex("(?i)^\\s*<?SAGE_TOOL_RESULT\\b")

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

        val isToolContinuation = toolResultCue.containsMatchIn(cleaned)
        val wantsTaskContext = taskCue.containsMatchIn(cleaned) || isToolContinuation
        val wantsToolContext = actionCue.containsMatchIn(cleaned) || isToolContinuation

        if (conversationalCue.containsMatchIn(cleaned) || wantsTaskContext) {
            return Profile(
                systemGuide = BrainPromptBudget.LOCAL_RESPONSE_GUIDE,
                combinedCharacterBudget = 2_000,
                outputTokens = 48,
                deterministic = false,
                includeTaskContext = wantsTaskContext,
                includeToolContext = wantsToolContext
            )
        }

        if (wantsToolContext) {
            return Profile(
                systemGuide = BrainPromptBudget.LOCAL_RESPONSE_GUIDE,
                combinedCharacterBudget = 2_000,
                outputTokens = 32,
                deterministic = false,
                includeToolContext = true
            )
        }

        return Profile(
            systemGuide = BrainPromptBudget.LOCAL_RESPONSE_GUIDE,
            combinedCharacterBudget = 1_600,
            outputTokens = 40,
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
