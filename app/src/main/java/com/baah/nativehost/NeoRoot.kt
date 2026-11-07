package com.baah.nativehost
import com.baah.nativehost.NeoText as Text

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baah.nativehost.legacy.*
import kotlinx.coroutines.delay
import org.json.*

/** BAAH-AOS page composition and styling; independent NEO services provide all actions. */
@Composable fun NeoRoot(app: NeoApp, export: () -> Unit, restore: () -> Unit, backupPreview: BackupPreview? = null, cancelRestore: () -> Unit = {}, confirmRestore: () -> Unit = {}) {
    val busy by app.busy.collectAsState(); val running by app.running.collectAsState(); val message by app.message.collectAsState()
    val operationPercent by app.operationPercent.collectAsState()
    var showProgress by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        showProgress = false
        if (busy) { delay(300); showProgress = true }
    }
    val permission by app.control.permission.collectAsState()
    var selected by rememberSaveable { mutableStateOf(app.data.loadSettings().optString("selected", app.repository.names().first())) }
    var tab by rememberSaveable { mutableIntStateOf(0) }; var page by rememberSaveable { mutableStateOf<String?>(null) }
    var quick by remember { mutableStateOf(false) }; var tool by remember { mutableStateOf<String?>(null) }
    var quickAnchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var fullscreen by remember { mutableStateOf(false) }; var notice by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val dataRevision by app.dataRevision.collectAsState()
    var baahVisited by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { if (tab == 1) baahVisited = true }
    val settingsRevision by UserData.settingsRevision.collectAsState()
    val saved = remember(revision, dataRevision, settingsRevision) { app.data.loadSettings() }
    var clockMinute by remember { mutableLongStateOf(System.currentTimeMillis() / 60000) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(15000); clockMinute = System.currentTimeMillis() / 60000 } }
    val nextSchedule = remember(saved, clockMinute) {
        val rules = saved.optJSONArray("schedules")
        (0 until (rules?.length() ?: 0)).map { rules!!.getJSONObject(it) }.filter { it.optBoolean("enabled") }
            .mapNotNull { rule -> ScheduleReceiver.next(rule)?.let { NextSchedule(it, ScheduleName(rule.optString("name", rule.optString("config")))) } }
            .minByOrNull { it.nextTriggerAt ?: Long.MAX_VALUE }
    }
    val dark = when (saved.optString("theme", "System")) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    LaunchedEffect(dark, activity) { activity?.applySystemBars(dark) }
    val colors = if (dark) darkColorScheme(primary = Color(0xFFAEC6FF), onPrimary = Color(0xFF18345E),
        primaryContainer = Color(0xFF43465C), onPrimaryContainer = Color(0xFFDAEAFF), secondaryContainer = Color(0xFF43465C), onSecondaryContainer = Color(0xFFDAEAFF),
        background = Color(0xFF121212), surface = Color(0xFF1C1C1E), surfaceVariant = Color(0xFF2C2C2E), surfaceContainerHigh = Color(0xFF2C2C2E), outline = Color(0xFF3A3A3C), onSurface = Color.White, onSurfaceVariant = Color(0xFF98989D))
    else lightColorScheme(primary = Color(0xFF2F71C9), onPrimary = Color.White, primaryContainer = Color(0xFFD9E7FB), onPrimaryContainer = Color(0xFF43465C),
        secondaryContainer = Color(0xFFD9E7FB), onSecondaryContainer = Color(0xFF43465C), background = Color(0xFFFAF9F6), surface = Color.White,
        surfaceVariant = Color(0xFFF5F5F4), surfaceContainerHigh = Color(0xFFF5F5F4), outline = Color(0xFFE7E5E4), onSurface = Color(0xFF1C1917), onSurfaceVariant = Color(0xFF78716C))
    fun select(name: String) { selected = name; app.action(showBusy = false) { app.data.saveSettings(app.data.loadSettings().put("selected", name)) } }
    LaunchedEffect(busy, dataRevision) { if (!busy && selected !in app.repository.names()) selected = app.repository.names().first() }
    LaunchedEffect(dataRevision) {
        val settings = app.data.loadSettings()
        selected = settings.optString("selected").takeIf { it in app.repository.names() } ?: app.repository.names().first()
        NeoLocale.language = NeoLocale.resolve(settings.optString("language", "system"))
    }
    LaunchedEffect(message) { if (message.startsWith("失败") || message.contains("备份已导出") || message.contains("备份已恢复")) notice = message }
    MaterialTheme(colorScheme = colors, typography = BaahTypography) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
            if (!fullscreen && (page == null || page == "configs")) NavigationBar(containerColor = colors.background) {
                val icons = listOf(Icons.Outlined.Home, Icons.Outlined.List, Icons.Outlined.Schedule, Icons.Outlined.Settings)
                listOf("挂机", "BAAH", "定时", "设置").forEachIndexed { i, title -> NavigationBarItem(tab == i, { tab = i; page = null }, icon = { Icon(icons[i], UiStrings.text(title)) }, label = { Text(title) }) }
            }
        }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
            if (page != null) BackHandler { page = null }
            if (baahVisited || tab == 1) {
                val visible = tab == 1 && page == null
                Layout(content = { Column(Modifier.safeDrawingPadding()) {
                    key(selected, dataRevision) { BaahConfigScreen(app.repository, selected, onManageConfigs = { page = "configs" }) }
                } }, modifier = if (visible) Modifier else Modifier.clipToBounds().clearAndSetSemantics {}) { children, constraints ->
                    val child = children.single().measure(constraints)
                    layout(if (visible) child.width else 0, if (visible) child.height else 0) { child.placeRelativeWithLayer(0, 0) { alpha = if (visible) 1f else 0f } }
                }
            }
            when {
                page == "configs" -> Column(Modifier.safeDrawingPadding()) {
                    BaahConfigManagerScreen(app.repository, selected, ::select, { page = null },
                        onRename = { from, to -> app.renameConfig(from, to); selected = to },
                        onImported = { app.dataRevision.value++ })
                }
                page == "notifications" -> NotificationPage(app) { page = null }
                tab == 0 -> BaahHangarScreen(app, selected, ::select, { quickAnchor = it; quick = true }, { tool = "config_quick_call_task" }, { tool = "setting_explore" }, { fullscreen = it }, nextSchedule, showBusy = busy && showProgress)
                tab == 1 -> Unit
                tab == 2 -> NeoSchedules(app, selected)
                else -> NeoSettings(app, export, restore, busy && showProgress, { page = "notifications" }, { revision++ })
            }
        } }
        if (busy && showProgress) BaahDialog(onDismissRequest = {}, title = { Text("正在处理") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (message.isNotBlank()) Text(message)
                operationPercent?.let { percent ->
                    Text("$percent%")
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
            }
        }, dismissButton = { TextButton({ app.requestStop(release = true) }) { Text("取消") } })
        if (!busy && !app.runtime.ready()) BaahDialog(onDismissRequest = {}, title = { Text("首次初始化") },
            text = { Text("准备Python环境并下载上游BAAH。") },
            confirmButton = { TextButton({ app.action { app.prepareEnvironment() } }) { Text("开始初始化") } })
        else if (!busy) {
            val prompt = when (permission) {
                ControlConnection.PermissionState.Request -> "请授予 BAAH NEO Shizuku 权限。" to "授权"
                ControlConnection.PermissionState.OpenManager -> "请在 Shizuku 中启动服务，或在已授权应用中允许 BAAH NEO。" to "打开 Shizuku"
                ControlConnection.PermissionState.Install -> "请先下载并安装 Shizuku。" to "下载 Shizuku"
                else -> null
            }
            if (prompt != null) BaahDialog(
                onDismissRequest = {},
                title = { Text("Shizuku 权限") },
                text = { Text(prompt.first) },
                confirmButton = {
                    TextButton({ runCatching { app.control.requestPermission() }.onFailure { notice = it.message } }) {
                        Text(prompt.second)
                    }
                }
            )
        }
        if (quick) NeoQuickOptions(app, quickAnchor) { quick = false }
        backupPreview?.let { backup -> BaahDialog(onDismissRequest = cancelRestore, title = { Text("恢复完整备份") }, text = { Text(backup.summary()) },
            confirmButton = { TextButton(confirmRestore) { Text("恢复") } }, dismissButton = { TextButton(cancelRestore) { Text("取消") } }) }
        tool?.let { BaahToolsDialog(it, app.repository, selected, app) { tool = null } }
        notice?.let { BaahDialog(onDismissRequest = { notice = null }, text = { Text(it) }, confirmButton = { TextButton({ notice = null }) { Text("确定") } }) }
    }
}

