package com.dayzero.app.domain

import com.dayzero.app.data.local.TaskEntity

enum class TaskSection { NOW, UPCOMING, COMPLETED }

/**
 * Display state is always derived from (task, now). Nothing here is persisted, so tasks move
 * between sections and become OVERDUE without any database writes.
 */
object TaskLogic {
    fun isOverdue(t: TaskEntity, now: Long): Boolean = t.status != TaskStatus.DONE && now > t.deadline

    fun sectionOf(t: TaskEntity, now: Long): TaskSection = when {
        t.status == TaskStatus.DONE -> TaskSection.COMPLETED
        t.status == TaskStatus.IN_PROGRESS -> TaskSection.NOW
        now >= t.startTime -> TaskSection.NOW
        else -> TaskSection.UPCOMING
    }
}

data class TaskBoard(
    val now: List<TaskEntity>,
    val upcoming: List<TaskEntity>,
    val completed: List<TaskEntity>,
) {
    val isEmpty get() = now.isEmpty() && upcoming.isEmpty() && completed.isEmpty()

    companion object {
        /**
         * @param completedFrom/completedTo only tasks finished inside this window are shown as
         * COMPLETED; older ones live in History so the list stays short.
         */
        fun build(all: List<TaskEntity>, nowMs: Long, completedFrom: Long, completedTo: Long): TaskBoard {
            val active = TaskSorting.activeOrder(all, nowMs)
            return TaskBoard(
                now = active.filter { TaskLogic.sectionOf(it, nowMs) == TaskSection.NOW },
                upcoming = active.filter { TaskLogic.sectionOf(it, nowMs) == TaskSection.UPCOMING },
                completed = all.filter {
                    it.status == TaskStatus.DONE && (it.completedAt ?: it.deadline) in completedFrom until completedTo
                }.sortedByDescending { it.completedAt ?: 0L },
            )
        }
    }
}

data class DailyProgress(
    val total: Int,
    val done: Int,
    val pending: Int,
    val overdue: Int,
    val percent: Int,
) {
    companion object {
        fun of(tasksForDay: List<TaskEntity>, now: Long): DailyProgress {
            val done = tasksForDay.count { it.status == TaskStatus.DONE }
            val total = tasksForDay.size
            return DailyProgress(
                total = total,
                done = done,
                pending = total - done,
                overdue = tasksForDay.count { TaskLogic.isOverdue(it, now) },
                percent = if (total == 0) 0 else done * 100 / total,
            )
        }
    }
}
