package com.dayzero.app.data.repo

import androidx.room.withTransaction
import com.dayzero.app.data.local.BudgetDao
import com.dayzero.app.data.local.BudgetEntity
import com.dayzero.app.data.local.DayZeroDatabase
import com.dayzero.app.data.local.NotificationRuleDao
import com.dayzero.app.data.local.NotificationRuleEntity
import com.dayzero.app.data.local.NotificationSeenDao
import com.dayzero.app.data.local.NotificationSeenEntity
import com.dayzero.app.data.local.TransactionDao
import com.dayzero.app.data.local.TransactionEntity
import com.dayzero.app.domain.Clock
import com.dayzero.app.domain.DayKeys
import com.dayzero.app.domain.TransactionSource
import com.dayzero.app.domain.TransactionType
import com.dayzero.app.domain.money.BudgetNotifier
import com.dayzero.app.domain.money.BudgetState
import com.dayzero.app.domain.money.BudgetStatus
import com.dayzero.app.domain.money.DuplicateMatcher
import com.dayzero.app.domain.money.Match
import com.dayzero.app.domain.money.ParseOutcome
import com.dayzero.app.domain.money.ParsedPayment
import com.dayzero.app.domain.money.ParserRegistry
import com.dayzero.app.domain.money.PaymentNotification
import com.dayzero.app.domain.money.TxCategory
import com.dayzero.app.domain.money.sameReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-day budgets. A day's limit is its own row, else the latest earlier row, else none. Changing
 * the budget therefore only ever affects the day it starts on and the days after it.
 */
@Singleton
class BudgetRepository @Inject constructor(
    private val db: DayZeroDatabase,
    private val dao: BudgetDao,
    private val txDao: TransactionDao,
    private val settingsRepo: SettingsRepository,
    private val notifier: BudgetNotifier,
    private val clock: Clock,
) {
    fun observeLimit(dayKey: String): Flow<Long> = dao.observeLimitFor(dayKey).map { it ?: 0L }
    suspend fun limitFor(dayKey: String): Long = dao.limitFor(dayKey) ?: 0L

    /** [fromTomorrow] = true leaves today's limit untouched. 0 means "no budget" from that day on. */
    suspend fun setBudget(limitMinor: Long, fromTomorrow: Boolean = false) {
        require(limitMinor >= 0)
        val s = settingsRepo.current()
        val today = DayKeys.keyFor(clock.now(), s.resetMinuteOfDay)
        val day = if (fromTomorrow) DayKeys.shift(today, 1) else today
        db.withTransaction {
            val existing = dao.get(day)
            dao.upsert((existing ?: BudgetEntity(day, limitMinor, s.currency)).copy(limitMinor = limitMinor, currency = s.currency))
        }
        reevaluate(today)
    }

    /**
     * Recomputes today's budget state from the database and alerts only on a fresh crossing. Called
     * after every change that could move spending: new/edited/deleted transactions, type
     * corrections and budget edits. Past days are never alerted on.
     */
    suspend fun reevaluate(dayKey: String) {
        val s = settingsRepo.current()
        val now = clock.now()
        if (dayKey != DayKeys.keyFor(now, s.resetMinuteOfDay)) return
        var alert: BudgetStatus? = null
        var cleared = false
        db.withTransaction {
            val status = BudgetStatus.of(txDao.spentFor(dayKey), dao.limitFor(dayKey) ?: 0L)
            val row = dao.get(dayKey)
            if (status.state == BudgetState.OVER && row?.exceededNotifiedAt == null) {
                dao.upsert((row ?: BudgetEntity(dayKey, status.limitMinor, s.currency)).copy(exceededNotifiedAt = now))
                alert = status
            } else if (status.state != BudgetState.OVER && row?.exceededNotifiedAt != null) {
                dao.upsert(row.copy(exceededNotifiedAt = null))
                cleared = true
            }
        }
        alert?.let { if (s.notificationsEnabled) notifier.showExceeded(it.overByMinor, s.currency) }
        if (cleared) notifier.dismissExceeded()
    }
}

