package com.pineapple.sageos2.brain

/** Keeps model-control tokens and Qwen reasoning markup out of Sage's visible reply. */
object BrainOutputCleaner {
    private val completeThinking = Regex("(?is)<think>.*?</think>")
    private val prefixThroughThinkingEnd = Regex("(?is)^.*?</think>")
    private val unclosedThinking = Regex("(?is)^\\s*<think>.*$")
    private val modelControlToken = Regex(
        "(?is)<\\|(?:im_start|im_end|assistant|user|system|endoftext)\\|>|</?(?:s|bos|eos)>"
    )

    fun clean(raw: String?): String {
        var value = raw.orEmpty()
        value = modelControlToken.replace(value, " ")
        value = completeThinking.replace(value, " ")
        value = prefixThroughThinkingEnd.replace(value, " ")
        if (unclosedThinking.matches(value)) return ""
        return value
            .lineSequence()
            .map { it.trimEnd() }
            .joinToString("\n")
            .trim()
    }
}
