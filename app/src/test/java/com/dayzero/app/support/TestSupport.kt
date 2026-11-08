package com.dayzero.app.support

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dayzero.app.data.local.DayZeroDatabase
import com.dayzero.app.data.local.TaskEntity
import com.dayzero.app.data.repo.HistoryLogger
import com.dayzero.app.data.repo.NotificationActionProcessor
import com.dayzero.app.data.repo.ReminderEngine
import com.dayzero.app.data.repo.SettingsRepository
import com.dayzero.app.data.repo.TaskRepository
import com.dayzero.app.domain.Clock
import com.dayzero.app.domain.ReminderScheduler
import com.dayzero.app.domain.TaskDraft
import com.dayzero.app.domain.TaskNotifier
import java.time.ZoneId
import java.time.ZonedDateTime

const val MIN = 60_000L
const val HOUR = 60 * MIN

/** Sunday 8 Nov 2026, 09:00 in the machine's zone, so day-window maths is zone independent. */
val T0: Long = ZonedDateTime.of(2026, 11, 8, 9, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

class FakeClock(var t: Long = T0) : Clock {
    override fun now() = t
    fun advance(ms: Long) { t += ms }
}

class FakeScheduler : ReminderScheduler {
    val scheduled = mutableListOf<TaskEntity>()
    val cancelled = mutableListOf<Long>()
    override fun schedule(task: TaskEntity) { scheduled += task }
    override fun cancel(taskId: Long) { cancelled += taskId }
}

class FakeNotifier : TaskNotifier {
    val checkIns = mutableListOf<TaskEntity>()
    val overdues = mutableListOf<TaskEntity>()
    val dismissed = mutableListOf<Long>()
    override fun showCheckIn(task: TaskEntity) { checkIns += task }
    override fun showOverdue(task: TaskEntity) { overdues += task }
    override fun dismiss(taskId: Long) { dismissed += taskId }
}

/** A real in-memory Room database with the real repositories, and fakes only at the OS edge. */
class TestEnv {
    val ctx: Context = ApplicationProvider.getApplicationContext()
    val db: DayZeroDatabase = Room.inMemoryDatabaseBuilder(ctx, DayZeroDatabase::class.java)
        .allowMainThreadQueries().build()
    val clock = FakeClock()
    val scheduler = FakeScheduler()
    val notifier = FakeNotifier()
    val settings = SettingsRepository(db.settingsDao())
    val history = HistoryLogger(db.taskHistoryDao(), settings, clock)
    val repo = TaskRepository(db, db.taskDao(), settings, history, scheduler, notifier, clock)
    val engine = ReminderEngine(db, db.taskDao(), settings, history, scheduler, notifier, clock)
    val processor = NotificationActionProcessor(repo, notifier)

    fun close() = db.close()
}

fun draft(
    title: String = "Write report",
    start: Long = T0 + 10 * MIN,
    deadline: Long = T0 + 4 * HOUR,
    priority: com.dayzero.app.domain.Priority = com.dayzero.app.domain.Priority.NORMAL,
) = TaskDraft(title, priority, start, deadline, 30, null)
