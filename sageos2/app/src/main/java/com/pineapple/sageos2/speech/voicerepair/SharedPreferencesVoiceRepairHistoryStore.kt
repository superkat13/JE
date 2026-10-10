package com.pineapple.sageos2.speech.voicerepair

import android.content.Context
import com.pineapple.sageos2.speech.CommandRecognizerBackend
import org.json.JSONArray
import org.json.JSONObject

/** SharedPreferences-backed voice repair history with a bounded terminal tail and one in-flight marker. */
class SharedPreferencesVoiceRepairHistoryStore(
    context: Context,
    private val capacity: Int = DEFAULT_HISTORY_CAPACITY
) : VoiceRepairHistoryStore {
    init { require(capacity > 0) { "history capacity must be positive" } }
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized override fun observe(session: VoiceRepairSession) {
        if (session.state in TERMINAL) {
            val kept = (history() + session).filterNot { it.id == session.id }
            writeHistory(kept.takeLast(capacity))
            prefs.edit().remove(KEY_IN_FLIGHT).apply()
        } else {
            writeInFlight(session)
        }
    }

    @Synchronized override fun latestTerminal(): VoiceRepairSession? =
        readSessions(KEY_HISTORY).lastOrNull()

    @Synchronized override fun interruptedSession(): VoiceRepairSession? =
        readSingle(KEY_IN_FLIGHT)

    @Synchronized override fun history(): List<VoiceRepairSession> =
        readSessions(KEY_HISTORY)

    @Synchronized override fun clear() {
        prefs.edit().remove(KEY_HISTORY).remove(KEY_IN_FLIGHT).apply()
    }

    private fun writeHistory(sessions: List<VoiceRepairSession>) {
        prefs.edit().putString(KEY_HISTORY, JSONArray(sessions.map(::encode)).toString()).apply()
    }

    private fun writeInFlight(session: VoiceRepairSession) {
        prefs.edit().putString(KEY_IN_FLIGHT, encode(session).toString()).apply()
    }

    private fun readSessions(key: String): List<VoiceRepairSession> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList { for (i in 0 until array.length()) decode(array.getJSONObject(i))?.let(::add) }
        }.getOrDefault(emptyList())
    }

    private fun readSingle(key: String): VoiceRepairSession? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    private fun encode(session: VoiceRepairSession) = JSONObject().apply {
        put("id", session.id)
        put("state", session.state.name)
        put("cause", session.cause.name)
        put("attemptCount", session.attemptCount)
        put("maxAttempts", session.maxAttempts)
        put("startedAtMs", session.startedAtMs)
        session.endedAtMs?.let { put("endedAtMs", it) }
        session.deadlineMs?.let { put("deadlineMs", it) }
        put("testPhrase", session.testPhrase)
        put("repairAction", session.repairAction.name)
        session.repairAppliedAtMs?.let { put("repairAppliedAtMs", it) }
        put("steps", JSONArray(session.steps.map { step ->
            JSONObject().put("name", step.name).put("timestampMs", step.timestampMs)
                .put("detail", step.detail)
        }))
        put("history", JSONArray(session.history))
        put("interrupted", session.interrupted)
        put("cancelled", session.cancelled)
        session.firstTest?.let { put("firstTest", encodeTest(it)) }
        session.secondTest?.let { put("secondTest", encodeTest(it)) }
    }

    private fun encodeTest(result: VoiceRepairTestResult) = JSONObject().apply {
        put("expected", result.expected)
        result.recognized?.let { put("recognized", it) }
        result.errorCode?.let { put("errorCode", it) }
        put("backend", result.backend.name)
        put("elapsedMs", result.elapsedMs)
        put("nonEmpty", result.nonEmpty)
    }

    private fun decode(obj: JSONObject): VoiceRepairSession? = runCatching {
        val steps = decodeSteps(obj.optJSONArray("steps"))
        val history = decodeStrings(obj.optJSONArray("history"))
        VoiceRepairSession(
            id = obj.getString("id"),
            state = VoiceRepairState.valueOf(obj.optString("state", VoiceRepairState.IDLE.name)),
            cause = VoiceRepairCause.valueOf(obj.optString("cause", VoiceRepairCause.NONE.name)),
            attemptCount = obj.optInt("attemptCount"),
            maxAttempts = obj.optInt("maxAttempts", VoiceRepairPolicy.MAX_ATTEMPTS),
            startedAtMs = obj.optLong("startedAtMs"),
            endedAtMs = if (obj.has("endedAtMs")) obj.optLong("endedAtMs") else null,
            deadlineMs = if (obj.has("deadlineMs")) obj.optLong("deadlineMs") else null,
            testPhrase = obj.optString("testPhrase"),
            firstTest = if (obj.has("firstTest")) decodeTest(obj.getJSONObject("firstTest")) else null,
            repairAction = VoiceRepairAction.valueOf(obj.optString("repairAction", VoiceRepairAction.NONE.name)),
            repairAppliedAtMs = if (obj.has("repairAppliedAtMs")) obj.optLong("repairAppliedAtMs") else null,
            secondTest = if (obj.has("secondTest")) decodeTest(obj.getJSONObject("secondTest")) else null,
            steps = steps,
            history = history,
            interrupted = obj.optBoolean("interrupted"),
            cancelled = obj.optBoolean("cancelled")
        )
    }.getOrNull()

    private fun decodeTest(obj: JSONObject): VoiceRepairTestResult? = runCatching {
        VoiceRepairTestResult(
            expected = obj.optString("expected"),
            recognized = if (obj.has("recognized")) obj.optString("recognized") else null,
            errorCode = if (obj.has("errorCode")) obj.optInt("errorCode") else null,
            backend = CommandRecognizerBackend.valueOf(
                obj.optString("backend", CommandRecognizerBackend.UNAVAILABLE.name)
            ),
            elapsedMs = obj.optLong("elapsedMs"),
            nonEmpty = obj.optBoolean("nonEmpty")
        )
    }.getOrNull()

    private fun decodeSteps(array: JSONArray?): List<VoiceRepairStep> =
        if (array == null) emptyList() else buildList {
            for (i in 0 until array.length()) {
                val step = array.optJSONObject(i) ?: continue
                add(VoiceRepairStep(
                    name = step.optString("name"),
                    timestampMs = step.optLong("timestampMs"),
                    detail = step.optString("detail")
                ))
            }
        }

    private fun decodeStrings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else buildList {
            for (i in 0 until array.length()) array.optString(i)?.let(::add)
        }

    companion object {
        const val DEFAULT_HISTORY_CAPACITY = 8
        private const val PREFS = "sageos2_voice_repair_history"
        private const val KEY_HISTORY = "history"
        private const val KEY_IN_FLIGHT = "in_flight"
        private val TERMINAL = setOf(
            VoiceRepairState.SUCCESS, VoiceRepairState.HEALTHY, VoiceRepairState.FAILED,
            VoiceRepairState.CANCELLED, VoiceRepairState.INTERRUPTED
        )
    }
}