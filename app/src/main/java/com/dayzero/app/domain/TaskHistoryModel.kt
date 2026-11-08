package com.dayzero.app.domain

import com.dayzero.app.data.local.TaskHistoryEntity

object TaskEvent {
    const val CREATED = "CREATED"
    const val EDITED = "EDITED"
    const val STATUS_CHANGED = "STATUS_CHANGED"
    const val COMPLETED = "COMPLETED"
    const val DEADLINE_CHANGED = "DEADLINE_CHANGED"
    const val OVERDUE = "OVERDUE"
    const val CHECK_IN = "CHECK_IN"
    const val DELETED = "DELETED"
}

enum class HistoryFilter(val label: String) {
    TODAY("Today"), YESTERDAY("Yesterday"), LAST_7("7 days"), ALL("All");

    /** Inclusive dayKey range, relative to the current logical day. */
    fun range(todayKey: String): Pair<String, String> = when (this) {
        TODAY -> todayKey to todayKey
        YESTERDAY -> DayKeys.shift(todayKey, -1).let { it to it }
        LAST_7 -> DayKeys.shift(todayKey, -6) to todayKey
        ALL -> "0000-01-01" to "9999-12-31"
    }
}

object HistoryFormatter {
    private fun pretty(status: String?) = status?.replace('_', ' ') ?: "?"

    /** [fmt] renders epoch millis; injected so this stays locale/zone-free and testable. */
    fun describe(e: TaskHistoryEntity, fmt: (Long) -> String): String {
        fun time(v: String?) = v?.toLongOrNull()?.let(fmt) ?: "?"
        return when (e.event) {
            TaskEvent.CREATED -> "Created · due ${time(e.newValue)}"
            TaskEvent.EDITED -> "Edited"
            TaskEvent.STATUS_CHANGED -> "${pretty(e.oldValue)} → ${pretty(e.newValue)}"
            TaskEvent.COMPLETED -> "Completed at ${time(e.newValue)}"
            TaskEvent.DEADLINE_CHANGED -> "Deadline ${time(e.oldValue)} → ${time(e.newValue)}"
            TaskEvent.OVERDUE -> "Overdue · deadline was ${time(e.oldValue)}"
            TaskEvent.CHECK_IN -> "Check-in: ${pretty(e.newValue)}"
            TaskEvent.DELETED -> "Deleted"
            else -> e.event
        }
    }
}
