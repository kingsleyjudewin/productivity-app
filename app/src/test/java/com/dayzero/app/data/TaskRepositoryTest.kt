package com.dayzero.app.data

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
class TaskRepositoryTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() = env.close()

    private fun create(d: TaskDraft = draft()): Long = runBlocking { (env.repo.create(d) as TaskResult.Ok).id }
    private fun task(id: Long) = runBlocking { env.db.taskDao().get(id)!! }
    private fun events(id: Long) = runBlocking { env.db.taskHistoryDao().observeForTask(id).first() }.map { it.event }

    // ---- creation ----
    @Test fun create_persistsEverythingAndLogsCreation() {
        val id = create(draft("  Radar sim ", priority = Priority.CRITICAL).copy(category = " Work ", estimatedMinutes = 45))
        val t = task(id)
        assertEquals("Radar sim", t.name)
        assertEquals(Priority.CRITICAL, t.priority)
        assertEquals("Work", t.category)
        assertEquals(45, t.estimatedMinutes)
        assertEquals(TaskStatus.NOT_STARTED, t.status)
        assertEquals(t.deadline, t.originalDeadline)
        assertEquals(t.startTime, t.nextAlertAt)           // first alert is at the scheduled start
        assertEquals(listOf(TaskEvent.CREATED), events(id))
        val created = runBlocking { env.db.taskHistoryDao().observeForTask(id).first() }.single()
        assertEquals(t.deadline.toString(), created.newValue) // original deadline recorded
        assertEquals(id, env.scheduler.scheduled.last().id)
    }

    @Test fun create_quickTask_onlyTitleNeeded() {
        val id = create(TaskDraft("Call mom", startTime = T0, deadline = T0 + 30 * MIN))
        val t = task(id)
        assertEquals(Priority.NORMAL, t.priority)
        assertNull(t.category)
        assertEquals(30, t.estimatedMinutes)
    }

    @Test fun create_blankCategoryStoredAsNull() {
        assertNull(task(create(draft().copy(category = "   "))).category)
    }

    @Test fun create_invalidIsRejected_nothingSavedOrScheduled() {
        val r = runBlocking { env.repo.create(draft(title = " ")) }
        assertEquals(TaskResult.Invalid(listOf(TaskError.TITLE_EMPTY)), r)
        assertTrue(runBlocking { env.db.taskDao().getActive() }.isEmpty())
        assertTrue(env.scheduler.scheduled.isEmpty())
    }

    @Test fun create_pastDeadlineRejected() {
        val r = runBlocking { env.repo.create(draft(start = T0 - 2 * HOUR, deadline = T0 - HOUR)) }
        assertTrue(r is TaskResult.Invalid && TaskError.DEADLINE_IN_PAST in r.errors)
    }

    // ---- editing / rescheduling ----
    @Test fun edit_updatesFields_andLogsEdit() {
        val id = create()
        val r = runBlocking { env.repo.update(id, draft("Renamed", priority = Priority.HIGH)) }
        assertTrue(r is TaskResult.Ok)
        val t = task(id)
        assertEquals("Renamed", t.name); assertEquals(Priority.HIGH, t.priority)
        assertTrue(TaskEvent.EDITED in events(id))
        assertFalse(TaskEvent.DEADLINE_CHANGED in events(id))
    }

    @Test fun edit_deadlineChange_keepsOriginal_andLogsOldAndNew() {
        val id = create()
        val orig = task(id).deadline
        val newDeadline = orig + 2 * HOUR
        runBlocking { env.repo.update(id, draft(deadline = newDeadline)) }
        val t = task(id)
        assertEquals(newDeadline, t.deadline)
        assertEquals(orig, t.originalDeadline)
        val e = runBlocking { env.db.taskHistoryDao().observeForTask(id).first() }.first { it.event == TaskEvent.DEADLINE_CHANGED }
        assertEquals(orig.toString(), e.oldValue); assertEquals(newDeadline.toString(), e.newValue)
    }

    @Test fun edit_nameOnly_onOverdueTask_isAllowed() {
        val id = create()
        env.clock.t = task(id).deadline + HOUR
        val r = runBlocking { env.repo.update(id, draft("Still doing it")) }
        assertTrue(r is TaskResult.Ok)
    }

    @Test fun edit_invalidRejected_andTaskUnchanged() {
        val id = create()
        val r = runBlocking { env.repo.update(id, draft(title = "")) }
        assertTrue(r is TaskResult.Invalid)
        assertEquals("Write report", task(id).name)
    }

    @Test fun edit_missingTask_notFound() {
        assertEquals(TaskResult.NotFound, runBlocking { env.repo.update(999, draft()) })
    }

    @Test fun reschedule_resetsReminderState_andReplacesAlarms() {
        val id = create()
        env.clock.t = T0 + 10 * MIN
        runBlocking { env.engine.onCheckInDue(id) }
        assertEquals(1, task(id).reminderCount)
        val before = env.scheduler.scheduled.size
        val newStart = T0 + 5 * HOUR
        runBlocking { env.repo.update(id, draft(start = newStart, deadline = newStart + HOUR)) }
        val t = task(id)
        assertEquals(0, t.reminderCount)
        assertEquals(newStart, t.nextAlertAt)
        assertNull(t.overdueNotifiedAt)
        assertTrue(env.scheduler.scheduled.size > before)
        assertTrue(id in env.notifier.dismissed)              // stale notification for old schedule removed
    }

    // ---- deletion ----
    @Test fun delete_removesTask_cancelsAlarms_dismissesNotification_keepsHistory() {
        val id = create()
        assertTrue(runBlocking { env.repo.delete(id) } is TaskResult.Ok)
        assertNull(runBlocking { env.db.taskDao().get(id) })
        assertTrue(id in env.scheduler.cancelled)
        assertTrue(id in env.notifier.dismissed)
        assertEquals(listOf(TaskEvent.DELETED, TaskEvent.CREATED), events(id))
    }

    @Test fun delete_missing_notFound() {
        assertEquals(TaskResult.NotFound, runBlocking { env.repo.delete(42) })
    }

    // ---- status transitions ----
    @Test fun done_savesCompletionTimestamp_cancelsReminders_logsTransition() {
        val id = create()
        env.clock.t = T0 + 20 * MIN
        runBlocking { env.repo.setStatus(id, TaskStatus.DONE) }
        val t = task(id)
        assertEquals(TaskStatus.DONE, t.status)
        assertEquals(T0 + 20 * MIN, t.completedAt)
        assertNull(t.nextAlertAt)
        assertTrue(id in env.scheduler.cancelled)
        assertTrue(id in env.notifier.dismissed)
        val ev = events(id)
        assertTrue(TaskEvent.STATUS_CHANGED in ev && TaskEvent.COMPLETED in ev)
        val completed = runBlocking { env.db.taskHistoryDao().observeForTask(id).first() }.first { it.event == TaskEvent.COMPLETED }
        assertEquals((T0 + 20 * MIN).toString(), completed.newValue)
    }

    @Test fun inProgress_preservesDeadline_stopsCheckIns_keepsOverdueAlarmArmed() {
        val id = create()
        val deadline = task(id).deadline
        runBlocking { env.repo.setStatus(id, TaskStatus.IN_PROGRESS) }
        val t = task(id)
        assertEquals(TaskStatus.IN_PROGRESS, t.status)
        assertEquals(deadline, t.deadline); assertEquals(deadline, t.originalDeadline)
        assertNull(t.nextAlertAt)
        assertNull(t.overdueNotifiedAt)                       // overdue alarm still armed
        assertEquals(id, env.scheduler.scheduled.last().id)
    }

    @Test fun notStarted_afterDone_clearsCompletion_keepsHistory() {
        val id = create()
        runBlocking { env.repo.setStatus(id, TaskStatus.DONE) }
        env.clock.advance(5 * MIN)
        runBlocking { env.repo.setStatus(id, TaskStatus.NOT_STARTED) }
        val t = task(id)
        assertEquals(TaskStatus.NOT_STARTED, t.status)
        assertNull(t.completedAt)
        assertNotNull(t.nextAlertAt)                           // reminders resume
        assertTrue(TaskEvent.COMPLETED in events(id))          // history untouched
    }

    @Test fun sameStatus_isNoOp() {
        val id = create()
        val n = events(id).size
        runBlocking { env.repo.setStatus(id, TaskStatus.NOT_STARTED) }
        assertEquals(n, events(id).size)
    }

    @Test fun statusOfMissingTask_notFound() {
        assertEquals(TaskResult.NotFound, runBlocking { env.repo.setStatus(7, TaskStatus.DONE) })
    }
}
