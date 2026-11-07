package com.baah.nativehost

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ScheduleRuleTest {
    private val zone = ZoneId.of("Asia/Singapore")
    private fun ms(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
    @Test fun picksNextTimeAndSkipsUnselectedDays() {
        val rule = ScheduleRule(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)))
        assertEquals(ms("2026-11-09T08:00"), rule.next(ms("2026-11-05T12:00"), zone))
        assertEquals(ms("2026-11-09T20:00"), rule.next(ms("2026-11-09T08:00"), zone))
    }
    @Test fun intervalDoesNotReplayMissedRuns() {
        val start = ms("2026-11-05T08:00")
        val rule = ScheduleRule(emptySet(), emptyList(), start, 2 * 3_600_000L)
        assertEquals(ms("2026-11-05T14:00"), rule.next(ms("2026-11-05T13:30"), zone))
        assertEquals(start, rule.next(start - 100, zone))
    }
}
