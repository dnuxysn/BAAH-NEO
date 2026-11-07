package com.baah.nativehost.legacy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baah.nativehost.NeoText as Text
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Step types, action IDs and defaults come from the BAAH 2.5.8 Release.
@Composable
internal fun BaahLoginFlowEditor(value: JSONObject, onChanged: (JSONObject) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Button({ editing = true }, Modifier.fillMaxWidth()) { Text("编辑登录流程") }
    if (!editing) return

    val context = LocalContext.current
    val catalog = remember {
        context.assets.open("baah-aos/login-flow.json").bufferedReader().use { JSONObject(it.readText()) }
    }
    val steps = catalog.getJSONObject("steps")
    val actions = catalog.getJSONObject("actions")
    val conditions = catalog.getJSONObject("conditions")
    var flow by remember { mutableStateOf(JSONObject(value.toString())) }
    var json by remember { mutableStateOf<String?>(null) }
    var invalid by remember { mutableStateOf(false) }
    val rows = flow.optJSONArray("a_l") ?: JSONArray()
    fun update(rows: JSONArray) {
        flow = JSONObject(flow.toString()).put("a_l", rows)
    }
    fun step(type: String, id: String) = template(steps, type).put("id", id)

    BaahDialog(onDismissRequest = { editing = false }, title = { Text("编辑登录流程") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("登录界面出现时按顺序执行。坐标基于 1280 × 720 虚拟屏。", fontSize = 13.sp)
            if (json == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({
                        update(JSONArray(rows.toString()).put(step("do_action_f", UUID.randomUUID().toString())))
                    }) { Text("添加步骤") }
                    TextButton({ json = JSONObject(flow.toString()).put("a_l", rows).toString(2) }) { Text("流程 JSON") }
                }
                repeat(rows.length()) { index ->
                    val row = rows.optJSONObject(index) ?: return@repeat
                    key(index, row.optString("id")) {
                        BaahCard(Modifier.fillMaxWidth()) {
                            val type = row.optString("id_n")
                            val id = row.optString("id")
                            fun change(value: JSONObject) = update(JSONArray(rows.toString()).put(index, value))
                            Text("${index + 1} · ${com.baah.nativehost.UiStrings.text("步骤 ID")} $id", fontSize = 12.sp)
                            BaahDropdown(type, choices(steps), translateLabels = false) {
                                if (it != type) change(step(it, id.ifBlank { UUID.randomUUID().toString() }))
                            }
                            if (steps.has(type)) {
                                FlowParts(type, row.optJSONArray("i_f_o") ?: JSONArray(), actions, conditions) {
                                    change(JSONObject(row.toString()).put("i_f_o", it))
                                }
                            } else Text("未识别的上游步骤")
                            Row {
                                TextButton({
                                    update(JSONArray(rows.toString()).put(index, rows.get(index - 1)).put(index - 1, row))
                                }, enabled = index > 0) { Text("上移") }
                                TextButton({
                                    update(JSONArray(rows.toString()).put(index, rows.get(index + 1)).put(index + 1, row))
                                }, enabled = index < rows.length() - 1) { Text("下移") }
                                TextButton({ update(JSONArray(rows.toString()).apply { remove(index) }) }) { Text("删除") }
                            }
                        }
                    }
                }
            } else {
                OutlinedTextField(json.orEmpty(), { json = it; invalid = false }, Modifier.fillMaxWidth(),
                    label = { Text("流程 JSON") }, minLines = 6, isError = invalid,
                    supportingText = if (invalid) ({ Text("流程 JSON 格式无效") }) else null)
            }
        }
    }, confirmButton = {
        TextButton({
            val parsed = if (json == null) JSONObject(flow.toString()).put("a_l", rows) else runCatching {
                JSONObject(json.orEmpty()).also { obj ->
                    val list = obj.getJSONArray("a_l")
                    repeat(list.length()) { index ->
                        val row = list.getJSONObject(index)
                        require(row.getString("id_n").isNotBlank())
                        row.getJSONArray("i_f_o")
                    }
                }
            }.getOrNull()
            if (parsed == null) invalid = true
            else { onChanged(parsed); editing = false }
        }) { Text("确定") }
    }, dismissButton = { TextButton({ editing = false }) { Text("取消") } })
}

