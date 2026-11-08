package com.dayzero.app.domain

import com.dayzero.app.data.local.TaskHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {
    private fun e(event: String, old: String? = null, new: String? = null) =
        TaskHistoryEntity(taskId = 1, taskName = "T", event = event, oldValue = old, newValue = new, timestamp = 0, dayKey = "2026-11-08")
    private val fmt: (Long) -> String = { "@$it" }

    @Test fun describe() {
        assertEquals("Created · due @500", HistoryFormatter.describe(e(TaskEvent.CREATED, null, "500"), fmt))
        assertEquals("NOT STARTED → IN PROGRESS", HistoryFormatter.describe(e(TaskEvent.STATUS_CHANGED, "NOT_STARTED", "IN_PROGRESS"), fmt))
        assertEquals("Completed at @9", HistoryFormatter.describe(e(TaskEvent.COMPLETED, null, "9"), fmt))
        assertEquals("Deadline @1 → @2", HistoryFormatter.describe(e(TaskEvent.DEADLINE_CHANGED, "1", "2"), fmt))
        assertEquals("Overdue · deadline was @7", HistoryFormatter.describe(e(TaskEvent.OVERDUE, "7"), fmt))
        assertEquals("Check-in: NOT STARTED", HistoryFormatter.describe(e(TaskEvent.CHECK_IN, null, "NOT_STARTED"), fmt))
    }

    @Test fun filters_mapToDayKeyRanges() {
        assertEquals("2026-11-08" to "2026-11-08", HistoryFilter.TODAY.range("2026-11-08"))
        assertEquals("2026-11-07" to "2026-11-07", HistoryFilter.YESTERDAY.range("2026-11-08"))
        assertEquals("2026-11-02" to "2026-11-08", HistoryFilter.LAST_7.range("2026-11-08"))
    }

    @Test fun last7_crossesMonthBoundary() {
        assertEquals("2026-10-30" to "2026-11-05", HistoryFilter.LAST_7.range("2026-11-05"))
    }

    @Test fun all_coversEverything() {
        val (from, to) = HistoryFilter.ALL.range("2026-11-08")
        assert("1999-01-01" in from..to && "2099-12-31" in from..to)
    }
}
