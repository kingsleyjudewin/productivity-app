package com.dayzero.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayzero.app.data.local.AppUsageEntity
import com.dayzero.app.data.repo.ScreenTimeEngine
import com.dayzero.app.data.repo.SettingsRepository
import com.dayzero.app.data.repo.UsageLimitRepository
import com.dayzero.app.data.repo.UsageRepository
import com.dayzero.app.domain.Clock
import com.dayzero.app.domain.DayKeys
import com.dayzero.app.monitor.InstalledApp
import com.dayzero.app.monitor.InstalledApps
import com.dayzero.app.monitor.MonitorController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppRow(val pkg: String, val label: String, val usedMs: Long, val limitMin: Int, val interventions: Int) {
    val limitMs get() = limitMin * 60_000L
    val remainingMs get() = limitMs - usedMs
}

data class ScreenState(
    val loaded: Boolean = false,
    val totalMs: Long = 0,
    val monitoredMs: Long = 0,
    val limitMin: Int = 0,
    val interventions: Int = 0,
    val monitoringEnabled: Boolean = true,
    val rows: List<AppRow> = emptyList(),
) {
    val remainingMs get() = if (limitMin > 0) limitMin * 60_000L - totalMs else null
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScreenTimeViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    usageRepo: UsageRepository,
    private val limits: UsageLimitRepository,
    private val engine: ScreenTimeEngine,
    private val monitor: MonitorController,
    private val clock: Clock,
) : ViewModel() {

    private val ticker = flow { while (true) { emit(clock.now()); delay(60_000) } }

    val state: StateFlow<ScreenState> = combine(settingsRepo.settings, ticker) { s, now ->
        s.resetMinuteOfDay to DayKeys.keyFor(now, s.resetMinuteOfDay)
    }.distinctUntilChanged().flatMapLatest { (_, key) ->
        combine(settingsRepo.settings, limits.observeAll(), usageRepo.observeForDay(key)) { s, lims, usage ->
            val byPkg = usage.associateBy { it.packageName }
            val total = byPkg[AppUsageEntity.TOTAL]
            val rows = lims.map { l ->
                val u = byPkg[l.packageName]
                AppRow(l.packageName, l.appLabel, u?.usageMs ?: 0L, l.limitMinutes, u?.interventions ?: 0)
            }.sortedByDescending { it.usedMs }
            ScreenState(
                loaded = true,
                totalMs = total?.usageMs ?: 0L,
                monitoredMs = rows.sumOf { it.usedMs },
                limitMin = s.dailyScreenLimitMinutes,
                interventions = usage.sumOf { it.interventions },
                monitoringEnabled = s.monitoringEnabled,
                rows = rows,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenState())

    fun refresh() { viewModelScope.launch { engine.refresh() } }

    fun setAppLimit(pkg: String, label: String, minutes: Int) {
        viewModelScope.launch { limits.set(pkg, label, minutes); engine.refresh() }
    }

    fun removeApp(pkg: String) { viewModelScope.launch { limits.remove(pkg); engine.refresh() } }

    fun setTotalLimit(minutes: Int) {
        viewModelScope.launch { settingsRepo.update { it.copy(dailyScreenLimitMinutes = minutes) }; engine.refresh() }
    }

    fun setMonitoring(on: Boolean) {
        viewModelScope.launch { settingsRepo.update { it.copy(monitoringEnabled = on) }; monitor.sync() }
    }
}

data class SelectableApp(val app: InstalledApp, val selected: Boolean, val limitMin: Int)

@HiltViewModel
class AppSelectionViewModel @Inject constructor(
    private val installed: InstalledApps,
    private val limits: UsageLimitRepository,
    private val settingsRepo: SettingsRepository,
    private val engine: ScreenTimeEngine,
) : ViewModel() {
    private val apps = MutableStateFlow<List<InstalledApp>?>(null)
    val query = MutableStateFlow("")

    init { viewModelScope.launch { apps.value = installed.load() } }

    /** Selected first, then suggested social apps, then A-Z; filtered by the search text. */
    val list: StateFlow<List<SelectableApp>?> = combine(apps, limits.observeAll(), query) { all, lims, q ->
        all ?: return@combine null
        val byPkg = lims.associateBy { it.packageName }
        all.filter { q.isBlank() || it.label.contains(q.trim(), ignoreCase = true) }
            .map { SelectableApp(it, it.pkg in byPkg, byPkg[it.pkg]?.limitMinutes ?: 0) }
            .sortedWith(
                compareByDescending<SelectableApp> { it.selected }
                    .thenByDescending { it.app.suggested }
                    .thenBy { it.app.label.lowercase() },
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggle(a: SelectableApp) {
        viewModelScope.launch {
            if (a.selected) limits.remove(a.app.pkg)
            else limits.set(a.app.pkg, a.app.label, settingsRepo.current().defaultLimitMinutes)
            engine.refresh()
        }
    }
}
