package com.pineapple.sageos2.apps

data class OwnerAppRecord(
    val packageName: String,
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val purpose: String = "",
    val enabled: Boolean = true,
    val startupProcedure: String = ""
) {
    init {
        require(packageName.isNotBlank())
        require(displayName.isNotBlank())
    }
}

data class OwnerAppSnapshot(
    val revision: Long,
    val apps: List<OwnerAppRecord>
)

interface OwnerAppProvider {
    fun snapshot(): OwnerAppSnapshot
}

object EmptyOwnerAppProvider : OwnerAppProvider {
    override fun snapshot() = OwnerAppSnapshot(0L, emptyList())
}

class OwnerAppResolver {
    fun resolve(query: String, snapshot: OwnerAppSnapshot): OwnerAppRecord? {
        val needle = normalize(query)
        if (needle.isEmpty()) return null
        return snapshot.apps
            .filter { it.enabled }
            .map { app -> app to score(needle, app) }
            .filter { it.second < NO_MATCH }
            .minWithOrNull(compareBy<Pair<OwnerAppRecord, Int>> { it.second }.thenBy { it.first.displayName.length })
            ?.first
    }

    private fun score(needle: String, app: OwnerAppRecord): Int {
        val names = buildList {
            add(app.displayName)
            add(app.packageName)
            addAll(app.aliases)
        }.map(::normalize)

        return when {
            names.any { it == needle } -> 0
            names.any { it.startsWith(needle) || needle.startsWith(it) } -> 1
            names.any { it.contains(needle) || needle.contains(it) } -> 2
            else -> NO_MATCH
        }
    }

    private fun normalize(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    companion object { private const val NO_MATCH = 1000 }
}
