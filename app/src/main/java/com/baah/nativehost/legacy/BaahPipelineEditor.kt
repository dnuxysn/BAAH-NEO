// Adapted from BAAH-AOS (MaaFwApp-derived, AGPL-3.0); see third_party/baah-adapter.
package com.baah.nativehost.legacy
import com.baah.nativehost.NeoText as Text

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** Mirrors the original BAAH pipeline editor and writes aligned task / enabled arrays. */
@Composable
internal fun BaahPipelineEditor(initial: JSONObject, choices: Map<String, String>, onChanged: (Any) -> Unit,
    modifier: Modifier = Modifier, beforeContent: LazyListScope.() -> Unit = {}, afterContent: LazyListScope.() -> Unit = {}) {
    var data by remember { mutableStateOf(JSONObject(initial.toString())) }
    var picker by remember { mutableStateOf<Int?>(null) }
    var removePipeline by remember { mutableStateOf(false) }
    fun edit(block: (JSONObject) -> Unit) {
        val copy = JSONObject(data.toString())
        block(copy)
        data = copy
        onChanged(copy)
    }
    val pipelines = data.optJSONArray("ALL_PIPELINES") ?: JSONArray()
    val selected = data.optInt("ACTIVATE_IND").coerceIn(0, maxOf(0, pipelines.length() - 1))
    val pipeline = pipelines.optJSONObject(selected)
    val tasks = pipeline?.optJSONArray("TASK_PIPELINE") ?: JSONArray()
    val enabled = pipeline?.optJSONArray("TASK_ONOFF") ?: JSONArray()
    fun changeTasks(block: (JSONObject, JSONArray, JSONArray) -> Unit) = edit { root ->
        val value = root.getJSONArray("ALL_PIPELINES").getJSONObject(selected)
        val names = value.optJSONArray("TASK_PIPELINE") ?: JSONArray()
        val switches = JSONArray().apply { repeat(names.length()) { put(value.optJSONArray("TASK_ONOFF")?.optBoolean(it, true) ?: true) } }
        value.put("TASK_PIPELINE", names).put("TASK_ONOFF", switches)
        block(value, names, switches)
    }
    fun insertTask(at: Int) = changeTasks { value, names, switches ->
        val next = JSONArray(); val flags = JSONArray()
        for (i in 0..names.length()) {
            if (i == at) { next.put(choices.keys.firstOrNull { it == "邮件" } ?: choices.keys.firstOrNull() ?: "登录游戏"); flags.put(true) }
            if (i < names.length()) { next.put(names.get(i)); flags.put(switches.optBoolean(i, true)) }
        }
        value.put("TASK_PIPELINE", next).put("TASK_ONOFF", flags)
    }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        beforeContent()
        item(key = "pipeline-controls") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PipelineButton("全选", Modifier.weight(1f), enabled = pipeline != null) { changeTasks { _, names, flags -> repeat(names.length()) { flags.put(it, true) } } }
            PipelineButton("全不选", Modifier.weight(1f), enabled = pipeline != null) { changeTasks { _, names, flags -> repeat(names.length()) { flags.put(it, false) } } }
        }
        Text("你可以设置多种流程，但是只能手动切换激活其中一个", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val chipWidth = ((maxWidth - 6.dp * (pipelines.length() - 1).coerceAtLeast(0)) / pipelines.length().coerceAtLeast(1)).coerceAtLeast(80.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(pipelines.length()) { index ->
                    Surface(Modifier.width(chipWidth).height(40.dp).clickable { edit { it.put("ACTIVATE_IND", index) } },
                        shape = RoundedCornerShape(10.dp), color = if (index == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        border = if (index == selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                            Text("流程 ${index + 1}", fontSize = 14.sp, color = if (index == selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            }
            PipelineButton("+", Modifier.width(40.dp)) { edit { root ->
                val all = root.optJSONArray("ALL_PIPELINES") ?: JSONArray()
                all.put(pipeline?.let { JSONObject(it.toString()) } ?: JSONObject().put("TASK_PIPELINE", JSONArray().put(choices.keys.firstOrNull() ?: "登录游戏")).put("TASK_ONOFF", JSONArray().put(true)))
                root.put("ALL_PIPELINES", all).put("ACTIVATE_IND", all.length() - 1)
            } }
            if (pipelines.length() > 1) PipelineButton("删除", Modifier.width(48.dp), destructive = true) { removePipeline = true }
        }
        }
        }
        items(tasks.length(), key = { "task:$selected:$it" }, contentType = { "task" }) { index ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outline), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BaahSwitch(enabled.optBoolean(index, true), { checked -> changeTasks { _, _, flags -> flags.put(index, checked) } })
                        Text("≡", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("任务 ${index + 1}: ", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(tasks.optString(index), Modifier.weight(1f).clickable { picker = index }, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PipelineButton("+", Modifier.weight(1f)) { insertTask(index + 1) }
                        PipelineButton("↑", Modifier.weight(1f), enabled = index > 0) { changeTasks { _, names, flags ->
                            val prev = names.get(index - 1); names.put(index - 1, names.get(index)); names.put(index, prev)
                            val flag = flags.get(index - 1); flags.put(index - 1, flags.get(index)); flags.put(index, flag)
                        } }
                        PipelineButton("↓", Modifier.weight(1f), enabled = index + 1 < tasks.length()) { changeTasks { _, names, flags ->
                            val next = names.get(index + 1); names.put(index + 1, names.get(index)); names.put(index, next)
                            val flag = flags.get(index + 1); flags.put(index + 1, flags.get(index)); flags.put(index, flag)
                        } }
                        PipelineButton("删除", Modifier.weight(1f), destructive = true) { changeTasks { _, names, flags -> names.remove(index); flags.remove(index) } }
                    }
                }
            }
        }
        if (pipeline != null && tasks.length() == 0) item(key = "empty-pipeline") { PipelineButton("+", Modifier.fillMaxWidth()) { insertTask(0) } }
        afterContent()
    }
    picker?.let { index -> BaahDialog(onDismissRequest = { picker = null }, title = { Text("选择任务") }, text = {
        Column { choices.forEach { (key, title) -> Text(title, Modifier.fillMaxWidth().clickable {
            changeTasks { _, names, _ -> names.put(index, key) }; picker = null
        }.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.primary) } }
    }, dismissButton = { TextButton({ picker = null }) { Text("取消") } }) }
    if (removePipeline) BaahDialog(onDismissRequest = { removePipeline = false }, title = { Text("删除流程") }, text = { Text("删除流程 ${selected + 1} 及其中的全部任务？") },
        confirmButton = { TextButton({ edit { root -> root.getJSONArray("ALL_PIPELINES").remove(selected); root.put("ACTIVATE_IND", 0) }; removePipeline = false }) { Text("删除") } },
        dismissButton = { TextButton({ removePipeline = false }) { Text("取消") } })
}

@Composable
private fun PipelineButton(label: String, modifier: Modifier, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.height(40.dp).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        shape = RoundedCornerShape(10.dp), color = colors.surface,
        border = BorderStroke(1.dp, if (enabled) colors.outline else colors.onSurface.copy(alpha = .12f))) {
        Box(Modifier.padding(2.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, color = if (!enabled) colors.onSurface.copy(alpha = .38f)
                else if (destructive) Color(0xFFD32F2F) else colors.primary)
        }
    }
}
