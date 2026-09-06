package com.pineapple.sageos2.continuity

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SharedPreferencesTaskContinuityStore(context: Context) : TaskContinuityStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized override fun upsert(checkpoint: TaskCheckpoint) {
        val all = readAll().associateBy { it.taskId }.toMutableMap()
        all[checkpoint.taskId] = checkpoint
        writeAll(all.values.sortedBy { it.updatedAtMs })
    }

    override fun get(taskId: String): TaskCheckpoint? = readAll().firstOrNull { it.taskId == taskId }
    override fun active(): List<TaskCheckpoint> = readAll().filter { it.state == TaskState.ACTIVE || it.state == TaskState.WAITING }.sortedByDescending { it.updatedAtMs }
    override fun recent(limit: Int): List<TaskCheckpoint> = readAll().sortedByDescending { it.updatedAtMs }.take(limit.coerceAtLeast(0))

    @Synchronized override fun remove(taskId: String) {
        writeAll(readAll().filterNot { it.taskId == taskId })
    }

    private fun readAll(): List<TaskCheckpoint> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList { for (i in 0 until array.length()) decode(array.getJSONObject(i))?.let(::add) }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(tasks: Collection<TaskCheckpoint>) {
        prefs.edit().putString(KEY_TASKS, JSONArray(tasks.map(::encode)).toString()).apply()
    }

    private fun encode(task: TaskCheckpoint) = JSONObject().apply {
        put("taskId", task.taskId); put("title", task.title); put("state", task.state.name)
        put("summary", task.summary); put("nextStep", task.nextStep); put("updatedAtMs", task.updatedAtMs)
        put("metadata", JSONObject(task.metadata))
    }

    private fun decode(obj: JSONObject): TaskCheckpoint? = runCatching {
        val metaObj = obj.optJSONObject("metadata") ?: JSONObject()
        val metadata = buildMap {
            val keys = metaObj.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, metaObj.optString(key)) }
        }
        TaskCheckpoint(
            taskId = obj.getString("taskId"),
            title = obj.optString("title"),
            state = TaskState.valueOf(obj.optString("state", TaskState.ACTIVE.name)),
            summary = obj.optString("summary"),
            nextStep = obj.optString("nextStep"),
            updatedAtMs = obj.optLong("updatedAtMs"),
            metadata = metadata
        )
    }.getOrNull()

    companion object { private const val PREFS = "sageos2_tasks"; private const val KEY_TASKS = "tasks" }
}
