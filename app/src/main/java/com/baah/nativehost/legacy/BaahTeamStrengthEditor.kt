// Adapted from BAAH-AOS (MaaFwApp-derived, AGPL-3.0); see third_party/baah-adapter.
package com.baah.nativehost.legacy
import com.baah.nativehost.NeoText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baah.nativehost.legacy.BaahConfigField
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun BaahTeamStrengthEditor(initial: JSONArray, drafts: MutableMap<String, String>,
    onInvalid: (Boolean) -> Unit, onChanged: (Any) -> Unit) {
    var teams by remember { mutableStateOf(JSONArray(initial.toString())) }
    val prefix = "TEAM_SET_STRENGTH:"
    val attributes = listOf("red" to "红", "blue" to "蓝", "yellow" to "黄", "purple" to "紫")
    val invalid = remember { mutableStateMapOf<String, Boolean>() }
    Text("队伍强度（攻击属性比例 0–10）", fontSize = 14.sp)
    for (index in 0 until teams.length()) {
        Text("队伍 ${index + 1}", fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        attributes.forEach { (key, label) ->
            Column(Modifier.weight(1f)) {
            val draftKey = "$prefix$index:$key"
            BaahNumberEditor(BaahConfigField(draftKey, label, "ui.number", null,
                JSONObject().put("min", 0).put("max", 10).put("precision", 0)),
                teams.getJSONObject(index).optInt(key), drafts, { invalid[draftKey] = it; onInvalid(invalid.values.any { it }) }, compact = true) { number ->
                val copy = JSONArray(teams.toString())
                copy.getJSONObject(index).put(key, number)
                teams = copy
                onChanged(copy)
            }
            }
        }
        }
    }
}
