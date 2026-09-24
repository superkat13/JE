package com.pineapple.sageos2.action

sealed interface SemanticSelection {
    data class Match(val index: Int) : SemanticSelection
    data class Ambiguous(val count: Int) : SemanticSelection
    data object None : SemanticSelection
}

object SemanticTargetSelector {
    fun choose(labels: List<String>, query: String): SemanticSelection {
        val wanted = normalize(query)
        if (wanted.isEmpty()) return SemanticSelection.None
        val normalized = labels.map(::normalize)
        val exact = normalized.indices.filter { normalized[it] == wanted }
        if (exact.size == 1) return SemanticSelection.Match(exact.single())
        if (exact.size > 1) return SemanticSelection.Ambiguous(exact.size)

        val fuzzy = normalized.indices.filter { candidate ->
            val value = normalized[candidate]
            value.isNotEmpty() && (value.startsWith(wanted) || value.contains(wanted))
        }
        return when (fuzzy.size) {
            0 -> SemanticSelection.None
            1 -> SemanticSelection.Match(fuzzy.single())
            else -> SemanticSelection.Ambiguous(fuzzy.size)
        }
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
