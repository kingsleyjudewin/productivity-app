package com.dayzero.app.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayzero.app.data.local.TaskHistoryDao
import com.dayzero.app.data.local.TaskHistoryEntity
import com.dayzero.app.data.repo.SettingsRepository
import com.dayzero.app.domain.Clock
import com.dayzero.app.domain.DayKeys
import com.dayzero.app.domain.HistoryFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    dao: TaskHistoryDao,
    settingsRepo: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    val filter = MutableStateFlow(HistoryFilter.TODAY)

    val entries: StateFlow<List<TaskHistoryEntity>> =
        combine(filter, settingsRepo.settings) { f, s -> f to DayKeys.keyFor(clock.now(), s.resetMinuteOfDay) }
            .flatMapLatest { (f, key) ->
                val (from, to) = f.range(key)
                dao.observeBetween(from, to)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
