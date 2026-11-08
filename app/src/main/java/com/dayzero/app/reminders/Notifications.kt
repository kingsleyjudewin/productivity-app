package com.dayzero.app.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dayzero.app.MainActivity
import com.dayzero.app.R
import com.dayzero.app.data.local.TaskEntity
import com.dayzero.app.domain.TaskAction
import com.dayzero.app.domain.TaskNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

object Channels {
    const val CHECK_IN = "task_checkin"
    const val OVERDUE = "task_overdue"
    const val INTERVENTION = "usage_intervention"
    const val MONITOR = "usage_monitor"
    const val MONITOR_STATUS = "usage_monitor_status"
    const val BUDGET = "budget_alert"

    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHECK_IN, "Task check-ins", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Asks whether a task is done when it is time to execute." },
        )
        nm.createNotificationChannel(
            NotificationChannel(OVERDUE, "Overdue tasks", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Tells you once when a task passes its deadline." },
        )
        nm.createNotificationChannel(
            NotificationChannel(INTERVENTION, "Screen-time interventions", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Interrupts you when a monitored app reaches its daily limit." },
        )
        nm.createNotificationChannel(
            NotificationChannel(MONITOR, "Monitoring status", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while DAYZERO watches your screen time. Silent."; setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(MONITOR_STATUS, "Monitoring problems", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Tells you if monitoring stops because a permission was turned off." },
        )
        nm.createNotificationChannel(
            NotificationChannel(BUDGET, "Budget alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Tells you once when today's spending crosses your daily limit." },
        )
    }
}

object NotifIds {
    // One stable id per (task, kind): re-posting replaces the old notification instead of stacking.
    fun checkIn(taskId: Long): Int = (taskId * 2).toInt()
    fun overdue(taskId: Long): Int = (taskId * 2 + 1).toInt()
}

object TaskIntents {
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_RESCHEDULE_TASK_ID = "reschedule_task_id"
    const val ACTION_DONE = "com.dayzero.app.action.DONE"
    const val ACTION_IN_PROGRESS = "com.dayzero.app.action.IN_PROGRESS"
    const val ACTION_NOT_STARTED = "com.dayzero.app.action.NOT_STARTED"

    fun actionName(a: TaskAction) = when (a) {
        TaskAction.DONE -> ACTION_DONE
        TaskAction.IN_PROGRESS -> ACTION_IN_PROGRESS
        TaskAction.NOT_STARTED -> ACTION_NOT_STARTED
    }

    fun parse(action: String?): TaskAction? = when (action) {
        ACTION_DONE -> TaskAction.DONE
        ACTION_IN_PROGRESS -> TaskAction.IN_PROGRESS
        ACTION_NOT_STARTED -> TaskAction.NOT_STARTED
        else -> null
    }

    /** The task id travels as an extra AND in the data URI, which keeps each PendingIntent unique. */
    fun actionPending(ctx: Context, taskId: Long, a: TaskAction): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, TaskActionReceiver::class.java)
                .setAction(actionName(a))
                .setData(Uri.parse("dayzero://task/$taskId/${a.name}"))
                .putExtra(EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun openApp(ctx: Context, taskId: Long, reschedule: Boolean): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .setData(Uri.parse("dayzero://open/$taskId/${if (reschedule) "reschedule" else "view"}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (reschedule) putExtra(EXTRA_RESCHEDULE_TASK_ID, taskId) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

@Singleton
class AndroidTaskNotifier @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : TaskNotifier {

    override fun showCheckIn(task: TaskEntity) {
        val n = NotificationCompat.Builder(ctx, Channels.CHECK_IN)
            .base(task, "DAYZERO — TIME TO EXECUTE", openApp = TaskIntents.openApp(ctx, task.id, false))
            .setStyle(NotificationCompat.BigTextStyle().bigText("${task.name}\nHave you completed this task?"))
            .addAction(0, "DONE", TaskIntents.actionPending(ctx, task.id, TaskAction.DONE))
            .addAction(0, "IN PROGRESS", TaskIntents.actionPending(ctx, task.id, TaskAction.IN_PROGRESS))
            .addAction(0, "NOT STARTED", TaskIntents.actionPending(ctx, task.id, TaskAction.NOT_STARTED))
            .build()
        post(NotifIds.checkIn(task.id), n)
    }

    override fun showOverdue(task: TaskEntity) {
        val n = NotificationCompat.Builder(ctx, Channels.OVERDUE)
            .base(task, "DAYZERO — OVERDUE", openApp = TaskIntents.openApp(ctx, task.id, false))
            .setStyle(NotificationCompat.BigTextStyle().bigText("${task.name}\nThe deadline has passed. What's the status?"))
            .addAction(0, "DONE", TaskIntents.actionPending(ctx, task.id, TaskAction.DONE))
            .addAction(0, "CONTINUE", TaskIntents.actionPending(ctx, task.id, TaskAction.IN_PROGRESS))
            .addAction(0, "RESCHEDULE", TaskIntents.openApp(ctx, task.id, reschedule = true))
            .build()
        post(NotifIds.overdue(task.id), n)
    }

    override fun dismiss(taskId: Long) {
        val nm = NotificationManagerCompat.from(ctx)
        nm.cancel(NotifIds.checkIn(taskId))
        nm.cancel(NotifIds.overdue(taskId))
    }

    private fun NotificationCompat.Builder.base(task: TaskEntity, title: String, openApp: PendingIntent) = this
        .setSmallIcon(R.drawable.ic_stat_dayzero)
        .setContentTitle(title)
        .setContentText(task.name)
        .setContentIntent(openApp)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setOnlyAlertOnce(true)
        .setAutoCancel(true)

    private fun post(id: Int, n: Notification) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try { nm.notify(id, n) } catch (_: SecurityException) { /* permission revoked mid-flight */ }
    }
}
