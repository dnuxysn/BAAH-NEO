package com.baah.nativehost

import android.app.*
import android.content.*
import org.json.JSONObject
import java.util.Calendar
import java.time.*

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as NeoApp
        val settings = app.data.loadSettings()
        val list = settings.optJSONArray("schedules")
        if (intent.action == "run") {
            val id = intent.getStringExtra("id")
            for (i in 0 until (list?.length() ?: 0)) {
                val entry = list!!.getJSONObject(i)
                if (entry.optString("id") != id || !entry.optBoolean("enabled")) continue
                val expected = intent.getLongExtra("expected", 0)
                if (expected <= entry.optLong("lastTriggeredAt")) continue
                entry.put("lastTriggeredAt", expected)
                if ((app.running.value || app.busy.value) && !entry.optBoolean("forceStart")) entry.put("lastResult", "跳过：已有任务运行")
                else {
                    entry.put("lastResult", "正在启动")
                    try { context.startForegroundService(Intent(context, RunService::class.java).setAction("schedule")
                        .putExtra("config", entry.optString("config", entry.optString("runConfigurationId"))).putExtra("scheduleId", id)) }
                    catch (e: Exception) { entry.put("lastResult", "启动失败：${e.message}").put("inProgress", false) }
                }
                UserData.appendScheduleLog(settings, entry, entry.optString("lastResult"))
            }
        } else {
            for (i in 0 until (list?.length() ?: 0)) {
                val entry = list!!.getJSONObject(i)
                if (entry.optBoolean("inProgress")) entry.put("lastResult", if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "升级中断" else "重启中断").put("inProgress", false)
            }
        }
        app.data.saveSettings(settings)
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
            val data = UserData(context).loadSettings().optJSONArray("schedules")
            for (i in 0 until (data?.length() ?: 0)) {
                val entry = data!!.getJSONObject(i)
                val alarm = PendingIntent.getBroadcast(context, entry.getString("id").hashCode(), Intent(context, ScheduleReceiver::class.java)
                    .setAction("run").putExtra("id", entry.getString("id")).putExtra("expected", next(entry) ?: 0), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.cancel(alarm)
                if (!entry.optBoolean("enabled")) continue
                val next = next(entry) ?: continue
                if (android.os.Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm)
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm)
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