data class TransactionDraft(
    val amountMinor: Long,
    val merchant: String?,
    val category: TxCategory?,
    val timestamp: Long,
    val type: TransactionType,
)

sealed interface TxResult {
    data class Ok(val id: Long) : TxResult
    data class Invalid(val message: String) : TxResult
    data object NotFound : TxResult
}

sealed interface IngestResult {
    data class Inserted(val tx: TransactionEntity) : IngestResult
    data class Updated(val tx: TransactionEntity) : IngestResult
    data object Duplicate : IngestResult
    data object Ignored : IngestResult
}

@Singleton
class TransactionRepository @Inject constructor(
    private val db: DayZeroDatabase,
    private val dao: TransactionDao,
    private val settingsRepo: SettingsRepository,
    private val budgets: BudgetRepository,
    private val clock: Clock,
) {
    fun observeSpent(dayKey: String): Flow<Long> = dao.observeSpent(dayKey)
    fun observeCount(dayKey: String): Flow<Int> = dao.observeCount(dayKey)
    fun observeUnknownCount(): Flow<Int> = dao.observeUnknownCount()
    fun observeForDay(dayKey: String): Flow<List<TransactionEntity>> = dao.observeForDay(dayKey)
    fun observeBetween(from: String, to: String): Flow<List<TransactionEntity>> = dao.observeBetween(from, to)
    suspend fun get(id: Long) = dao.get(id)

    private suspend fun dayKeyOf(ts: Long) = DayKeys.keyFor(ts, settingsRepo.current().resetMinuteOfDay)

    private fun validate(d: TransactionDraft): String? = when {
        d.amountMinor <= 0 -> "Enter an amount."
        d.amountMinor > 100_000_000_00L -> "That amount is too large."
        (d.merchant?.length ?: 0) > 60 -> "Description is too long."
        d.timestamp > clock.now() + 5 * 60_000L -> "Date can't be in the future."
        else -> null
    }

    suspend fun addManual(d: TransactionDraft): TxResult {
        validate(d)?.let { return TxResult.Invalid(it) }
        val day = dayKeyOf(d.timestamp)
        val id = dao.insert(
            TransactionEntity(
                amountMinor = d.amountMinor, type = d.type, merchant = d.merchant?.trim()?.ifBlank { null },
                timestamp = d.timestamp, dayKey = day, source = TransactionSource.MANUAL, category = d.category?.name,
            ),
        )
        budgets.reevaluate(day)
        return TxResult.Ok(id)
    }

    /** Edits any transaction. The first automatic detection is preserved for reference. */
    suspend fun update(id: Long, d: TransactionDraft): TxResult {
        validate(d)?.let { return TxResult.Invalid(it) }
        val day = dayKeyOf(d.timestamp)
        var oldDay: String? = null
        val found = db.withTransaction {
            val old = dao.get(id) ?: return@withTransaction false
            oldDay = old.dayKey
            val auto = old.source == TransactionSource.AUTO
            dao.update(
                old.copy(
                    amountMinor = d.amountMinor, type = d.type, merchant = d.merchant?.trim()?.ifBlank { null },
                    category = d.category?.name, timestamp = d.timestamp, dayKey = day, userEdited = true,
                    originalAmountMinor = old.originalAmountMinor ?: if (auto) old.amountMinor else null,
                    originalType = old.originalType ?: if (auto) old.type else null,
                ),
            )
            true
        }
        if (!found) return TxResult.NotFound
        budgets.reevaluate(day)
        oldDay?.let { if (it != day) budgets.reevaluate(it) }
        return TxResult.Ok(id)
    }

    suspend fun setCategory(id: Long, category: TxCategory?): TxResult {
        val old = dao.get(id) ?: return TxResult.NotFound
        dao.update(old.copy(category = category?.name))
        return TxResult.Ok(id)
    }

    suspend fun delete(id: Long): TxResult {
        val old = dao.get(id) ?: return TxResult.NotFound
        dao.delete(id)
        budgets.reevaluate(old.dayKey)
        return TxResult.Ok(id)
    }

    /** Records a parsed notification, merging with what we already know. Never double-counts. */
    suspend fun ingest(p: ParsedPayment, pkg: String, rawTs: Long): IngestResult {
        val now = clock.now()
        val ts = if (rawTs <= 0 || rawTs > now + 60 * 60_000L) now else rawTs
        val reset = settingsRepo.current().resetMinuteOfDay
        val touched = HashSet<String>()
        val result = db.withTransaction<IngestResult> {
            val refKey = p.reference?.let { "ref:$pkg:$it" }
            val match: Match? = refKey?.let { dao.findByDedupeKey(it) }?.let { DuplicateMatcher.sameReference(it, p, ts) }
                ?: DuplicateMatcher.find(
                    dao.findByAmountBetween(p.amountMinor, ts - DuplicateMatcher.PENDING_WINDOW_MS, ts + DuplicateMatcher.PENDING_WINDOW_MS),
                    p, pkg, ts,
                )
            when (match) {
                is Match.Duplicate -> IngestResult.Duplicate
                is Match.Update -> {
                    val day = DayKeys.keyFor(match.merged.timestamp, reset)
                    val withKey = if (match.merged.dedupeKey == null && refKey != null && dao.findByDedupeKey(refKey) == null) {
                        match.merged.copy(dedupeKey = refKey)
                    } else match.merged
                    val saved = withKey.copy(dayKey = day)
                    dao.update(saved)
                    touched += match.existing.dayKey; touched += day
                    IngestResult.Updated(saved)
                }
                null -> {
                    val day = DayKeys.keyFor(ts, reset)
                    val tx = TransactionEntity(
                        amountMinor = p.amountMinor, type = p.type, merchant = p.merchant, timestamp = ts, dayKey = day,
                        sourcePackage = pkg, source = TransactionSource.AUTO, dedupeKey = refKey, txnRef = p.reference,
                    )
                    val id = dao.insert(tx)
                    if (id == -1L) IngestResult.Duplicate else { touched += day; IngestResult.Inserted(tx.copy(id = id)) }
                }
            }
        }
        touched.forEach { budgets.reevaluate(it) }
        return result
    }
}

