package com.dayzero.app.data.repo

import androidx.room.withTransaction
import com.dayzero.app.data.local.AppUsageDao
import com.dayzero.app.data.local.AppUsageEntity
import com.dayzero.app.data.local.DayZeroDatabase
import com.dayzero.app.data.local.UsageLimitDao
import com.dayzero.app.domain.Clock
import com.dayzero.app.domain.DayKeys
import com.dayzero.app.domain.usage.Intervention
import com.dayzero.app.domain.usage.InterventionAction
import com.dayzero.app.domain.usage.InterventionMessages
import com.dayzero.app.domain.usage.InterventionNotifier
import com.dayzero.app.domain.usage.InterventionPolicy
import com.dayzero.app.domain.usage.InterventionState
import com.dayzero.app.domain.usage.UsageAccessChecker
import com.dayzero.app.domain.usage.UsageAggregator
import com.dayzero.app.domain.usage.UsageCheckScheduler
import com.dayzero.app.domain.usage.UsageEventSource
import com.dayzero.app.domain.usage.UsageFormat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

data class RefreshResult(
    val permissionGranted: Boolean,
    val totalScreenMs: Long = 0,
    /** How long the caller (service loop) should wait before refreshing again. */
    val nextDelayMs: Long = ScreenTimeEngine.BASE_DELAY_MS,
    val foregroundMonitored: Set<String> = emptySet(),
)

/**
 * The single place that measures usage and decides on interventions. Called by the foreground
 * service, the WorkManager sync, a backup alarm and the UI. All callers are serialised by a
 * mutex, and every decision is persisted in one transaction, so concurrent callers cannot
 * double-fire an intervention.
 *
 * Usage numbers are recomputed from the system's event log (see [UsageAggregator]) and written
 * as absolute totals, which is also how "reconcile with Android's statistics" and "recover after
 * restart" work: a fresh process simply rebuilds the same totals.
 */
