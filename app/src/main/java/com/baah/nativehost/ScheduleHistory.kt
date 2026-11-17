package com.baah.nativehost

import org.json.JSONArray
import org.json.JSONObject

// Trigger time describes delivery, so it must not be presented as a successful completion.
internal fun lastSuccessfulRunAt(rule: JSONObject, history: JSONArray?): Long? {
    val recorded = rule.optLong("lastSuccessfulAt", 0)
    val finished = if (rule.optString("lastResult") in setOf("完成", "SUCCEEDED")) {
        rule.optLong("lastFinishedAt", rule.optLong("lastRunFinishedAt", 0))
    } else 0L
    val fromHistory = (0 until (history?.length() ?: 0)).asSequence()
        .mapNotNull { history!!.optJSONObject(it) }
        .filter { it.optString("id") == rule.optString("id") && it.optString("result") in setOf("完成", "SUCCEEDED") }
        .map { it.optLong("at", 0) }
        .maxOrNull() ?: 0L
    return maxOf(recorded, finished, fromHistory).takeIf { it > 0 }
}
