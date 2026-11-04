package com.pineapple.sageos2.continuity

import android.content.Context
import com.pineapple.sageos2.persistence.ContinuityDecode
import com.pineapple.sageos2.persistence.ContinuityRead
import com.pineapple.sageos2.persistence.ContinuityStoreHealth
import com.pineapple.sageos2.persistence.ContinuityStoreSafety
import org.json.JSONArray
import org.json.JSONObject

class SharedPreferencesTaskContinuityStore(context: Context) : TaskContinuityStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized override fun upsert(checkpoint: TaskCheckpoint) {
        val read = readState()
        val all = read.value.associateBy { it.taskId }.toMutableMap()
        all[checkpoint.taskId] = checkpoint
        writeAll(all.values.sortedBy { it.updatedAtMs }, read.health)
    }

    override fun get(taskId: String): TaskCheckpoint? = readState().value.firstOrNull { it.taskId == taskId }
    override fun active(): List<TaskCheckpoint> = readState().value.filter { it.state == TaskState.ACTIVE || it.state == TaskState.WAITING }.sortedByDescending { it.updatedAtMs }
    override fun recent(limit: Int): List<TaskCheckpoint> = readState().value.sortedByDescending { it.updatedAtMs }.take(limit.coerceAtLeast(0))

    @Synchronized override fun remove(taskId: String) {
        val read = readState()
        writeAll(read.value.filterNot { it.taskId == taskId }, read.health)
    }

    /** Whether the stored value is missing, empty, readable, or damaged. Never mutates. */
    fun health(): ContinuityStoreHealth = readState().health

    /** Raw stored text for private recovery inspection; null when missing or wrongly typed. */
    fun rawStateTextOrNull(): String? = ContinuityStoreSafety.readRaw(prefs, KEY_TASKS).text

    private fun readState(): ContinuityRead<List<TaskCheckpoint>> =
        ContinuityStoreSafety.read(prefs, KEY_TASKS, emptyList(), ::decode)

    private fun writeAll(tasks: Collection<TaskCheckpoint>, health: ContinuityStoreHealth) {
        ContinuityStoreSafety.save(
            prefs,
            KEY_TASKS,
            JSONArray(tasks.map(::encode)).toString(),
            health,
            "Task continuity"
        )
    }

    private fun encode(task: TaskCheckpoint) = JSONObject().apply {
        put("taskId", task.taskId); put("title", task.title); put("state", task.state.name)
        put("summary", task.summary); put("nextStep", task.nextStep); put("updatedAtMs", task.updatedAtMs)
        put("metadata", JSONObject(task.metadata))
    }

    private fun decode(raw: String): ContinuityDecode<List<TaskCheckpoint>> {
        val array = JSONArray(raw)
        var unreadable = 0
        val tasks = buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i)
                if (obj == null) {
                    unreadable++
                    continue
                }
                val task = runCatching { decodeOne(obj) }.getOrNull()
                if (task == null) unreadable++ else add(task)
            }
        }
        return ContinuityDecode(tasks, unreadable, tasks.isEmpty())
    }

    private fun decodeOne(obj: JSONObject): TaskCheckpoint? {
        val metaObj = obj.optJSONObject("metadata") ?: JSONObject()
        val metadata = buildMap {
            val keys = metaObj.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, metaObj.optString(key)) }
        }
        val taskId = obj.getString("taskId").trim()
        if (taskId.isEmpty()) return null
        return TaskCheckpoint(
            taskId = taskId,
            title = obj.optString("title"),
            state = TaskState.valueOf(obj.optString("state", TaskState.ACTIVE.name)),
            summary = obj.optString("summary"),
            nextStep = obj.optString("nextStep"),
            updatedAtMs = obj.optLong("updatedAtMs"),
            metadata = metadata
        )
    }

    companion object { private const val PREFS = "sageos2_tasks"; private const val KEY_TASKS = "tasks" }
}