@Composable private fun NeoSettings(app: NeoApp, export: () -> Unit, restore: () -> Unit, showBusy: Boolean, notifications: () -> Unit, changed: () -> Unit) {
    val busy by app.busy.collectAsState(); val running by app.running.collectAsState()
    var revision by remember { mutableIntStateOf(0) }; val settings = remember(revision) { app.data.loadSettings() }
    var result by remember { mutableStateOf<String?>(null) }
    var updateReady by remember { mutableStateOf<String?>(null) }
    var appUpdateReady by remember { mutableStateOf<NeoAppRelease?>(null) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var rawLog by remember { mutableStateOf<String?>(null) }
    var logTitle by remember { mutableStateOf("启动器日志") }
    var logFiles by remember { mutableStateOf<List<java.io.File>?>(null) }
    var exportBaah by remember { mutableStateOf(true) }
    val availableUpdate by app.availableUpdate.collectAsState()
    val logStore = remember { BaahLogs(app) }
    val logExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { app.action { logStore.export(it, exportBaah); result = "日志已导出" } } }
    fun option(key: String, value: Any) { app.action(showBusy = false) { app.data.saveSettings(app.data.loadSettings().put(key, value)); revision++; changed() } }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设置", fontSize = 30.sp, modifier = Modifier.padding(bottom = 12.dp))
        Text("显示设置", fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
        BaahCard(Modifier.fillMaxWidth()) {
            Text("主题", fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("System" to "跟随系统", "Light" to "浅色", "Dark" to "深色").forEach { (key, title) ->
                FilterChip(settings.optString("theme", "System") == key, { option("theme", key) }, { Text(title, fontSize = 13.sp) }, modifier = Modifier.height(40.dp))
            } }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("语言", fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "zh" to "简体中文", "en" to "English").forEach { (key, title) ->
                    FilterChip(settings.optString("language", "system") == key, {
                        if (running) result = "运行期间不可切换语言" else {
                            option("language", key)
                            NeoLocale.language = if (key == "system") java.util.Locale.getDefault().language.let { if (it.startsWith("en")) "en" else "zh" } else key
                        }
                    }, { Text(title, fontSize = 13.sp) }, modifier = Modifier.height(40.dp))
                }
            }
            Text("切换语言会重新加载界面，运行期间不可切换", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("更新管理", fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
        BaahCard(Modifier.fillMaxWidth()) {
            SettingsAction("重新初始化资源", "从内置资源包重新解压，保留用户配置", !busy && !running, dimmed = showBusy || running) { app.action { app.closeSession(); app.prepareEnvironment(true); result = "运行环境已重新初始化" } }
            HorizontalDivider()
            Toggle("启动时检查更新", settings.optBoolean("checkOnStart")) { option("checkOnStart", it) }
            Text("启动应用时自动检查 BAAH 更新", fontSize = 12.sp)
            HorizontalDivider()
            Toggle("自动下载更新", settings.optBoolean("autoDownload")) { option("autoDownload", it) }
            Text("仅用于有发布地址的 BAAH 更新", fontSize = 12.sp)
            HorizontalDivider()
            SettingsAction("检查软件更新", "当前 BAAH NEO：${BuildConfig.VERSION_NAME}", !busy, dimmed = showBusy) { app.action {
                val release = NeoAppUpdates.latest(settings.optBoolean("appBeta"))
                if (NeoAppUpdates.compareVersions(release.tag, BuildConfig.VERSION_NAME) > 0) appUpdateReady = release
                else result = "当前已是最新软件版本：${BuildConfig.VERSION_NAME}"
            } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!settings.optBoolean("appBeta"), { option("appBeta", false) }, { Text("稳定版", fontSize = 13.sp) }, modifier = Modifier.height(40.dp))
                FilterChip(settings.optBoolean("appBeta"), { option("appBeta", true) }, { Text("公测版", fontSize = 13.sp) }, modifier = Modifier.height(40.dp))
            }
            availableUpdate?.let { Text("发现上游新版本：$it", fontSize = 12.sp) }
            HorizontalDivider()
            SettingsAction("检查 BAAH 更新", "当前 BAAH：${app.repository.installedVersion()}", !busy, dimmed = showBusy) { app.action {
                val tag = app.runtime.releaseTag(settings.optBoolean("beta"))
                val version = tag.removePrefix("BAAH").removePrefix("v")
                if (version == app.repository.installedVersion()) result = "当前已是上游最新版本：$tag" else updateReady = tag
            } }
            Box(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!settings.optBoolean("beta"), { option("beta", false) }, { Text("稳定版", fontSize = 13.sp) }, modifier = Modifier.height(40.dp))
                    FilterChip(settings.optBoolean("beta"), { option("beta", true) }, { Text("公测版", fontSize = 13.sp) }, modifier = Modifier.height(40.dp))
                }
                EditionUpdateExtras(app, running, busy)
            }
        }
        Text("数据管理", fontSize = 18.sp)
        BaahCard(Modifier.fillMaxWidth()) {
            SettingsAction("导出配置", "导出应用设置和任务配置", !busy && !running, dimmed = showBusy || running, action = export)
            SettingsAction("导入配置", UiStrings.text("恢复 %s 的完整备份").format(app.applicationInfo.loadLabel(app.packageManager)), !busy && !running, dimmed = showBusy || running, action = restore)
        }
        Text("通知", fontSize = 18.sp)
        BaahCard(Modifier.fillMaxWidth()) { SettingsAction("通知设置", "管理内部和外部通知渠道", action = notifications) }
        Text("日志", fontSize = 18.sp)
        BaahCard(Modifier.fillMaxWidth()) {
            SettingsAction("启动器日志", "查看启动器运行日志与崩溃记录") { logTitle = "启动器日志"; logFiles = logStore.files(false) }
            SettingsAction("导出启动器日志", "导出运行环境与启动器日志") { exportBaah = false; logExporter.launch("BAAH-NEO-host-logs.zip") }
            SettingsAction("BAAH 日志", "查看 BAAH 任务日志") { logTitle = "BAAH 日志"; logFiles = logStore.files(true) }
            SettingsAction("导出 BAAH 日志", "导出最近 7 天的 BAAH 日志") { exportBaah = true; logExporter.launch("BAAH-NEO-task-logs.zip") }
            Toggle("自动清理日志", settings.optBoolean("cleanOldLogs", true)) { option("cleanOldLogs", it) }
            Text("冷启动时删除 7 天前的旧日志", fontSize = 12.sp)
        }
        Text("关于", fontSize = 18.sp)
        BaahCard(Modifier.fillMaxWidth()) {
            Text("${app.applicationInfo.loadLabel(app.packageManager)} ${BuildConfig.VERSION_NAME}", fontSize = 16.sp)
            Text("开源致谢 · BAAH-AOS、BAAH、MaaFramework", fontSize = 12.sp)
        }
    }
    appUpdateReady?.let { release ->
        val download = release.apkUrl.takeUnless { BuildConfig.PRIVATE_EDITION }
        BaahDialog(onDismissRequest = { appUpdateReady = null }, title = { Text(release.tag) },
            text = { Text(if (BuildConfig.PRIVATE_EDITION) "发现 GitHub 新版本。当前为自用构建，请使用同包名的安装包更新。" else "发现 GitHub 新版本。") },
            confirmButton = { TextButton({
                appUpdateReady = null
                runCatching { uriHandler.openUri(download ?: release.pageUrl) }
                    .onFailure { result = "无法打开 GitHub 发布页面" }
            }) { Text(if (download == null) "查看发布" else "下载 APK") } },
            dismissButton = { TextButton({ appUpdateReady = null }) { Text("取消") } })
    }
    updateReady?.let { tag -> BaahDialog(onDismissRequest = { updateReady = null }, title = { Text(tag) }, text = { Text("下载并安装此版本？用户配置将保留。") },
        confirmButton = { TextButton(enabled = !busy && !running, onClick = { updateReady = null; app.action { app.closeSession(); app.runtime.update(settings.optBoolean("beta"), tag); app.availableUpdate.value = null; result = editionUpdateMessage } }) { Text("更新") } }, dismissButton = { TextButton({ updateReady = null }) { Text("取消") } }) }
    logFiles?.let { files -> BaahDialog(onDismissRequest = { logFiles = null }, title = { Text("选择日志") }, text = { Column {
        if (files.isEmpty()) Text("目前没有日志")
        files.forEach { file -> TextButton({ logFiles = null; app.action(showBusy = false) { rawLog = logStore.read(file) } }) { Text(file.name) } }
    } }, confirmButton = { TextButton({ logFiles = null }) { Text("关闭") } }) }
    rawLog?.let { body -> BaahDialog(onDismissRequest = { rawLog = null }, title = { Text(logTitle) },
        text = { androidx.compose.material3.Text(body) }, confirmButton = { TextButton({ rawLog = null }) { Text("关闭") } }) }
    result?.let { BaahDialog(onDismissRequest = { result = null }, text = { Text(it) }, confirmButton = { TextButton({ result = null }) { Text("确定") } }) }
}

