package com.baah.nativehost
data class ReleaseCandidate(val tag: String, val prerelease: Boolean = false, val draft: Boolean = false)
object ReleasePolicy {
    private const val tagPrefix = "https://github.com/BlueArchiveArisHelper/BAAH/releases/tag/"
    fun tagFromReleaseUrl(url: String): String {
        require(url.startsWith(tagPrefix)) { "不是上游 Release 地址" }
        val tag = java.net.URLDecoder.decode(url.removePrefix(tagPrefix), "UTF-8")
        require(tag.matches(Regex("[A-Za-z0-9._-]+"))) { "发布标签无效" }
        return tag
    }
    fun newestPublicRelease(feed: String): String {
        val link = Regex("""<link\b[^>]*\bhref=["']([^"']+)["']""").findAll(feed)
            .map { it.groupValues[1] }.firstOrNull { it.startsWith(tagPrefix) }
            ?: error("该渠道暂无上游发布版本")
        return tagFromReleaseUrl(link)
    }
    fun select(releases: List<ReleaseCandidate>, beta: Boolean): String =
        releases.firstOrNull { !it.draft && (beta || !it.prerelease) }?.tag
            ?.also { require(it.matches(Regex("[A-Za-z0-9._-]+"))) { "发布标签无效" } }
            ?: error("该渠道暂无上游发布版本")
}
