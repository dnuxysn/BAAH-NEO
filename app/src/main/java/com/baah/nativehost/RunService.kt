package com.baah.nativehost

import android.app.*
import android.content.*
import android.os.*
import kotlinx.coroutines.launch

class RunService : Service() {
    private var wake: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("automation", "挂机运行", NotificationManager.IMPORTANCE_LOW))
        startForeground(402, notification(this, "BAAH NEO 正在准备运行"))
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BAAHNEO:run").apply { acquire() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        val app = application as NeoApp
        if (intent?.action == "stop") app.requestStop(release = true)
        if (intent?.action == "schedule") {
            val triggeredConfig = intent.getStringExtra("config") ?: return START_NOT_STICKY
            app.action {
                val id = intent.getStringExtra("scheduleId")
                try {
                    val rules = app.data.loadSettings().optJSONArray("schedules")
                    val rule = (0 until (rules?.length() ?: 0)).map { rules!!.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
                    if (app.running.value && rule?.optBoolean("forceStart") == true) app.stop()
                    val backgroundMode = rule?.optBoolean("backgroundMode", true) ?: true
                    val config = rule?.optString("config", rule.optString("runConfigurationId", triggeredConfig)) ?: triggeredConfig
                    app.start(config, fromSchedule = id, foreground = !backgroundMode)
                    if (backgroundMode && rule?.optBoolean("screenSaverOnStart") == true) app.sleepScreen(hardware = false)
                } catch (e: Exception) {
                    val settings = app.data.loadSettings(); val list = settings.optJSONArray("schedules")
                    for (i in 0 until (list?.length() ?: 0)) list!!.getJSONObject(i).takeIf { it.optString("id") == id }?.put("lastResult", "启动失败：${e.message}")?.put("inProgress", false)
                    app.data.saveSettings(settings)
                    update(this@RunService, "定时启动失败：${e.message}"); throw e
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() { wake?.takeIf { it.isHeld }?.release(); super.onDestroy() }
    companion object {
        const val CHANNEL = "automation"
        const val RESULT_CHANNEL = "results"
        private var progressDone = 0
        private var progressTotal = 0
        fun resetProgress() { progressDone = 0; progressTotal = 0 }
        fun update(context: Context, text: String, done: Int = -1, total: Int = -1, active: Boolean = true, operationPercent: Int? = null) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val app = context.applicationContext as NeoApp
            val enabled = com.baah.nativehost.notify.NotificationSettingsManager(context).json().optString("internalEnabled", "true").toBoolean()
            if (!enabled) {
                if (!app.running.value && !app.busy.value && !app.gameAlive.value) manager.cancel(402)
                return
            }
            manager.createNotificationChannel(NotificationChannel("automation", "挂机运行", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(RESULT_CHANNEL, "任务结果", NotificationManager.IMPORTANCE_HIGH))
            manager.notify(402, notification(context, text, done, total, active, operationPercent))
        }
        private fun notification(context: Context, text: String, done: Int = -1, total: Int = -1, active: Boolean = true, operationPercent: Int? = null): Notification {
            val stop = PendingIntent.getService(context, 1, Intent(context, RunService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
            val options = com.baah.nativehost.notify.NotificationSettingsManager(context).json()
            val app = context.applicationContext as NeoApp
            val phase = NotificationPhase.resolve(text, active, app.busy.value)
            if (done >= 0) progressDone = done
            if (total >= 0) progressTotal = total
            val taskTotal = progressTotal.takeIf { it > 0 } ?: app.progressTasks.size
            val taskPercent = if (taskTotal > 0 && (app.running.value || total > 0)) {
                ((100L * progressDone.coerceIn(0, taskTotal)) / taskTotal).toInt()
            } else null
            val percent = if (active) operationPercent ?: taskPercent else null
            val state = BaahProgress(phase, "BAAH NEO", UiStrings.text(text), percent)
            val result = buildProgressNotification(context, state, app.gameAlive.value,
                if (!options.optString("internalEnabled", "true").toBoolean()) EventNotificationLevel.OFF else if (options.optString("popup", "false").toBoolean()) EventNotificationLevel.HIGH else EventNotificationLevel.DEFAULT, alert = !active)
            return Notification.Builder.recoverBuilder(context, result).addAction(Notification.Action.Builder(null, UiStrings.text("停止并关闭"), stop).build()).build()
        }
    }
}