@Composable
private fun FlowParts(type: String, parts: JSONArray, actions: JSONObject, conditions: JSONObject,
    change: (JSONArray) -> Unit) {
    fun update(index: Int, value: JSONObject) = change(JSONArray(parts.toString()).put(index, value))
    when (type) {
        "ifelse_action_f" -> {
            FlowCondition(parts.optJSONObject(0), actions, conditions) { update(0, it) }
            FlowAction("满足条件时", parts.optJSONObject(1), actions) { update(1, it) }
            FlowAction("不满足条件时", parts.optJSONObject(2), actions) { update(2, it) }
        }
        "run_until_f" -> {
            FlowValue("最多执行次数", parts.optJSONObject(0)?.opt("p_v") ?: 5) {
                update(0, JSONObject().put("id_n", "maxtimes").put("p_v", it))
            }
            FlowAction("执行动作", parts.optJSONObject(1), actions) { update(1, it) }
            FlowCondition(parts.optJSONObject(2), actions, conditions) { update(2, it) }
        }
        "do_action_f" -> FlowAction("执行动作", parts.optJSONObject(0), actions) { update(0, it) }
    }
}

@Composable
private fun FlowAction(label: String, value: JSONObject?, actions: JSONObject, change: (JSONObject) -> Unit) {
    val id = value?.optString("id_n").orEmpty()
    Text(label, fontSize = 13.sp)
    BaahDropdown(id.ifBlank { com.baah.nativehost.UiStrings.text("选择") }, choices(actions), translateLabels = false) {
        if (it != id) change(template(actions, it))
    }
    if (value == null) return
    if (!actions.has(id)) {
        Text("未识别的上游动作", fontSize = 12.sp)
        return
    }
    key(id) { FlowParameters(value, "a_p", change) }
}

@Composable
private fun FlowCondition(value: JSONObject?, actions: JSONObject, conditions: JSONObject, change: (JSONObject) -> Unit) {
    val id = value?.optString("id_n").orEmpty()
    Text("判断条件", fontSize = 13.sp)
    BaahDropdown(id.ifBlank { com.baah.nativehost.UiStrings.text("选择") }, choices(conditions), translateLabels = false) {
        if (it != id) change(template(conditions, it))
    }
    if (value == null || !conditions.has(id)) return
    if (template(conditions, id).optJSONObject("c_obj") != null) {
        FlowAction("比较对象", value.optJSONObject("c_obj"), actions) {
            change(JSONObject(value.toString()).put("c_obj", it))
        }
    }
    key(id) { FlowParameters(value, "c_v", change) }
}

@Composable
private fun FlowParameters(value: JSONObject, name: String, change: (JSONObject) -> Unit) {
    val params = value.optJSONArray(name) ?: return
    repeat(params.length()) { index ->
        val param = params.optJSONObject(index) ?: return@repeat
        val input = param.opt("p_v")?.takeUnless { it == JSONObject.NULL } ?: return@repeat
        key(index, param.optString("id_n")) {
            FlowValue(param.optString("id_n"), input) {
                val item = JSONObject(param.toString()).put("p_v", it)
                change(JSONObject(value.toString()).put(name, JSONArray(params.toString()).put(index, item)))
            }
        }
    }
}

@Composable
private fun FlowValue(label: String, value: Any, change: (Any) -> Unit) {
    val field = BaahConfigField(label, label, if (value is String) "ui.textarea" else "value", null)
    ConfigField(field, if (value is Number) value.toDouble() else value, emptyMap(), mutableMapOf(), {}, change)
}

private fun template(group: JSONObject, type: String) = JSONObject(group.getJSONObject(type).getJSONObject("data").toString())

@Composable
private fun choices(group: JSONObject): List<Pair<String, String>> {
    val locale = if (projectLanguage() == "en") "en" else "zh"
    return remember(group, locale) {
        group.keys().asSequence().map { it to group.getJSONObject(it).optString(locale, it) }.toList()
    }
}
