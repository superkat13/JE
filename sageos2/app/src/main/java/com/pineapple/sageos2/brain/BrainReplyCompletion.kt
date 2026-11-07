package com.pineapple.sageos2.brain

object BrainReplyCompletion {
    private const val TOOL_OPEN = "<SAGE_TOOL>"
    private const val TOOL_CLOSE = "</SAGE_TOOL>"
    private val sentenceTerminals = charArrayOf('.', '!', '?', '。', '！', '？')
    private val closingMarks = charArrayOf('"', '\'', ')', ']', '}', '”', '’', '»')

    fun startsToolBlock(text: String): Boolean = text.trimStart().startsWith(TOOL_OPEN)

    fun isCompleteToolBlock(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith(TOOL_OPEN) && trimmed.endsWith(TOOL_CLOSE)
    }

    fun endsAtSentenceBoundary(text: String): Boolean {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return false
        var index = trimmed.length - 1
        while (index >= 0 && trimmed[index] in closingMarks) index -= 1
        return index >= 0 && trimmed[index] in sentenceTerminals
    }

    fun completePrefix(text: String): String? {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return null
        if (endsAtSentenceBoundary(trimmed)) return trimmed

        for (index in trimmed.indices.reversed()) {
            val candidate = trimmed[index]
            if (candidate !in sentenceTerminals) continue
            if (candidate == '.' &&
                index > 0 &&
                index + 1 < trimmed.length &&
                trimmed[index - 1].isDigit() &&
                trimmed[index + 1].isDigit()
            ) {
                continue
            }
            var end = index + 1
            while (end < trimmed.length && trimmed[end] in closingMarks) end += 1
            val following = trimmed.getOrNull(end)
            if (following == null || following.isWhitespace()) {
                val prefix = trimmed.substring(0, end).trimEnd()
                if (prefix.isNotEmpty()) return prefix
            }
        }
        return null
    }
}
