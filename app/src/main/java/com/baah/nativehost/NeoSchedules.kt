package com.baah.nativehost
import com.baah.nativehost.NeoText as Text

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baah.nativehost.legacy.*
import org.json.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NeoSchedules(app: NeoApp, selected: String) {
    val busy by app.busy.collectAsState(); val running by app.running.collectAsState()
    val settingsRevision by UserData.settingsRevision.collectAsState()
    var revision by remember { mutableIntStateOf(0) }; val settings = remember(revision, settingsRevision) { app.data.loadSettings() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val array = settings.optJSONArray("schedules") ?: JSONArray()
    var editing by remember { mutableStateOf<JSONObject?>(null) }; var history by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun changeLog(id: String? = null) {
        app.action(showBusy = false) {
            val fresh = app.data.loadSettings(); val log = fresh.optJSONArray("scheduleLog") ?: JSONArray()
            val retained = JSONArray()
            if (id != null) for (i in 0 until log.length()) { val row = log.getJSONObject(i)
                if (row.optString("logId", "legacy-$i") != id) retained.put(row)
            }
            fresh.put("scheduleLog", retained); app.data.saveSettings(fresh)
        }
    }
    fun save(entry: JSONObject, delete: Boolean = false) {
        app.action(showBusy = false) {
            if (app.running.value) return@action
            val fresh = app.data.loadSettings(); val schedules = fresh.optJSONArray("schedules") ?: JSONArray()
            val index = (0 until schedules.length()).firstOrNull { schedules.getJSONObject(it).getString("id") == entry.getString("id") }
            if (delete) {
                // Cancel the old rule before removing it from storage.
                if (index != null) { schedules.getJSONObject(index).put("enabled", false); fresh.put("schedules", schedules); app.data.saveSettings(fresh); ScheduleReceiver.arm(app); schedules.remove(index) }
            } else if (index != null) schedules.put(index, entry) else schedules.put(entry)
            fresh.put("schedules", schedules); app.data.saveSettings(fresh); ScheduleReceiver.arm(app); revision++; editing = null
        }
    }
    fun setEnabled(id: String, enabled: Boolean) {
        app.action(showBusy = false) {
            if (app.running.value) return@action
            val fresh = app.data.loadSettings()
            val schedules = fresh.optJSONArray("schedules") ?: return@action
            val entry = (0 until schedules.length()).asSequence().map { schedules.getJSONObject(it) }
                .firstOrNull { it.getString("id") == id } ?: return@action
            entry.put("enabled", enabled)
            app.data.saveSettings(fresh)
            ScheduleReceiver.arm(app)
        }
    }
    editing?.let { draft ->
        val isNew = (0 until array.length()).none { array.getJSONObject(it).getString("id") == draft.getString("id") }
        NeoScheduleEditor(app, draft, isNew, { editing = null }, ::save)
    } ?: Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopAppBar(title = { Text("定时", style = MaterialTheme.typography.headlineMedium) }, actions = {
            IconButton({ app.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Icon(Icons.Outlined.Alarm, com.baah.nativehost.UiStrings.text("精确闹钟设置")) }
            IconButton({ app.action(showBusy = false) {
                val fresh = app.data.loadSettings(); val log = fresh.optJSONArray("scheduleLog") ?: JSONArray()
                for (i in 0 until log.length()) if (!log.getJSONObject(i).has("logId"))
                    log.getJSONObject(i).put("logId", UUID.randomUUID().toString())
                fresh.put("scheduleLog", log); app.data.saveSettings(fresh); history = true
            } }) { Icon(Icons.Outlined.History, com.baah.nativehost.UiStrings.text("触发日志")) }
            IconButton({ editing = JSONObject().put("id", UUID.randomUUID().toString()).put("config", selected).put("enabled", true) }) { Icon(Icons.Outlined.Add, com.baah.nativehost.UiStrings.text("添加规则")) }
        }, windowInsets = WindowInsets(0, 0, 0, 0), colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, actionIconContentColor = MaterialTheme.colorScheme.primary))
        if (android.os.Build.VERSION.SDK_INT >= 31 && !app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) BaahCard(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Text("系统未允许精确闹钟", style = MaterialTheme.typography.titleMedium)
            Text("系统可能延迟非精确闹钟，请允许精确闹钟以按设定时刻执行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ app.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("去允许") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (array.length() == 0) item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Text("暂无定时规则\n点右上角 ＋ 添加", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            items((0 until array.length()).map { array.getJSONObject(it) }, key = { it.getString("id") }) { entry ->
                BaahCard(Modifier.fillMaxWidth().clickable { editing = JSONObject(entry.toString()) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.optString("name", "每日挂机"), style = MaterialTheme.typography.titleMedium)
                            val description = if (entry.optString("scheduleType") == "INTERVAL") "每 ${entry.optInt("intervalDays")} 天 ${entry.optInt("intervalHours")} 小时 ${entry.optInt("intervalMinutes")} 分钟"
                                else entry.optJSONArray("executionTimes")?.let { a -> (0 until a.length()).joinToString("、") { a.getString(it) } } ?: entry.optString("time", "08:00")
                            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("运行配置：${entry.optString("config", entry.optString("runConfigurationId"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val next = runCatching { ScheduleReceiver.next(entry) }.getOrNull()
                            Text(if (!entry.optBoolean("enabled")) "已停用" else "下次：${next?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) } ?: "无"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            val result = when (val raw = entry.optString("lastResult", "暂无")) { "SUCCEEDED" -> "完成"; "FAILED" -> "失败"; "RUNNING" -> "运行中"; "STOPPED" -> "已停止"; "INTERRUPTED" -> "已中断"; else -> raw }
                            Text("上次运行：$result · ${"%.1f".format(entry.optDouble("lastMinutes", 0.0))} 分钟", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        BaahSwitch(entry.optBoolean("enabled"), { setEnabled(entry.getString("id"), it) }, enabled = !busy && !running)
                    }
                }
            }
        }
    }
    if (history) BaahDialog(onDismissRequest = { history = false }, title = { Text("触发日志") }, text = {
        val log = settings.optJSONArray("scheduleLog") ?: JSONArray()
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (log.length() == 0) Text("暂无触发日志")
            (0 until log.length()).reversed().forEach { i -> val entry = log.getJSONObject(i)
                Row(Modifier.fillMaxWidth()) {
                    Text(Instant.ofEpochMilli(entry.getLong("at")).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")) + " · " + entry.optString("name") + "\n" + entry.optString("result"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton({ changeLog(entry.optString("logId", "legacy-$i")) }) { Text("删除") }
                }
            }
        }
    }, dismissButton = { TextButton({ changeLog() }) { Text("清空日志") } }, confirmButton = { TextButton({ history = false }) { Text("关闭") } })
    error?.let { BaahDialog(onDismissRequest = { error = null }, text = { Text(it) }, confirmButton = { TextButton({ error = null }) { Text("确定") } }) }
}
