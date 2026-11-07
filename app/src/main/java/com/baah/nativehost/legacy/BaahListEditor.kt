// Adapted from BAAH-AOS (MaaFwApp-derived, AGPL-3.0); see third_party/baah-adapter.
package com.baah.nativehost.legacy
import com.baah.nativehost.NeoText as Text

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baah.nativehost.legacy.BaahConfigField
import org.json.JSONArray
import org.json.JSONObject

/** The original grouped editor, including 34dp cells and 56dp row delete controls. */
@Composable
internal fun BaahListEditor(field: BaahConfigField, initial: JSONArray, drafts: MutableMap<String, String>, onInvalid: (Boolean) -> Unit, onChanged: (Any) -> Unit) {
    var outer by remember(field.key) { mutableStateOf(JSONArray(initial.toString())) }
    val meta = field.metadata!!
    val headers = meta.optJSONArray("headers")
    val groupLabel = headers?.optString(0)?.takeIf { it.isNotBlank() } ?: "组"
    val columns = meta.optJSONArray("last_dim") ?: JSONArray()
    val sample = (0 until outer.length()).mapNotNull { outer.optJSONArray(it) }.firstOrNull { it.length() > 0 }
    val threeLevel = if (sample != null) sample.opt(0) is JSONArray else meta.optInt("dim", 2) >= 3
    val hasSwitch = meta.optBoolean("has_switch")
    val prefix = "${field.key}:"
    val invalidCells = remember { mutableStateMapOf<String, Boolean>() }
    fun clearDrafts() { invalidCells.clear(); drafts.keys.filter { it.startsWith(prefix) }.forEach { drafts.remove(it) }; onInvalid(false) }
    fun edit(block: (JSONArray) -> Unit) { val copy = JSONArray(outer.toString()); block(copy); outer = copy; onChanged(copy) }
    Text(field.label, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    Spacer(Modifier.height(2.dp)) // BaahCard's 8dp gap supplies the rest of the legacy 10dp title margin.
    if (threeLevel && columns.length() > 0) Row(Modifier.fillMaxWidth()) {
        if (hasSwitch) Spacer(Modifier.width(48.dp))
        repeat(columns.length()) { Text(columns.optString(it), Modifier.weight(1f).padding(end = 4.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        Spacer(Modifier.width(52.dp))
    }
    Column {
        for (groupIndex in 0 until outer.length()) {
            val group = outer.optJSONArray(groupIndex) ?: JSONArray()
            Row(Modifier.fillMaxWidth().padding(top = if (groupIndex == 0) 4.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("第 ${groupIndex + 1} $groupLabel", Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({ edit { it.remove(groupIndex) }; clearDrafts() }, Modifier.height(38.dp), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD32F2F))) { Text("删除本$groupLabel", fontSize = 12.sp) }
            }
            if (group.length() == 0) Text("（空）", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (rowIndex in 0 until group.length()) {
                val node = group.opt(rowIndex)
                val row = node as? JSONArray
                val switched = hasSwitch && row != null && row.opt(row.length() - 1) is Boolean
                val leaves = mutableListOf<Pair<List<Int>, Any?>>()
                fun collect(value: Any?, path: List<Int>) {
                    if (value is JSONArray) repeat(value.length()) { collect(value.opt(it), path + it) }
                    else leaves += path to value
                }
                if (row != null) repeat(row.length() - if (switched) 1 else 0) { collect(row.opt(it), listOf(it)) }
                else collect(node, emptyList())
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (switched) {
                        Box(Modifier.padding(end = 8.dp)) {
                            BaahSwitch(row!!.optBoolean(row.length() - 1), { checked -> edit { it.getJSONArray(groupIndex).getJSONArray(rowIndex).put(row.length() - 1, checked) } }, large = true)
                        }
                    }
                    leaves.forEachIndexed { columnIndex, (path, value) ->
                        key(field.key, groupIndex, rowIndex, path) {
                            val cellKey = "$prefix$groupIndex:$rowIndex:${path.joinToString(":")}"
                            val label = columns.optString(columnIndex, "值")
                            fun change(value: Any) { edit { data ->
                                val items = data.getJSONArray(groupIndex)
                                if (path.isEmpty()) items.put(rowIndex, value) else {
                                    var target = items.getJSONArray(rowIndex)
                                    path.dropLast(1).forEach { target = target.getJSONArray(it) }
                                    target.put(path.last(), value)
                                }
                            } }
                            Box(Modifier.weight(1f).padding(end = 4.dp)) {
                                val numeric = (value as? Number) ?: (value as? String)?.let { it.toLongOrNull() ?: it.toDoubleOrNull() }
                                if (numeric != null) {
                                    BaahNumberEditor(BaahConfigField(cellKey, label, "ui.number", null, JSONObject().put("precision", if (numeric is Int || numeric is Long) 0 else 6)),
                                        numeric, drafts, { invalidCells[cellKey] = it; onInvalid(invalidCells.values.any { it }) }, compact = true, showLabel = false, compactHeight = 34) {
                                        change(if (value is String) it.toString() else it)
                                    }
                                } else BaahListTextCell(label, value) { change(it) }
                            }
                        }
                    }
                    OutlinedButton({ edit { it.getJSONArray(groupIndex).remove(rowIndex) }; clearDrafts() }, Modifier.width(56.dp).height(34.dp),
                        contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD32F2F))) { Text("删除", fontSize = 13.sp) }
                }
            }
            OutlinedButton({ edit { data ->
                val items = data.getJSONArray(groupIndex)
                if (threeLevel) {
                    val values = JSONArray(); repeat(columns.length().coerceAtLeast(1)) { values.put(1) }; if (hasSwitch) values.put(true); items.put(values)
                } else {
                    val last = items.opt(items.length() - 1)
                    val previous = if (last is JSONArray) last.optInt(0) else (last as? Number)?.toInt() ?: 0
                    if (hasSwitch) items.put(JSONArray().put(previous + 1).put(true)) else items.put(previous + 1)
                }
            } }, Modifier.fillMaxWidth().padding(top = 6.dp).height(38.dp), contentPadding = PaddingValues(horizontal = 14.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                Text("+ 添加 ${if (threeLevel) "行" else headers?.optString(1)?.takeIf { it.isNotBlank() } ?: "项"}", fontSize = 13.sp)
            }
        }
        OutlinedButton({ edit { it.put(JSONArray()) } }, Modifier.fillMaxWidth().padding(top = 8.dp).height(38.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) { Text("+ 添加 $groupLabel", fontSize = 13.sp) }
    }
}

@Composable
private fun BaahListTextCell(label: String, value: Any?, onChanged: (Any) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var input by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    Surface(Modifier.fillMaxWidth().height(34.dp).clickable { editing = true }, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Box(contentAlignment = Alignment.Center) { Text(value?.toString().orEmpty(), fontSize = 15.sp) }
    }
    if (editing) BaahDialog(onDismissRequest = { editing = false }, title = { Text(label) }, text = {
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true)
    }, confirmButton = { TextButton({ onChanged(if (value is Boolean) input.equals("true", true) else input); editing = false }) { Text("确定") } },
        dismissButton = { TextButton({ editing = false }) { Text("取消") } })
}