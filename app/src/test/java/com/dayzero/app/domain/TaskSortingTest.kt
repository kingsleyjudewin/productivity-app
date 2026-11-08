package com.dayzero.app.domain

import com.dayzero.app.data.local.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskSortingTest {
    private val now = 1_000_000L
    private fun t(name: String, p: Priority, deadline: Long, s: TaskStatus = TaskStatus.NOT_STARTED) =
        TaskEntity(name = name, priority = p, startTime = 0, deadline = deadline, originalDeadline = deadline, status = s)

    @Test fun overdueFirst_thenPriority_thenDeadline() {
        val list = listOf(
            t("low-soon", Priority.LOW, now + 10),
            t("crit-late", Priority.CRITICAL, now + 500),
            t("crit-soon", Priority.CRITICAL, now + 100),
            t("overdue-low", Priority.LOW, now - 5),
        )
        assertEquals(
            listOf("overdue-low", "crit-soon", "crit-late", "low-soon"),
            TaskSorting.activeOrder(list, now).map { it.name },
        )
    }

    @Test fun completedTasksAreExcluded() {
        val list = listOf(t("done", Priority.CRITICAL, now + 1, TaskStatus.DONE), t("open", Priority.LOW, now + 1))
        assertEquals(listOf("open"), TaskSorting.activeOrder(list, now).map { it.name })
    }
}

class TaskSortingRulesTest {
    private val now = 1_000_000L
    private fun t(name: String, p: Priority, deadline: Long) =
        TaskEntity(name = name, priority = p, startTime = 0, deadline = deadline, originalDeadline = deadline)

    @Test fun fullRuleOrder_overdue_critical_high_normal_low_thenEarlierDeadline() {
        val sorted = TaskSorting.activeOrder(
            listOf(
                t("low", Priority.LOW, now + 1), t("normal-late", Priority.NORMAL, now + 90),
                t("normal-early", Priority.NORMAL, now + 10), t("high", Priority.HIGH, now + 50),
                t("critical", Priority.CRITICAL, now + 70), t("overdue-low", Priority.LOW, now - 1),
                t("overdue-crit", Priority.CRITICAL, now - 5),
            ),
            now,
        ).map { it.name }
        assertEquals(
            listOf("overdue-crit", "overdue-low", "critical", "high", "normal-early", "normal-late", "low"),
            sorted,
        )
    }
}
