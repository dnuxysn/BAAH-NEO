package com.baah.nativehost
import com.baah.nativehost.NeoText as Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baah.nativehost.legacy.*
import kotlinx.coroutines.launch
import org.json.*
import java.time.*
import java.time.format.DateTimeFormatter

/** NEO editor; matches the existing BAAH screen arrangement, with independent rule storage. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun NeoScheduleEditor(app: NeoApp, initial: JSONObject, isNew: Boolean, close: () -> Unit, save: (JSONObject, Boolean) -> Unit) {
    var draft by remember(initial) { mutableStateOf(JSONObject(initial.toString()).apply {
        if (!has("name")) put("name", "")
        if (!has("scheduleType")) put("scheduleType", "FIXED_TIME")
        if (!has("daysOfWeek")) put("daysOfWeek", JSONArray((1..7).toList()))
        if (!has("executionTimes")) put("executionTimes", JSONArray().put(optString("time", "08:00")))
        if (!has("startTimeMs")) put("startTimeMs", System.currentTimeMillis() + 60_000)
    }) }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var timeIndex by remember { mutableStateOf<Int?>(null) }
    var startTime by remember { mutableStateOf(false) }; var startDate by remember { mutableStateOf(false) }
    val busy by app.busy.collectAsState(); val running by app.running.collectAsState()
    fun update(key: String, value: Any) { draft = JSONObject(draft.toString()).put(key, value) }
    fun submit() {
        runCatching {
            require(draft.optString("name").isNotBlank()) { "名称不能为空" }
            require(draft.optString("config") in app.repository.names()) { "请选择运行配置" }
            if (draft.optString("scheduleType") == "FIXED_TIME") {
                require(draft.getJSONArray("daysOfWeek").length() > 0) { "至少选择一天" }
                require(draft.getJSONArray("executionTimes").length() > 0) { "至少添加一个触发时刻" }
            } else require(draft.optInt("intervalDays") * 1440L + draft.optInt("intervalHours") * 60L + draft.optInt("intervalMinutes") > 0) { "间隔须大于 0" }
            draft.put("runConfigurationId", draft.getString("config"))
            save(draft, false)
        }.onFailure { error = it.message; scope.launch { scroll.animateScrollTo(0) } }
    }
    BackHandler(onBack = close)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopAppBar(title = { Text(if (isNew) "新建规则" else "编辑规则") }, navigationIcon = {
            IconButton(close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, com.baah.nativehost.UiStrings.text("返回")) }
        }, actions = {
            if (!isNew) IconButton({ save(draft, true) }, enabled = !busy && !running) { Icon(Icons.Outlined.DeleteOutline, com.baah.nativehost.UiStrings.text("删除")) }
            TextButton(::submit, enabled = !busy && !running) { Text("保存") }
        }, windowInsets = WindowInsets(0,0,0,0), colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Column(Modifier.verticalScroll(scroll).padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(draft.optString("name"), { update("name", it) }, label = { Text("名称") }, placeholder = { Text("未命名规则") }, modifier = Modifier.fillMaxWidth())
            ScheduleSection("触发方式") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("FIXED_TIME" to "固定时刻", "INTERVAL" to "固定间隔").forEach { (key, title) ->
                        FilterChip(draft.optString("scheduleType") == key, { update("scheduleType", key) }, { Text(title) })
                    }
                }
            }
            if (draft.optString("scheduleType") == "FIXED_TIME") {
                val days = draft.getJSONArray("daysOfWeek").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
                ScheduleSection("执行日期") {
                    (0..7).chunked(4).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { day -> FilterChip(if (day == 0) days.size == 7 else day in days, {
                            val next = if (day == 0) { if (days.size == 7) emptySet() else (1..7).toSet() } else if (day in days) days - day else days + day
                            update("daysOfWeek", JSONArray(next.sorted()))
                        }, { Text(listOf("每天", "周一", "周二", "周三", "周四", "周五", "周六", "周日")[day], fontSize = 13.sp) }, modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)) }
                    } }
                }
                ScheduleSection("执行时间") {
                    val times = draft.getJSONArray("executionTimes")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (0 until times.length()).forEach { i -> InputChip(false, { timeIndex = i }, label = { Text(times.getString(i)) }, trailingIcon = {
                            IconButton({ update("executionTimes", JSONArray().also { a -> (0 until times.length()).filter { it != i }.forEach { a.put(times.getString(it)) } }) }, Modifier.size(18.dp)) { Icon(Icons.Outlined.Close, com.baah.nativehost.UiStrings.text("删除时刻"), Modifier.size(14.dp)) }
                        }) }
                        AssistChip({ timeIndex = times.length() }, { Text("添加时刻") }, leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)) })
                    }
                }
            } else {
                val date = Instant.ofEpochMilli(draft.getLong("startTimeMs")).atZone(ZoneId.systemDefault())
                ScheduleSection("首次触发") { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ startDate = true }) { Text(date.toLocalDate().toString()) }
                    OutlinedButton({ startTime = true }) { Text(date.format(DateTimeFormatter.ofPattern("HH:mm"))) }
                } }
                ScheduleSection("执行间隔") { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("intervalDays" to "天", "intervalHours" to "小时", "intervalMinutes" to "分钟").forEach { (key, title) ->
                        OutlinedTextField(draft.optInt(key).toString(), { value -> value.toIntOrNull()?.takeIf { it >= 0 }?.let { update(key, it) } }, label = { Text(title) }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                } }
            }
            ScheduleSection("运行配置") { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                app.repository.names().forEach { name -> FilterChip(draft.optString("config") == name, { update("config", name) }, { Text(name) }) }
            } }
            ScheduleSection("高级选项") {
                val backgroundMode = draft.optBoolean("backgroundMode", true)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("运行模式", Modifier.weight(1f))
                    Text(if (backgroundMode) "后台模式" else "前台模式", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    BaahSwitch(backgroundMode, { update("backgroundMode", it) })
                }
                Toggle("强制启动", draft.optBoolean("forceStart")) { update("forceStart", it) }
                Text("到达设定时间时若有任务正在执行，中断后执行本规则", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (backgroundMode) {
                    Toggle("启动后启用屏保", draft.optBoolean("screenSaverOnStart")) { update("screenSaverOnStart", it) }
                }
                Toggle("任务结束后自动熄屏", draft.optBoolean("autoSleepAfterTask")) { update("autoSleepAfterTask", it) }
                if (draft.optBoolean("autoSleepAfterTask")) Toggle("启动时已亮屏则不熄屏", draft.optBoolean("skipAutoSleepIfAwake", true)) { update("skipAutoSleepIfAwake", it) }
                Toggle("任务结束后关闭目标应用", draft.optBoolean("closeAppAfterTask")) { update("closeAppAfterTask", it) }
            }
        }
    }
    if (timeIndex != null || startTime) {
        val index = timeIndex
        val current = if (index != null) draft.getJSONArray("executionTimes").optString(index, "08:00").let(LocalTime::parse)
            else Instant.ofEpochMilli(draft.getLong("startTimeMs")).atZone(ZoneId.systemDefault()).toLocalTime()
        val picker = rememberTimePickerState(current.hour, current.minute, true)
        BaahDialog(onDismissRequest = { timeIndex = null; startTime = false }, title = { Text("选择时间") }, text = { TimePicker(picker) },
            confirmButton = { TextButton({
                val time = LocalTime.of(picker.hour, picker.minute)
                if (index != null) {
                    val times = draft.getJSONArray("executionTimes"); times.put(index, time.toString())
                    update("executionTimes", JSONArray((0 until times.length()).map { times.getString(it) }.distinct().sorted()))
                } else update("startTimeMs", Instant.ofEpochMilli(draft.getLong("startTimeMs")).atZone(ZoneId.systemDefault()).toLocalDate().atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                timeIndex = null; startTime = false
            }) { Text("确定") } }, dismissButton = { TextButton({ timeIndex = null; startTime = false }) { Text("取消") } })
    }
    if (startDate) {
        val picker = rememberDatePickerState(draft.getLong("startTimeMs"))
        DatePickerDialog(onDismissRequest = { startDate = false }, confirmButton = { TextButton({
            picker.selectedDateMillis?.let { millis ->
                val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                val time = Instant.ofEpochMilli(draft.getLong("startTimeMs")).atZone(ZoneId.systemDefault()).toLocalTime()
                update("startTimeMs", day.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }; startDate = false
        }) { Text("确定") } }) { DatePicker(picker) }
    }
}

@Composable private fun ScheduleSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary); content() }
}
