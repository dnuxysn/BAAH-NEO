// Adapted from BAAH-AOS (MaaFwApp-derived, AGPL-3.0); see third_party/baah-adapter.
package com.baah.nativehost.legacy
import com.baah.nativehost.NeoText as Text

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Configuration files use Android document pickers, as in the original BAAH UI. */
@Composable
fun BaahConfigManagerScreen(repository: BaahConfigRepository, selected: String,
    onSelected: (String) -> Unit, onBack: () -> Unit,
    onRename: suspend (String, String) -> Unit, onImported: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var names by remember { mutableStateOf(repository.names()) }
    var newName by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameName by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<JSONObject?>(null) }
    var exportName by remember { mutableStateOf(selected) }
    fun refresh() { names = repository.names() }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            val data = repository.load(exportName).toString(2)
            checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(data) }
        }.onSuccess { notice = "配置已导出" }.onFailure { notice = it.message }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = checkNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use { it.readText() }
            val data = JSONObject(text)
            require(data.has("ACTIVITY_PATH") && (data.has("TASK_PIPELINE") || data.has("TASK_ORDER") || data.has("TASK_ORDER_GROUP"))) { "请选择有效的 BAAH 配置文件" }
            pendingImport = data
        }.onFailure { notice = it.message }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable(onClick = onBack), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("‹", fontSize = 32.sp, color = MaterialTheme.colorScheme.primary)
            }
            Text("编辑配置", fontSize = 22.sp)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text("配置文件", Modifier.padding(top = 8.dp, start = 12.dp), fontSize = 20.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text("点名字切换配置，再点当前配置可改名。", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEach { name ->
                Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(if (selected == name) 1.dp else .5.dp, if (selected == name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Text(name + if (selected == name) com.baah.nativehost.UiStrings.text("（当前）") else "", Modifier.weight(1f).clickable {
                            if (selected == name) {
                                renameTarget = name
                                renameName = name
                                renameError = null
                            } else onSelected(name)
                        }.padding(vertical = 12.dp),
                            color = if (selected == name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        TextButton({
                            runCatching {
                                var suffix = 1
                                while ("${name}_$suffix" in names) suffix++
                                repository.create("${name}_$suffix", name)
                                refresh()
                            }.onFailure { notice = it.message }
                        }) { Text("复制") }
                        TextButton({ delete = name }, enabled = names.size > 1) { Text("删除") }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.weight(1f).height(44.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Box(Modifier.padding(horizontal = 12.dp), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
                    androidx.compose.foundation.text.BasicTextField(newName, { newName = it }, Modifier.fillMaxWidth(), singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { inner ->
                            if (newName.isEmpty()) Text("Name", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        })
                }
            }
            Button({
                runCatching { repository.create(newName.trim(), selected); newName = ""; refresh() }
                    .onFailure { notice = it.message }
            }, Modifier.height(44.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) { Text("添加") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, Modifier.weight(1f).height(44.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) { Text("导入") }
            OutlinedButton({ exportName = selected; exporter.launch("$selected.json") }, Modifier.weight(1f).height(44.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) { Text("导出") }
        }
    }
    renameTarget?.let { name ->
        BaahDialog(
            onDismissRequest = { if (!renaming) renameTarget = null },
            title = { Text("重命名配置") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it; renameError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("新名称") },
                    singleLine = true,
                    enabled = !renaming,
                    isError = renameError != null,
                    supportingText = renameError?.let { error -> { Text(error) } },
                )
            },
            confirmButton = { TextButton({
                val nextName = renameName.trim()
                renaming = true
                scope.launch {
                    try {
                        onRename(name, nextName)
                        refresh()
                        renameTarget = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        renameError = e.message ?: "重命名配置失败"
                    } finally {
                        renaming = false
                    }
                }
            }, enabled = !renaming) { Text("确定") } },
            dismissButton = { TextButton({ renameTarget = null }, enabled = !renaming) { Text("取消") } },
        )
    }
    delete?.let { name ->
        BaahDialog(onDismissRequest = { delete = null }, title = { Text("删除配置") }, text = { Text("删除 $name.json？") },
            confirmButton = { TextButton({
                runCatching { repository.delete(name); if (selected == name) onSelected(repository.names().first()); refresh() }
                    .onFailure { notice = it.message }
                delete = null
            }) { Text("删除") } }, dismissButton = { TextButton({ delete = null }) { Text("取消") } })
    }
    pendingImport?.let { data ->
        BaahDialog(onDismissRequest = { pendingImport = null }, title = { Text("导入配置") },
            text = { Text("将覆盖当前选择的 $selected.json，其他配置保留。") },
            confirmButton = { TextButton({
                runCatching { repository.save(selected, data); refresh() }.onSuccess { onImported(); notice = "配置已导入" }.onFailure { notice = it.message }
                pendingImport = null
            }) { Text("导入") } }, dismissButton = { TextButton({ pendingImport = null }) { Text("取消") } })
    }
    notice?.let { message -> BaahDialog(onDismissRequest = { notice = null }, text = { Text(message) },
        confirmButton = { TextButton({ notice = null }) { Text("确定") } }) }
}
