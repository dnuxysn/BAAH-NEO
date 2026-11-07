package com.baah.nativehost

/** Called only after resumed-activity confirmation, during Python prelaunch. */
object InputReadiness {
    private val displayId = Regex("\\bdisplayId=(\\d+)(?:,|\\s)")
    fun ready(dump: String, display: Int, gamePackage: String): Boolean {
        // Older Android dumps lack inputConfig; the caller already checked the resumed activity.
        if ("inputConfig=" !in dump) return true
        return dump.lineSequence().filter { gamePackage in it && displayId.find(it)?.groupValues?.get(1)?.toInt() == display }
            .any { line -> "ownerPid=" in line && "inputConfig=" in line && "touchableRegion=" in line &&
                listOf("NO_INPUT_CHANNEL", "NOT_TOUCHABLE", "touchableRegion=<empty>").none { it in line } }
    }
}
