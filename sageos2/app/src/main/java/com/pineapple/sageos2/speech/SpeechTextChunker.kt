package com.pineapple.sageos2.speech

object SpeechTextChunker {
    const val DEFAULT_MAX_CHARS = 3_900

    fun chunk(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        require(maxChars > 0) { "maxChars must be positive" }
        if (text.isEmpty()) return emptyList()
        if (text.length <= maxChars) return listOf(text)

        val chunks = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxChars, text.length)
            if (end < text.length) {
                val boundary = findBoundary(text, start, end)
                if (boundary > start) end = boundary
                if (end < text.length &&
                    end - start > 1 &&
                    text[end - 1].isHighSurrogate() &&
                    text[end].isLowSurrogate()
                ) {
                    end -= 1
                }
            }
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }

    private fun findBoundary(text: String, start: Int, end: Int): Int {
        val earliest = start + maxOf(1, (end - start) / 2)
        for (index in end - 1 downTo earliest) {
            if (text[index] == '\n') return index + 1
        }
        for (index in end - 1 downTo earliest) {
            if (text[index] in charArrayOf('.', '!', '?', ';', ':', '。', '！', '？')) {
                return index + 1
            }
        }
        for (index in end - 1 downTo earliest) {
            if (text[index].isWhitespace()) return index + 1
        }
        return end
    }
}