@Composable internal fun SettingsAction(title: String, detail: String, enabled: Boolean = true, dimmed: Boolean = !enabled, action: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 18.sp, color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun Toggle(title: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); BaahSwitch(checked, change) }
}

@Composable private fun NeoQuickOptions(app: NeoApp, anchor: androidx.compose.ui.geometry.Rect?, dismiss: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }; val options = remember(revision) { app.data.loadSettings() }
    fun set(key: String, value: Any) { app.action(showBusy = false) { app.data.saveSettings(app.data.loadSettings().put(key, value)); revision++ } }
    var confirmHardware by remember { mutableStateOf(false) }
    val content: @Composable () -> Unit = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("快捷操作")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { app.action { app.sleepScreen(); dismiss() } }, Modifier.weight(1f)) { Text("熄屏挂机") }
            OutlinedButton(onClick = { app.action { app.closeGame(); dismiss() } }, Modifier.weight(1f)) { Text("关闭游戏") }
        }
        OutlinedButton(onClick = { app.action { app.muteGame(); dismiss() } }, Modifier.fillMaxWidth()) { Text("关闭游戏声音") }
        HorizontalDivider(); Text("自动设置")
        Toggle("游戏启动时关闭游戏声音", options.optBoolean("muteOnStart")) { set("muteOnStart", it) }
        Toggle("任务自动结束时关闭游戏", options.optBoolean("closeAfter")) { set("closeAfter", it) }
        Toggle("显示触摸预览", options.optBoolean("touchPreview", true)) { set("touchPreview", it) }
        Toggle("限制单次运行时长", options.optBoolean("limitEnabled")) { set("limitEnabled", it) }
        Toggle("熄屏挂机时关闭屏幕", options.optBoolean("hardwareScreenOff")) { if (it) confirmHardware = true else set("hardwareScreenOff", false) }
        if (options.optBoolean("limitEnabled")) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val minutes = options.optInt("limitMinutes", 120)
            TextButton(onClick = { set("limitMinutes", (minutes - 30).coerceAtLeast(30)) }) { Text("−") }; Text("$minutes 分钟")
            TextButton(onClick = { set("limitMinutes", minutes + 30) }) { Text("＋") }
        }
    } }
    QuickOptionsSurface(anchor, dismiss, content)
    if (confirmHardware) BaahDialog(onDismissRequest = { confirmHardware = false }, title = { Text("开启硬件熄屏") },
        text = { Text("点击熄屏挂机将关闭手机屏幕，游戏继续在虚拟屏中运行。") },
        confirmButton = { TextButton({ confirmHardware = false; set("hardwareScreenOff", true) }) { Text("开启") } },
        dismissButton = { TextButton({ confirmHardware = false }) { Text("取消") } })
}
