package com.baah.nativehost

/** BAAH and MaaFramework always see logical 720p, regardless of the game display. */
internal enum class BackgroundResolution(val width: Int, val height: Int, val density: Int) {
    HD(1280, 720, 240), FULL_HD(1920, 1080, 360);

    fun nativePoint(x: Float, y: Float): Pair<Float, Float> {
        require(x in 0f..1279f && y in 0f..719f)
        return x * width / LOGICAL_WIDTH to y * height / LOGICAL_HEIGHT
    }

    companion object {
        const val LOGICAL_WIDTH = 1280
        const val LOGICAL_HEIGHT = 720
        fun fromSetting(value: String) = if (value == "1080p") FULL_HD else HD
        fun fromSize(width: Int, height: Int) = entries.firstOrNull { it.width == width && it.height == height }
            ?: throw IllegalArgumentException("不支持的后台分辨率：${width}x$height")
    }
}
