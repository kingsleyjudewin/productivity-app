package com.dayzero.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DayKeysTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun midnightReset_usesCalendarDate() {
        assertEquals("2026-11-08", DayKeys.keyFor(at(2026, 11, 8, 0, 0), 0, zone))
        assertEquals("2026-11-08", DayKeys.keyFor(at(2026, 11, 8, 23, 59), 0, zone))
    }

    @Test fun resetAtFourAm_earlyMorningBelongsToPreviousDay() {
        assertEquals("2026-11-07", DayKeys.keyFor(at(2026, 11, 8, 3, 59), 240, zone))
        assertEquals("2026-11-08", DayKeys.keyFor(at(2026, 11, 8, 4, 0), 240, zone))
    }

    @Test fun window_matchesKey() {
        val start = DayKeys.startMillis("2026-11-08", 240, zone)
        val end = DayKeys.endMillis("2026-11-08", 240, zone)
        assertEquals(at(2026, 11, 8, 4, 0), start)
        assertEquals(at(2026, 11, 9, 4, 0), end)
    }

    @Test fun shift_crossesMonthBoundary() {
        assertEquals("2026-12-01", DayKeys.shift("2026-11-30", 1))
    }
}
