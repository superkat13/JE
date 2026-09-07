package com.pineapple.sageos2.workflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ChickenTonightModule {
    LOCAL_DEVICE_POSTURE,
    NETWORK_CONTEXT_READONLY,
    SERVICE_METADATA_READONLY,
    EVIDENCE_REPORT
}

data class ChickenTonightScope(
    val scopeId: String,
    val authorizationReference: String,
    val targetSummary: String,
    val expiresAtMs: Long? = null,
    val allowedModules: Set<ChickenTonightModule> = setOf(ChickenTonightModule.EVIDENCE_REPORT),
    val notes: String = ""
) {
    fun isUsable(nowMs: Long = System.currentTimeMillis()): Boolean =
        scopeId.isNotBlank() &&
            authorizationReference.isNotBlank() &&
            targetSummary.isNotBlank() &&
            allowedModules.isNotEmpty() &&
            (expiresAtMs == null || expiresAtMs > nowMs)

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtMs != null && expiresAtMs <= nowMs
}

interface ChickenTonightScopeProvider {
    fun current(): ChickenTonightScope?
}

object EmptyChickenTonightScopeProvider : ChickenTonightScopeProvider {
    override fun current(): ChickenTonightScope? = null
}

class SharedPreferencesChickenTonightScopeStore(context: Context) : ChickenTonightScopeProvider {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun current(): ChickenTonightScope? = prefs.getString(KEY, null)?.let { raw ->
        runCatching { decode(raw) }.getOrNull()
    }

    fun save(scope: ChickenTonightScope) {
        require(scope.scopeId.isNotBlank())
        prefs.edit().putString(KEY, encode(scope)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    fun exportJson(): String? = current()?.let(::encode)

    private fun encode(scope: ChickenTonightScope): String = JSONObject().apply {
        put("scopeId", scope.scopeId)
        put("authorizationReference", scope.authorizationReference)
        put("targetSummary", scope.targetSummary)
        scope.expiresAtMs?.let { put("expiresAtMs", it) }
        put("allowedModules", JSONArray(scope.allowedModules.map { it.name }))
        put("notes", scope.notes)
    }.toString()

    private fun decode(raw: String): ChickenTonightScope {
        val obj = JSONObject(raw)
        val modules = buildSet {
            val array = obj.optJSONArray("allowedModules") ?: JSONArray()
            for (i in 0 until array.length()) {
                runCatching { ChickenTonightModule.valueOf(array.optString(i)) }.getOrNull()?.let(::add)
            }
        }
        return ChickenTonightScope(
            scopeId = obj.optString("scopeId").trim(),
            authorizationReference = obj.optString("authorizationReference").trim(),
            targetSummary = obj.optString("targetSummary").trim(),
            expiresAtMs = if (obj.has("expiresAtMs")) obj.optLong("expiresAtMs") else null,
            allowedModules = modules,
            notes = obj.optString("notes", "")
        )
    }

    companion object {
        private const val PREFS = "sage_chicken_tonight_scope_v2"
        private const val KEY = "scope"

        val NON_NEGOTIABLE_EXCLUSIONS = listOf(
            "credential collection",
            "credential guessing or brute force",
            "exploit delivery",
            "persistence",
            "destructive changes",
            "identity or account takeover"
        )
    }
}
