package com.baah.nativehost
object NotificationPhase {
    fun resolve(text: String, active: Boolean, busy: Boolean): BaahPhase = when {
        !active && listOf("异常", "失败", "中断").any(text::contains) -> BaahPhase.Failed
        !active && (text.contains("停止") || text.contains("关闭")) -> BaahPhase.Stopped
        !active -> BaahPhase.Completed
        text.contains("正在启动") -> BaahPhase.Starting
        busy || text.contains("准备") -> BaahPhase.Preparing
        else -> BaahPhase.Running
    }
}
