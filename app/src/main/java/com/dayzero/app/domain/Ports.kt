package com.dayzero.app.domain

import com.dayzero.app.data.local.TaskEntity

/** Schedules / cancels the OS alarms that wake the app for a task. Idempotent by design. */
interface ReminderScheduler {
    /** Makes the OS alarms match the task's persisted state (replaces, adds or removes). */
    fun schedule(task: TaskEntity)
    fun cancel(taskId: Long)
}

/** Posts and removes task notifications. Posting twice for the same task replaces, never stacks. */
interface TaskNotifier {
    fun showCheckIn(task: TaskEntity)
    fun showOverdue(task: TaskEntity)
    fun dismiss(taskId: Long)
}

enum class TaskAction { DONE, IN_PROGRESS, NOT_STARTED }
