package com.dayzero.app.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayzero.app.data.local.TaskEntity
import com.dayzero.app.data.repo.SettingsRepository
import com.dayzero.app.data.repo.TaskRepository
import com.dayzero.app.data.repo.TaskResult
import com.dayzero.app.domain.Clock
import com.dayzero.app.domain.DailyProgress
import com.dayzero.app.domain.DayKeys
import com.dayzero.app.domain.TaskBoard
import com.dayzero.app.domain.TaskDraft
import com.dayzero.app.domain.TaskError
import com.dayzero.app.domain.TaskStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class TasksState(
    val dateLabel: String = "",
    val nowMs: Long = 0,
    val progress: DailyProgress = DailyProgress(0, 0, 0, 0, 0),
    val board: TaskBoard = TaskBoard(emptyList(), emptyList(), emptyList()),
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TasksViewModel @Inject constructor(
    settingsRepo: SettingsRepository,
    private val repo: TaskRepository,
    private val clock: Clock,
) : ViewModel() {

    // Re-evaluated every 30s so tasks slide NOW/UPCOMING and turn OVERDUE with zero DB writes.
    private val ticker = flow { while (true) { emit(clock.now()); delay(30_000) } }

    val state: StateFlow<TasksState> = combine(settingsRepo.settings, ticker) { s, now ->
        s.resetMinuteOfDay to DayKeys.keyFor(now, s.resetMinuteOfDay)
    }.distinctUntilChanged()
        .flatMapLatest { (reset, key) ->
            val start = DayKeys.startMillis(key, reset)
            val end = DayKeys.endMillis(key, reset)
            combine(repo.observeAll(), repo.observeForDay(start, end), ticker) { all, today, now ->
                TasksState(
                    dateLabel = LocalDate.parse(key).format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                    nowMs = now,
                    progress = DailyProgress.of(today, now),
                    board = TaskBoard.build(all, now, start, end),
                    loaded = true,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksState())

    fun setStatus(task: TaskEntity, status: TaskStatus) {
        viewModelScope.launch { repo.setStatus(task.id, status) }
    }

    /** [onResult] gets an empty list on success, otherwise the validation errors to show. */
    fun save(editingId: Long?, draft: TaskDraft, onResult: (List<TaskError>) -> Unit) {
        viewModelScope.launch {
            val r = if (editingId == null) repo.create(draft) else repo.update(editingId, draft)
            onResult((r as? TaskResult.Invalid)?.errors ?: emptyList())
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }
}
