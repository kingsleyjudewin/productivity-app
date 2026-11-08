package com.dayzero.app.money

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayzero.app.data.repo.IngestResult
import com.dayzero.app.data.repo.TransactionDraft
import com.dayzero.app.data.repo.TxResult
import com.dayzero.app.domain.DayKeys
import com.dayzero.app.domain.TransactionSource
import com.dayzero.app.domain.TransactionType
import com.dayzero.app.domain.money.BudgetState
import com.dayzero.app.domain.money.BudgetStatus
import com.dayzero.app.domain.money.TxCategory
import com.dayzero.app.domain.money.TxFilter
import com.dayzero.app.support.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class MoneyEngineTest {
    private lateinit var e: MoneyEnv
    @Before fun setUp() { e = MoneyEnv() }
    @After fun tearDown() { e.close() }

    private val today get() = DayKeys.keyFor(e.clock.now(), 0)
    private fun spend(minor: Long, merchant: String? = "Shop", type: TransactionType = TransactionType.EXPENSE, at: Long = e.clock.now()) =
        runBlocking { e.tx.addManual(TransactionDraft(minor, merchant, TxCategory.FOOD, at, type)) }
    private fun spent() = runBlocking { e.db.transactionDao().spentFor(today) }

    // ---- budgets ---------------------------------------------------------------------------
    @Test fun budgetCreation_andQuickOptions() = runBlocking {
        assertEquals(0L, e.budgets.limitFor(today))
        for (v in listOf(10000L, 20000L, 30000L, 50000L, 12345L)) { e.budgets.setBudget(v); assertEquals(v, e.budgets.limitFor(today)) }
    }
    @Test fun changingTodayDoesNotRewriteYesterday() = runBlocking {
        val yesterday = DayKeys.shift(today, -1)
        e.db.budgetDao().upsert(com.dayzero.app.data.local.BudgetEntity(yesterday, 10000, "INR"))
        e.budgets.setBudget(50000)
        assertEquals(10000L, e.budgets.limitFor(yesterday))
        assertEquals(50000L, e.budgets.limitFor(today))
    }
    @Test fun changingTomorrow_leavesTodayAlone_andCarriesForward() = runBlocking {
        e.budgets.setBudget(20000)
        e.budgets.setBudget(30000, fromTomorrow = true)
        assertEquals(20000L, e.budgets.limitFor(today))
        val t1 = DayKeys.shift(today, 1)
        assertEquals(30000L, e.budgets.limitFor(t1))
        assertEquals(30000L, e.budgets.limitFor(DayKeys.shift(today, 9)))   // no row needed per day
    }
    @Test fun zeroBudget_meansNoBudget() = runBlocking {
        e.budgets.setBudget(20000); e.budgets.setBudget(0)
        assertEquals(BudgetState.NO_BUDGET, BudgetStatus.of(5000, e.budgets.limitFor(today)).state)
    }
    @Test fun customResetTime_putsEarlyMorningInPreviousDay() = runBlocking {
        e.base.settings.update { it.copy(resetMinuteOfDay = 4 * 60) }
        val at1am = T0 - 8 * HOUR                      // 01:00 on the same date
        spend(5000, at = at1am)
        val prev = DayKeys.keyFor(at1am, 4 * 60)
        assertNotEquals(DayKeys.keyFor(T0, 4 * 60), prev)
        assertEquals(5000L, e.db.transactionDao().spentFor(prev))
        assertEquals(0L, e.db.transactionDao().spentFor(DayKeys.keyFor(T0, 4 * 60)))
    }
    @Test fun statusMath() {
        val a = BudgetStatus.of(3000, 10000); assertEquals(BudgetState.WITHIN, a.state); assertEquals(7000L, a.remainingMinor)
        val b = BudgetStatus.of(10000, 10000); assertEquals(BudgetState.WITHIN, b.state); assertEquals(0L, b.remainingMinor)
        val c = BudgetStatus.of(14000, 10000); assertEquals(BudgetState.OVER, c.state); assertEquals(4000L, c.overByMinor); assertEquals(0L, c.remainingMinor)
        assertEquals("Over by ₹40", c.label("INR"))
        assertEquals("Within budget", a.label("INR")); assertEquals("No budget set", BudgetStatus.of(5, 0).label("INR"))
    }

    // ---- what counts as spending -------------------------------------------------------------
    @Test fun onlyConfirmedExpensesCount() {
        spend(10000)
        spend(5000, type = TransactionType.INCOME); spend(7000, type = TransactionType.REFUND)
        spend(9000, type = TransactionType.FAILED); spend(3000, type = TransactionType.PENDING); spend(2000, type = TransactionType.UNKNOWN)
        assertEquals(10000L, spent())
    }
    @Test fun transactionCountIgnoresFailedAndPending() {
        spend(100); spend(100, type = TransactionType.FAILED); spend(100, type = TransactionType.PENDING); spend(100, type = TransactionType.INCOME)
        assertEquals(2, runBlocking { e.db.transactionDao().observeCount(today).first() })
    }

    // ---- exceeded alert ---------------------------------------------------------------------
    @Test fun noAlertWhenExactlyAtLimit() { runBlocking { e.budgets.setBudget(10000) }; spend(10000); assertTrue(e.alerts.shown.isEmpty()) }
    @Test fun alertOnceWithCorrectAmount() {
        runBlocking { e.budgets.setBudget(10000) }
        spend(9000); assertTrue(e.alerts.shown.isEmpty())
        spend(5000); assertEquals(listOf(4000L to "INR"), e.alerts.shown)
        spend(1000); spend(2000)                                  // already over: no repeats
        assertEquals(1, e.alerts.shown.size)
    }
    @Test fun alertWording() {
        assertEquals("You have crossed your daily spending limit by ₹40.", com.dayzero.app.domain.money.AlertText.body(4000, "INR"))
        assertEquals("BUDGET EXCEEDED", com.dayzero.app.domain.money.AlertText.TITLE)
    }
    @Test fun nonExpensesNeverTriggerAlert() {
        runBlocking { e.budgets.setBudget(1000) }
        spend(99999, type = TransactionType.INCOME); spend(99999, type = TransactionType.FAILED); spend(99999, type = TransactionType.UNKNOWN)
        assertTrue(e.alerts.shown.isEmpty())
    }
    @Test fun deletingBringsBackUnderLimit_thenRecrossingAlertsAgain() = runBlocking {
        e.budgets.setBudget(10000)
        val id = (spend(15000) as TxResult.Ok).id
        assertEquals(1, e.alerts.shown.size)
        e.tx.delete(id)
        assertEquals(1, e.alerts.dismissed)
        spend(12000)
        assertEquals(2, e.alerts.shown.size); assertEquals(2000L, e.alerts.shown.last().first)
    }
    @Test fun correctingAmountDownClearsExceeded_andUp_alerts() = runBlocking {
        e.budgets.setBudget(10000)
        val id = (spend(8000) as TxResult.Ok).id
        e.tx.update(id, TransactionDraft(13000, "Shop", null, e.clock.now(), TransactionType.EXPENSE))
        assertEquals(listOf(3000L to "INR"), e.alerts.shown)
        e.tx.update(id, TransactionDraft(6000, "Shop", null, e.clock.now(), TransactionType.EXPENSE))
        assertEquals(1, e.alerts.dismissed)
        e.tx.update(id, TransactionDraft(11000, "Shop", null, e.clock.now(), TransactionType.EXPENSE))
        assertEquals(2, e.alerts.shown.size)
    }
    @Test fun reclassifyingUnknownToExpense_canCrossBudget() = runBlocking {
        e.budgets.setBudget(10000)
        val id = (spend(20000, type = TransactionType.UNKNOWN) as TxResult.Ok).id
        assertTrue(e.alerts.shown.isEmpty())
        e.tx.update(id, TransactionDraft(20000, "Shop", null, e.clock.now(), TransactionType.EXPENSE))
        assertEquals(10000L, e.alerts.shown.single().first)
    }
    @Test fun loweringBudgetBelowSpend_alertsOnce() = runBlocking {
        e.budgets.setBudget(50000); spend(20000)
        e.budgets.setBudget(15000); assertEquals(5000L, e.alerts.shown.single().first)
        e.budgets.setBudget(12000); assertEquals(1, e.alerts.shown.size)     // still the same crossing
    }
    @Test fun raisingBudgetAboveSpend_clears() = runBlocking {
        e.budgets.setBudget(10000); spend(15000); e.budgets.setBudget(50000)
        assertEquals(1, e.alerts.dismissed)
    }
    @Test fun alertPersistsAcrossRepositoryRecreation() = runBlocking {
        e.budgets.setBudget(10000); spend(15000)
        val rebuilt = com.dayzero.app.data.repo.BudgetRepository(e.db, e.db.budgetDao(), e.db.transactionDao(), e.base.settings, e.alerts, e.clock)
        rebuilt.reevaluate(today)
        assertEquals(1, e.alerts.shown.size)     // state lives in the database, not memory
    }
    @Test fun yesterdayNeverAlerts() = runBlocking {
        e.budgets.setBudget(1000)
        spend(5000, at = e.clock.now() - 26 * HOUR)
        assertTrue(e.alerts.shown.isEmpty())
    }
    @Test fun alertsRespectNotificationsOffSetting() = runBlocking {
        e.base.settings.update { it.copy(notificationsEnabled = false) }
        e.budgets.setBudget(1000); spend(5000)
        assertTrue(e.alerts.shown.isEmpty())
    }

    // ---- manual entry --------------------------------------------------------------------------
    @Test fun manualAddEditDelete() = runBlocking {
        val id = (spend(4500, "Lunch") as TxResult.Ok).id
        val t = e.tx.get(id)!!
        assertEquals(TransactionSource.MANUAL, t.source); assertEquals("FOOD", t.category); assertEquals(4500L, t.amountMinor)
        e.tx.update(id, TransactionDraft(5000, "Dinner", TxCategory.BILLS, t.timestamp, TransactionType.EXPENSE))
        assertEquals("Dinner", e.tx.get(id)!!.merchant); assertEquals(5000L, spent())
        assertNull("manual rows keep no 'original' detection", e.tx.get(id)!!.originalAmountMinor)
        e.tx.delete(id); assertNull(e.tx.get(id)); assertEquals(0L, spent())
    }
    @Test fun validation() = runBlocking {
        assertTrue(e.tx.addManual(TransactionDraft(0, "x", null, e.clock.now(), TransactionType.EXPENSE)) is TxResult.Invalid)
        assertTrue(e.tx.addManual(TransactionDraft(100, "x", null, e.clock.now() + 3 * HOUR, TransactionType.EXPENSE)) is TxResult.Invalid)
        assertTrue(e.tx.addManual(TransactionDraft(100, "x".repeat(80), null, e.clock.now(), TransactionType.EXPENSE)) is TxResult.Invalid)
        assertEquals(TxResult.NotFound, e.tx.delete(999))
    }
    @Test fun backdatedEntryLandsOnItsOwnDay() = runBlocking {
        val y = e.clock.now() - 24 * HOUR
        spend(7000, at = y)
        assertEquals(7000L, e.db.transactionDao().spentFor(DayKeys.keyFor(y, 0)))
        assertEquals(0L, spent())
    }
    @Test fun movingTransactionBetweenDaysRecomputesBoth() = runBlocking {
        e.budgets.setBudget(10000)
        val id = (spend(15000) as TxResult.Ok).id
        assertEquals(1, e.alerts.shown.size)
        e.tx.update(id, TransactionDraft(15000, "Shop", null, e.clock.now() - 24 * HOUR, TransactionType.EXPENSE))
        assertEquals(1, e.alerts.dismissed)
    }
    @Test fun categoriseAutoDetected() = runBlocking {
        e.apps.select(GPAY, "Google Pay")
        val t = (e.ingest.process(e.note("Paid ₹100 to Swiggy")) as IngestResult.Inserted).tx
        e.tx.setCategory(t.id, TxCategory.FOOD)
        assertEquals("FOOD", e.tx.get(t.id)!!.category); assertEquals(TransactionSource.AUTO, e.tx.get(t.id)!!.source)
    }

    // ---- filters -------------------------------------------------------------------------------
    @Test fun filterRanges() {
        val k = "2026-11-08"
        assertEquals("2026-11-08" to "2026-11-08", TxFilter.TODAY.range(k))
        assertEquals("2026-11-07" to "2026-11-07", TxFilter.YESTERDAY.range(k))
        assertEquals("2026-11-02" to "2026-11-08", TxFilter.LAST_7.range(k))
        assertEquals("2026-11-01" to "2026-11-08", TxFilter.MONTH.range(k))
        assertTrue(TxFilter.ALL.range(k).let { "2020-01-01" in it.first..it.second })
    }
    @Test fun filtersReturnTheRightRows() = runBlocking {
        spend(100, "today"); spend(200, "yest", at = e.clock.now() - 24 * HOUR); spend(300, "old", at = e.clock.now() - 20 * 24 * HOUR)
        val dao = e.db.transactionDao()
        fun rows(f: TxFilter) = f.range(today).let { runBlocking { dao.observeBetween(it.first, it.second).first() } }.map { it.merchant }
        assertEquals(listOf("today"), rows(TxFilter.TODAY)); assertEquals(listOf("yest"), rows(TxFilter.YESTERDAY))
        assertEquals(setOf("today", "yest"), rows(TxFilter.LAST_7).toSet()); assertEquals(3, rows(TxFilter.ALL).size)
    }

    // ---- notification ingestion: privacy ---------------------------------------------------------
    @Test fun unselectedAppsAreIgnoredEntirely() = runBlocking {
        assertEquals(IngestResult.Ignored, e.ingest.process(e.note("Paid ₹100 to Swiggy")))        // nothing selected
        e.apps.select(GPAY, "Google Pay")
        assertEquals(IngestResult.Ignored, e.ingest.process(e.note("Paid ₹100 to X", pkg = "com.whatsapp", key = "w")))
        assertEquals(0, e.db.transactionDao().observeBetween("0", "9").first().size)
    }
    @Test fun deselectingStopsTracking() = runBlocking {
        e.apps.select(GPAY, "Google Pay"); e.apps.deselect(GPAY)
        assertEquals(IngestResult.Ignored, e.ingest.process(e.note("Paid ₹100 to Swiggy")))
    }
    @Test fun otpAndNonPaymentNotificationsStoreNothing() = runBlocking {
        e.apps.select(GPAY, "Google Pay")
        e.ingest.process(e.note("Your OTP is 123456 for ₹500", key = "a"))
        e.ingest.process(e.note("Get ₹50 cashback on recharge", key = "b"))
        assertEquals(0, e.db.transactionDao().observeBetween("0", "9").first().size)
    }
    @Test fun rawNotificationTextIsNeverStored() = runBlocking {
        e.apps.select(GPAY, "Google Pay")
        val r = e.ingest.process(e.note("You paid ₹250 to Swiggy. UPI Ref 412345678901. Secret-marker-XYZ", title = "Payment successful")) as IngestResult.Inserted
        val dump = StringBuilder()
        e.db.openHelper.readableDatabase.query("SELECT * FROM transactions").use { c -> while (c.moveToNext()) for (i in 0 until c.columnCount) dump.append(c.getString(i)).append('|') }
        e.db.openHelper.readableDatabase.query("SELECT * FROM notification_seen").use { c -> while (c.moveToNext()) for (i in 0 until c.columnCount) dump.append(c.getString(i)).append('|') }
        assertFalse(dump.contains("Secret-marker")); assertFalse(dump.contains("Payment successful")); assertFalse(dump.contains("You paid"))
        assertNull(r.tx.note)
    }

    // ---- ingestion: classification, dedupe, corrections ----------------------------------------------
    private fun selectG() = runBlocking { e.apps.select(GPAY, "Google Pay") }

    @Test fun expenseIsRecordedAndCounted() = runBlocking {
        selectG()
        val t = (e.ingest.process(e.note("You paid ₹250 to Swiggy. UPI Ref 412345678901")) as IngestResult.Inserted).tx
        assertEquals(TransactionType.EXPENSE, t.type); assertEquals(25000L, t.amountMinor); assertEquals("Swiggy", t.merchant)
        assertEquals(GPAY, t.sourcePackage); assertEquals(TransactionSource.AUTO, t.source); assertEquals(25000L, spent())
    }
    @Test fun incomeRefundFailedPending_areNotCounted() = runBlocking {
        selectG()
        e.ingest.process(e.note("Rahul paid you ₹500", key = "1"))
        e.ingest.process(e.note("Refund of ₹300 from Amazon", key = "2"))
        e.ingest.process(e.note("Payment of ₹100 to Shop failed", key = "3"))
        e.ingest.process(e.note("Payment of ₹700 to Cafe is pending", key = "4"))
        assertEquals(0L, spent())
        val types = e.db.transactionDao().observeBetween("0", "9").first().map { it.type }.toSet()
        assertEquals(setOf(TransactionType.INCOME, TransactionType.REFUND, TransactionType.FAILED, TransactionType.PENDING), types)
    }
    @Test fun ambiguousBecomesUnknown_andIsNotCounted() = runBlocking {
        selectG()
        val t = (e.ingest.process(e.note("Paid ₹100 to A and ₹200 to B")) as IngestResult.Inserted).tx
        assertEquals(TransactionType.UNKNOWN, t.type); assertEquals(0L, spent())
        assertEquals(1, e.db.transactionDao().observeUnknownCount().first())
    }
    @Test fun sameNotificationDeliveredTwice_countsOnce() = runBlocking {
        selectG()
        val n = e.note("You paid ₹250 to Swiggy")
        e.ingest.process(n); assertEquals(IngestResult.Duplicate, e.ingest.process(n)); assertEquals(25000L, spent())
    }
    @Test fun repeatAfterRestart_newIngestorSameDb_isDuplicate() = runBlocking {
        selectG()
        val n = e.note("You paid ₹250 to Swiggy")
        e.ingest.process(n)
        assertEquals(IngestResult.Duplicate, e.ingestor().process(n)); assertEquals(25000L, spent())
    }
    @Test fun deletedTransactionIsNotResurrectedByRedelivery() = runBlocking {
        selectG()
        val n = e.note("You paid ₹250 to Swiggy")
        val t = (e.ingest.process(n) as IngestResult.Inserted).tx
        e.tx.delete(t.id)
        assertEquals(IngestResult.Duplicate, e.ingestor().process(n)); assertEquals(0L, spent())
    }
    @Test fun sameTransactionDifferentNotificationKeys_dedupedByContent() = runBlocking {
        selectG()
        e.ingest.process(e.note("You paid ₹250 to Swiggy", key = "a"))
        e.clock.advance(20_000)
        val r = e.ingest.process(e.note("Payment successful: ₹250 paid to Swiggy", key = "b"))
        assertTrue(r is IngestResult.Duplicate || r is IngestResult.Updated)
        assertEquals(25000L, spent())
    }
    @Test fun sameReferenceTwice_countsOnce_evenFarApart() = runBlocking {
        selectG()
        e.ingest.process(e.note("Paid ₹250 to Swiggy. UPI Ref 412345678901", key = "a"))
        e.clock.advance(3 * HOUR)
        e.ingest.process(e.note("Payment of ₹250 to Swiggy successful. UPI Ref 412345678901", key = "b"))
        assertEquals(25000L, spent())
    }
    @Test fun twoGenuinePaymentsSameAmountDifferentRefs_bothCount() = runBlocking {
        selectG()
        e.ingest.process(e.note("Paid ₹50 to Chai Point. UPI Ref 111111111111", key = "a"))
        e.clock.advance(60_000)
        e.ingest.process(e.note("Paid ₹50 to Chai Point. UPI Ref 222222222222", key = "b"))
        assertEquals(10000L, spent())
    }
    @Test fun pendingThenSuccess_becomesOneExpense() = runBlocking {
        selectG()
        e.ingest.process(e.note("Payment of ₹400 to Cafe is pending", key = "a"))
        assertEquals(0L, spent())
        e.clock.advance(2 * MIN)
        val r = e.ingest.process(e.note("You paid ₹400 to Cafe", key = "b"))
        assertTrue(r is IngestResult.Updated)
        assertEquals(40000L, spent()); assertEquals(1, e.db.transactionDao().observeBetween("0", "9").first().size)
    }
    @Test fun pendingThenFailure_staysUncounted() = runBlocking {
        selectG()
        e.ingest.process(e.note("Payment of ₹400 to Cafe is pending", key = "a"))
        e.clock.advance(MIN)
        e.ingest.process(e.note("Payment of ₹400 to Cafe failed", key = "b"))
        assertEquals(0L, spent())
        assertEquals(listOf(TransactionType.FAILED), e.db.transactionDao().observeBetween("0", "9").first().map { it.type })
    }
    @Test fun retryAfterFailure_isANewPayment() = runBlocking {
        selectG()
        e.ingest.process(e.note("Payment of ₹400 to Cafe failed", key = "a"))
        e.clock.advance(MIN)
        e.ingest.process(e.note("You paid ₹400 to Cafe", key = "b"))
        assertEquals(40000L, spent())
    }
    @Test fun userCorrection_preservesOriginal_andIsNotOverwritten() = runBlocking {
        selectG()
        val t = (e.ingest.process(e.note("You paid ₹250 to Swiggy. UPI Ref 412345678901", key = "a")) as IngestResult.Inserted).tx
        e.tx.update(t.id, TransactionDraft(20000, "Swiggy Instamart", TxCategory.FOOD, t.timestamp, TransactionType.EXPENSE))
        val fixed = e.tx.get(t.id)!!
        assertEquals(25000L, fixed.originalAmountMinor); assertEquals(TransactionType.EXPENSE, fixed.originalType); assertTrue(fixed.userEdited)
        e.ingestor().process(e.note("You paid ₹250 to Swiggy. UPI Ref 412345678901", key = "z"))   // same ref later
        val after = e.tx.get(t.id)!!
        assertEquals(20000L, after.amountMinor); assertEquals("Swiggy Instamart", after.merchant); assertEquals(20000L, spent())
    }
    @Test fun markingAnAutoExpenseAsNotAnExpense_removesItFromSpend() = runBlocking {
        selectG()
        val t = (e.ingest.process(e.note("You paid ₹250 to Swiggy")) as IngestResult.Inserted).tx
        e.tx.update(t.id, TransactionDraft(25000, "Swiggy", null, t.timestamp, TransactionType.FAILED))
        assertEquals(0L, spent()); assertEquals(TransactionType.EXPENSE, e.tx.get(t.id)!!.originalType)
    }
    @Test fun manualEntryThenMatchingNotification_notDoubleCounted() = runBlocking {
        selectG()
        spend(25000, "Swiggy")
        e.clock.advance(2 * MIN)
        e.ingest.process(e.note("You paid ₹250 to Swiggy"))
        assertEquals(25000L, spent())
    }
    @Test fun autoExpenseCrossingBudget_alerts() = runBlocking {
        selectG(); e.budgets.setBudget(10000)
        e.ingest.process(e.note("Paid ₹140 to Mall", key = "a"))
        assertEquals(listOf(4000L to "INR"), e.alerts.shown)
        e.ingest.process(e.note("Paid ₹140 to Mall", key = "a"))       // redelivered
        assertEquals(1, e.alerts.shown.size)
    }
    @Test fun missingReferenceStillUnique_viaFingerprintsAndWindow() = runBlocking {
        selectG()
        e.ingest.process(e.note("Paid ₹30 to Auto", key = "a"))
        e.clock.advance(HOUR)
        e.ingest.process(e.note("Paid ₹30 to Auto", key = "b"))         // an hour later: a separate ride
        assertEquals(6000L, spent())
    }

    // ---- persistence + Home integration -----------------------------------------------------------
    @Test fun dataSurvivesReopeningTheRepositories() = runBlocking {
        e.budgets.setBudget(20000); spend(3000)
        val tx2 = com.dayzero.app.data.repo.TransactionRepository(e.db, e.db.transactionDao(), e.base.settings, e.budgets, e.clock)
        assertEquals(3000L, tx2.observeSpent(today).first())
        assertEquals(20000L, e.budgets.limitFor(today))
    }
    @Test fun homeFlows_updateLive() = runBlocking {
        e.budgets.setBudget(10000)
        fun status() = runBlocking { BudgetStatus.of(e.tx.observeSpent(today).first(), e.budgets.observeLimit(today).first()) }
        assertEquals(BudgetState.WITHIN, status().state); assertEquals(10000L, status().remainingMinor)
        spend(4000); assertEquals(6000L, status().remainingMinor)
        spend(7000); assertEquals(BudgetState.OVER, status().state); assertEquals(1000L, status().overByMinor)
    }
}
