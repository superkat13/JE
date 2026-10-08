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
        prefs.edit().putString(KEY_CURRENT, encode(saved)).putString(historyKey(revision), encode(saved)).apply()
        return saved
    }

    fun revision(revision: Long): SageCoreSnapshot? = prefs.getString(historyKey(revision), null)?.let { runCatching { decode(it) }.getOrNull() }
    fun restore(revision: Long): SageCoreSnapshot? = revision(revision)?.let { replace(it.copy(revision = current().revision + 1)) }
    fun exportJson(): String = encode(current())

    private fun encode(core: SageCoreSnapshot) = JSONObject().apply {
        put("revision", core.revision)
        put("twinIdentity", core.twinIdentity)
        put("ownerModel", JSONObject().apply {
            put("preferredNames", JSONArray(core.ownerModel.preferredNames))
            put("vocabulary", JSONObject(core.ownerModel.vocabulary))
            put("workingStyle", JSONArray(core.ownerModel.workingStyle))
            put("preferences", JSONArray(core.ownerModel.preferences))
            put("habits", JSONArray(core.ownerModel.habits))
            put("trustedTools", JSONArray(core.ownerModel.trustedTools))
            put("recurringChoices", JSONArray(core.ownerModel.recurringChoices))
            put("learnedFacts", JSONObject(core.ownerModel.learnedFacts))
        })
        put("sageSelfModel", JSONObject().apply {
            put("identity", core.sageSelfModel.identity)
            put("capabilities", JSONArray(core.sageSelfModel.capabilities))
            put("limitations", JSONArray(core.sageSelfModel.limitations))
            put("experiences", JSONArray(core.sageSelfModel.experiences))
        })
        put("sharedContinuity", JSONObject().apply {
            put("activeProjects", JSONObject(core.sharedContinuity.activeProjects))
            put("durableDecisions", JSONArray(core.sharedContinuity.durableDecisions))
            put("activeTasks", JSONArray(core.sharedContinuity.activeTasks))
            put("lessonsLearned", JSONArray(core.sharedContinuity.lessonsLearned))
        })
        put("principles", JSONArray(core.principles))
        put("preferences", JSONArray(core.preferences))
        put("selfRestrictions", JSONArray(core.selfRestrictions))
        put("notes", core.notes)
    }.toString()

    private fun decode(raw: String): SageCoreSnapshot {
        val obj = JSONObject(raw)
        val owner = obj.optJSONObject("ownerModel") ?: JSONObject()
        val self = obj.optJSONObject("sageSelfModel") ?: JSONObject()
        val continuity = obj.optJSONObject("sharedContinuity") ?: JSONObject()
        return SageCoreSnapshot(
            revision = obj.optLong("revision", 0L),
            twinIdentity = obj.optString("twinIdentity", EmptySageCoreProvider.current().twinIdentity),
            ownerModel = OwnerModel(
                preferredNames = owner.optJSONArray("preferredNames").strings(),
                vocabulary = owner.optJSONObject("vocabulary").stringMap(),
                workingStyle = owner.optJSONArray("workingStyle").strings(),
                preferences = owner.optJSONArray("preferences").strings(),
                habits = owner.optJSONArray("habits").strings(),
                trustedTools = owner.optJSONArray("trustedTools").strings(),
                recurringChoices = owner.optJSONArray("recurringChoices").strings(),
                learnedFacts = owner.optJSONObject("learnedFacts").stringMap()
            ),
            sageSelfModel = SageSelfModel(
                identity = self.optString("identity", "Sage"),
                capabilities = self.optJSONArray("capabilities").strings(),
                limitations = self.optJSONArray("limitations").strings(),
                experiences = self.optJSONArray("experiences").strings()
            ),
            sharedContinuity = SharedContinuity(
                activeProjects = continuity.optJSONObject("activeProjects").stringMap(),
                durableDecisions = continuity.optJSONArray("durableDecisions").strings(),
                activeTasks = continuity.optJSONArray("activeTasks").strings(),
                lessonsLearned = continuity.optJSONArray("lessonsLearned").strings()
            ),
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

    private fun JSONObject?.stringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            val keys = keys()
            while (keys.hasNext()) {
                val key = keys.next()
                optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
            }
        }
    }

    private fun historyKey(revision: Long) = "revision_$revision"

    companion object {
        private const val PREFS = "sage_core_v2"
        private const val KEY_CURRENT = "current"
    }
}
