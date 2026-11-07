// Adapted from BAAH-AOS (AGPL-3.0); display and process backend belongs to NEO.
package com.baah.nativehost.legacy
import com.baah.nativehost.NeoText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.baah.nativehost.*
import org.json.JSONObject

@Composable
internal fun BaahToolsDialog(pageId: String, repository: BaahConfigRepository, config: String,
    session: NeoApp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val quick = pageId == "config_quick_call_task"
    var data by remember(config) { mutableStateOf(repository.load(config)) }
    val drafts = remember { mutableStateMapOf<String, String>() }
    val invalid = remember { mutableStateMapOf<String, Boolean>() }
    val page = remember { repository.pages("zh_CN").find { it.id == pageId } }
    val choices = remember { repository.taskChoices("zh_CN") }
    var message by remember { mutableStateOf<String?>(null) }
    BaahDialog(onDismissRequest = onDismiss, title = { Text(if (quick) "小工具" else "推图设置") },
        bodyHorizontalPadding = if (quick) 14.dp else 18.dp, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (quick) {
                Text("快速调用任务", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("点一下就单独跑这个任务", fontSize = 12.sp)
                val groups = remember {
                    context.assets.open("baah-aos/schema_zh_CN.json").bufferedReader().use {
                        val rows = JSONObject(it.readText()).getJSONArray("quick_tasks")
                        List(rows.length()) { index -> rows.getJSONArray(index).let { row -> List(row.length()) { row.getString(it) } } }
                    }
                }
                groups.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { task -> Button({ if (!session.running.value && !session.busy.value) { session.action { session.start(config, task) }; onDismiss() } else message = "请先停止当前任务" },
                            Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                            Text(when (task) {
                                "推第一部主线剧情" -> "推第一部主\n线剧情"
                                "推第二部主线剧情" -> "推第二部主\n线剧情"
                                "推短篇剧情/小故事" -> "推短篇剧情/\n小故事"
                                "推支线剧情/社团故事" -> "推支线剧情/\n社团故事"
                                else -> task
                            }, fontSize = 14.sp, letterSpacing = (-1).sp,
                                lineHeight = 20.sp, textAlign = TextAlign.Center)
                        } }
                    }
                }
            } else {
                Text("改完点保存；正在挂机时需重启挂机才会读取新配置。", fontSize = 12.sp)
                page?.fields?.filter { field ->
                    val visibleKey = field.metadata?.optString("visibleWhen").orEmpty()
                    data.has(field.key) && (visibleKey.isBlank() ||
                        (data.optBoolean(visibleKey) != (field.metadata?.optBoolean("visibleInverted") == true)))
                }?.forEach { field ->
                    ConfigField(field, data.opt(field.key), choices, drafts, { invalid[field.key] = it }) {
                        data = JSONObject(data.toString()).put(field.key, it)
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (quick) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onDismiss, Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)) {
                    Text("关闭", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                }
            }
        }
    }, confirmButton = if (quick) null else ({ TextButton({
        if (invalid.values.any { it }) message = "请先修正无效配置"
        else runCatching { repository.save(config, data) }.onSuccess { onDismiss() }.onFailure { message = it.message }
    }) { Text("保存") } }), dismissButton = if (quick) null else ({ TextButton(onDismiss) { Text("关闭") } }))
}
