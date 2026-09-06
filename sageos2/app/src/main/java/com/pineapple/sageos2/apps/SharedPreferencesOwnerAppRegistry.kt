package com.pineapple.sageos2.apps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SharedPreferencesOwnerAppRegistry(context: Context) : OwnerAppProvider {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun snapshot(): OwnerAppSnapshot {
        val raw = prefs.getString(KEY, null) ?: return EmptyOwnerAppProvider.snapshot()
        return runCatching { decode(raw) }.getOrElse { EmptyOwnerAppProvider.snapshot() }
    }

    @Synchronized
    fun upsert(record: OwnerAppRecord): OwnerAppSnapshot {
        val current = snapshot()
        val apps = current.apps.filterNot { it.packageName == record.packageName } + record
        val next = OwnerAppSnapshot(current.revision + 1, apps)
        save(next)
        return next
    }

    @Synchronized
    fun remove(packageName: String): OwnerAppSnapshot {
        val current = snapshot()
        val next = OwnerAppSnapshot(current.revision + 1, current.apps.filterNot { it.packageName == packageName })
        save(next)
        return next
    }

    fun exportJson(): String = encode(snapshot())

    private fun save(snapshot: OwnerAppSnapshot) {
        prefs.edit().putString(KEY, encode(snapshot)).apply()
    }

    private fun encode(snapshot: OwnerAppSnapshot): String = JSONObject().apply {
        put("revision", snapshot.revision)
        put("apps", JSONArray().apply {
            snapshot.apps.forEach { app ->
                put(JSONObject().apply {
                    put("packageName", app.packageName)
                    put("displayName", app.displayName)
                    put("aliases", JSONArray(app.aliases))
                    put("purpose", app.purpose)
                    put("enabled", app.enabled)
                })
            }
        })
    }.toString()

    private fun decode(raw: String): OwnerAppSnapshot {
        val root = JSONObject(raw)
        val array = root.optJSONArray("apps") ?: JSONArray()
        val apps = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val packageName = item.optString("packageName").trim()
                val displayName = item.optString("displayName").trim()
                if (packageName.isEmpty() || displayName.isEmpty()) continue
                val aliasArray = item.optJSONArray("aliases") ?: JSONArray()
                val aliases = buildList {
                    for (j in 0 until aliasArray.length()) aliasArray.optString(j).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
                add(OwnerAppRecord(packageName, displayName, aliases, item.optString("purpose"), item.optBoolean("enabled", true)))
            }
        }
        return OwnerAppSnapshot(root.optLong("revision", 0L), apps)
    }

    companion object {
        private const val PREFS = "sage_owner_apps_v2"
        private const val KEY = "state"
    }
}
