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

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import com.dayzero.app.data.local.TaskEntity
import com.dayzero.app.reminders.AlarmReminderScheduler
import com.dayzero.app.reminders.AndroidTaskNotifier
import com.dayzero.app.reminders.Channels
import com.dayzero.app.reminders.NotifIds
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class AndroidNotificationAndAlarmTest {
    private val ctx: Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)
    private val am get() = ctx.getSystemService(AlarmManager::class.java)
    private lateinit var notifier: AndroidTaskNotifier
    private val clock = FakeClock()

    private fun task(id: Long = 7, name: String = "Finish radar simulation", next: Long? = T0 + MIN, overdueAt: Long? = null) =
        TaskEntity(id = id, name = name, startTime = T0, deadline = T0 + HOUR, originalDeadline = T0 + HOUR, nextAlertAt = next, overdueNotifiedAt = overdueAt)

    @Before fun setUp() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Channels.ensure(ctx)
        notifier = AndroidTaskNotifier(ctx)
    }

    private fun posted() = shadowOf(nm).allNotifications

    @Test fun checkIn_hasTitleTaskNameQuestion_andThreeActions() {
        notifier.showCheckIn(task())
        val n = posted().single()
        assertEquals("DAYZERO — TIME TO EXECUTE", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("Finish radar simulation", n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertTrue(n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().contains("Have you completed this task?"))
        assertEquals(listOf("DONE", "IN PROGRESS", "NOT STARTED"), n.actions.map { it.title.toString() })
        assertEquals(Channels.CHECK_IN, n.channelId)
    }

    @Test fun actionButtons_carryTheTaskId_safely() {
        notifier.showCheckIn(task(id = 7))
        val n = posted().single()
        n.actions.forEach {
            val intent = shadowOf(it.actionIntent).savedIntent
            assertEquals(7L, intent.getLongExtra("task_id", -1))
            assertTrue(intent.data.toString().startsWith("dayzero://task/7/"))
        }
    }

    @Test fun postingTwiceForTheSameTask_replacesInsteadOfDuplicating() {
        notifier.showCheckIn(task()); notifier.showCheckIn(task()); notifier.showCheckIn(task())
        assertEquals(1, posted().size)
    }

    @Test fun differentTasks_getSeparateNotifications() {
        notifier.showCheckIn(task(id = 1)); notifier.showCheckIn(task(id = 2))
        assertEquals(2, posted().size)
        assertNotEquals(NotifIds.checkIn(1), NotifIds.checkIn(2))
        assertNotEquals(NotifIds.checkIn(1), NotifIds.overdue(1))
    }

    @Test fun overdue_offersDoneContinueReschedule() {
        notifier.showOverdue(task())
        val n = posted().single()
        assertEquals("DAYZERO — OVERDUE", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(listOf("DONE", "CONTINUE", "RESCHEDULE"), n.actions.map { it.title.toString() })
        val resched = shadowOf(n.actions[2].actionIntent).savedIntent
        assertEquals(7L, resched.getLongExtra("reschedule_task_id", -1))
    }

    @Test fun dismiss_removesBothNotificationsForThatTaskOnly() {
        notifier.showCheckIn(task(id = 1)); notifier.showOverdue(task(id = 1)); notifier.showCheckIn(task(id = 2))
        notifier.dismiss(1)
        assertEquals(1, posted().size)
    }

    @Test fun nothingPosted_whenNotificationsAreBlocked() {
        shadowOf(nm).setNotificationsEnabled(false)
        notifier.showCheckIn(task())
        assertTrue(posted().isEmpty())
    }

    @Test fun nothingPosted_whenRuntimePermissionRevoked() {
        shadowOf(ctx as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.showCheckIn(task())
        assertTrue(posted().isEmpty())
    }

    // ---- alarms ----
    private fun scheduler() = AlarmReminderScheduler(ctx, clock)
    private fun alarms() = shadowOf(am).scheduledAlarms

    @Test fun schedule_createsCheckInAndOverdueAlarms() {
        scheduler().schedule(task())
        assertEquals(2, alarms().size)
    }

    @Test fun schedule_isIdempotent_noDuplicatesAfterRepeatedRestore() {
        val s = scheduler()
        repeat(5) { s.schedule(task()) }
        assertEquals(2, alarms().size)
    }

    @Test fun schedule_replacesAlarmWhenTimeChanges() {
        val s = scheduler()
        s.schedule(task(next = T0 + 5 * MIN))
        s.schedule(task(next = T0 + 9 * MIN))
        val times = alarms().map { it.triggerAtTime }
        assertTrue(T0 + 9 * MIN in times && T0 + 5 * MIN !in times)
    }

    @Test fun cancel_removesAllAlarmsForTask() {
        val s = scheduler()
        s.schedule(task(id = 1)); s.schedule(task(id = 2))
        s.cancel(1)
        assertEquals(2, alarms().size)
    }

    @Test fun doneTask_hasNoAlarms() {
        val s = scheduler()
        s.schedule(task())
        s.schedule(task().copy(status = com.dayzero.app.domain.TaskStatus.DONE))
        assertTrue(alarms().isEmpty())
    }

    @Test fun noPendingCheckIn_keepsOnlyOverdueAlarm() {
        scheduler().schedule(task(next = null))
        assertEquals(1, alarms().size)
        assertEquals(T0 + HOUR, alarms().single().triggerAtTime)
    }

    @Test fun afterOverdueNoticeSent_overdueAlarmIsRemoved() {
        val s = scheduler()
        s.schedule(task(next = null))
        s.schedule(task(next = null, overdueAt = T0 + HOUR))
        assertTrue(alarms().isEmpty())
    }

    @Test fun exactAlarmPermissionDenied_stillSchedulesViaInexactFallback() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        scheduler().schedule(task())
        assertEquals(2, alarms().size)
    }

    @Test fun alarmInThePast_isFiredSoonRatherThanDropped() {
        scheduler().schedule(task(next = T0 - HOUR))
        assertTrue(alarms().all { it.triggerAtTime >= T0 })
    }
}
