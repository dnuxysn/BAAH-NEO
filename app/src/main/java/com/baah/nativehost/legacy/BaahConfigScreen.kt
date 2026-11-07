// Adapted from BAAH-AOS (MaaFwApp-derived, AGPL-3.0); see third_party/baah-adapter.
package com.baah.nativehost.legacy
import com.baah.nativehost.NeoText as Text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baah.nativehost.legacy.*
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun BaahConfigScreen(repository: BaahConfigRepository, configName: String, initialPage: String? = null,
    onManageConfigs: (() -> Unit)? = null) {
    val language = if (projectLanguage() == "en") "en_US" else "zh_CN"
    val loaded by produceState<Triple<List<BaahConfigPage>, Map<String, String>, JSONObject>?>(null, repository, language, configName) {
        val started = android.os.SystemClock.elapsedRealtime()
        value = null
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Triple(repository.pages(language), repository.taskChoices(language), repository.load(configName))
        }
        android.util.Log.i("NeoPerf", "config_loaded language=$language elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
    }
    val content = loaded
    if (content == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        return
    }
    BaahLoadedConfigScreen(repository, configName, language, content, initialPage, onManageConfigs)
}

@Composable
private fun BaahLoadedConfigScreen(repository: BaahConfigRepository, configName: String, language: String,
    content: Triple<List<BaahConfigPage>, Map<String, String>, JSONObject>, initialPage: String?, onManageConfigs: (() -> Unit)?) {
    val pages = content.first
    val taskChoices = content.second
    var group by remember { mutableStateOf(initialPage?.let { id -> pages.find { it.id == id }?.tabs?.firstOrNull() } ?: "每日") }
    var pageId by remember { mutableStateOf(initialPage) }
    var data by remember(configName) { mutableStateOf(content.third) }
    val invalidFields = remember(configName) { mutableStateMapOf<String, Boolean>() }
    val drafts = remember(configName) { mutableStateMapOf<String, String>() }
    var notice by remember { mutableStateOf<String?>(null) }
    val page = pages.find { it.id == pageId } ?: pages.firstOrNull { group in it.tabs && it.fields.isNotEmpty() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Blue Archive Aris Helper ${repository.installedVersion()}", fontSize = 18.sp)
        Text("当前配置：$configName · 修改后点保存生效；正在挂机时需重启挂机。", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        onManageConfigs?.let { action ->
            BaahCard(Modifier.fillMaxWidth().clickable(onClick = action)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("编辑配置  ›", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Text("当前配置：$configName", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("BAAH", "每日").forEach { title ->
                Surface(Modifier.weight(1f).height(44.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    color = if (group == title) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    border = if (group == title) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Box(Modifier.fillMaxSize().clickable { group = title; pageId = null }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text(if (title == "每日" && language == "en_US") "Daily" else title, fontSize = 14.sp,
                            color = if (group == title) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.width(104.dp).fillMaxHeight(), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(.5.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.padding(6.dp).verticalScroll(rememberScrollState())) {
                    pages.filter { group in it.tabs && it.id != "BAAH" }.forEach { item ->
                        Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            color = if (page?.id == item.id) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent) {
                            Text(item.title, fontSize = 13.sp, color = if (page?.id == item.id)
                                MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth().clickable { pageId = item.id }.padding(horizontal = 8.dp, vertical = 10.dp))
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val fields = page?.fields.orEmpty().filter { field ->
                    val visibleKey = field.metadata?.optString("visibleWhen").orEmpty()
                    (data.has(field.key) || field.key in setOf("USE_OBJ_FLOW_WHEN_LOGIN", "OBJ_FLOW_WHEN_LOGIN")) &&
                        (visibleKey.isBlank() ||
                        (data.optBoolean(visibleKey) != (field.metadata?.optBoolean("visibleInverted") == true)))
                }
                val title: @Composable () -> Unit = {
                    Text(page?.title.orEmpty(), fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                val renderField: @Composable (BaahConfigField) -> Unit = { field ->
                        if (field.kind == "list" && field == page?.fields?.firstOrNull { it.kind == "list" } && page.fields.any { it.kind != "list" }) {
                            Text("本页的其它配置", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                        val current = when (field.key) {
                            "USE_OBJ_FLOW_WHEN_LOGIN" -> data.optBoolean(field.key)
                            "OBJ_FLOW_WHEN_LOGIN" -> data.optJSONObject(field.key) ?: JSONObject()
                            else -> data.opt(field.key)
                        }
                        ConfigField(field, current, taskChoices, drafts, { invalidFields[field.key] = it }) { value ->
                            data = JSONObject(data.toString()).put(field.key, value)
                        }
                }
                key(configName, page?.id) {
                    val pipelineIndex = fields.indexOfFirst { it.kind == "pipeline" && data.opt(it.key) is JSONObject }
                    if (pipelineIndex >= 0) {
                        val field = fields[pipelineIndex]
                        BaahPipelineEditor(data.getJSONObject(field.key), taskChoices,
                            { value -> data = JSONObject(data.toString()).put(field.key, value) }, Modifier.fillMaxSize(),
                            beforeContent = {
                                item(key = "page-title") { title() }
                                fields.take(pipelineIndex).forEach { entry -> item(key = entry.key) { renderField(entry) } }
                            }, afterContent = {
                                fields.drop(pipelineIndex + 1).forEach { entry -> item(key = entry.key) { renderField(entry) } }
                            })
                    } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "page-title") { title() }
                        items(fields, key = { it.key }) { renderField(it) }
                    }
                }
            }
        }
        Button({
            if (invalidFields.values.any { it }) {
                notice = "仍有无效的配置值，请修正后保存"
                return@Button
            }
            runCatching { repository.save(configName, data) }
                .onSuccess { notice = "已保存到 $configName.json" }
                .onFailure { notice = it.message }
        }, Modifier.fillMaxWidth().height(44.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) { Text("保存") }
    }
    notice?.let { message -> BaahDialog(onDismissRequest = { notice = null }, text = { Text(message) },
        confirmButton = { TextButton({ notice = null }) { Text("确定") } }) }
}

@Composable
internal fun ConfigField(field: BaahConfigField, value: Any?, taskChoices: Map<String, String>, drafts: MutableMap<String, String>, onInvalid: (Boolean) -> Unit, onChanged: (Any) -> Unit) {
    val editable = field.metadata?.optBoolean("readOnly") != true
    if (field.kind == "login-flow" && value is JSONObject) {
        BaahLoginFlowEditor(value, onChanged)
        return
    }
    if (field.key == "TASK_ORDER_GROUP" && value is JSONObject) {
        BaahPipelineEditor(value, taskChoices, onChanged, Modifier.height(400.dp))
        return
    }
    BaahCard(Modifier.fillMaxWidth(), verticalPadding = if (field.kind == "list") 12.dp else 10.dp) {
        if (field.key == "TEAM_SET_STRENGTH" && value is JSONArray) {
            BaahTeamStrengthEditor(value, drafts, onInvalid, onChanged)
            return@BaahCard
        }
        val numeric = (value as? Number) ?: (value as? String)?.let { it.toLongOrNull() ?: it.toDoubleOrNull() }
        if (numeric != null && (field.kind == "ui.number" || value is String && field.kind != "ui.textarea")) {
            BaahNumberEditor(field, numeric, drafts, onInvalid) { onChanged(if (value is String) it.toString() else it) }
            return@BaahCard
        }
        if (field.kind == "list" && value is JSONArray && field.metadata != null) {
            BaahListEditor(field, value, drafts, onInvalid, onChanged)
            return@BaahCard
        }
        if (field.kind in listOf("ui.select", "ui.radio", "ui.toggle") && field.options != null) {
            var selection by remember(field.key) { mutableStateOf(value?.toString().orEmpty()) }
            var expanded by remember(field.key) { mutableStateOf(false) }
            val choices = remember(field.options) {
                when (val options = field.options) {
                    is JSONObject -> options.keys().asSequence().map { it to options.optString(it, it) }.toList()
                    is JSONArray -> List(options.length()) { options.optString(it) to options.optString(it) }
                    else -> emptyList()
                }
            }
            Text(field.label, fontSize = 14.sp)
            BaahDropdown(selection, choices.filterNot { field.key == "SERVER_TYPE" && it.first.startsWith("PC_") }, editable) { key ->
                selection = key
                onChanged(when (value) {
                    is Int -> key.toInt()
                    is Long -> key.toLong()
                    is Number -> key.toDouble()
                    else -> key
                })
                onInvalid(false)
            }
            return@BaahCard
        }
        when (value) {
            is Boolean -> {
                var checked by remember(field.key) { mutableStateOf(value) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(field.label, Modifier.weight(1f), fontSize = 14.sp)
                    BaahSwitch(checked, { checked = it; onChanged(it) }, enabled = editable)
                }
            }
            else -> {
                Text(field.label, fontSize = 14.sp)
                val text = drafts[field.key] ?: when (value) {
                    is JSONArray -> value.toString(2)
                    is JSONObject -> value.toString(2)
                    else -> value?.toString().orEmpty()
                }
                var editing by remember(field.key) { mutableStateOf(false) }
                var input by remember(field.key) { mutableStateOf(text) }
                var error by remember(field.key) { mutableStateOf(false) }
                Surface(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(editable) { input = text; error = false; editing = true },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    androidx.compose.material3.Text(text, fontSize = 14.sp, minLines = if (field.kind == "ui.textarea") 2 else 1, maxLines = if (field.kind == "ui.textarea") 5 else 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
                }
                if (editing) BaahDialog(onDismissRequest = { editing = false }, title = { Text(field.label) }, text = {
                    OutlinedTextField(input, { input = it; error = false }, Modifier.fillMaxWidth(), isError = error,
                        singleLine = value !is JSONArray && value !is JSONObject,
                        supportingText = if (error) ({ Text("请输入有效的值") }) else null)
                }, confirmButton = { TextButton({
                    runCatching {
                        val parsed: Any = when (value) {
                            is JSONArray -> JSONArray(input)
                            is JSONObject -> JSONObject(input)
                            is Int, is Long -> input.toLong()
                            is Number -> input.toDouble()
                            else -> input
                        }
                        drafts[field.key] = input
                        onChanged(parsed)
                    }.onSuccess { onInvalid(false); editing = false }.onFailure { error = true }
                }) { Text("确定") } }, dismissButton = { TextButton({ editing = false }) { Text("取消") } })
            }
        }
    }
}
