package com.dayzero.app.domain

import com.dayzero.app.data.local.UserSettingsEntity
import com.dayzero.app.support.HOUR
import com.dayzero.app.support.MIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderPlannerTest {
    private val s = UserSettingsEntity(firstReminderMin = 30, taskReminderIntervalMin = 15)
    private val now = 1_000_000_000L

    @Test fun futureStart_firstAlertAtStart() {
        val p = ReminderPlanner.initial(TaskStatus.NOT_STARTED, now + 10 * MIN, now + 2 * HOUR, now, s)
        assertEquals(Plan(0, now + 10 * MIN), p)
    }

    @Test fun startAlreadyPassed_skipsStartAlert_nextIsFirstReminder() {
        val p = ReminderPlanner.initial(TaskStatus.NOT_STARTED, now - MIN, now + 2 * HOUR, now, s)
        assertEquals(Plan(1, now + 30 * MIN), p)
    }

    @Test fun inProgressAndDone_haveNoPendingCheckIn() {
        assertNull(ReminderPlanner.initial(TaskStatus.IN_PROGRESS, now, now + HOUR, now, s).nextAlertAt)
        assertNull(ReminderPlanner.initial(TaskStatus.DONE, now, now + HOUR, now, s).nextAlertAt)
    }

    @Test fun ladder_isStart_then30_thenInterval_thenStops() {
        val a1 = ReminderPlanner.afterAlert(0, now + 10 * HOUR, now, s)
        assertEquals(Plan(1, now + 30 * MIN), a1)
        val a2 = ReminderPlanner.afterAlert(1, now + 10 * HOUR, now, s)
        assertEquals(Plan(2, now + 15 * MIN), a2)
        val a3 = ReminderPlanner.afterAlert(2, now + 10 * HOUR, now, s)
        assertEquals(Plan(3, null), a3)
    }

    @Test fun reminderNeverPlannedAtOrPastDeadline() {
        assertNull(ReminderPlanner.afterAlert(0, now + 30 * MIN, now, s).nextAlertAt)
        assertEquals(now + 30 * MIN, ReminderPlanner.afterAlert(0, now + 30 * MIN + 1, now, s).nextAlertAt)
    }

    @Test fun configuredIntervalsAreHonoured() {
        val custom = s.copy(firstReminderMin = 45, taskReminderIntervalMin = 10)
        assertEquals(now + 45 * MIN, ReminderPlanner.afterAlert(0, now + 9 * HOUR, now, custom).nextAlertAt)
        assertEquals(now + 10 * MIN, ReminderPlanner.afterAlert(1, now + 9 * HOUR, now, custom).nextAlertAt)
    }
}
