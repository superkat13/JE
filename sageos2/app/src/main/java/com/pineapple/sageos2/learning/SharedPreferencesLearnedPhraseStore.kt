package com.pineapple.sageos2.learning

import android.content.Context
import java.util.Locale

/**
 * Keeps using Sage 1.33.3's durable `sage_state/phrase_aliases` format in place.
 * Existing lessons therefore work immediately after an update, remain downgrade-readable,
 * and are never copied, renamed, or deleted by a migration marker.
 */
class SharedPreferencesLearnedPhraseStore(context: Context) : LearnedPhraseStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun list(): List<LearnedPhrase> = prefs.getStringSet(KEY, emptySet()).orEmpty()
        .mapNotNull(::decode)
        .distinctBy { normalize(it.phrase) }
        .sortedBy { it.phrase.lowercase(Locale.US) }

    override fun meaningFor(phrase: String): String? {
        val key = normalize(phrase)
        return list().firstOrNull { normalize(it.phrase) == key }?.meaning
    }

    @Synchronized
    override fun save(phrase: String, meaning: String): Boolean {
        val key = normalize(phrase)
        val cleanMeaning = clean(meaning)
        if (key.isEmpty() || cleanMeaning.isEmpty()) return false
        val next = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        next.removeAll { decode(it)?.let { item -> normalize(item.phrase) == key } == true }
        next += "$key\t$cleanMeaning"
        return prefs.edit().putStringSet(KEY, next).commit()
    }

    @Synchronized
    override fun remove(phrase: String): Boolean {
        val key = normalize(phrase)
        if (key.isEmpty()) return false
        val next = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        val removed = next.removeAll { decode(it)?.let { item -> normalize(item.phrase) == key } == true }
        return removed && prefs.edit().putStringSet(KEY, next).commit()
    }

    companion object {
        private const val PREFS = "sage_state"
        private const val KEY = "phrase_aliases"

        fun decode(raw: String): LearnedPhrase? {
            val split = raw.indexOf('\t')
            if (split <= 0 || split >= raw.length - 1) return null
            val phrase = normalize(raw.substring(0, split))
            val meaning = clean(raw.substring(split + 1))
            if (phrase.isEmpty() || meaning.isEmpty()) return null
            return LearnedPhrase(phrase, meaning)
        }

        fun normalize(value: String): String = clean(value).lowercase(Locale.US)
            .replace(Regex("[^a-z0-9']+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun clean(value: String): String = value
            .replace('\t', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
