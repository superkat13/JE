package com.pineapple.sageos2.brain

data class BrainToolDirective(
    val name: String,
    val arguments: Map<String, String>
)

object BrainToolDirectiveParser {
    private const val OPEN = "<SAGE_TOOL>"
    private const val CLOSE = "</SAGE_TOOL>"
    private val token = Regex("[a-z0-9_.-]{1,64}")
    private const val MAX_LINES = 160
    private const val MAX_VALUE_CHARS = 8_192
    private const val MAX_BLOCK_CHARS = 32_768

    /**
     * Returns null for normal prose. If the response opts into a SAGE_TOOL block,
     * malformed tool syntax throws instead of silently degrading into an action.
     */
    fun parse(response: String): BrainToolDirective? {
        val text = response.trim()
        if (!text.startsWith(OPEN) && !text.endsWith(CLOSE)) return null
        require(text.startsWith(OPEN) && text.endsWith(CLOSE)) {
            "A Sage tool response must be exactly one complete SAGE_TOOL block"
        }
        require(text.length <= MAX_BLOCK_CHARS) { "Sage tool block exceeded size limit" }

        val body = text.removePrefix(OPEN).removeSuffix(CLOSE).trim()
        require(body.isNotBlank()) { "Sage tool block is empty" }
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.size in 1..MAX_LINES) { "Sage tool block has an invalid line count" }

        val fields = linkedMapOf<String, String>()
        for (line in lines) {
            val split = line.indexOf('=')
            require(split > 0) { "Malformed Sage tool line" }
            val key = line.substring(0, split).trim()
            val value = line.substring(split + 1)
            require(token.matches(key)) { "Invalid Sage tool key: $key" }
            require(value.isNotEmpty() && value.length <= MAX_VALUE_CHARS) { "Invalid Sage tool value for $key" }
            require(key !in fields) { "Duplicate Sage tool key: $key" }
            fields[key] = value
        }

        val name = fields.remove("name") ?: error("Sage tool block is missing name")
        require(token.matches(name)) { "Invalid Sage tool name: $name" }
        return BrainToolDirective(name, fields.toMap())
    }
}
