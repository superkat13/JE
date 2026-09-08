package com.pineapple.sageos2.core

/** Owner-facing language stays human; exact engine evidence remains in Advanced diagnostics. */
object SageResponseCopy {
    fun forBrainFailure(reason: String): String {
        val normalized = reason.lowercase()
        return when {
            normalized.contains("model file is missing") ||
                normalized.contains("local model path is not configured") ->
                "I can't find the part of me that writes replies yet. Tap Advanced, open Local Brain & capabilities, choose the model, then send that again."

            normalized.contains("model load failed") ||
                normalized.contains("could not load that gguf") ||
                normalized.contains("did not finish loading") ->
                "I couldn't finish getting ready to answer. Your message is still here. Tap Advanced → Local Brain & capabilities for the next step."

            normalized.contains("timed out") || normalized.contains("timeout") ->
                "I was taking too long, so I stopped this turn instead of leaving chat stuck. Your message is saved—try it once more."

            normalized.contains("context window") || normalized.contains("prompt exceeded") ->
                "That was more context than I could hold in one turn. Your message is saved—send a shorter version and I'll pick it up."

            normalized.contains("self-check") ->
                "My reply check didn't pass. I'm still here; if we need the exact evidence, it's under Advanced → Diagnostics."

            else ->
                "I couldn't finish that reply, but I'm still here and your message is saved. Try it again; if it keeps happening, the details are under Advanced → Diagnostics."
        }
    }
}
