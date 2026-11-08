package com.dayzero.app.reminders

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.dayzero.app.data.local.TaskEntity
import com.dayzero.app.domain.Clock
import com.dayzero.app.domain.ReminderScheduler
import com.dayzero.app.domain.TaskStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class AlarmKind { CHECK_IN, OVERDUE }

/**
 * Two alarms per task at most (check-in, overdue), each identified by a data URI. Re-scheduling
 * the same task+kind REPLACES the alarm, which is what makes boot restore and WorkManager syncs
 * idempotent. Exact alarms are used when the OS permits; otherwise it degrades to inexact
 * (still Doze-aware, but may arrive minutes late).
 */
@Singleton
class AlarmReminderScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val clock: Clock,
) : ReminderScheduler {

    private val am = ctx.getSystemService(AlarmManager::class.java)

    override fun schedule(task: TaskEntity) {
        if (task.status == TaskStatus.DONE) return cancel(task.id)
        val soonest = clock.now() + 1_000
        task.nextAlertAt?.let { set(task.id, AlarmKind.CHECK_IN, maxOf(it, soonest)) }
            ?: cancel(task.id, AlarmKind.CHECK_IN)
        if (task.overdueNotifiedAt == null) set(task.id, AlarmKind.OVERDUE, maxOf(task.deadline, soonest))
        else cancel(task.id, AlarmKind.OVERDUE)
    }

    override fun cancel(taskId: Long) {
        AlarmKind.entries.forEach { cancel(taskId, it) }
    }

    private fun cancel(taskId: Long, kind: AlarmKind) {
        pending(taskId, kind, create = false)?.let { am.cancel(it); it.cancel() }
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun set(taskId: Long, kind: AlarmKind, at: Long) {
        val pi = pending(taskId, kind, create = true) ?: return
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pending(taskId: Long, kind: AlarmKind, create: Boolean): PendingIntent? {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_ALARM)
            .setData(Uri.parse("dayzero://alarm/$taskId/${kind.name}"))
            .putExtra(TaskIntents.EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_KIND, kind.name)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(ctx, 0, intent, flags)
    }

    companion object {
        const val ACTION_ALARM = "com.dayzero.app.action.ALARM"
        const val EXTRA_KIND = "alarm_kind"
    }
}