@Singleton
class PaymentAppRepository @Inject constructor(private val dao: NotificationRuleDao) {
    fun observeSelected(): Flow<List<NotificationRuleEntity>> = dao.observeAll().map { l -> l.filter { it.enabled } }
    suspend fun select(pkg: String, label: String) = dao.upsert(NotificationRuleEntity(pkg, label, enabled = true))
    suspend fun deselect(pkg: String) = dao.getEnabled(pkg)?.let { dao.delete(it) } ?: Unit
}

/**
 * The only door from notification text into the database. Privacy rules enforced here:
 * unselected apps are dropped before anything is read; text is parsed in memory and discarded;
 * only a SHA-256 fingerprint of the notification is remembered (for restart-safe dedupe).
 */
@Singleton
class PaymentIngestor @Inject constructor(
    private val rules: NotificationRuleDao,
    private val seen: NotificationSeenDao,
    private val repo: TransactionRepository,
    private val clock: Clock,
) {
    var registry = ParserRegistry()
    private val mutex = Mutex()

    suspend fun process(n: PaymentNotification): IngestResult = mutex.withLock {
        rules.getEnabled(n.pkg) ?: return IngestResult.Ignored
        val fp = fingerprint(n)
        val now = clock.now()
        if (seen.insert(NotificationSeenEntity(fp, now)) == -1L) return IngestResult.Duplicate
        if (now % 20 == 0L) seen.prune(now - RETENTION_MS)
        try {
            when (val o = registry.parse(n)) {
                ParseOutcome.NotPayment -> IngestResult.Ignored
                is ParseOutcome.Parsed -> repo.ingest(o.payment, n.pkg, n.postTime)
            }
        } catch (e: Exception) {
            seen.delete(fp) // let a later delivery retry
            throw e
        }
    }

    companion object {
        const val RETENTION_MS = 14L * 24 * 60 * 60 * 1000
        fun fingerprint(n: PaymentNotification): String {
            val d = MessageDigest.getInstance("SHA-256")
            d.update("${n.pkg}|${n.sbnKey}|${n.postTime}|".toByteArray())
            d.update(n.combined().toByteArray())
            return d.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
