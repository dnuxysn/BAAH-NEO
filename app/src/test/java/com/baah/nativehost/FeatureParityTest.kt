package com.baah.nativehost
import org.junit.Assert.*
import org.junit.Test
import com.baah.nativehost.legacy.NeoLocale
class FeatureParityTest {
    @Test fun releaseChannelsExcludeDraftsAndKeepReleaseOrder() {
        val releases = listOf(ReleaseCandidate("draft", true, true), ReleaseCandidate("v3.0-beta", true), ReleaseCandidate("v2.5.7"))
        assertEquals("v2.5.7", ReleasePolicy.select(releases, false))
        assertEquals("v3.0-beta", ReleasePolicy.select(releases, true))
        assertEquals("v2.5.7", ReleasePolicy.select(releases.drop(2), true))
    }
    @Test(expected = IllegalStateException::class) fun absentReleaseDoesNotFallBackToMain() {
        ReleasePolicy.select(listOf(ReleaseCandidate("draft", draft=true)), true)
    }
    @Test fun systemLanguageAndExplicitChoice() {
        assertEquals("en", NeoLocale.resolve("system", "en-US"))
        assertEquals("zh", NeoLocale.resolve("system", "zh-CN"))
        assertEquals("zh", NeoLocale.resolve("zh", "en-US"))
        assertEquals("en", NeoLocale.resolve("en", "zh-CN"))
    }
    @Test fun stoppedFailedAndPreparationRemainDistinct() {
        assertEquals(BaahPhase.Stopped, NotificationPhase.resolve("本轮已停止", false, false))
        assertEquals(BaahPhase.Failed, NotificationPhase.resolve("控制服务中断", false, false))
        assertEquals(BaahPhase.Completed, NotificationPhase.resolve("本轮完成", false, false))
        assertEquals(BaahPhase.Preparing, NotificationPhase.resolve("准备运行", true, true))
        assertEquals(BaahPhase.Starting, NotificationPhase.resolve("正在启动 cn", true, true))
    }
}
