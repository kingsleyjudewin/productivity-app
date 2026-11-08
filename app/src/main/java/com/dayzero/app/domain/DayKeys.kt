package com.dayzero.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A "day" in DAYZERO starts at the user's configured reset time, not at midnight.
 * Every per-day record is tagged with a dayKey (ISO date of the logical day, e.g. "2026-11-08").
 * Keys sort lexicographically in date order, so range queries on them are safe.
 */
object DayKeys {
    fun keyFor(
        millis: Long,
        resetMinuteOfDay: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String =
        Instant.ofEpochMilli(millis).atZone(zone)
            .minusMinutes(resetMinuteOfDay.toLong())
            .toLocalDate().toString()

    fun startMillis(
        key: String,
        resetMinuteOfDay: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long =
        LocalDate.parse(key).atStartOfDay(zone)
            .plusMinutes(resetMinuteOfDay.toLong())
            .toInstant().toEpochMilli()

    fun endMillis(
        key: String,
        resetMinuteOfDay: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = startMillis(LocalDate.parse(key).plusDays(1).toString(), resetMinuteOfDay, zone)

    fun shift(key: String, days: Long): String = LocalDate.parse(key).plusDays(days).toString()
}
