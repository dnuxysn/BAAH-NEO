package com.baah.nativehost
import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
data class BackupPreview(val file: File, val product: String, val configurations: Int, val schedules: Int) {
    fun summary() = "$product\n运行配置：$configurations\n定时规则：$schedules\n恢复将替换当前用户数据。"
    companion object {
        fun inspect(context: Context, uri: Uri): BackupPreview {
            val file = File.createTempFile("neo-import-", ".zip", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                ZipFile(file).use { zip ->
                    val entries = zip.entries().asSequence().toList(); val names = entries.map { it.name }
                    require(names.distinct().size == names.size) { "备份含重复路径" }
                    names.forEach { name ->
                        RuntimeHost.child(context.cacheDir, name)
                        require(name.substringBefore('/') in setOf("neo-backup.json", "baah", "baah-user", "neo-settings.json", "upstream-version.txt", "settings.json", "files")) { "不支持的备份内容" }
                    }
                    fun json(name: String) = zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).bufferedReader().use { JSONObject(it.readText()) } }
                    val neo = json("neo-backup.json")
                    val settings: JSONObject
                    val prefix: String
                    val product: String
                    if (neo != null) {
                        require(neo.optInt("format") == 1) { "备份格式不兼容" }
                        settings = json("neo-settings.json") ?: JSONObject(); prefix = "baah/BAAH_CONFIGS/"; product = "BAAH NEO"
                    } else {
                        settings = json("settings.json") ?: error("备份格式不兼容")
                        require(settings.optString("format") == "BAAH-AOS-user-data" && settings.optInt("version") == 1) { "备份格式不兼容" }
                        prefix = "files/BAAH_CONFIGS/"; product = "BAAH-AOS"
                    }
                    val configs = names.filter { it.startsWith(prefix) && it.endsWith(".json") }
                    require(configs.isNotEmpty()) { "备份缺少运行配置" }
                    configs.forEach { json(it) }
                    return BackupPreview(file, product, configs.size, settings.optJSONArray("schedules")?.length() ?: 0)
                }
            } catch (error: Throwable) { file.delete(); throw error }
        }
    }
}
