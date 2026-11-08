package com.dayzero.app.ui.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayzero.app.data.local.NotificationRuleEntity
import com.dayzero.app.data.local.TransactionEntity
import com.dayzero.app.data.repo.BudgetRepository
import com.dayzero.app.data.repo.PaymentAppRepository
import com.dayzero.app.data.repo.SettingsRepository
import com.dayzero.app.data.repo.TransactionDraft
import com.dayzero.app.data.repo.TransactionRepository
import com.dayzero.app.data.repo.TxResult
import com.dayzero.app.domain.DayKeys
import com.dayzero.app.domain.money.BudgetStatus
import com.dayzero.app.domain.money.TxCategory
import com.dayzero.app.domain.money.TxFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MoneyState(
    val currency: String = "INR",
    val status: BudgetStatus = BudgetStatus.of(0, 0),
    val count: Int = 0,
    val unknown: Int = 0,
    val dayKey: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MoneyViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val txRepo: TransactionRepository,
    private val budgets: BudgetRepository,
    private val apps: PaymentAppRepository,
) : ViewModel() {

    val state: StateFlow<MoneyState> = settingsRepo.settings.flatMapLatest { s ->
        val key = DayKeys.keyFor(System.currentTimeMillis(), s.resetMinuteOfDay)
        combine(txRepo.observeSpent(key), budgets.observeLimit(key), txRepo.observeCount(key), txRepo.observeUnknownCount()) { spent, limit, n, u ->
            MoneyState(s.currency, BudgetStatus.of(spent, limit), n, u, key)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoneyState())

    val filter = MutableStateFlow(TxFilter.TODAY)

    val history: StateFlow<List<TransactionEntity>> = combine(settingsRepo.settings, filter) { s, f ->
        f.range(DayKeys.keyFor(System.currentTimeMillis(), s.resetMinuteOfDay))
    }.flatMapLatest { (from, to) -> txRepo.observeBetween(from, to) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currency: StateFlow<String> = MutableStateFlow("INR").also { out ->
        viewModelScope.launch { settingsRepo.settings.collect { out.value = it.currency } }
    }

    val selectedApps: StateFlow<List<NotificationRuleEntity>> =
        apps.observeSelected().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setBudget(minor: Long, fromTomorrow: Boolean) = viewModelScope.launch { budgets.setBudget(minor, fromTomorrow) }
    fun toggleApp(pkg: String, label: String, on: Boolean) =
        viewModelScope.launch { if (on) apps.select(pkg, label) else apps.deselect(pkg) }

    fun save(id: Long?, d: TransactionDraft, onResult: (String?) -> Unit) = viewModelScope.launch {
        val r = if (id == null) txRepo.addManual(d) else txRepo.update(id, d)
        onResult((r as? TxResult.Invalid)?.message)
    }
    fun delete(id: Long) = viewModelScope.launch { txRepo.delete(id) }
    fun setCategory(id: Long, c: TxCategory) = viewModelScope.launch { txRepo.setCategory(id, c) }
}
