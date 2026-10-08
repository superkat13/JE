package com.pineapple.sageos2.speech

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic SentencePiece unigram encoder for short English wake phrases.
 * Despite its filename, the pinned bpe.model declares UNIGRAM, not BPE.
 * Select the highest-scoring complete segmentation; greedy pair merging is incorrect.
 */
class WakePhraseCompiler(context: Context) {
    private val appContext = context.applicationContext
    private val vocabulary: Vocabulary by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadVocabulary() }

    fun compile(rawPhrase: String): String? {
        val normalized = normalizeForKws(rawPhrase) ?: return null
        val symbols = codePointStrings(normalized)
        val scores = DoubleArray(symbols.size + 1) { Double.NEGATIVE_INFINITY }
        val previous = IntArray(symbols.size + 1) { -1 }
        val pieces = arrayOfNulls<String>(symbols.size + 1)
        scores[0] = 0.0
        for (start in symbols.indices) {
            if (!scores[start].isFinite()) continue
            val candidate = StringBuilder()
            for (end in start until symbols.size) {
                candidate.append(symbols[end])
                if (candidate.length > vocabulary.maxPieceLength) break
                val piece = candidate.toString()
                val entry = vocabulary.entries[piece] ?: continue
                if (entry.type != TYPE_NORMAL || piece !in vocabulary.tokens) continue
                val score = scores[start] + entry.score
                if (score > scores[end + 1]) {
                    scores[end + 1] = score
                    previous[end + 1] = start
                    pieces[end + 1] = piece
                }
            }
        }
        if (previous[symbols.size] < 0) return null
        val result = mutableListOf<String>()
        var end = symbols.size
        while (end > 0) {
            result += pieces[end] ?: return null
            end = previous[end]
        }
        return result.asReversed().joinToString(" ")
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
    private data class Vocabulary(val entries: Map<String, Entry>, val tokens: Set<String>) {
        val maxPieceLength = entries.keys.maxOf { it.length }
    }

    companion object {
        private const val TYPE_NORMAL = 1
        private const val MAX_PHRASE_CHARACTERS = 80
        private const val BPE_VOCAB_ASSET = "sherpa-kws/bpe-vocab.tsv"
        private const val TOKENS_ASSET = "sherpa-kws/tokens.txt"
    }
}
