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
import com.dayzero.app.reminders.TaskIntents

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class NotificationActionProcessorTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() = env.close()

    private fun create(): Long = runBlocking { (env.repo.create(draft()) as TaskResult.Ok).id }
    private fun task(id: Long) = runBlocking { env.db.taskDao().get(id)!! }
    private fun handle(id: Long, a: TaskAction) = runBlocking { env.processor.handle(id, a) }

    @Test fun done_updatesRoomImmediately_andClearsNotification() {
        val id = create(); handle(id, TaskAction.DONE)
        assertEquals(TaskStatus.DONE, task(id).status)
        assertNotNull(task(id).completedAt)
        assertTrue(id in env.notifier.dismissed)
        assertTrue(id in env.scheduler.cancelled)
    }

    @Test fun inProgress_updatesRoom() {
        val id = create(); handle(id, TaskAction.IN_PROGRESS)
        assertEquals(TaskStatus.IN_PROGRESS, task(id).status)
        assertTrue(id in env.notifier.dismissed)
    }

    @Test fun notStarted_keepsStatus_recordsCheckIn_andKeepsReminding() {
        val id = create()
        env.clock.t = T0 + 10 * MIN
        runBlocking { env.engine.onCheckInDue(id) }
        env.clock.advance(MIN)
        handle(id, TaskAction.NOT_STARTED)
        val t = task(id)
        assertEquals(TaskStatus.NOT_STARTED, t.status)
        assertEquals(env.clock.t, t.lastCheckInAt)
        assertNotNull(t.nextAlertAt)                            // next nudge still pending
        assertTrue(id in env.notifier.dismissed)
        val h = runBlocking { env.db.taskHistoryDao().observeForTask(id).first() }
        assertTrue(h.any { it.event == TaskEvent.CHECK_IN && it.newValue == "NOT_STARTED" })
    }

    @Test fun actionForDeletedTask_doesNotCrash() {
        handle(12345, TaskAction.DONE)
    }

    @Test fun actionOnAlreadyDoneTask_isHarmless() {
        val id = create(); handle(id, TaskAction.DONE)
        val completedAt = task(id).completedAt
        env.clock.advance(HOUR)
        handle(id, TaskAction.NOT_STARTED)
        assertEquals(TaskStatus.DONE, task(id).status)
        assertEquals(completedAt, task(id).completedAt)
    }

    @Test fun intentActions_roundTrip_andAreUniquePerTaskAndAction() {
        TaskAction.entries.forEach { assertEquals(it, TaskIntents.parse(TaskIntents.actionName(it))) }
        assertNull(TaskIntents.parse("something.else"))
        val ctx = env.ctx
        val a = org.robolectric.Shadows.shadowOf(TaskIntents.actionPending(ctx, 1, TaskAction.DONE)).savedIntent
        val b = org.robolectric.Shadows.shadowOf(TaskIntents.actionPending(ctx, 2, TaskAction.DONE)).savedIntent
        val c = org.robolectric.Shadows.shadowOf(TaskIntents.actionPending(ctx, 1, TaskAction.IN_PROGRESS)).savedIntent
        assertEquals(1L, a.getLongExtra(TaskIntents.EXTRA_TASK_ID, -1))
        assertEquals(2L, b.getLongExtra(TaskIntents.EXTRA_TASK_ID, -1))
        assertFalse(a.filterEquals(b)); assertFalse(a.filterEquals(c))
    }
}
