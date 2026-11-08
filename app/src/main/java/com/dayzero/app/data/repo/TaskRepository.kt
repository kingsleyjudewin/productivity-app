package com.dayzero.app.data.repo

import androidx.room.withTransaction
import com.dayzero.app.data.local.DayZeroDatabase
import com.dayzero.app.data.local.TaskDao
import com.dayzero.app.data.local.TaskEntity
import com.dayzero.app.domain.Clock
import com.dayzero.app.domain.ReminderPlanner
import com.dayzero.app.domain.ReminderScheduler
import com.dayzero.app.domain.TaskDraft
import com.dayzero.app.domain.TaskError
import com.dayzero.app.domain.TaskEvent
import com.dayzero.app.domain.TaskNotifier
import com.dayzero.app.domain.TaskStatus
import com.dayzero.app.domain.TaskValidator
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TaskResult {
    data class Ok(val id: Long) : TaskResult
    data class Invalid(val errors: List<TaskError>) : TaskResult
    data object NotFound : TaskResult
}

/**
 * Single entry point for every task mutation. Each mutation writes the task, its history entry and
 * the reminder plan in ONE transaction, then syncs the OS alarms, so the DB is always the truth.
 */
@Singleton
class TaskRepository @Inject constructor(
    private val db: DayZeroDatabase,
    private val dao: TaskDao,
    private val settingsRepo: SettingsRepository,
    private val history: HistoryLogger,
    private val scheduler: ReminderScheduler,
    private val notifier: TaskNotifier,
    private val clock: Clock,
) {
    fun observeForDay(start: Long, end: Long): Flow<List<TaskEntity>> = dao.observeForDay(start, end)
    fun observeAll(): Flow<List<TaskEntity>> = dao.observeAll()
    suspend fun get(id: Long): TaskEntity? = dao.get(id)

    suspend fun create(d: TaskDraft): TaskResult {
        val now = clock.now()
        val errors = TaskValidator.validate(d, now, requireFutureDeadline = true)
        if (errors.isNotEmpty()) return TaskResult.Invalid(errors)
        val s = settingsRepo.current()
        val plan = ReminderPlanner.initial(TaskStatus.NOT_STARTED, d.startTime, d.deadline, now, s)
        val saved = db.withTransaction {
            val base = TaskEntity(
                name = d.title.trim(), priority = d.priority, category = d.category?.trim()?.ifBlank { null },
                startTime = d.startTime, deadline = d.deadline, originalDeadline = d.deadline,
                estimatedMinutes = d.estimatedMinutes, createdAt = now,
                reminderCount = plan.reminderCount, nextAlertAt = plan.nextAlertAt,
            )
            val withId = base.copy(id = dao.insert(base))
            history.log(withId, TaskEvent.CREATED, null, d.deadline.toString())
            withId
        }
        scheduler.schedule(saved)
        return TaskResult.Ok(saved.id)
    }

    /** Edits and reschedules. [TaskEntity.originalDeadline] is never touched. */
    suspend fun update(id: Long, d: TaskDraft): TaskResult {
        val now = clock.now()
        val s = settingsRepo.current()
        var errors: List<TaskError> = emptyList()
        var timingChanged = false
        val updated = db.withTransaction {
            val old = dao.get(id) ?: return@withTransaction null
            val deadlineChanged = d.deadline != old.deadline
            errors = TaskValidator.validate(d, now, requireFutureDeadline = deadlineChanged)
            if (errors.isNotEmpty()) return@withTransaction null
            timingChanged = deadlineChanged || d.startTime != old.startTime
            val fieldsChanged = d.title.trim() != old.name || d.priority != old.priority ||
                d.estimatedMinutes != old.estimatedMinutes || d.category?.trim()?.ifBlank { null } != old.category
            var t = old.copy(
                name = d.title.trim(), priority = d.priority, category = d.category?.trim()?.ifBlank { null },
                startTime = d.startTime, deadline = d.deadline, estimatedMinutes = d.estimatedMinutes,
            )
            if (timingChanged) {
                val plan = ReminderPlanner.initial(t.status, t.startTime, t.deadline, now, s)
                t = t.copy(reminderCount = plan.reminderCount, nextAlertAt = plan.nextAlertAt, overdueNotifiedAt = null)
            }
            dao.update(t)
            if (fieldsChanged) history.log(t, TaskEvent.EDITED, old.name, t.name)
            if (deadlineChanged) history.log(t, TaskEvent.DEADLINE_CHANGED, old.deadline.toString(), t.deadline.toString())
            t
        }
        if (errors.isNotEmpty()) return TaskResult.Invalid(errors)
        if (updated == null) return TaskResult.NotFound
        if (updated.status == TaskStatus.DONE) scheduler.cancel(id) else scheduler.schedule(updated)
        if (timingChanged) notifier.dismiss(id) // a notification for the old schedule is stale
        return TaskResult.Ok(id)
    }

    suspend fun setStatus(id: Long, status: TaskStatus): TaskResult {
        val now = clock.now()
        val s = settingsRepo.current()
        val updated = db.withTransaction {
            val old = dao.get(id) ?: return@withTransaction null
            if (old.status == status) return@withTransaction old
            val t = when (status) {
                TaskStatus.DONE -> old.copy(status = status, completedAt = now, nextAlertAt = null)
                // Check-ins stop once the user says they're working; the overdue alarm stays armed.
                TaskStatus.IN_PROGRESS -> old.copy(status = status, completedAt = null, nextAlertAt = null)
                TaskStatus.NOT_STARTED -> {
                    val plan = ReminderPlanner.initial(status, old.startTime, old.deadline, now, s)
                    old.copy(status = status, completedAt = null, reminderCount = plan.reminderCount, nextAlertAt = plan.nextAlertAt)
                }
            }
            dao.update(t)
            history.log(t, TaskEvent.STATUS_CHANGED, old.status.name, status.name)
            if (status == TaskStatus.DONE) history.log(t, TaskEvent.COMPLETED, null, now.toString())
            t
        } ?: return TaskResult.NotFound
        if (updated.status == TaskStatus.DONE) {
            scheduler.cancel(id)
            notifier.dismiss(id)
        } else {
            scheduler.schedule(updated)
        }
        return TaskResult.Ok(id)
    }

    /** User answered "NOT STARTED" on a check-in: status is unchanged, reminders continue. */
    suspend fun recordCheckIn(id: Long, answer: TaskStatus): TaskResult {
        val now = clock.now()
        val t = db.withTransaction {
            val old = dao.get(id) ?: return@withTransaction null
            if (old.status == TaskStatus.DONE) return@withTransaction old
            val t = old.copy(lastCheckInAt = now)
            dao.update(t)
            history.log(t, TaskEvent.CHECK_IN, null, answer.name)
            t
        } ?: return TaskResult.NotFound
        scheduler.schedule(t)
        return TaskResult.Ok(id)
    }

    suspend fun delete(id: Long): TaskResult {
        val found = db.withTransaction {
            val t = dao.get(id) ?: return@withTransaction false
            history.log(t, TaskEvent.DELETED)
            dao.delete(id)
            true
        }
        if (!found) return TaskResult.NotFound
        scheduler.cancel(id)
        notifier.dismiss(id)
        return TaskResult.Ok(id)
    }
}