@Singleton
class ScreenTimeEngine @Inject constructor(
    private val db: DayZeroDatabase,
    private val usageDao: AppUsageDao,
    private val limitDao: UsageLimitDao,
    private val settingsRepo: SettingsRepository,
    private val source: UsageEventSource,
    private val access: UsageAccessChecker,
    private val notifier: InterventionNotifier,
    private val scheduler: UsageCheckScheduler,
    private val clock: Clock,
) {
    /** Replaceable in tests for deterministic messages. */
    var random: Random = Random.Default

    private val mutex = Mutex()
    private var agg: UsageAggregator? = null
    private var aggKey: String? = null
    private var aggReset: Int = -1
    private var aggEnd = 0L
    private var processingStart = 0L

    suspend fun refresh(): RefreshResult = mutex.withLock { doRefresh() }

    private suspend fun doRefresh(): RefreshResult {
        val s = settingsRepo.current()
        if (!access.isGranted()) {
            agg = null // never trust stale state across a permission gap
            if (s.monitoringEnabled || limitDao.getEnabled().isNotEmpty()) notifier.showPermissionRevoked()
            return RefreshResult(permissionGranted = false)
        }
        notifier.clearPermissionRevoked()

        val now = clock.now()
        val key = DayKeys.keyFor(now, s.resetMinuteOfDay)
        if (agg == null || aggKey != key || aggReset != s.resetMinuteOfDay) {
            // Day rolled over while we were alive: settle the finished day's totals first, so the
            // minutes between the last refresh and the reset are not lost from yesterday.
            if (agg != null && aggKey != null && aggKey != key && aggReset == s.resetMinuteOfDay) finalizeDay(agg!!, aggKey!!, aggEnd)
            val start = DayKeys.startMillis(key, s.resetMinuteOfDay)
            val end = DayKeys.endMillis(key, s.resetMinuteOfDay)
            processingStart = start - LOOKBACK_MS
            agg = UsageAggregator(start, end, processingStart)
            aggKey = key; aggReset = s.resetMinuteOfDay; aggEnd = end
        }
        val a = agg!!
        a.feed(source.events(if (a.lastTs > 0) a.lastTs else processingStart, now))
        val snap = a.snapshot(now)

        val limits = limitDao.getEnabled()
        val cooldownMs = s.interventionCooldownMin.coerceAtLeast(1) * MIN
        val pending = mutableListOf<Intervention>()
        var wakeAt: Long? = null
        var delay = BASE_DELAY_MS
        val fgMonitored = HashSet<String>()

        fun considerWake(at: Long) { wakeAt = minOf(wakeAt ?: Long.MAX_VALUE, at) }

        db.withTransaction {
            for (lim in limits) {
                val used = snap.perApp[lim.packageName] ?: 0L
                val limitMs = lim.limitMinutes * MIN
                val row = usageDao.get(lim.packageName, key) ?: AppUsageEntity(packageName = lim.packageName, dayKey = key)
                val foreground = lim.packageName in snap.foreground
                if (foreground) fgMonitored += lim.packageName
                val recentlyActive = snap.lastActive[lim.packageName]?.let { now - it <= InterventionPolicy.RECENT_MS } ?: false
                var next = row.copy(usageMs = used)
                if (InterventionPolicy.shouldFire(used, limitMs, row.state(), foreground || recentlyActive, now, cooldownMs)) {
                    next = next.copy(interventions = row.interventions + 1, lastInterventionAt = now, snoozeUntil = null)
                    val (headline, emoji) = InterventionMessages.pick(random, lim.limitMinutes, false)
                    pending += Intervention(
                        lim.packageName, lim.appLabel, headline, emoji,
                        "${lim.appLabel} · ${UsageFormat.short(used)} today · limit ${UsageFormat.minutesOf(lim.limitMinutes)}",
                        isTotal = false,
                    )
                }
                usageDao.upsert(next)
                if (foreground && used < limitMs) { delay = minOf(delay, (limitMs - used + 500).coerceAtLeast(MIN_DELAY_MS)); considerWake(now + (limitMs - used) + 1_000) }
                next.snoozeUntil?.takeIf { it > now }?.let { considerWake(it); delay = minOf(delay, (it - now + 500).coerceAtLeast(MIN_DELAY_MS)) }
            }

            // Whole-device screen time + optional overall limit.
            val totalRow = usageDao.get(AppUsageEntity.TOTAL, key) ?: AppUsageEntity(packageName = AppUsageEntity.TOTAL, dayKey = key)
            val totalLimitMs = s.dailyScreenLimitMinutes * MIN
            var totalNext = totalRow.copy(usageMs = snap.screenOnMs)
            if (InterventionPolicy.shouldFire(snap.screenOnMs, totalLimitMs, totalRow.state(), snap.screenOn, now, cooldownMs)) {
                totalNext = totalNext.copy(interventions = totalRow.interventions + 1, lastInterventionAt = now, snoozeUntil = null)
                val (headline, emoji) = InterventionMessages.pick(random, s.dailyScreenLimitMinutes, true)
                pending += Intervention(
                    AppUsageEntity.TOTAL, "Screen time", headline, emoji,
                    "${UsageFormat.short(snap.screenOnMs)} on your phone today · limit ${UsageFormat.minutesOf(s.dailyScreenLimitMinutes)}",
                    isTotal = true,
                )
            }
            usageDao.upsert(totalNext)
            if (snap.screenOn && totalLimitMs > 0 && snap.screenOnMs < totalLimitMs) {
                delay = minOf(delay, (totalLimitMs - snap.screenOnMs + 500).coerceAtLeast(MIN_DELAY_MS))
                considerWake(now + (totalLimitMs - snap.screenOnMs) + 1_000)
            }
            totalNext.snoozeUntil?.takeIf { it > now }?.let { considerWake(it) }
        }

        wakeAt?.let { scheduler.scheduleAt(it) } ?: scheduler.cancel()
        if (s.notificationsEnabled) pending.forEach { notifier.show(it) }
        return RefreshResult(true, snap.screenOnMs, delay, fgMonitored)
    }

    /** Writes final usage for a completed day. Usage only; a finished day never triggers alerts. */
    private suspend fun finalizeDay(old: UsageAggregator, key: String, dayEnd: Long) {
        old.feed(source.events(if (old.lastTs > 0) old.lastTs else processingStart, dayEnd))
        val snap = old.snapshot(dayEnd)
        db.withTransaction {
            for (lim in limitDao.getEnabled()) {
                val row = usageDao.get(lim.packageName, key) ?: continue
                usageDao.upsert(row.copy(usageMs = snap.perApp[lim.packageName] ?: 0L))
            }
            usageDao.get(AppUsageEntity.TOTAL, key)?.let { usageDao.upsert(it.copy(usageMs = snap.screenOnMs)) }
        }
    }

    private fun AppUsageEntity.state() = InterventionState(interventions, lastInterventionAt, snoozeUntil)

    companion object {
        const val MIN = 60_000L
        /** Poll interval while the screen is on and nothing is close to a limit. */
        const val BASE_DELAY_MS = 20_000L
        const val MIN_DELAY_MS = 2_000L
        /** Events before the day starts, read only to learn which app was already open. */
        const val LOOKBACK_MS = 30 * MIN
    }
}

/** Applies a tap on an intervention notification. */
@Singleton
class InterventionActionProcessor @Inject constructor(
    private val db: DayZeroDatabase,
    private val usageDao: AppUsageDao,
    private val settingsRepo: SettingsRepository,
    private val notifier: InterventionNotifier,
    private val scheduler: UsageCheckScheduler,
    private val clock: Clock,
) {
    suspend fun handle(key: String, action: InterventionAction) {
        val now = clock.now()
        val s = settingsRepo.current()
        val dayKey = DayKeys.keyFor(now, s.resetMinuteOfDay)
        val snoozeUntil = when (action) {
            InterventionAction.REMIND -> now + InterventionPolicy.REMIND_MS
            InterventionAction.FINISHED -> now + InterventionPolicy.FINISHED_QUIET_MS
            InterventionAction.DISMISS -> null
        }
        if (snoozeUntil != null) {
            db.withTransaction {
                usageDao.get(key, dayKey)?.let { usageDao.upsert(it.copy(snoozeUntil = snoozeUntil)) }
            }
            scheduler.scheduleAt(snoozeUntil)
        }
        notifier.dismiss(key)
    }
}
