package com.baah.nativehost

import android.content.Context
import android.system.Os
import kotlinx.coroutines.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class RuntimeHost(private val app: Context, private val report: (String) -> Unit) {
    val upstream = File(app.filesDir, "baah")
    val user = File(app.filesDir, "baah-user")
    private val root = File(app.filesDir, "rootfs")
    private val adapter get() = File(root, "opt/baah-aos")
    private var process: Process? = null
    fun alive() = process?.isAlive == true
    suspend fun releaseTag(beta: Boolean): String = withContext(Dispatchers.IO) {
        try {
            val releases = org.json.JSONArray(downloadText("https://api.github.com/repos/BlueArchiveArisHelper/BAAH/releases?per_page=30"))
            ReleasePolicy.select((0 until releases.length()).map { i -> releases.getJSONObject(i).let {
                ReleaseCandidate(it.getString("tag_name"), it.optBoolean("prerelease"), it.optBoolean("draft"))
            } }, beta)
        } catch (error: ReleaseHttpException) {
            if (error.code != 403 && error.code != 429) throw error
            // Public Release pages remain available when the shared unauthenticated API quota is exhausted.
            if (beta) ReleasePolicy.newestPublicRelease(downloadText("https://github.com/BlueArchiveArisHelper/BAAH/releases.atom"))
            else {
                val latest = open("https://github.com/BlueArchiveArisHelper/BAAH/releases/latest")
                try { latest.inputStream.close(); ReleasePolicy.tagFromReleaseUrl(latest.url.toString()) }
                finally { latest.disconnect() }
            }
        }
    }
    fun ready() = File(root, ".baah-environment-ready").isFile &&
        File(root, ".baah-python-deps-ready-v1").isFile && File(upstream, "BAAH.py").isFile
    suspend fun reinitialize() = withContext(Dispatchers.IO) {
        check(process?.isAlive != true)
        File(root, ".baah-environment-ready").delete()
        File(root, ".baah-python-deps-ready-v1").delete()
        prepare()
    }
    suspend fun cacheRelease(tag: String) { downloadArchive(tag, reportProgress = false) }

    private suspend fun downloadArchive(tag: String, reportProgress: Boolean = true): File {
        require(tag.matches(Regex("[A-Za-z0-9._-]+")))
        val destination = File(app.filesDir, "downloads/baah-$tag.zip").apply { parentFile!!.mkdirs() }
        if (destination.isFile) return destination
        val temporary = File.createTempFile("${destination.name}-", ".part", destination.parentFile)
        val ref = "tags/$tag"
        try {
            val conn = open("https://codeload.github.com/BlueArchiveArisHelper/BAAH/zip/refs/$ref")
            try {
                conn.inputStream.buffered().use { source -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var count = 0L; var last = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(buffer); if (n < 0) break
                        output.write(buffer, 0, n); count += n
                        if (reportProgress && System.currentTimeMillis() - last > 300) { report("下载 BAAH $tag：${count / 1024} KiB"); last = System.currentTimeMillis() }
                    }
                } }
            } finally { conn.disconnect() }
            check(temporary.renameTo(destination)) { "不能保存更新包" }
            return destination
        } finally { temporary.delete() }
    }

    suspend fun prepare() = withContext(Dispatchers.IO) {
        if (!File(root, ".baah-environment-ready").isFile) extract()
        copyAssets("baah-aos", adapter)
        if (!File(upstream, "BAAH.py").isFile) update(false)
        if (!File(root, ".baah-python-deps-ready-v1").isFile) {
            report("正在安装 Python 依赖，请保持联网")
            val log = File(app.filesDir, "python-setup.log")
            val task = spawn(listOf("/usr/bin/python3", "-u", "/opt/baah-aos/python_setup.py"), log)
            try {
                withTimeout(25 * 60_000L) {
                    while (task.isAlive) {
                        log.takeIf { it.exists() }?.readLines()?.lastOrNull { it.startsWith("[BAAH-AOS-SETUP] ") }?.let { line ->
                            val event = JSONObject(line.substringAfter("[BAAH-AOS-SETUP] "))
                            report("${event.getString("title")}（${event.getInt("done")}/${event.getInt("total")}）")
                        }
                        delay(1000)
                    }
                }
                check(task.exitValue() == 0 && File(root, ".baah-python-deps-ready-v1").isFile) {
                    "Python 依赖初始化失败：${log.readText().takeLast(3000)}"
                }
            } finally { if (task.isAlive) task.destroyForcibly() }
        }
        report("BAAH 运行环境已就绪")
    }
    private suspend fun extract() {
        report("首次解压 Python 环境（约 157 MiB 压缩包）")
        root.mkdirs()
        // Overlaying a prior pip environment can mix incompatible package versions.
        clearGuestRoot(root)
        val links = mutableListOf<Pair<File, String>>()
        val hardLinks = mutableListOf<Pair<File, File>>()
        var count = 0
        app.assets.open("baah-rootfs.tar.xz").buffered(256 * 1024).use { source ->
            TarArchiveInputStream(XZInputStream(source)).use { tar ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: break
                    val file = child(root, entry.name.removePrefix("./"))
                    when {
                        entry.isSymbolicLink -> links += file to entry.linkName
                        entry.isLink -> hardLinks += file to child(root, entry.linkName.removePrefix("./"))
                        entry.isDirectory -> file.mkdirs()
                        entry.isFile -> {
                            file.parentFile!!.mkdirs(); file.outputStream().buffered().use { tar.copyTo(it) }
                            Os.chmod(file.absolutePath, entry.mode and 511)
                        }
                    }
                    if (++count % 500 == 0) report("解压 Python 环境：已处理 $count 个条目")
                }
            }
        }
        hardLinks.forEach { (file, target) -> file.parentFile!!.mkdirs(); target.copyTo(file, true); Os.chmod(file.path, Os.stat(target.path).st_mode and 511) }
        links.forEach { (file, target) -> file.parentFile!!.mkdirs(); file.delete(); Os.symlink(target, file.path) }
        check(File(root, "usr/bin/python3.12").isFile)
        File(root, ".baah-environment-ready").writeText("1")
    }
    suspend fun update(beta: Boolean, selectedTag: String? = null) = withContext(Dispatchers.IO) {
        check(process?.isAlive != true) { "请先关闭运行环境再更新" }
        report("正在查询 BAAH ${if (beta) "测试" else "稳定"}更新")
        val tag = selectedTag ?: releaseTag(beta)
        require(tag.matches(Regex("[A-Za-z0-9._-]+")))
        val stage = File(app.filesDir, "upstream-staging-${System.nanoTime()}").apply { mkdirs() }
        try {
            val archive = downloadArchive(tag)
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    var files = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        val relative = entry.name.substringAfter('/', "")
                        if (relative.isEmpty()) continue
                        val target = child(stage, relative)
                        if (entry.isDirectory) target.mkdirs() else {
                            target.parentFile!!.mkdirs(); target.outputStream().use { zip.copyTo(it) }
                        }
                        if (++files % 100 == 0) report("下载与解压 BAAH：$files 个文件")
                    }
            }
            check(File(stage, "BAAH.py").isFile && File(stage, "main.py").isFile) { "下载内容不是完整 BAAH" }
            // Preserve all runtime state, configurations and account values across updates.
            val old = File(app.filesDir, "baah-before-update-${System.currentTimeMillis()}")
            if (upstream.exists()) {
                listOf("BAAH_CONFIGS", "DATA/CONFIGS", "DATA/USER_DEFINED", "DATA/USER_STORAGE", "DATA/LOGS").forEach { name ->
                    File(upstream, name).takeIf { it.exists() }?.let { source ->
                        val target = File(stage, name)
                        target.deleteRecursively(); source.copyRecursively(target, true)
                    }
                }
                upstream.listFiles().orEmpty().filter { it.name.startsWith(".baah-") }.forEach { it.copyTo(File(stage, it.name), true) }
                check(upstream.renameTo(old)) { "更新前备份失败" }
            }
            if (!stage.renameTo(upstream)) { if (old.exists()) old.renameTo(upstream); error("上游更新切换失败") }
            File(app.filesDir, "upstream-version.txt").writeText(tag)
            report("BAAH $tag 已安装。$editionUpdateMessage")
        } finally { stage.deleteRecursively() }
    }
    suspend fun launch(display: Int) = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) return@withContext
        check(ready()); check(display > 0)
        copyAssets("baah-aos", adapter)
        File(adapter, "vd_id_app").writeText(display.toString())
        process = spawn(listOf("/usr/bin/python3", "-u", "/opt/baah-aos/wrapper.py"), File(app.filesDir, "host.log"))
        try {
            withTimeout(25_000) {
                while (true) {
                    check(process?.isAlive == true) { "Python 宿主退出：${File(app.filesDir, "host.log").readText().takeLast(2000)}" }
                    if (runCatching { request("/healthz").optBoolean("ok") }.getOrDefault(false)) break
                    delay(250)
                }
            }
        } catch (e: Throwable) { close(); throw e }
    }
    suspend fun close() = withContext(Dispatchers.IO) {
        process?.let { child ->
            runCatching { request("/stop", true) }
            runCatching { child.outputStream.close() }
            if (!child.waitFor(8, TimeUnit.SECONDS)) child.destroyForcibly()
        }
        process = null
    }
    fun request(path: String, post: Boolean = false): JSONObject {
        val conn = open("http://127.0.0.1:${BuildConfig.WRAPPER_PORT}$path", post)
        try { return JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) }
        finally { conn.disconnect() }
    }
    private fun spawn(args: List<String>, log: File): Process {
        val libs = File(app.applicationInfo.nativeLibraryDir)
        val tmp = File(app.filesDir, "proot-tmp").apply { mkdirs() }
        File(root, "tmp").mkdirs(); File(root, "opt/baah").mkdirs(); user.mkdirs(); File(root, "opt/baah-user").mkdirs()
        if (log.name == "host.log" && log.exists()) {
            val archive = File(app.filesDir, "host-logs/host-${System.currentTimeMillis()}.log").apply { parentFile!!.mkdirs() }
            check(log.renameTo(archive)) { "无法归档运行环境日志" }
        }
        val command = listOf(File(libs, "libproot.so").path, "-0", "-r", root.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${File(root, "tmp").path}:/dev/shm",
            "-b", "${upstream.path}:/opt/baah", "-b", "${user.path}:/opt/baah-user", "-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/local/bin:/usr/bin:/bin", "LANG=C.UTF-8",
            "TZ=${java.util.TimeZone.getDefault().id}", "BAAH_AOS=1",
            "BAAH_AOS_BRIDGE_PORT=${BuildConfig.BRIDGE_PORT}", "BAAH_AOS_WRAPPER_PORT=${BuildConfig.WRAPPER_PORT}",
            "BAAH_AOS_GUI_PORT=${BuildConfig.CONSOLE_PORT}", "BAAH_NEO_ONLY=${if (BuildConfig.PRIVATE_EDITION) 1 else 0}") +
            (if (BuildConfig.PRIVATE_EDITION) listOf("ADB_SERVER_SOCKET=tcp:127.0.0.1:5039") else emptyList()) + args
        return ProcessBuilder(command).directory(File(root, "root")).redirectErrorStream(true).redirectOutput(log).apply {
            environment().apply {
                put("PROOT_LOADER", File(libs, "libproot-loader.so").path); put("PROOT_TMP_DIR", tmp.path)
                put("PROOT_NO_SECCOMP", "1"); put("LD_LIBRARY_PATH", libs.path); remove("LD_PRELOAD")
            }
        }.start()
    }
    private fun copyAssets(name: String, target: File) {
        val children = app.assets.list(name).orEmpty()
        if (children.isNotEmpty()) { target.mkdirs(); children.forEach { copyAssets("$name/$it", File(target, it)) } }
        else { target.parentFile!!.mkdirs(); app.assets.open(name).use { src -> target.outputStream().use(src::copyTo) } }
    }
    private class ReleaseHttpException(val code: Int) : java.io.IOException(
        if (code == 403 || code == 429) "GitHub 暂时拒绝更新请求（HTTP $code），请稍后重试"
        else "获取上游版本失败（HTTP $code）")
    companion object {
        internal fun clearGuestRoot(root: File) {
            val directory = root.toPath()
            java.nio.file.Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).filter { it != directory }
                    .forEach { java.nio.file.Files.delete(it) }
            }
        }
        fun child(root: File, relative: String): File {
            val file = File(root, relative)
            require(!File(relative).isAbsolute && (file.canonicalPath == root.canonicalPath || file.canonicalPath.startsWith(root.canonicalPath + File.separator))) { "无效的归档路径" }
            return file
        }
        private fun open(url: String, post: Boolean = false): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000; setRequestProperty("User-Agent", "BAAH-NEO/${BuildConfig.VERSION_NAME}")
            if (post) { requestMethod = "POST"; doOutput = true; outputStream.close() }
        }
        private fun downloadText(url: String): String {
            val conn = open(url)
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw ReleaseHttpException(code)
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally { conn.disconnect() }
        }
    }
}
