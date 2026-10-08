package com.pineapple.sageos2.speech.voicerepair

import android.content.Context
import com.pineapple.sageos2.speech.CommandRecognizerBackend
import org.json.JSONArray
import org.json.JSONObject

/** Private app data; stores only explicitly supplied diagnostic text, never ordinary conversation. */
class SharedPreferencesVoiceRepairStore(context: Context) : VoiceRepairStore {
    private val prefs = context.getSharedPreferences("sage_voice_repair_v1", Context.MODE_PRIVATE)

    override fun load(): List<VoiceRepairSession> = runCatching {
        val rows = JSONArray(prefs.getString("sessions", "[]"))
        (0 until rows.length()).takeLast(VoiceRepairSessionManager.HISTORY_LIMIT).mapNotNull { index ->
            runCatching { decode(rows.getJSONObject(index)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    override fun save(sessions: List<VoiceRepairSession>) {
        val rows = JSONArray()
        sessions.takeLast(VoiceRepairSessionManager.HISTORY_LIMIT).forEach { rows.put(encode(it)) }
        check(prefs.edit().putString("sessions", rows.toString()).commit()) { "Hearing test could not be saved" }
    }

    private fun encode(s: VoiceRepairSession) = JSONObject().apply {
        put("id", s.id); put("state", s.state.name); put("cause", s.cause.name)
        put("attemptCount", s.attemptCount); put("started", s.startedAtMs)
        put("ended", s.endedAtMs); put("deadline", s.deadlineMs); put("phrase", s.testPhrase)
        put("first", s.firstTest?.let(::encodeTest)); put("second", s.secondTest?.let(::encodeTest))
        put("action", s.repairAction.name); put("applied", s.repairAppliedAtMs)
        put("interrupted", s.interrupted); put("cancelled", s.cancelled)
        put("steps", JSONArray().apply { s.steps.takeLast(30).forEach { step ->
            put(JSONObject().put("name", step.name).put("time", step.timestampMs).put("detail", step.detail))
        } })
    }

    private fun encodeTest(t: VoiceRepairTestResult) = JSONObject().apply {
        put("expected", t.expected); put("recognized", t.recognized); put("error", t.errorCode)
        put("backend", t.backend.name); put("elapsed", t.elapsedMs); put("nonempty", t.nonEmpty)
    }

    private fun JSONObject.nullLong(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun decodeTest(j: JSONObject) = VoiceRepairTestResult(j.getString("expected"),
        if (j.isNull("recognized")) null else j.getString("recognized"),
        if (j.isNull("error")) null else j.getInt("error"),
        CommandRecognizerBackend.valueOf(j.getString("backend")), j.getLong("elapsed"), j.getBoolean("nonempty"))

    private fun decode(j: JSONObject): VoiceRepairSession {
        val steps = j.getJSONArray("steps")
        return VoiceRepairSession(id = j.getString("id"), state = VoiceRepairState.valueOf(j.getString("state")),
            cause = VoiceRepairCause.valueOf(j.getString("cause")), attemptCount = j.getInt("attemptCount"),
            startedAtMs = j.getLong("started"), endedAtMs = j.nullLong("ended"), deadlineMs = j.nullLong("deadline"),
            testPhrase = j.getString("phrase"), firstTest = j.optJSONObject("first")?.let(::decodeTest),
            secondTest = j.optJSONObject("second")?.let(::decodeTest),
            repairAction = VoiceRepairAction.valueOf(j.getString("action")), repairAppliedAtMs = j.nullLong("applied"),
            interrupted = j.getBoolean("interrupted"), cancelled = j.getBoolean("cancelled"),
            steps = (0 until steps.length()).map { steps.getJSONObject(it).let { step ->
                VoiceRepairStep(step.getString("name"), step.getLong("time"), step.getString("detail"))
            } })
    }
}
