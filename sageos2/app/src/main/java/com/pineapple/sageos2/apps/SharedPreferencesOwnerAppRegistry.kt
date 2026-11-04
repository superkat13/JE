package com.pineapple.sageos2.apps

import android.content.Context
import com.pineapple.sageos2.persistence.ContinuityDecode
import com.pineapple.sageos2.persistence.ContinuityRead
import com.pineapple.sageos2.persistence.ContinuityStoreHealth
import com.pineapple.sageos2.persistence.ContinuityStoreSafety
import org.json.JSONArray
import org.json.JSONObject

class SharedPreferencesOwnerAppRegistry(context: Context) : OwnerAppProvider {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun snapshot(): OwnerAppSnapshot = readState().value

    /** Whether the stored value is missing, empty, readable, or damaged. Never mutates. */
    fun health(): ContinuityStoreHealth = readState().health

    /** Raw stored text for private recovery inspection; null when missing or wrongly typed. */
    fun rawStateTextOrNull(): String? = ContinuityStoreSafety.readRaw(prefs, KEY).text

    @Synchronized
    fun upsert(record: OwnerAppRecord): OwnerAppSnapshot {
        val read = readState()
        val current = read.value
        val apps = current.apps.filterNot { it.packageName == record.packageName } + record
        val next = OwnerAppSnapshot(current.revision + 1, apps)
        save(next, read.health)
        return next
    }

    @Synchronized
    fun remove(packageName: String): OwnerAppSnapshot {
        val read = readState()
        val current = read.value
        val next = OwnerAppSnapshot(current.revision + 1, current.apps.filterNot { it.packageName == packageName })
        save(next, read.health)
        return next
    }

    fun exportJson(): String = encode(snapshot())

    private fun readState(): ContinuityRead<OwnerAppSnapshot> = ContinuityStoreSafety.read(
        prefs,
        KEY,
        OwnerAppSnapshot(0L, emptyList()),
        ::decode
    )

    private fun save(snapshot: OwnerAppSnapshot, health: ContinuityStoreHealth) {
        ContinuityStoreSafety.save(prefs, KEY, encode(snapshot), health, "Owner Apps")
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
                    put("startupProcedure", app.startupProcedure)
                })
            }
        })
    }.toString()

    private fun decode(raw: String): ContinuityDecode<OwnerAppSnapshot> {
        val root = JSONObject(raw)
        val array = root.optJSONArray("apps")
            ?: throw IllegalArgumentException("apps array is missing or wrongly typed")
        var unreadable = 0
        val apps = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                if (item == null) {
                    unreadable++
                    continue
                }
                val packageName = item.optString("packageName").trim()
                val displayName = item.optString("displayName").trim()
                if (packageName.isEmpty() || displayName.isEmpty()) {
                    unreadable++
                    continue
                }
                val aliasArray = item.optJSONArray("aliases") ?: JSONArray()
                val aliases = buildList {
                    for (j in 0 until aliasArray.length()) aliasArray.optString(j).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
                add(
                    OwnerAppRecord(
                        packageName = packageName,
                        displayName = displayName,
                        aliases = aliases,
                        purpose = item.optString("purpose"),
                        enabled = item.optBoolean("enabled", true),
                        startupProcedure = item.optString("startupProcedure")
                    )
                )
            }
        }
        return ContinuityDecode(
            value = OwnerAppSnapshot(root.optLong("revision", 0L), apps),
            unreadableRecords = unreadable,
            empty = apps.isEmpty()
        )
    }

    companion object {
        private const val PREFS = "sage_owner_apps_v2"
        private const val KEY = "state"
    }
}
