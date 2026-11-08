package com.dayzero.app.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateTime = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
private val timeOnly = DateTimeFormatter.ofPattern("HH:mm")

fun fmtDateTime(ms: Long): String = dateTime.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
fun fmtTime(ms: Long): String = timeOnly.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** "22:30" if it's today, otherwise "Mon 9 Nov, 22:30". */
fun fmtSmart(ms: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val same = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (same) fmtTime(ms) else fmtDateTime(ms)
}
