package com.dayzero.app.domain

import com.dayzero.app.data.local.TaskEntity

object TaskSorting {
    /**
     * Unfinished tasks ordered by what to do first: overdue items, then by priority,
     * then by nearest deadline, then by name for a stable order.
     */
    fun activeOrder(tasks: List<TaskEntity>, now: Long): List<TaskEntity> =
        tasks.filter { it.status != TaskStatus.DONE }
            .sortedWith(
                compareBy<TaskEntity> { !TaskLogic.isOverdue(it, now) }
                    .thenBy { it.priority.ordinal }
                    .thenBy { it.deadline }
                    .thenBy { it.name },
            )
}
