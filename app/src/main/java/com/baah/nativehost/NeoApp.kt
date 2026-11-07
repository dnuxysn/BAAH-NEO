package com.baah.nativehost

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.URLEncoder

data class TouchTrace(val x: Float, val y: Float, val at: Long)

class NeoApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val message = MutableStateFlow("就绪")
    val busy = MutableStateFlow(false)
    val operationPercent = MutableStateFlow<Int?>(null)
    val running = MutableStateFlow(false)
    val status = MutableStateFlow(JSONObject())
    val logs = MutableStateFlow("")
    val display = MutableStateFlow(-1)
    val fps = MutableStateFlow(0f)
    val gameAlive = MutableStateFlow(false)
    val screenBlanked = MutableStateFlow(false)
    val dataRevision = MutableStateFlow(0)
    val availableUpdate = MutableStateFlow<String?>(null)
    var progressTasks: List<String> = emptyList()
        private set
    val touchTrace = MutableStateFlow<TouchTrace?>(null)
    @Volatile var lastPreviewFrame: android.graphics.Bitmap? = null
    val repository by lazy { com.baah.nativehost.legacy.BaahConfigRepository(this) }
    lateinit var control: ControlConnection
    lateinit var runtime: RuntimeHost
    lateinit var data: UserData
    private var bridge: AdbBridge? = null
    internal val mainActivityOnTop = MutableStateFlow(false)
    internal val keepScheduleScreenOn = MutableStateFlow(false)
    var controller: FrameworkController? = null
        private set
    private val operations = Mutex()
    private var startedAt = 0L
    private var stopped = false
    private var currentGame = ""
    private var ownsScreenOff = false
        set(value) { field = value; screenBlanked.value = value }
    private var activeAction: Job? = null
    private var notificationKey = ""
    private var scheduleId: String? = null
    private var runGeneration = -1
    @Volatile private var piRun: PiTaskDispatcher.Run? = null
    internal val screenCover by lazy { ScreenCover(this) }
    private var interactiveAtStart = true
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        control = ControlConnection(this); data = UserData(this); runtime = RuntimeHost(this) { text, percent ->
            operationPercent.value = percent
            message.value = text
            if (busy.value && percent != null) RunService.update(this, text, operationPercent = percent)
        }
        com.baah.nativehost.legacy.NeoLocale.language = com.baah.nativehost.legacy.NeoLocale.resolve(data.loadSettings().optString("language", "system"))
        ScheduleReceiver.arm(this)
        scope.launch { UiStrings.load(this@NeoApp); repository.preload(if (com.baah.nativehost.legacy.NeoLocale.language == "en") "en_US" else "zh_CN") }
        scope.launch {
            val settings = data.loadSettings()
            if (settings.optBoolean("cleanOldLogs", true)) {
                val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60_000
                listOf("host-logs", "baah/DATA/LOGS", "rootfs/opt/baah-aos/log").forEach { path ->
                    File(filesDir, path).takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
                }
            }
            if (runtime.ready() && settings.optBoolean("checkOnStart")) runCatching {
                val tag = runtime.releaseTag(settings.optBoolean("beta"))
                val installed = File(filesDir, "upstream-version.txt").takeIf { it.exists() }?.readText()?.trim()
                availableUpdate.value = tag.takeIf { it != installed }
                if (tag != installed && settings.optBoolean("autoDownload")) runtime.cacheRelease(tag)
            }.onFailure { android.util.Log.w("BAAH NEO", "Background release check or download failed", it) }
        }
        scope.launch {
            var lastFrames = -1L; var lastTime = 0L
            while (isActive) {
                if (display.value > 0) {
                    if (running.value && !runtime.alive()) {
                        running.value = false; finishRun("运行环境异常退出")
                        closeSession(); message.value = "失败：Python 运行环境异常退出，请查看启动器日志"
                        continue
                    }
                    if (running.value && control.remote?.asBinder()?.isBinderAlive != true) {
                        runCatching { runtime.request("/stop", true) }
                        running.value = false; finishRun("控制服务中断")
                        closeSession(); message.value = "失败：Shizuku 控制服务已断开，本轮已停止"
                        continue
                    }
                    val snapshot = runCatching { runtime.request("/status") }.getOrNull()
                    if (snapshot != null && (!running.value || snapshot.optInt("runner_generation", -2) == runGeneration)) {
                        status.value = snapshot
                        val lines = snapshot.optJSONArray("log_lines")
                        logs.value = (0 until (lines?.length() ?: 0)).joinToString("\n") { lines!!.getString(it) }
                        logs.value.lineSequence().lastOrNull { it.startsWith("[BAAH-AOS-PROGRESS] ") && it.contains("\"plan\"") }?.let { plan ->
                            runCatching { val tasks = JSONObject(plan.substringAfter("[BAAH-AOS-PROGRESS] ")).getJSONArray("tasks")
                                progressTasks = (0 until tasks.length()).map { tasks.getString(it) }
                            }
                        }
                        val progress = logs.value.lineSequence().lastOrNull { it.startsWith("[BAAH-AOS-PROGRESS] ") }
                        if (running.value && progress != null && progress != notificationKey) {
                            notificationKey = progress
                            runCatching {
                                val event = JSONObject(progress.substringAfter("[BAAH-AOS-PROGRESS] "))
                                val title = event.optString("title", "正在运行")
                                RunService.update(this@NeoApp, title, event.optInt("done"), event.optInt("total"))
                            }
                        }
                        val active = snapshot.optBoolean("runner_wanted") || snapshot.optBoolean("runner_alive")
                        if (running.value && data.loadSettings().let { it.optBoolean("limitEnabled") && System.currentTimeMillis() - startedAt >= it.optInt("limitMinutes", 120).coerceAtLeast(1) * 60_000L }) {
                            stop(); message.value = "达到单次运行时长限制，任务已停止"
                        }
                        if (running.value && !active) operations.withLock {
                            if (!running.value || snapshot.optInt("runner_generation", -2) != runGeneration) return@withLock
                            running.value = false
                            val result = if (stopped) "已停止" else if (snapshot.optInt("runner_exit_code", -1) == 0) "完成" else "异常退出"
                            finishRun(result)
                            message.value = if (result == "异常退出") "本轮异常退出，请查看日志" else "本轮$result"
                        }
                    }
                    runCatching {
                        val stats = JSONObject(control.remote!!.stats())
                        screenBlanked.value = stats.optBoolean("screenOffRequested")
                        gameAlive.value = gameRunning()
                        val now = stats.getLong("timeMs"); val frames = stats.getLong("frames")
                        fps.value = if (lastFrames < 0 || frames < lastFrames) 0f else ((frames - lastFrames) * 1000f / (now - lastTime).coerceAtLeast(1))
                        lastFrames = frames; lastTime = now
                    }.onFailure { fps.value = 0f; gameAlive.value = false }
                }
                delay(2000)
            }
        }
    }
    fun action(showBusy: Boolean = true, block: suspend () -> Unit) {
        scope.launch {
            operations.withLock {
                if (showBusy) {
                    message.value = ""
                    operationPercent.value = null
                    activeAction = currentCoroutineContext()[Job]
                    busy.value = true
                }
                try {
                    block()
                } catch (e: CancellationException) {
                    if (showBusy && operationPercent.value != null) message.value = "操作已取消"
                    throw e
                } catch (e: Exception) {
                    message.value = "失败：${e.message}"
                } finally {
                    if (showBusy) {
                        if (!running.value && operationPercent.value != null) RunService.update(this@NeoApp, message.value, active = false)
                        busy.value = false
                        operationPercent.value = null
                        activeAction = null
                    }
                }
            }
        }
    }
    internal suspend fun renameConfig(oldName: String, newName: String) = withContext(Dispatchers.IO) {
        operations.withLock {
            check(!running.value) { "请先停止任务再重命名配置" }
            repository.rename(oldName, newName) { data.renameConfigReferences(oldName, newName) }
        }
    }
    fun requestStop(release: Boolean = false) {
        val close = release || busy.value
        if (close) activeAction?.cancel()
        action { if (close) closeSession() else stop(); message.value = "任务已停止" }
    }
    suspend fun prepareEnvironment(reinitialize: Boolean = false) {
        startForegroundService(Intent(this, RunService::class.java))
        try { if (reinitialize) runtime.reinitialize() else runtime.prepare() }
        catch (e: Exception) { message.value = "失败：${e.message}"; throw e }
        finally {
            if (display.value < 0) stopService(Intent(this, RunService::class.java))
        }
    }
    suspend fun start(
        config: String,
        task: String? = null,
        fromSchedule: String? = null,
        foreground: Boolean = false
    ) {
        check(!running.value) { "已有任务在运行" }; check(config in data.names()) { "请先选择有效配置" }
        check(control.granted()) { "请先在设置页授予 Shizuku 权限" }
        val piEntry = PiProject.resolve(this, singleTask = task != null)
        piRun?.job?.join()
        repository.prepareForRun(config)
        currentGame = repository.load(config).getString("ACTIVITY_PATH").substringBefore('/')
        interactiveAtStart = getSystemService(android.os.PowerManager::class.java).isInteractive
        startForegroundService(Intent(this, RunService::class.java))
        try {
            runtime.prepare()
            val remote = control.connect()
            if (foreground) {
                keepScheduleScreenOn.value = true
                if (ownsScreenOff) {
                    remote.setScreenPower(true)
                    ownsScreenOff = false
                }
                screenCover.hide()
                // Relaunch the Python host after gaining focus so it inherits the current scheduling group.
                runtime.close()
                if (!mainActivityOnTop.value) {
                    if (!interactiveAtStart) remote.command("input keyevent KEYCODE_WAKEUP")
                    val activity = android.content.ComponentName(this, MainActivity::class.java).flattenToShortString()
                    remote.command("am start --display 0 --activity-reorder-to-front -n $activity")
                    check(withTimeoutOrNull(8_000) { mainActivityOnTop.first { it } } == true) {
                        "无法切到前台，请解锁手机后重试"
                    }
                }
            }
            val id = withContext(Dispatchers.IO) { remote.createDisplay() }
            display.value = id
            if (controller == null) controller = FrameworkController(remote) { x, y -> touchTrace.value = TouchTrace(x, y, android.os.SystemClock.elapsedRealtime()) }
            if (bridge == null) bridge = AdbBridge(::execute)
            runtime.launch(id)
            progressTasks = emptyList()
            RunService.resetProgress()
            stopped = false; startedAt = System.currentTimeMillis()
            scheduleId = fromSchedule
            val startPath = "/start?config=${encode(config)}" + (task?.let { "&task=${encode(it)}" } ?: "")
            val framework = checkNotNull(controller)
            val run = PiTaskDispatcher.launch(scope, this, framework.piControllerHandle, piEntry) {
                runtime.request(startPath, true)
            }
            piRun = run
            runGeneration = run.accepted.await().getInt("runner_generation")
            running.value = true; message.value = "正在启动 $config${task?.let { " / $it" } ?: ""}"
            if (data.loadSettings().optBoolean("muteOnStart")) muteGame()
            data.loadSettings().put("selected", config).let(data::saveSettings)
            if (fromSchedule != null) {
                val settings = data.loadSettings(); val list = settings.optJSONArray("schedules")
                for (i in 0 until (list?.length() ?: 0)) list!!.getJSONObject(i).takeIf { it.optString("id") == fromSchedule }?.put("inProgress", true)?.put("lastResult", "运行中")
                data.saveSettings(settings)
            }
            RunService.update(this, message.value)
        } catch (e: Throwable) { withContext(NonCancellable) { closeSession() }; throw e }
    }
    suspend fun stop() {
        stopped = true
        if (display.value > 0) runCatching { runtime.request("/stop", true) }
        if (running.value) { running.value = false; finishRun("已停止") }
        message.value = "任务已停止"
    }
    suspend fun closeSession() {
        keepScheduleScreenOn.value = false
        if (running.value) stop()
        piRun?.let { run ->
            run.outcome.complete(false)
            run.job.join()
            piRun = null
        }
        runtime.close(); bridge?.close(); bridge = null
        controller?.close(); controller = null
        if (ownsScreenOff) { runCatching { control.remote?.setScreenPower(true) }; ownsScreenOff = false }
        if (currentGame.isNotBlank()) runCatching { control.remote?.mutePackage(currentGame, false) }
        screenCover.hide()
        runCatching { control.release() }; display.value = -1; fps.value = 0f; gameAlive.value = false
        stopService(Intent(this, RunService::class.java))
    }
    suspend fun sleepScreen(hardware: Boolean? = null) {
        check(display.value > 0) { "请先启动游戏" }
        if (hardware ?: data.loadSettings().optBoolean("hardwareScreenOff")) {
            control.connect().setScreenPower(false); ownsScreenOff = true
        } else {
            if (!android.provider.Settings.canDrawOverlays(this)) control.connect().command("cmd appops set $packageName SYSTEM_ALERT_WINDOW allow")
            check(android.provider.Settings.canDrawOverlays(this)) { "请在系统中授予 BAAH NEO 悬浮窗权限" }
            withContext(Dispatchers.Main) { screenCover.show() }
        }
    }
    suspend fun closeGame() {
        check(currentGame.isNotBlank()) { "请先启动游戏" }
        val remote = control.connect()
        remote.command("am force-stop $currentGame"); remote.mutePackage(currentGame, false)
        gameAlive.value = false
    }
    suspend fun muteGame() {
        check(currentGame.isNotBlank()) { "请先启动游戏" }
        control.connect().mutePackage(currentGame, true)
    }
    private fun recordResult(result: String) {
        if (startedAt == 0L) return
        val settings = data.loadSettings().put("lastResult", result).put("lastFinishedAt", System.currentTimeMillis())
            .put("lastMinutes", (System.currentTimeMillis() - startedAt).coerceAtLeast(0) / 60_000.0)
        data.saveSettings(settings); startedAt = 0L
        val schedules = settings.optJSONArray("schedules")
        if (schedules != null) for (i in 0 until schedules.length()) {
            val schedule = schedules.getJSONObject(i)
            if (schedule.optString("id") == scheduleId) {
                schedule.put("inProgress", false).put("lastResult", result)
                    .put("lastMinutes", settings.getDouble("lastMinutes")).put("lastFinishedAt", System.currentTimeMillis())
                UserData.appendScheduleLog(settings, schedule, result)
            }
        }
        data.saveSettings(settings)
    }
    private suspend fun finishRun(result: String) {
        keepScheduleScreenOn.value = false
        piRun?.outcome?.complete(result == "完成")
        val before = data.loadSettings()
        val rules = before.optJSONArray("schedules")
        val rule = (0 until (rules?.length() ?: 0)).map { rules!!.getJSONObject(it) }.firstOrNull { it.optString("id") == scheduleId }
        recordResult(result)
        scheduleId = null
        if (result == "完成" && (before.optBoolean("closeAfter") || rule?.optBoolean("closeAppAfterTask") == true)) runCatching { closeGame() }
        if (rule?.optBoolean("autoSleepAfterTask") == true && result == "完成" &&
            !(rule.optBoolean("skipAutoSleepIfAwake", true) && interactiveAtStart)) runCatching { sleepScreen(hardware = true) }
        if (rule?.optBoolean("screenSaverOnStart") == true) screenCover.hide()
        if (ownsScreenOff && rule?.optBoolean("autoSleepAfterTask") != true) { runCatching { control.remote?.setScreenPower(true) }; ownsScreenOff = false }
        if (!gameAlive.value) stopService(Intent(this, RunService::class.java))
        RunService.update(this, "本轮$result", active = false)
        val manager = com.baah.nativehost.notify.NotificationSettingsManager(this)
        val notifications = manager.json()
        val send = when (result) {
            "完成" -> notifications.optString("sendOnComplete").toBoolean()
            "控制服务中断" -> notifications.optString("sendOnServiceDied").toBoolean()
            "已停止" -> false
            else -> notifications.optString("sendOnError").toBoolean()
        }
        if (send && notifications.optString("enabledProviders").isNotBlank()) scope.launch {
            manager.send("BAAH NEO · $result", if (notifications.optString("includeLogDetails").toBoolean()) logs.value.takeLast(3000) else "本轮任务$result")
        }
    }
    private fun execute(command: String): ByteArray {
        val remote = control.remote ?: error("Shizuku 连接已断开")
        if (command == "baah-control-stats") return remote.stats().toByteArray()
        check(display.value > 0)
        if (command == "baah-input-ready") {
            val ready = gameRunning() && InputReadiness.ready(remote.command("dumpsys input"), display.value, currentGame)
            return (if (ready) "ready\n" else "waiting\n").toByteArray()
        }
        if (command.startsWith("screencap") || command == "baah-screencap-bgr") {
            check(gameRunning()) { "游戏未在 NEO 虚拟屏运行" }
            return if (command == "baah-screencap-bgr") controller!!.captureBgr() else controller!!.capture()
        }
        if (command.startsWith("wm size")) return "Physical size: 1280x720\n".toByteArray()
        if (command.startsWith("wm density")) return "Physical density: 240\n".toByteArray()
        val touch = touchCommand.matchEntire(command)
        if (touch != null) {
            check(gameRunning()) { "游戏未在 NEO 虚拟屏运行" }
            val a = touch.groupValues
            if (a[1] == "tap") controller!!.click(a[2].toInt(), a[3].toInt())
            else controller!!.swipe(a[2].toInt(), a[3].toInt(), a[4].toInt(), a[5].toInt(), a[6].toIntOrNull() ?: 300)
            return byteArrayOf()
        }
        keyCommand.matchEntire(command)?.let {
            check(gameRunning()) { "游戏未在 NEO 虚拟屏运行" }
            controller!!.key(it.groupValues[1].toInt()); return byteArrayOf()
        }
        check(!command.startsWith("input ")) { "不支持的输入命令；没有转发到主屏" }
        return remote.command(command).toByteArray()
    }
    private var presenceAt = 0L
    private var presenceDisplay = -1
    private var presenceResult = false
    @Synchronized fun gameRunning(): Boolean {
        val remote = control.remote ?: return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (presenceDisplay == display.value && now - presenceAt < 250) return presenceResult
        val output = remote.command("dumpsys activity activities")
        var target = false
        for (line in output.lineSequence()) {
            displayLine.find(line)?.let { target = it.groupValues[1].toInt() == display.value }
            if (line.trimStart().startsWith("Display: mDisplayId=")) target = false
            if (target && (line.contains("topResumedActivity=") || line.contains("mResumedActivity=")) &&
                listOf("com.YostarJP.BlueArchive", "com.RoamingStar.BlueArchive", "com.nexon.bluearchive").any(line::contains)) {
                presenceAt = now; presenceDisplay = display.value; presenceResult = true
                return true
            }
        }
        presenceAt = now; presenceDisplay = display.value; presenceResult = false
        return false
    }
    companion object {
        private val touchCommand = Regex("^input (?:-d \\d+ )?(tap|swipe) (\\d+) (\\d+)(?: (\\d+) (\\d+)(?: (\\d+))?)?$")
        private val keyCommand = Regex("^input (?:-d \\d+ )?keyevent (\\d+)$")
        private val displayLine = Regex("^\\s*Display #(\\d+)")
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    }
}
