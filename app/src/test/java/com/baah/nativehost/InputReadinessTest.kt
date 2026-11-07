package com.baah.nativehost
import org.junit.Assert.*
import org.junit.Test
class InputReadinessTest {
    private val ready = "name=com.example.game/Main, displayId=12, ownerPid=100, inputConfig=0x0, touchableRegion=[0,0][1280,720]"
    @Test fun requiresTheCorrectTouchableWindow() {
        assertTrue(InputReadiness.ready(ready, 12, "com.example.game"))
        assertFalse(InputReadiness.ready(ready, 1, "com.example.game"))
        assertFalse(InputReadiness.ready(ready, 12, "other.game"))
        for (flag in listOf("NO_INPUT_CHANNEL", "NOT_TOUCHABLE"))
            assertFalse(InputReadiness.ready(ready.replace("0x0", flag), 12, "com.example.game"))
        assertFalse(InputReadiness.ready(ready.replace("[0,0][1280,720]", "<empty>"), 12, "com.example.game"))
    }
    @Test fun supportsLegacyDumpAfterActivityCheck() {
        assertTrue(InputReadiness.ready("legacy format", 12, "com.example.game"))
    }
}
