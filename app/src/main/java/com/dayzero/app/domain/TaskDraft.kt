package com.dayzero.app.domain

data class TaskDraft(
    val title: String,
    val priority: Priority = Priority.NORMAL,
    val startTime: Long,
    val deadline: Long,
    val estimatedMinutes: Int = 30,
    val category: String? = null,
)

enum class TaskError(val message: String) {
    TITLE_EMPTY("Enter a task title."),
    TITLE_TOO_LONG("Title is too long (100 max)."),
    DEADLINE_BEFORE_START("Deadline must be after the start."),
    DEADLINE_IN_PAST("Deadline must be in the future."),
    DURATION_INVALID("Duration must be 1 minute to 24 hours."),
}

object TaskValidator {
    const val MAX_TITLE = 100

    fun validate(d: TaskDraft, now: Long, requireFutureDeadline: Boolean): List<TaskError> = buildList {
        val title = d.title.trim()
        if (title.isEmpty()) add(TaskError.TITLE_EMPTY)
        if (title.length > MAX_TITLE) add(TaskError.TITLE_TOO_LONG)
        if (d.estimatedMinutes !in 1..1440) add(TaskError.DURATION_INVALID)
        if (d.deadline <= d.startTime) add(TaskError.DEADLINE_BEFORE_START)
        else if (requireFutureDeadline && d.deadline <= now) add(TaskError.DEADLINE_IN_PAST)
    }
}
