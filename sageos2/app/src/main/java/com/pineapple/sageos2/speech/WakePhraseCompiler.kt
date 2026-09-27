package com.pineapple.sageos2.speech

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/**
 * Tiny deterministic SentencePiece-BPE encoder for short English wake phrases.
 * The merge table is exported from the exact pinned bpe.model packaged with Sage.
 */
class WakePhraseCompiler(context: Context) {
    private val appContext = context.applicationContext
    private val vocabulary: Vocabulary by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadVocabulary() }

    fun compile(rawPhrase: String): String? {
        val normalized = normalizeForKws(rawPhrase) ?: return null
        val symbols = codePointStrings(normalized).toMutableList()
        if (symbols.isEmpty()) return null

        while (symbols.size > 1) {
            var bestIndex = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (index in 0 until symbols.lastIndex) {
                val merged = symbols[index] + symbols[index + 1]
                val entry = vocabulary.entries[merged] ?: continue
                if (entry.type != TYPE_NORMAL) continue
                if (bestIndex < 0 || entry.score > bestScore) {
                    bestIndex = index
                    bestScore = entry.score
                }
            }
            if (bestIndex < 0) break
            symbols[bestIndex] = symbols[bestIndex] + symbols[bestIndex + 1]
            symbols.removeAt(bestIndex + 1)
        }

        if (symbols.any { it !in vocabulary.tokens }) return null
        return symbols.joinToString(" ")
    }

    private fun loadVocabulary(): Vocabulary {
        val entries = linkedMapOf<String, Entry>()
        appContext.assets.open(BPE_VOCAB_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val parts = line.split('\t')
                require(parts.size == 3) { "Malformed wake BPE vocabulary entry" }
                val piece = parts[0]
                val score = parts[1].toFloat()
                val type = parts[2].toInt()
                require(piece.isNotEmpty()) { "Empty wake BPE piece" }
                entries[piece] = Entry(score, type)
            }
        }
        require(entries.isNotEmpty()) { "Wake BPE vocabulary is empty" }

        val tokens = linkedSetOf<String>()
        appContext.assets.open(TOKENS_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trimEnd()
                val split = trimmed.lastIndexOf(' ')
                if (split > 0 && trimmed.substring(split + 1).toIntOrNull() != null) {
                    tokens += trimmed.substring(0, split)
                }
            }
        }
        require(tokens.isNotEmpty()) { "Wake token inventory is empty" }
        return Vocabulary(entries, tokens)
    }

    private fun normalizeForKws(raw: String): String? {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .uppercase(Locale.US)
            .trim()
            .replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return null
        if (normalized.length > MAX_PHRASE_CHARACTERS) return null
        return "▁" + normalized.replace(" ", "▁")
    }

    private fun codePointStrings(value: String): List<String> {
        val result = ArrayList<String>(value.length)
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            result += String(Character.toChars(codePoint))
            offset += Character.charCount(codePoint)
        }
        return result
    }

    private data class Entry(val score: Float, val type: Int)
    private data class Vocabulary(val entries: Map<String, Entry>, val tokens: Set<String>)

    companion object {
        private const val TYPE_NORMAL = 1
        private const val MAX_PHRASE_CHARACTERS = 80
        private const val BPE_VOCAB_ASSET = "sherpa-kws/bpe-vocab.tsv"
        private const val TOKENS_ASSET = "sherpa-kws/tokens.txt"
    }
}
