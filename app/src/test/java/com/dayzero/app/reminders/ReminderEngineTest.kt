package com.dayzero.app.reminders

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayzero.app.support.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

import com.dayzero.app.data.repo.TaskResult
import com.dayzero.app.domain.*

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class ReminderEngineTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() = env.close()

    private fun create(d: TaskDraft = draft()): Long = runBlocking { (env.repo.create(d) as TaskResult.Ok).id }
    private fun task(id: Long) = runBlocking { env.db.taskDao().get(id)!! }
    private fun checkIn(id: Long) = runBlocking { env.engine.onCheckInDue(id) }
    private fun overdue(id: Long) = runBlocking { env.engine.onOverdueDue(id) }
    private fun events(id: Long) = runBlocking { env.db.taskHistoryDao().observeForTask(id).first() }.map { it.event }

    @Test fun ladder_startThen30min_thenInterval_thenStops() {
        val id = create()                                      // start T0+10m, deadline T0+4h
        env.clock.t = T0 + 10 * MIN; checkIn(id)
        assertEquals(1, env.notifier.checkIns.size)
        assertEquals(T0 + 40 * MIN, task(id).nextAlertAt)      // +30 min
        env.clock.t = T0 + 40 * MIN; checkIn(id)
        assertEquals(2, env.notifier.checkIns.size)
        assertEquals(T0 + 55 * MIN, task(id).nextAlertAt)      // +15 min configured interval
        env.clock.t = T0 + 55 * MIN; checkIn(id)
        assertEquals(3, env.notifier.checkIns.size)
        assertNull(task(id).nextAlertAt)                       // capped: no more spam
        env.clock.t = T0 + 3 * HOUR; checkIn(id)
        assertEquals(3, env.notifier.checkIns.size)
    }

    @Test fun duplicateAlarmDelivery_notifiesOnce() {
        val id = create()
        env.clock.t = T0 + 10 * MIN
        checkIn(id); checkIn(id); checkIn(id)
        assertEquals(1, env.notifier.checkIns.size)
    }

    @Test fun alarmBeforeItIsDue_isIgnored() {
        val id = create()
        env.clock.t = T0 + 5 * MIN
        checkIn(id)
        assertTrue(env.notifier.checkIns.isEmpty())
        assertEquals(0, task(id).reminderCount)
    }

    @Test fun notificationCarriesTheRightTask() {
        create(draft("Other")); val id = create(draft("DSA practice"))
        env.clock.t = T0 + 10 * MIN; checkIn(id)
        assertEquals(listOf("DSA practice"), env.notifier.checkIns.map { it.name })
        assertEquals(id, env.notifier.checkIns.single().id)
    }

    @Test fun done_stopsReminders() {
        val id = create()
        env.clock.t = T0 + 10 * MIN; checkIn(id)
        runBlocking { env.repo.setStatus(id, TaskStatus.DONE) }
        env.clock.t = T0 + 40 * MIN; checkIn(id)
        assertEquals(1, env.notifier.checkIns.size)
        assertTrue(id in env.scheduler.cancelled)
    }

    @Test fun inProgress_stopsCheckIns() {
        val id = create()
        runBlocking { env.repo.setStatus(id, TaskStatus.IN_PROGRESS) }
        env.clock.t = T0 + 10 * MIN; checkIn(id)
        assertTrue(env.notifier.checkIns.isEmpty())
    }

    @Test fun deleted_stopsReminders_andIsSafe() {
        val id = create()
        runBlocking { env.repo.delete(id) }
        env.clock.t = T0 + 10 * MIN; checkIn(id); overdue(id)
        assertTrue(env.notifier.checkIns.isEmpty() && env.notifier.overdues.isEmpty())
    }

    @Test fun reminderNotScheduledPastDeadline() {
        val id = create(draft(start = T0 + 10 * MIN, deadline = T0 + 25 * MIN)) // 15 min window
        env.clock.t = T0 + 10 * MIN; checkIn(id)
        assertEquals(1, env.notifier.checkIns.size)
        assertNull(task(id).nextAlertAt)                       // +30 would overshoot the deadline
    }

    @Test fun notificationsDisabled_noPost_butStateAdvances() {
        runBlocking { env.settings.update { it.copy(notificationsEnabled = false) } }
        val id = create()
        env.clock.t = T0 + 10 * MIN; checkIn(id)
        assertTrue(env.notifier.checkIns.isEmpty())
        assertEquals(1, task(id).reminderCount)
    }

    @Test fun customReminderPreferences_areUsed() {
        runBlocking { env.settings.update { it.copy(firstReminderMin = 45, taskReminderIntervalMin = 10) } }
        val id = create()
        env.clock.t = T0 + 10 * MIN; checkIn(id)
        assertEquals(T0 + 55 * MIN, task(id).nextAlertAt)
    }

    // ---- overdue ----
    @Test fun overdue_firesOnce_recordsHistory_andNeverCompletesTheTask() {
        val id = create(draft(start = T0 + 10 * MIN, deadline = T0 + HOUR))
        val deadline = task(id).deadline
        env.clock.t = deadline + MIN
        overdue(id); overdue(id)
        assertEquals(1, env.notifier.overdues.size)
        assertEquals(TaskStatus.NOT_STARTED, task(id).status)
        assertNotNull(task(id).overdueNotifiedAt)
        val e = runBlocking { env.db.taskHistoryDao().observeForTask(id).first() }.single { it.event == TaskEvent.OVERDUE }
        assertEquals(deadline.toString(), e.oldValue)           // original deadline preserved in history
    }

    @Test fun overdue_beforeDeadline_ignored() {
        val id = create()
        env.clock.t = T0 + HOUR; overdue(id)
        assertTrue(env.notifier.overdues.isEmpty())
    }

    @Test fun overdue_notSentForDoneTask() {
        val id = create()
        runBlocking { env.repo.setStatus(id, TaskStatus.DONE) }
        env.clock.t = task(id).deadline + HOUR; overdue(id)
        assertTrue(env.notifier.overdues.isEmpty())
    }

    @Test fun overdue_stillSentForInProgressTask() {
        val id = create()
        runBlocking { env.repo.setStatus(id, TaskStatus.IN_PROGRESS) }
        env.clock.t = task(id).deadline + MIN; overdue(id)
        assertEquals(1, env.notifier.overdues.size)
    }

    @Test fun reschedulingAnOverdueTask_rearmsTheOverdueNotice_andStopsOldReminders() {
        val id = create(draft(start = T0 + 10 * MIN, deadline = T0 + HOUR))
        env.clock.t = T0 + HOUR + MIN; overdue(id)
        val newDeadline = env.clock.t + 2 * HOUR
        runBlocking { env.repo.update(id, draft(start = T0 + 10 * MIN, deadline = newDeadline)) }
        assertNull(task(id).overdueNotifiedAt)
        env.clock.t = newDeadline + MIN; overdue(id)
        assertEquals(2, env.notifier.overdues.size)
        assertEquals(newDeadline, task(id).deadline)
        assertEquals(T0 + HOUR, task(id).originalDeadline)
    }

    // ---- WorkManager / boot recovery ----
    @Test fun syncAll_isIdempotent_noDuplicateNotifications() {
        val id = create()
        env.clock.t = T0 + 10 * MIN
        runBlocking { env.engine.syncAll(); env.engine.syncAll(); env.engine.syncAll() }
        assertEquals(1, env.notifier.checkIns.size)
        assertEquals(1, task(id).reminderCount)
    }

    @Test fun syncAll_catchesUpOnMissedAlerts_afterLongOutage() {
        val id = create(draft(start = T0 + 10 * MIN, deadline = T0 + HOUR))
        env.clock.t = T0 + 3 * HOUR                             // phone was off / process killed
        runBlocking { env.engine.syncAll() }
        assertEquals(1, env.notifier.checkIns.size)             // one catch-up, not three
        assertEquals(1, env.notifier.overdues.size)
        assertEquals(TaskStatus.NOT_STARTED, task(id).status)
    }

    @Test fun syncAll_rearmsAlarmsFromDatabase_afterReboot() {
        val a = create(draft("A")); val b = create(draft("B"))
        env.scheduler.scheduled.clear()                         // simulate alarms wiped by reboot
        runBlocking { env.engine.syncAll() }
        assertEquals(setOf(a, b), env.scheduler.scheduled.map { it.id }.toSet())
        assertTrue(env.scheduler.scheduled.all { it.nextAlertAt == T0 + 10 * MIN })
    }

    @Test fun syncAll_skipsDoneTasks() {
        val id = create(); runBlocking { env.repo.setStatus(id, TaskStatus.DONE) }
        env.scheduler.scheduled.clear()
        runBlocking { env.engine.syncAll() }
        assertTrue(env.scheduler.scheduled.isEmpty())
    }

    @Test fun syncAll_repairsUnplannedTask() {
        val id = create()
        runBlocking { env.db.taskDao().update(task(id).copy(nextAlertAt = null, reminderCount = 0)) }
        runBlocking { env.engine.syncAll() }
        assertEquals(T0 + 10 * MIN, task(id).nextAlertAt)
    }
}
