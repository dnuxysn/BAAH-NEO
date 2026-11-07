package com.baah.nativehost.legacy

data class DisplayResolution(val width: Int, val height: Int) { val aspectRatio get() = width.toFloat() / height }
enum class GameState { Stopped, Starting, Running }
data class GameUi(val game: GameState, val fps: Float?)
data class RunUi(val logs: List<String>, val error: String?)
data class ProvisionUi(val busy: Boolean, val message: String)
data class NextSchedule(val nextTriggerAt: Long?, val strategy: ScheduleName)
data class ScheduleName(val name: String)
