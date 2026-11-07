package com.baah.nativehost

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.*

class UserData(private val app: Context) {
    private val configs get() = File(app.filesDir, "baah/BAAH_CONFIGS").apply { mkdirs() }
    val settings get() = File(app.filesDir, "neo-settings.json")
    fun names() = configs.listFiles().orEmpty().filter { it.isFile && it.extension == "json" && validName(it.nameWithoutExtension) }.map { it.nameWithoutExtension }.sorted()
    fun read(name: String): JSONObject { require(validName(name)); return JSONObject(File(configs, "$name.json").readText()) }
    fun save(name: String, json: JSONObject) { require(validName(name)); atomic(File(configs, "$name.json"), json.toString(2)) }
    fun loadSettings() = if (settings.exists()) JSONObject(settings.readText()) else JSONObject().put("schedules", JSONArray())
    fun saveSettings(value: JSONObject) { atomic(settings, value.toString(2)); settingsRevision.value++ }
    internal fun renameConfigReferences(oldName: String, newName: String) {
        val updated = loadSettings().put("selected", newName)
        val schedules = updated.optJSONArray("schedules")
        for (i in 0 until (schedules?.length() ?: 0)) {
            val rule = schedules!!.getJSONObject(i)
            for (key in listOf("config", "runConfigurationId")) {
                if (rule.optString(key) == oldName) rule.put(key, newName)
            }
        }
        saveSettings(updated)
    }
    fun export(uri: Uri) { app.contentResolver.openOutputStream(uri, "wt")!!.use { writeBackup(it) } }
    private fun writeBackup(out: OutputStream) = ZipOutputStream(out).use { zip ->
        zip.putNextEntry(ZipEntry("neo-backup.json")); zip.write("{\"format\":1,\"product\":\"BAAH NEO\"}".toByteArray()); zip.closeEntry()
        listOf("baah", "baah-user", "neo-settings.json", "upstream-version.txt").forEach { path ->
            val file = File(app.filesDir, path)
            if (file.exists()) file.walkTopDown().filter { it.isFile }.forEach { child ->
                val relative = child.relativeTo(app.filesDir).path.replace('\\', '/')
                zip.putNextEntry(ZipEntry(relative)); child.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
    fun restore(uri: Uri) {
        val stage = File(app.filesDir, "restore-${System.nanoTime()}").apply { mkdirs() }
        val rollback = File(app.filesDir, "before-${stage.name}")
        var discardRollback = false
        try {
            val seen = mutableSetOf<String>()
            app.contentResolver.openInputStream(uri)!!.use { source -> ZipInputStream(source).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(seen.add(entry.name)) { "备份含重复路径" }
                    require(entry.name.substringBefore('/') in setOf("neo-backup.json", "baah", "baah-user", "neo-settings.json", "upstream-version.txt", "settings.json", "files")) { "不支持的备份内容" }
                    val target = RuntimeHost.child(stage, entry.name)
                    if (entry.isDirectory) target.mkdirs() else { target.parentFile!!.mkdirs(); target.outputStream().use { zip.copyTo(it) } }
                }
            } }
            if (!File(stage, "neo-backup.json").exists() && File(stage, "settings.json").exists()) convertLegacy(stage)
            check(JSONObject(File(stage, "neo-backup.json").readText()).getInt("format") == 1) { "备份格式不兼容" }
            File(stage, "baah/BAAH_CONFIGS").listFiles().orEmpty().filter { it.extension == "json" }.forEach { JSONObject(it.readText()) }
            File(stage, "neo-settings.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) }
            val paths = listOf("baah", "baah-user", "neo-settings.json", "upstream-version.txt")
            val moved = mutableListOf<String>(); val installed = mutableListOf<String>()
            check(rollback.mkdir()) { "无法创建导入临时目录" }
            discardRollback = true
            ScheduleReceiver.cancelAll(app)
            try {
                paths.forEach { path ->
                    val current = File(app.filesDir, path)
                    if (current.exists()) { check(current.renameTo(File(rollback, path))); moved += path }
                    File(stage, path).takeIf { it.exists() }?.let { check(it.renameTo(current)); installed += path }
                }
            } catch (e: Throwable) {
                discardRollback = false
                installed.forEach {
                    check(File(app.filesDir, it).deleteRecursively()) { "无法移除未完成导入的数据：$it" }
                }
                moved.forEach { check(File(rollback, it).renameTo(File(app.filesDir, it))) { "恢复回滚失败：$it" } }
                discardRollback = true
                ScheduleReceiver.arm(app)
                throw e
            }
        } finally {
            stage.deleteRecursively()
            if (discardRollback) rollback.deleteRecursively()
        }
    }
    private fun convertLegacy(stage: File) {
        val snapshot = JSONObject(File(stage, "settings.json").readText())
        require(snapshot.optString("format") == "BAAH-AOS-user-data" && snapshot.optInt("version") == 1) { "不支持的 BAAH-AOS 备份版本" }
        val sourceConfigs = File(stage, "files/BAAH_CONFIGS")
        check(sourceConfigs.isDirectory) { "旧包备份缺少运行配置" }
        // Stage a complete NEO tree; user data overlays the currently installed upstream.
        val upstream = File(app.filesDir, "baah")
        if (upstream.exists()) upstream.copyRecursively(File(stage, "baah"), true)
        val roots = mapOf("BAAH_CONFIGS" to "baah/BAAH_CONFIGS", "baah-user" to "baah-user",
            "DATA_CONFIGS" to "baah/DATA/CONFIGS", "USER_DEFINED" to "baah/DATA/USER_DEFINED", "USER_STORAGE" to "baah/DATA/USER_STORAGE")
        roots.forEach { (old, target) -> File(stage, "files/$old").takeIf { it.exists() }?.copyRecursively(File(stage, target), true) }
        val oldBaah = snapshot.optJSONObject("baah") ?: JSONObject()
        val oldApp = snapshot.optJSONObject("app") ?: JSONObject()
        val settings = JSONObject().put("legacySnapshot", JSONObject(snapshot.toString())).put("notifications", snapshot.optJSONObject("notifications") ?: JSONObject())
            .put("schedules", snapshot.optJSONArray("schedules") ?: JSONArray()).put("selected", oldBaah.optString("selectedConfig", "default"))
            .put("theme", snapshot.optJSONObject("ui")?.optString("themeMode", "System") ?: "System")
            .put("muteOnStart", oldBaah.optString("muteOnStart").toBoolean()).put("cleanOldLogs", oldBaah.optString("cleanOldLogs", "true").toBoolean())
            .put("closeAfter", oldApp.optString("closeAppAfterTask").toBoolean()).put("touchPreview", oldApp.optString("touchPreviewEnabled", "true").toBoolean())
            .put("limitEnabled", oldApp.optString("runDurationLimitEnabled").toBoolean()).put("limitMinutes", oldApp.optString("runDurationLimitMinutes", "120").toIntOrNull() ?: 120)
            .put("checkOnStart", oldApp.optString("autoCheckUpdate").toBoolean()).put("autoDownload", oldApp.optString("autoDownloadUpdate").toBoolean())
            .put("beta", oldBaah.optString("baahUpdateChannel").equals("BETA", true)).put("appBeta", oldApp.optString("updateChannel").equals("BETA", true))
            .put("hardwareScreenOff", oldBaah.optString("hardwareScreenOff").toBoolean())
            .put("language", snapshot.optString("locale").takeIf { it.isNotBlank() && it != "null" }?.let { if (it.startsWith("en")) "en" else "zh" } ?: "system")
        val notifications = settings.getJSONObject("notifications")
        notifications.put("internalEnabled", (oldApp.optString("eventNotificationLevel") != "OFF").toString())
            .put("popup", (oldApp.optString("eventNotificationLevel") == "HIGH").toString())
        val schedules = settings.getJSONArray("schedules")
        for (i in 0 until schedules.length()) {
            val rule = schedules.getJSONObject(i)
            rule.put("config", rule.getString("runConfigurationId"))
            rule.put("lastMinutes", rule.optLong("lastRunDurationMs", 0) / 60_000.0)
            if (!rule.isNull("lastRunStatus")) rule.put("legacyTriggerResult", rule.opt("lastResult")).put("lastResult", rule.getString("lastRunStatus"))
            require(ScheduleReceiver.next(rule) != null || !rule.optBoolean("enabled")) { "旧包定时规则无效：${rule.optString("name")}" }
        }
        atomic(File(stage, "neo-settings.json"), settings.toString(2))
        atomic(File(stage, "neo-backup.json"), "{\"format\":1,\"product\":\"BAAH NEO\"}")
    }
    fun importConfig(uri: Uri, name: String) {
        val json = app.contentResolver.openInputStream(uri)!!.bufferedReader().use { JSONObject(it.readText()) }
        check(name !in names()) { "同名配置已存在，请换一个名字" }; save(name, json)
    }
    companion object {
        val settingsRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
        fun appendScheduleLog(settings: JSONObject, rule: JSONObject, result: String) {
            val log = settings.optJSONArray("scheduleLog") ?: org.json.JSONArray()
            log.put(JSONObject().put("logId", java.util.UUID.randomUUID().toString()).put("at", System.currentTimeMillis()).put("name", rule.optString("name"))
                .put("id", rule.optString("id")).put("result", result))
            while (log.length() > 100) log.remove(0)
            settings.put("scheduleLog", log)
        }
        fun validName(name: String) = name.matches(Regex("[A-Za-z0-9_-]+"))
        fun atomic(file: File, text: String) {
            file.parentFile!!.mkdirs(); val atomic = AtomicFile(file); val stream = atomic.startWrite()
            try { stream.write(text.toByteArray()); atomic.finishWrite(stream) } catch (e: Throwable) { atomic.failWrite(stream); throw e }
        }
    }
}
