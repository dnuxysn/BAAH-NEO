// BAAH-AOS log export utility, retained under AGPL-3.0.
package com.baah.nativehost.legacy

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BaahLogs(private val context: Context) {
    fun files(baah: Boolean): List<File> {
        val roots = if (baah) listOf(File(BaahStorage.upstream(context), "DATA/LOGS"),
            File(context.filesDir, "rootfs/opt/baah-aos/log"))
        else listOf(File(context.filesDir, "logs"), File(context.filesDir, "host.log"),
            File(context.filesDir, "host-logs"), File(context.filesDir, "python-setup.log"))
        return roots.flatMap { root -> if (root.isFile) listOf(root) else if (root.isDirectory)
            root.walkTopDown().filter { it.isFile }.toList() else emptyList() }.sortedByDescending { it.lastModified() }
    }
    suspend fun read(file: File): String = withContext(Dispatchers.IO) {
        java.io.RandomAccessFile(file, "r").use { input ->
            val start = (input.length() - 256 * 1024).coerceAtLeast(0)
            input.seek(start)
            val bytes = ByteArray((input.length() - start).toInt())
            input.readFully(bytes)
            (if (start > 0) "仅显示最后 256 KB\n" else "") + bytes.toString(Charsets.UTF_8)
        }
    }
    suspend fun export(uri: Uri, baah: Boolean) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        ZipOutputStream(checkNotNull(context.contentResolver.openOutputStream(uri))).use { zip ->
            zip.putNextEntry(ZipEntry("device.txt"))
            zip.write("BAAH NEO\n${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})\n".toByteArray())
            zip.closeEntry()
            for ((index, file) in files(baah).filter { !baah || it.lastModified() >= cutoff }.withIndex()) {
                zip.putNextEntry(ZipEntry("logs/$index-${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
    suspend fun clean() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        (files(true) + files(false)).filter { it.lastModified() < cutoff }.forEach { it.delete() }
    }
}
