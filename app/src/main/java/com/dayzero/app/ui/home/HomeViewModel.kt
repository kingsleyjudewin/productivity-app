package com.dayzero.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayzero.app.data.repo.BudgetRepository
import com.dayzero.app.data.repo.SettingsRepository
import com.dayzero.app.domain.money.BudgetState
import com.dayzero.app.domain.money.BudgetStatus
import com.dayzero.app.data.repo.TaskRepository
import com.dayzero.app.data.repo.TransactionRepository
import com.dayzero.app.data.repo.UsageRepository
import com.dayzero.app.domain.DayKeys
import com.dayzero.app.domain.MoneyFormat
import com.dayzero.app.domain.DailyProgress
import com.dayzero.app.domain.TaskSorting
import com.dayzero.app.domain.usage.UsageFormat
import com.dayzero.app.data.repo.ScreenTimeEngine
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

data class HomeState(
    val dateLabel: String = "",
    val progressPercent: Int = 0,
    val tasksDone: Int = 0,
    val tasksTotal: Int = 0,
    val pending: Int = 0,
    val overdue: Int = 0,
    val screenText: String = "0 min",
    val screenSub: String? = null,
    val screenOverLimit: Boolean = false,
    val spentText: String = "",
    val budgetText: String? = null,
    val overBudget: Boolean = false,
    val budgetSub: String? = null,
    val priorities: List<String> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    settingsRepo: SettingsRepository,
    taskRepo: TaskRepository,
    usageRepo: UsageRepository,
    txRepo: TransactionRepository,
    budgetRepo: BudgetRepository,
    private val engine: ScreenTimeEngine,
) : ViewModel() {

    /** Pull fresh usage when Home becomes visible; a no-op without usage access. */
    fun refresh() { viewModelScope.launch { engine.refresh() } }


    private val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(30_000) } }

    val state: StateFlow<HomeState> = combine(settingsRepo.settings, ticker) { s, now ->
        Triple(s, DayKeys.keyFor(now, s.resetMinuteOfDay), now)
    }.distinctUntilChanged { a, b -> a.first == b.first && a.second == b.second }
        .flatMapLatest { (s, key, _) ->
            val start = DayKeys.startMillis(key, s.resetMinuteOfDay)
            val end = DayKeys.endMillis(key, s.resetMinuteOfDay)
            combine(
                taskRepo.observeForDay(start, end), usageRepo.observeTotalScreenMs(key),
                txRepo.observeSpent(key), budgetRepo.observeLimit(key), ticker,
            ) { tasks, usageMs, spent, limit, now ->
                val b = BudgetStatus.of(spent, limit)
                val progress = DailyProgress.of(tasks, now)
                val open = TaskSorting.activeOrder(tasks, now)
                HomeState(
                    dateLabel = LocalDate.parse(key).format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                    progressPercent = progress.percent,
                    tasksDone = progress.done,
                    tasksTotal = progress.total,
                    pending = progress.pending,
                    overdue = progress.overdue,
                    screenText = UsageFormat.minutes(usageMs),
                    screenSub = UsageFormat.limitSub(usageMs, s.dailyScreenLimitMinutes),
                    screenOverLimit = s.dailyScreenLimitMinutes > 0 && usageMs >= s.dailyScreenLimitMinutes * 60_000L,
                    spentText = MoneyFormat.format(spent, s.currency),
                    budgetText = if (limit > 0) MoneyFormat.format(limit, s.currency) else null,
                    overBudget = b.state == BudgetState.OVER,
                    budgetSub = when (b.state) {
                        BudgetState.OVER -> "Over by ${MoneyFormat.format(b.overByMinor, s.currency)}"
                        BudgetState.WITHIN -> "${MoneyFormat.format(b.remainingMinor, s.currency)} left"
                        BudgetState.NO_BUDGET -> null
                    },
                    priorities = open.take(3).map { it.name },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}
