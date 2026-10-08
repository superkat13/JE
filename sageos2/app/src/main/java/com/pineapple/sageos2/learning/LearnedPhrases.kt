package com.pineapple.sageos2.learning

data class LearnedPhrase(
    val phrase: String,
    val meaning: String
)

interface LearnedPhraseStore {
    fun list(): List<LearnedPhrase>
    fun meaningFor(phrase: String): String?
    fun save(phrase: String, meaning: String): Boolean
    fun remove(phrase: String): Boolean
}

object EmptyLearnedPhraseStore : LearnedPhraseStore {
    override fun list() = emptyList<LearnedPhrase>()
    override fun meaningFor(phrase: String) = null
    override fun save(phrase: String, meaning: String) = false
    override fun remove(phrase: String) = false
}
