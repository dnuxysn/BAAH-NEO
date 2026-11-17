package com.baah.nativehost

import org.junit.Assert.*
import org.junit.Test

class BackgroundResolutionTest {
    @Test fun legacyAndUnknownSettingsKeep720p() {
        for (value in listOf("", "720p", "broken")) assertEquals(BackgroundResolution.HD, BackgroundResolution.fromSetting(value))
        assertEquals(BackgroundResolution.FULL_HD, BackgroundResolution.fromSetting("1080p"))
    }

    @Test fun real1080pDisplayPreservesTheGameLayoutSize() {
        val hd = BackgroundResolution.HD
        val full = BackgroundResolution.FULL_HD
        assertEquals(1920, full.width)
        assertEquals(1080, full.height)
        // Equal density-independent dimensions keep game controls at the same logical positions.
        assertEquals(hd.width.toFloat() / hd.density, full.width.toFloat() / full.density, 0f)
        assertEquals(hd.height.toFloat() / hd.density, full.height.toFloat() / full.density, 0f)
    }

    @Test fun downsampledRecognitionCoordinatesMapBackOntoTheGame() {
        for (resolution in BackgroundResolution.entries) {
            for ((x, y) in listOf(0f to 0f, 640f to 360f, 1279f to 719f, 394f to 650f, 1158f to 647f)) {
                val (nativeX, nativeY) = resolution.nativePoint(x, y)
                assertTrue(nativeX >= 0 && nativeX < resolution.width)
                assertTrue(nativeY >= 0 && nativeY < resolution.height)
                assertEquals(x, nativeX * BackgroundResolution.LOGICAL_WIDTH / resolution.width, 0.001f)
                assertEquals(y, nativeY * BackgroundResolution.LOGICAL_HEIGHT / resolution.height, 0.001f)
            }
        }
        assertEquals(960f to 540f, BackgroundResolution.FULL_HD.nativePoint(640f, 360f))
        assertEquals(640f to 360f, BackgroundResolution.HD.nativePoint(640f, 360f))
    }

    @Test fun swipeIntermediatePointsStayOnTheSameNativeLine() {
        val resolution = BackgroundResolution.FULL_HD
        val start = resolution.nativePoint(100f, 200f)
        val end = resolution.nativePoint(1100f, 600f)
        val middle = resolution.nativePoint(600f, 400f)
        assertEquals((start.first + end.first) / 2, middle.first, 0f)
        assertEquals((start.second + end.second) / 2, middle.second, 0f)
    }

    @Test fun rejectsUnsupportedDisplayAndOutOfFrameTouches() {
        assertThrows(IllegalArgumentException::class.java) { BackgroundResolution.fromSize(1080, 1920) }
        assertThrows(IllegalArgumentException::class.java) { BackgroundResolution.fromSize(2560, 1440) }
        for ((x, y) in listOf(-1f to 0f, 1280f to 0f, 0f to 720f, Float.NaN to 0f))
            assertThrows(IllegalArgumentException::class.java) { BackgroundResolution.FULL_HD.nativePoint(x, y) }
    }
}
