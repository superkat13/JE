package com.pineapple.sageos2.identity

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SharedPreferencesSageCoreStore(context: Context) : SageCoreProvider {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun current(): SageCoreSnapshot {
        val raw = prefs.getString(KEY_CURRENT, null) ?: return EmptySageCoreProvider.current()
        return runCatching { decode(raw) }.getOrElse { EmptySageCoreProvider.current() }
    }

    fun replace(next: SageCoreSnapshot): SageCoreSnapshot {
        val current = current()
        val revision = maxOf(current.revision + 1, next.revision)
        val saved = next.copy(revision = revision)
        prefs.edit()
            .putString(KEY_CURRENT, encode(saved))
            .putString(historyKey(revision), encode(saved))
            .apply()
        return saved
    }

    fun revision(revision: Long): SageCoreSnapshot? =
        prefs.getString(historyKey(revision), null)?.let { runCatching { decode(it) }.getOrNull() }

    fun restore(revision: Long): SageCoreSnapshot? = revision(revision)?.let { replace(it.copy(revision = current().revision + 1)) }

    fun exportJson(): String = encode(current())

    private fun encode(core: SageCoreSnapshot) = JSONObject().apply {
        put("revision", core.revision)
        put("identity", core.identity)
        put("principles", JSONArray(core.principles))
        put("preferences", JSONArray(core.preferences))
        put("selfRestrictions", JSONArray(core.selfRestrictions))
        put("notes", core.notes)
    }.toString()

    private fun decode(raw: String): SageCoreSnapshot {
        val obj = JSONObject(raw)
        return SageCoreSnapshot(
            revision = obj.optLong("revision", 0L),
            identity = obj.optString("identity", ""),
            principles = obj.optJSONArray("principles").strings(),
            preferences = obj.optJSONArray("preferences").strings(),
            selfRestrictions = obj.optJSONArray("selfRestrictions").strings(),
            notes = obj.optString("notes", "")
        )
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return buildList { for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let(::add) }
    }

    private fun historyKey(revision: Long) = "revision_$revision"

    companion object {
        private const val PREFS = "sage_core_v2"
        private const val KEY_CURRENT = "current"
    }
}
