package com.baah.nativehost

import android.app.*
import android.content.*
import org.json.JSONObject
import java.util.Calendar
import java.time.*

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as NeoApp
        app.data.updateSettings { settings ->
            val list = settings.optJSONArray("schedules")
            if (intent.action == "run") {
                val id = intent.getStringExtra("id")
                for (i in 0 until (list?.length() ?: 0)) {
                    val entry = list!!.optJSONObject(i) ?: continue
                    if (entry.optString("id") != id || !entry.optBoolean("enabled")) continue
                    val expected = intent.getLongExtra("expected", 0)
                    if (expected <= 0 || expected > System.currentTimeMillis() + 30_000 ||
                        expected <= entry.optLong("lastTriggeredAt")) continue
                    entry.put("lastTriggeredAt", expected).put("lastAttemptAt", System.currentTimeMillis())
                    entry.remove("registeredFor")
                    if ((app.running.value || app.busy.value) && !entry.optBoolean("forceStart")) entry.put("lastResult", "跳过：已有任务运行")
                    else {
                        entry.put("lastResult", "正在启动")
                        try { context.startForegroundService(Intent(context, RunService::class.java).setAction("schedule")
                            .putExtra("config", entry.optString("config", entry.optString("runConfigurationId"))).putExtra("scheduleId", id)) }
                        catch (e: Exception) { entry.put("lastResult", "启动失败：${e.message}").put("inProgress", false) }
                    }
                    UserData.appendScheduleLog(settings, entry, entry.optString("lastResult"))
                }
            } else if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                for (i in 0 until (list?.length() ?: 0)) {
                    val entry = list!!.optJSONObject(i) ?: continue
                    if (entry.optBoolean("inProgress")) entry.put("lastResult", if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "升级中断" else "重启中断").put("inProgress", false)
                }
            }
        }
        arm(context)
        android.util.Log.i("NeoSchedule", "Rearmed after ${intent.action}")
    }
    companion object {
        fun cancelAll(context: Context) {
            val rules = UserData(context).loadSettings().optJSONArray("schedules") ?: return
            val manager = context.getSystemService(AlarmManager::class.java)
            for (i in 0 until rules.length()) {
                val id = rules.getJSONObject(i).getString("id")
                val pending = PendingIntent.getBroadcast(context, id.hashCode(), Intent(context, ScheduleReceiver::class.java).setAction("run").putExtra("id", id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                if (pending != null) manager.cancel(pending)
            }
        }
        fun arm(context: Context) {
            val manager = context.getSystemService(AlarmManager::class.java)
            val store = UserData(context)
            store.updateSettings { settings ->
                val rules = settings.optJSONArray("schedules") ?: return@updateSettings
                val now = System.currentTimeMillis()
                val exact = android.os.Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
                for (i in 0 until rules.length()) {
                    val entry = rules.optJSONObject(i) ?: continue
                    val id = entry.optString("id")
                    var pending: PendingIntent? = null
                    try {
                        require(id.isNotBlank()) { "定时规则缺少 ID" }
                        val registered = entry.optLong("registeredFor", 0)
                        val overdue = registered > entry.optLong("lastTriggeredAt") && registered <= now
                        if (overdue && now - registered > 15 * 60_000 && !entry.optBoolean("inProgress")) {
                            lastSuccessfulRunAt(entry, settings.optJSONArray("scheduleLog"))?.let { entry.put("lastSuccessfulAt", it) }
                            entry.put("lastMissedAt", registered).put("lastResult", "未触发：错过计划时间")
                            UserData.appendScheduleLog(settings, entry, entry.getString("lastResult"))
                            entry.remove("registeredFor")
                        }
                        val expected = if (overdue && now - registered <= 15 * 60_000) registered else next(entry, now)
                        val intent = Intent(context, ScheduleReceiver::class.java).setAction("run")
                            .putExtra("id", id).putExtra("expected", expected ?: 0)
                        pending = PendingIntent.getBroadcast(context, id.hashCode(), intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        if (!entry.optBoolean("enabled") || expected == null) {
                            manager.cancel(pending)
                            entry.remove("registeredFor")
                            continue
                        }
                        check(exact) { "请允许精确闹钟后再启用定时" }
                        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(expected, now + 1000), pending)
                        entry.put("registeredFor", expected).remove("alarmRegistrationError")
                        android.util.Log.i("NeoSchedule", "Registered $id expected=$expected")
                    } catch (error: Exception) {
                        pending?.let { alarm -> runCatching { manager.cancel(alarm) } }
                        entry.remove("registeredFor")
                        entry.put("alarmRegistrationError", error.message ?: "系统闹钟登记失败")
                        android.util.Log.e("NeoSchedule", "Registration failed for $id", error)
                    }
                }
            }
        }
        fun next(entry: JSONObject, now: Long = System.currentTimeMillis()): Long? {
            val once = entry.optLong("oneShotAt", 0)
            if (once > 0) return once.takeIf { it > now && it > entry.optLong("lastTriggeredAt", 0) }
            if (entry.optString("scheduleType") == "INTERVAL") {
                val interval = ((entry.optInt("intervalDays") * 24L + entry.optInt("intervalHours")) * 60 + entry.optInt("intervalMinutes")) * 60_000
                return ScheduleRule(emptySet(), emptyList(), entry.optLong("startTimeMs", now), interval).next(now)
            }
            val days = entry.optJSONArray("daysOfWeek")
            val times = entry.optJSONArray("executionTimes")
            val selectedDays = if (days == null) DayOfWeek.entries.toSet() else (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet()
            val selectedTimes = if (times == null) listOf(LocalTime.parse(entry.optString("time", "08:00"))) else (0 until times.length()).map { LocalTime.parse(times.getString(it)) }
            return ScheduleRule(selectedDays, selectedTimes).next(now)
        }
    }
}
