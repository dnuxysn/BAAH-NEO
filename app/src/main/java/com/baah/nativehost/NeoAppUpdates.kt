package com.baah.nativehost

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

internal data class NeoAppRelease(val tag: String, val pageUrl: String, val apkUrl: String?)

internal object NeoAppUpdates {
    private const val repository = "dnuxysn/BAAH-NEO"
    private const val releasesUrl = "https://github.com/$repository/releases"
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val versionPattern = Regex("""[vV]?([0-9]+)\.([0-9]+)\.([0-9]+)(?:-([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*))?(?:\+[A-Za-z0-9.-]+)?""")

    suspend fun latest(includePrereleases: Boolean): NeoAppRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "BAAH-NEO/${BuildConfig.VERSION_NAME}")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("获取软件版本失败（HTTP ${response.code}）")
            response.body?.string().orEmpty()
        }
        val releases = JSONArray(body)
        (0 until releases.length()).mapNotNull { index ->
            val release = releases.getJSONObject(index)
            val tag = release.optString("tag_name")
            val page = release.optString("html_url")
            if (release.optBoolean("draft") || !versionPattern.matches(tag) ||
                (!includePrereleases && (release.optBoolean("prerelease") || versionPattern.matchEntire(tag)?.groupValues?.get(4).orEmpty().isNotEmpty())) ||
                !page.startsWith("$releasesUrl/tag/")) return@mapNotNull null
            val assets = release.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).asSequence().map { assets!!.getJSONObject(it) }
                .firstOrNull {
                    it.optString("state") == "uploaded" &&
                        it.optString("name").equals("BAAH-NEO-${tag.removePrefix("v").removePrefix("V")}.apk", ignoreCase = true)
                }?.optString("browser_download_url")?.takeIf { it.startsWith("$releasesUrl/download/") }
            NeoAppRelease(tag, page, apk)
        }.maxWithOrNull { left, right -> compareVersions(left.tag, right.tag) }
            ?: error("该渠道暂无软件发布版本")
    }

    internal fun compareVersions(left: String, right: String): Int {
        val first = requireNotNull(versionPattern.matchEntire(left)).groupValues
        val second = requireNotNull(versionPattern.matchEntire(right)).groupValues
        for (index in 1..3) {
            val comparison = compareNumbers(first[index], second[index])
            if (comparison != 0) return comparison
        }
        val firstSuffix = first[4]
        val secondSuffix = second[4]
        if (firstSuffix.isEmpty() || secondSuffix.isEmpty()) {
            return firstSuffix.isEmpty().compareTo(secondSuffix.isEmpty())
        }
        val a = firstSuffix.split('.')
        val b = secondSuffix.split('.')
        for ((x, y) in a.zip(b)) {
            val xIsNumber = x.all { it in '0'..'9' }
            val yIsNumber = y.all { it in '0'..'9' }
            val comparison = when {
                xIsNumber && yIsNumber -> compareNumbers(x, y)
                xIsNumber != yIsNumber -> yIsNumber.compareTo(xIsNumber)
                else -> x.compareTo(y)
            }
            if (comparison != 0) return comparison
        }
        return a.size.compareTo(b.size)
    }

    private fun compareNumbers(left: String, right: String): Int {
        val a = left.trimStart('0')
        val b = right.trimStart('0')
        return a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
