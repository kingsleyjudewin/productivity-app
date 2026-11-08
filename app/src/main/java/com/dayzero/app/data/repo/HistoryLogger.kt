package com.dayzero.app.data.repo

import com.dayzero.app.data.local.TaskEntity
import com.dayzero.app.data.local.TaskHistoryDao
import com.dayzero.app.data.local.TaskHistoryEntity
import com.dayzero.app.domain.Clock
import com.dayzero.app.domain.DayKeys
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryLogger @Inject constructor(
    private val dao: TaskHistoryDao,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    suspend fun log(task: TaskEntity, event: String, old: String? = null, new: String? = null) {
        val now = clock.now()
        dao.insert(
            TaskHistoryEntity(
                taskId = task.id, taskName = task.name, event = event, oldValue = old, newValue = new,
                timestamp = now, dayKey = DayKeys.keyFor(now, settings.current().resetMinuteOfDay),
            ),
        )
    }
}
