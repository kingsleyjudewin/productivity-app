package com.dayzero.app.domain

import com.dayzero.app.data.local.UserSettingsEntity

/** Where a task sits on the reminder ladder: how many alerts are spent and when the next is due. */
data class Plan(val reminderCount: Int, val nextAlertAt: Long?)

/**
 * The reminder ladder, kept pure so it is fully unit-testable:
 *   alert 1 at the scheduled start  ->  alert 2 after [firstReminderMin]  ->  alert 3 after the
 *   configured interval  ->  stop. Three alerts is the cap, so a task never spams the user.
 * Reminders are never planned at or after the deadline; the overdue notice takes over there.
 */
object ReminderPlanner {
    const val MAX_ALERTS = 3
    private const val MIN = 60_000L

    /** Plan for a task that is new, rescheduled or reopened. */
    fun initial(status: TaskStatus, start: Long, deadline: Long, now: Long, s: UserSettingsEntity): Plan {
        if (status != TaskStatus.NOT_STARTED) return Plan(0, null)
        if (start > now) return Plan(0, start)
        // Start already passed (e.g. "start now"): the start alert is considered satisfied.
        return Plan(1, clamp(now + gapFor(1, s), deadline))
    }

    /** Plan after an alert has just been delivered at [now]. */
    fun afterAlert(sent: Int, deadline: Long, now: Long, s: UserSettingsEntity): Plan {
        val count = sent + 1
        if (count >= MAX_ALERTS) return Plan(count, null)
        return Plan(count, clamp(now + gapFor(count, s), deadline))
    }

    private fun gapFor(count: Int, s: UserSettingsEntity): Long =
        (if (count <= 1) s.firstReminderMin else s.taskReminderIntervalMin).coerceAtLeast(1) * MIN

    private fun clamp(at: Long, deadline: Long): Long? = if (at >= deadline) null else at
}
