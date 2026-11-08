package com.dayzero.app.domain

import com.dayzero.app.data.local.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLogicTest {
    private val now = 10_000_000L
    private fun t(
        name: String, start: Long, deadline: Long, p: Priority = Priority.NORMAL,
        s: TaskStatus = TaskStatus.NOT_STARTED, completedAt: Long? = null,
    ) = TaskEntity(name = name, priority = p, startTime = start, deadline = deadline, originalDeadline = deadline, status = s, completedAt = completedAt)

    @Test fun sections() {
        assertEquals(TaskSection.NOW, TaskLogic.sectionOf(t("a", now - 1, now + 10), now))
        assertEquals(TaskSection.NOW, TaskLogic.sectionOf(t("a", now, now + 10), now))
        assertEquals(TaskSection.UPCOMING, TaskLogic.sectionOf(t("a", now + 1, now + 10), now))
        assertEquals(TaskSection.COMPLETED, TaskLogic.sectionOf(t("a", now - 5, now + 10, s = TaskStatus.DONE), now))
        // Being worked on counts as NOW even before the scheduled start.
        assertEquals(TaskSection.NOW, TaskLogic.sectionOf(t("a", now + 99, now + 200, s = TaskStatus.IN_PROGRESS), now))
    }

    @Test fun tasksMoveSectionsAsTimePasses_withoutAnyStateChange() {
        val task = t("a", now + 100, now + 500)
        assertEquals(TaskSection.UPCOMING, TaskLogic.sectionOf(task, now))
        assertEquals(TaskSection.NOW, TaskLogic.sectionOf(task, now + 100))
    }

    @Test fun overdue_onlyWhenPastDeadlineAndNotDone() {
        assertTrue(TaskLogic.isOverdue(t("a", 0, now - 1), now))
        assertFalse(TaskLogic.isOverdue(t("a", 0, now), now)) // exactly at deadline is not yet late
        assertFalse(TaskLogic.isOverdue(t("a", 0, now - 1, s = TaskStatus.DONE), now))
        assertTrue(TaskLogic.isOverdue(t("a", 0, now - 1, s = TaskStatus.IN_PROGRESS), now))
    }

    @Test fun board_excludesDoneFromNowAndUpcoming_andSortsByRules() {
        val tasks = listOf(
            t("low", now - 1, now + 50, Priority.LOW),
            t("crit", now - 1, now + 90, Priority.CRITICAL),
            t("late-low", now - 100, now - 10, Priority.LOW),
            t("future", now + 5, now + 60, Priority.HIGH),
            t("done", now - 1, now + 5, s = TaskStatus.DONE, completedAt = now - 2),
        )
        val b = TaskBoard.build(tasks, now, now - 1000, now + 1000)
        assertEquals(listOf("late-low", "crit", "low"), b.now.map { it.name })
        assertEquals(listOf("future"), b.upcoming.map { it.name })
        assertEquals(listOf("done"), b.completed.map { it.name })
    }

    @Test fun board_completedOutsideWindowIsHidden() {
        val old = t("old", 0, 10, s = TaskStatus.DONE, completedAt = 5)
        assertTrue(TaskBoard.build(listOf(old), now, now - 100, now + 100).completed.isEmpty())
    }

    @Test fun dailyProgress_counts() {
        val tasks = listOf(
            t("d1", 0, now + 10, s = TaskStatus.DONE), t("d2", 0, now + 10, s = TaskStatus.DONE),
            t("p", 0, now + 10), t("o", 0, now - 10),
        )
        val p = DailyProgress.of(tasks, now)
        assertEquals(DailyProgress(total = 4, done = 2, pending = 2, overdue = 1, percent = 50), p)
    }

    @Test fun dailyProgress_emptyIsZeroNotDivideByZero() {
        assertEquals(DailyProgress(0, 0, 0, 0, 0), DailyProgress.of(emptyList(), now))
    }

    @Test fun dailyProgress_roundsDown() {
        val tasks = listOf(t("a", 0, 1, s = TaskStatus.DONE), t("b", 0, now + 1), t("c", 0, now + 1))
        assertEquals(33, DailyProgress.of(tasks, now).percent)
    }
}
