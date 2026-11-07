// Adapted from BAAH-AOS (MaaFwApp-derived, AGPL-3.0); see third_party/baah-adapter.
package com.baah.nativehost.legacy

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BaahConfigField(val key: String, val label: String, val kind: String, val options: Any?, val metadata: JSONObject? = null)
data class BaahConfigPage(val id: String, val title: String, val tabs: List<String>, val fields: List<BaahConfigField>)

/** User configs live outside the replaceable environment and are bound into the BAAH guest. */
class BaahConfigRepository(context: Context) {
    private val app = context.applicationContext
    private val schemas = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val pageCache = java.util.concurrent.ConcurrentHashMap<String, List<BaahConfigPage>>()
    private val taskCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private fun schema(language: String) = schemas.getOrPut(language) {
        app.assets.open("baah-aos/schema_$language.json").bufferedReader().use { JSONObject(it.readText()) }
    }
    fun preload(language: String) { pages(language); taskChoices(language) }
    val directory = BaahStorage.configs(app).apply { mkdirs() }
    private val validName = Regex("[A-Za-z0-9_-]+")
    private val _names = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val namesState: kotlinx.coroutines.flow.StateFlow<List<String>> = _names

    fun installedVersion(): String = runCatching {
        val text = File(BaahStorage.upstream(app), "modules/configs/myversion.py").readText()
        Regex("myversion\\s*=\\s*['\"]([^'\"]+)").find(text)?.groupValues?.get(1) ?: "未知"
    }.getOrDefault("尚未初始化")

