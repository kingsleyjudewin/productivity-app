package com.dayzero.app.data.repo

import androidx.room.withTransaction
import com.dayzero.app.data.local.DayZeroDatabase
import com.dayzero.app.data.local.TaskDao
import com.dayzero.app.data.local.TaskEntity
import com.dayzero.app.domain.Clock
import com.dayzero.app.domain.ReminderPlanner
import com.dayzero.app.domain.ReminderScheduler
import com.dayzero.app.domain.TaskEvent
import com.dayzero.app.domain.TaskNotifier
import com.dayzero.app.domain.TaskStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs when an alarm fires, when WorkManager syncs, and after boot. Every entry point is
 * idempotent: it re-reads persisted state inside a transaction and only acts if something is
 * genuinely due, so duplicate alarms, a late worker, or a reboot can never double-notify.
 */
@Singleton
class ReminderEngine @Inject constructor(
    private val db: DayZeroDatabase,
    private val dao: TaskDao,
    private val settingsRepo: SettingsRepository,
    private val history: HistoryLogger,
    private val scheduler: ReminderScheduler,
    private val notifier: TaskNotifier,
    private val clock: Clock,
) {
    suspend fun onCheckInDue(taskId: Long) {
        val now = clock.now()
        val s = settingsRepo.current()
        val fired = db.withTransaction {
            val t = dao.get(taskId) ?: return@withTransaction null
            val due = t.nextAlertAt
            if (t.status == TaskStatus.DONE || due == null || due > now + TOLERANCE_MS) return@withTransaction null
            val plan = ReminderPlanner.afterAlert(t.reminderCount, t.deadline, now, s)
            t.copy(reminderCount = plan.reminderCount, nextAlertAt = plan.nextAlertAt).also { dao.update(it) }
        } ?: return
        scheduler.schedule(fired)
        if (s.notificationsEnabled) notifier.showCheckIn(fired)
    }

    suspend fun onOverdueDue(taskId: Long) {
        val now = clock.now()
        val s = settingsRepo.current()
        val fired = db.withTransaction {
            val t = dao.get(taskId) ?: return@withTransaction null
            if (t.status == TaskStatus.DONE || t.overdueNotifiedAt != null || t.deadline > now + TOLERANCE_MS) {
                return@withTransaction null
            }
            // Never marks the task complete; only records and announces that it is late.
            t.copy(overdueNotifiedAt = now).also {
                dao.update(it)
                history.log(it, TaskEvent.OVERDUE, t.deadline.toString(), null)
            }
        } ?: return
        scheduler.schedule(fired)
        if (s.notificationsEnabled) notifier.showOverdue(fired)
    }

    /** Re-arms everything from the database and fires anything that came due while we were dead. */
    suspend fun syncAll() {
        val s = settingsRepo.current()
        val now = clock.now()
        for (task in dao.getActive()) {
            repairIfUnplanned(task, now, s)
            onCheckInDue(task.id)
            onOverdueDue(task.id)
            dao.get(task.id)?.let { if (it.status != TaskStatus.DONE) scheduler.schedule(it) }
        }
    }

    private suspend fun repairIfUnplanned(t: TaskEntity, now: Long, s: com.dayzero.app.data.local.UserSettingsEntity) {
        if (t.status == TaskStatus.NOT_STARTED && t.reminderCount == 0 && t.nextAlertAt == null) {
            val plan = ReminderPlanner.initial(t.status, t.startTime, t.deadline, now, s)
            dao.update(t.copy(reminderCount = plan.reminderCount, nextAlertAt = plan.nextAlertAt))
        }
    }

    companion object {
        /** Alarms can fire a few seconds early on some OEMs; accept that without double-firing later. */
        const val TOLERANCE_MS = 5_000L
    }
}

/** Applies a notification button press to the database. */
@Singleton
class NotificationActionProcessor @Inject constructor(
    private val repo: TaskRepository,
    private val notifier: TaskNotifier,
) {
    suspend fun handle(taskId: Long, action: com.dayzero.app.domain.TaskAction) {
        when (action) {
            com.dayzero.app.domain.TaskAction.DONE -> repo.setStatus(taskId, TaskStatus.DONE)
            com.dayzero.app.domain.TaskAction.IN_PROGRESS -> repo.setStatus(taskId, TaskStatus.IN_PROGRESS)
            com.dayzero.app.domain.TaskAction.NOT_STARTED -> repo.recordCheckIn(taskId, TaskStatus.NOT_STARTED)
        }
        notifier.dismiss(taskId)
    }
}
