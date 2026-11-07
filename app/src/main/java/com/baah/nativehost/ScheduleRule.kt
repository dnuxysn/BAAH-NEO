package com.baah.nativehost

import java.time.*

data class ScheduleRule(val days: Set<DayOfWeek>, val times: List<LocalTime>, val start: Long? = null, val intervalMs: Long = 0) {
    fun next(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (intervalMs > 0 && start != null) {
            if (start > now) return start
            return start + ((now - start) / intervalMs + 1) * intervalMs
        }
        if (days.isEmpty() || times.isEmpty()) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0L..7L).flatMap { offset ->
            val date = today.plusDays(offset)
            if (date.dayOfWeek in days) times.map { date.atTime(it).atZone(zone).toInstant().toEpochMilli() } else emptyList()
        }.filter { it > now }.minOrNull()
    }
}