    @Synchronized fun names(): List<String> {
        ensureDefault()
        return directory.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }
            .map { it.nameWithoutExtension }.sorted().also { _names.value = it }
    }

    @Synchronized fun load(name: String): JSONObject {
        require(validName.matches(name))
        ensureDefault()
        return JSONObject(File(directory, "$name.json").readText())
    }

    @Synchronized fun save(name: String, data: JSONObject) {
        require(validName.matches(name))
        alignAndroidServer(data)
        val file = AtomicFile(File(directory, "$name.json"))
        val stream = file.startWrite()
        try {
            stream.write(data.toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            _names.value = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }
                .map { it.nameWithoutExtension }.sorted()
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    @Synchronized fun prepareForRun(name: String): JSONObject {
        val data = load(name)
        check(!data.optString("SERVER_TYPE").startsWith("PC_")) { "请选择 Android 游戏区服后再开始挂机" }
        val activity = data.optString("ACTIVITY_PATH")
        val resources = data.optString("PIC_PATH")
        alignAndroidServer(data)
        if (activity != data.optString("ACTIVITY_PATH") || resources != data.optString("PIC_PATH")) {
            save(name, data)
        }
        return data
    }

    /** BAAH 2.4.13 modules/configs/settingMaps.py; prelaunch must use the same server as BAAH. */
    private fun alignAndroidServer(data: JSONObject) {
        val mappings = mapOf(
            "JP" to ("com.YostarJP.BlueArchive/com.yostarjp.bluearchive.MxUnityPlayerActivity" to "./DATA/assets_jp"),
            "GLOBAL" to ("com.nexon.bluearchive/.MxUnityPlayerActivity" to "./DATA/assets"),
            "GLOBAL_EN" to ("com.nexon.bluearchive/.MxUnityPlayerActivity" to "./DATA/assets_global_en"),
            "CN" to ("com.RoamingStar.BlueArchive/com.tech.core.TechSplashActivity" to "./DATA/assets_cn"),
            "CN_BILI" to ("com.RoamingStar.BlueArchive.bilibili/com.tech.core.TechSplashActivity" to "./DATA/assets_cn"),
        )
        val server = mappings[data.optString("SERVER_TYPE")] ?: return
        data.put("ACTIVITY_PATH", server.first)
        val resources = data.optString("PIC_PATH")
        if (resources.isBlank() || resources in mappings.values.map { it.second }) data.put("PIC_PATH", server.second)
    }

    @Synchronized fun create(name: String, from: String = "default") {
        require(validName.matches(name)) { "配置名只能包含字母、数字、下划线和连字符" }
        check(!File(directory, "$name.json").exists()) { "配置已存在" }
        save(name, load(from.takeIf { File(directory, "$it.json").isFile } ?: names().first()))
    }

    @Synchronized internal fun rename(oldName: String, newName: String, saveReferences: () -> Unit) {
        require(validName.matches(oldName))
        require(newName.isNotBlank()) { "请输入配置名称" }
        require(validName.matches(newName)) { "配置名只能包含字母、数字、下划线和连字符" }
        val source = File(directory, "$oldName.json")
        val target = File(directory, "$newName.json")
        check(source.isFile) { "原配置不存在" }
        if (source == target) return
        check(!target.exists()) { "同名配置已存在，请换一个名字" }
        java.nio.file.Files.move(source.toPath(), target.toPath())
        try {
            saveReferences()
        } catch (failure: Exception) {
            java.nio.file.Files.move(target.toPath(), source.toPath())
            throw failure
        }
        names()
    }

    @Synchronized fun delete(name: String) {
        require(validName.matches(name))
        check(names().size > 1) { "至少保留一个配置" }
        check(File(directory, "$name.json").delete()) { "删除配置失败" }
        names()
    }

    private fun ensureDefault() {
        if (directory.listFiles().orEmpty().none { it.isFile && it.extension == "json" }) {
            val seed = app.assets.open("baah-aos/seeds/default.json").bufferedReader().use { it.readText() }
            save("default", JSONObject(seed))
        }
    }

    @Synchronized fun pages(language: String = "zh_CN"): List<BaahConfigPage> {
        val safeLanguage = language.takeIf { it in listOf("zh_CN", "en_US", "jp_JP") } ?: "zh_CN"
        pageCache[safeLanguage]?.let { return it }
        val schema = schema(safeLanguage)
        val records = schema.getJSONArray("records")
        val pages = schema.getJSONArray("pages")
        val hidden = setOf("ADB_PATH", "TARGET_IP_PATH", "TARGET_PORT", "TARGET_EMULATOR_PATH",
            "SCREENSHOT_METHOD", "KILL_PORT_IF_EXIST", "ADB_DIRECT_USE_SERIAL_NUMBER", "ADB_SEIAL_NUMBER",
            "CLOSE_EMULATOR_FINISH", "CLOSE_EMULATOR_ERROR", "ARIA2_PATH")
        val english = safeLanguage == "en_US"
        val result = List(pages.length()) { index ->
            val page = pages.getJSONObject(index)
            val id = page.getString("page")
            val fields = linkedMapOf<String, BaahConfigField>()
            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                if (record.optString("page") != id) continue
                val chain = record.optJSONArray("chain") ?: continue
                var key: String? = null
                for (j in 0 until chain.length()) {
                    val binding = chain.getJSONObject(j)
                    if (binding.optString("m") == "bind_value") key = binding.optJSONArray("args")?.optString(1)
                }
                if (key.isNullOrBlank() || key in hidden) continue
                val args = record.optJSONArray("args")
                val kwargs = record.optJSONObject("kwargs")
                val label = if (key == "CLOSE_GAME_FINISH") {
                    if (english) "Close the game when finished" else "运行结束后关闭游戏"
                } else kwargs?.optString("label")?.takeIf { it.isNotBlank() }
                    ?: (args?.opt(0) as? String)?.takeIf { it.isNotBlank() }
                    ?: kwargs?.optString("placeholder")?.takeIf { it.isNotBlank() }
                    ?: record.optString("label_fallback").takeIf { it.isNotBlank() } ?: key
                val metadata = JSONObject(kwargs?.toString() ?: "{}")
                for (j in 0 until chain.length()) {
                    val binding = chain.getJSONObject(j)
                    if (binding.optString("m") == "set_enabled" && binding.optJSONArray("args")?.optBoolean(0) == false)
                        metadata.put("readOnly", true)
                    if (binding.optString("m") == "bind_visibility_from") {
                        metadata.put("visibleWhen", binding.optJSONArray("args")?.optString(1))
                        metadata.put("visibleInverted", binding.optBoolean("inverted"))
                    }
                }
                fields.putIfAbsent(key, BaahConfigField(key, label, record.optString("kind"),
                    kwargs?.opt("option_labels") ?: kwargs?.opt("options") ?: args?.opt(0)?.takeIf { it is JSONObject || it is JSONArray }, metadata))
            }
            for (collection in listOf("list_fields", "static_keys", "accessed")) {
                val items = schema.optJSONArray(collection) ?: continue
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    if (item.optString("page") != id) continue
                    val key = item.optString("key")
                    if (key.isNotBlank() && key !in hidden && collection == "list_fields") {
                        val field = BaahConfigField(key, item.optString("title").takeIf { it.isNotBlank() } ?: key,
                            if (collection == "list_fields") "list" else "value", null,
                            if (collection == "list_fields") item else null)
                        if (collection == "list_fields") fields[key] = field else fields.putIfAbsent(key, field)
                    }
                }
            }
            if (id == "task_login_game") {
                fields["OBJ_FLOW_WHEN_LOGIN"] = BaahConfigField(
                    "OBJ_FLOW_WHEN_LOGIN", "编辑登录流程", "login-flow", null,
                    JSONObject().put("visibleWhen", "USE_OBJ_FLOW_WHEN_LOGIN"))
            }
            if (id == "setting_task_order") fields["TASK_ORDER_GROUP"] =
                BaahConfigField("TASK_ORDER_GROUP", if (english) "Task order" else "任务执行顺序", "pipeline", null)
            if (id == "setting_explore") fields["TEAM_SET_STRENGTH"] =
                BaahConfigField("TEAM_SET_STRENGTH", if (english) "Team strength" else "队伍强度", "teams", null,
                    JSONObject().put("visibleWhen", "EXPLORE_AUTO_TEAM").put("visibleInverted", true))
            val orderedFields = fields.values.toMutableList()
            val embeddedKey = when (id) { "setting_task_order" -> "TASK_ORDER_GROUP"; "setting_explore" -> "TEAM_SET_STRENGTH"; else -> null }
            embeddedKey?.let { key ->
                val embedded = orderedFields.find { it.key == key }
                if (embedded != null) {
                    orderedFields.remove(embedded)
                    val index = if (id == "setting_task_order") orderedFields.indexOfFirst { it.key == "POST_COMMAND" }
                        else orderedFields.indexOfFirst { it.key == "EXPLORE_RAINBOW_TEAMS" }.let { if (it < 0) -1 else it + 1 }
                    orderedFields.add(if (index < 0) orderedFields.size else index, embedded)
                }
            }
            val tabs = page.optJSONArray("tabs")
            BaahConfigPage(id, if (id == "setting_emulator") {
                if (english) "Game configuration" else "游戏配置"
            } else page.optString("title", id),
                if (tabs == null) emptyList() else List(tabs.length()) { tabs.getString(it) }, orderedFields)
        }.toMutableList()
        val server = result.flatMap { it.fields }.find { it.key == "SERVER_TYPE" }
        if (server != null) {
            val label = if (english) "Game server" else "游戏区服"
            for (index in result.indices) result[index] = result[index].copy(fields = result[index].fields.filter { it.key != "SERVER_TYPE" })
            val before = result.indexOfFirst { it.id == "setting_emulator" }.coerceAtLeast(0)
            result.add(before, BaahConfigPage("setting_server_type", label, listOf("BAAH"), listOf(server.copy(label = label))))
        }
        return result.filter { it.fields.isNotEmpty() }.also { pageCache[safeLanguage] = it }
    }

    @Synchronized fun taskChoices(language: String = "zh_CN"): Map<String, String> {
        val safe = language.takeIf { it in listOf("zh_CN", "en_US", "jp_JP") } ?: "zh_CN"
        taskCache[safe]?.let { return it }
        val schema = schema(safe)
        val records = schema.getJSONArray("records")
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            if (record.optString("page") != "setting_task_order" || record.optString("kind") != "ui.select") continue
            val labels = record.optJSONObject("kwargs")?.optJSONObject("option_labels")
                ?: record.optJSONArray("args")?.optJSONObject(0) ?: continue
            return labels.keys().asSequence().associateWith { labels.getString(it) }.also { taskCache[safe] = it }
        }
        return emptyMap()
    }
}
