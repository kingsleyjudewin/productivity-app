package com.dayzero.app.usage

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayzero.app.data.local.AppUsageEntity
import com.dayzero.app.domain.DayKeys
import com.dayzero.app.domain.usage.InterventionAction
import com.dayzero.app.support.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class ScreenTimeEngineTest {
    private lateinit var e: ScreenEnv
    @Before fun setUp() { e = ScreenEnv(); e.engine.random = Random(1) }
    @After fun tearDown() = e.close()

    private fun session(pkg: String, fromMin: Long, toMin: Long?) {
        e.source.all += resumed(T0 + fromMin * MIN, pkg)
        if (toMin != null) e.source.all += paused(T0 + toMin * MIN, pkg)
    }

    // ---- aggregation + persistence ----
    @Test fun belowLimit_recordsUsage_noIntervention() {
        e.limit(IG, 30); session(IG, 0, null); e.at(29)
        e.refresh()
        assertEquals(29 * MIN, e.row(IG)!!.usageMs)
        assertTrue(e.notifier.shown.isEmpty())
    }

    @Test fun onlyMonitoredAppsAreStored_plusTheDeviceTotal() {
        e.limit(IG, 30); session(IG, 0, 5); session(YT, 5, 9); e.at(20)
        e.refresh()
        assertNotNull(e.row(IG)); assertNull(e.row(YT))
        assertNotNull(e.row(AppUsageEntity.TOTAL))
    }

    @Test fun applicationSwitching_perAppLimitsAreIndependent() {
        e.limit(IG, 15); e.limit(YT, 60)
        session(IG, 0, 10); session(YT, 10, 40); session(IG, 40, 46); e.at(47)
        e.refresh()
        assertEquals(16 * MIN, e.row(IG)!!.usageMs)
        assertEquals(30 * MIN, e.row(YT)!!.usageMs)
        assertEquals(listOf(IG), e.notifier.shown.map { it.key })   // YT (30 of 60) stays quiet
    }

    @Test fun screenLocked_noFurtherUsage_noIntervention() {
        e.limit(IG, 30)
        e.source.all += listOf(screenOn(T0), resumed(T0, IG), screenOff(T0 + 10 * MIN))
        e.at(120)
        e.refresh()
        assertEquals(10 * MIN, e.row(IG)!!.usageMs)
        assertEquals(10 * MIN, e.row(AppUsageEntity.TOTAL)!!.usageMs)
        assertTrue(e.notifier.shown.isEmpty())
    }

    // ---- thresholds ----
    @Test fun reachingTheLimit_firesExactlyOneIntervention_withContent() {
        e.limit(IG, 30, "Instagram"); session(IG, 0, null)
        e.at(30); e.refresh()
        val i = e.notifier.shown.single()
        assertEquals(IG, i.key)
        assertTrue(i.detail.contains("Instagram") && i.detail.contains("30m"))
        assertTrue(i.headline.isNotBlank() && i.emoji.isNotBlank())
        assertEquals(1, e.row(IG)!!.interventions)
    }

    @Test fun duplicatePrevention_repeatedRefreshesDoNotRepeatTheAlert() {
        e.limit(IG, 30); session(IG, 0, null)
        e.at(30); e.refresh()
        repeat(10) { e.clock.advance(20_000); e.refresh() }       // ~3 minutes of polling
        assertEquals(1, e.notifier.shown.size)
    }

    @Test fun concurrentEngines_cannotDoubleFire() {
        e.limit(IG, 30); session(IG, 0, null); e.at(31)
        val other = e.newEngine()
        e.refresh(); runBlocking { other.refresh() }              // e.g. service + worker + alarm at once
        assertEquals(1, e.notifier.shown.size)
    }

    @Test fun cooldown_allowsAReminderOnlyAfterItExpires_whileStillUsingTheApp() {
        e.limit(IG, 30); session(IG, 0, null)
        e.at(30); e.refresh()
        e.at(39); e.refresh(); assertEquals(1, e.notifier.shown.size)
        e.at(40); e.refresh(); assertEquals(2, e.notifier.shown.size)
    }

    @Test fun configurableCooldown() {
        runBlocking { e.settings.update { it.copy(interventionCooldownMin = 30) } }
        e.limit(IG, 30); session(IG, 0, null)
        e.at(30); e.refresh()
        e.at(59); e.refresh(); assertEquals(1, e.notifier.shown.size)
        e.at(60); e.refresh(); assertEquals(2, e.notifier.shown.size)
    }

    @Test fun userAlreadyLeftTheApp_noLateScolding() {
        e.limit(IG, 30); session(IG, 0, 31); e.at(45)             // left 14 minutes ago
        e.refresh()
        assertTrue(e.notifier.shown.isEmpty())
    }

    @Test fun userJustLeftTheApp_stillGetsTheNotice() {
        e.limit(IG, 30); session(IG, 0, 31); e.clock.t = T0 + 31 * MIN + 30_000
        e.refresh()
        assertEquals(1, e.notifier.shown.size)
    }

    @Test fun dailyCap_stopsRepeatedNagging() {
        runBlocking { e.settings.update { it.copy(interventionCooldownMin = 5) } }
        e.limit(IG, 15); session(IG, 0, null)
        for (m in 15..300 step 5) { e.at(m.toLong()); e.refresh() }
        assertEquals(5, e.notifier.shown.size)
    }

    @Test fun notificationsDisabled_nothingShown_butStateIsTracked() {
        runBlocking { e.settings.update { it.copy(notificationsEnabled = false) } }
        e.limit(IG, 30); session(IG, 0, null); e.at(31); e.refresh()
        assertTrue(e.notifier.shown.isEmpty())
        assertEquals(1, e.row(IG)!!.interventions)
    }

    @Test fun overallDailyLimit_firesOnce_forTotalScreenTime() {
        runBlocking { e.settings.update { it.copy(dailyScreenLimitMinutes = 60) } }
        e.source.all += listOf(screenOn(T0))
        e.at(59); e.refresh(); assertTrue(e.notifier.shown.isEmpty())
        e.at(61); e.refresh(); e.at(62); e.refresh()
        val i = e.notifier.shown.single()
        assertTrue(i.isTotal && i.key == AppUsageEntity.TOTAL)
    }

    // ---- snooze actions ----
    private fun primed(): Long { e.limit(IG, 30); session(IG, 0, null); e.at(30); e.refresh(); return e.clock.t }

    @Test fun remindInFiveMinutes_overridesCooldown_andFiresOnce() {
        primed()
        runBlocking { e.processor.handle(IG, InterventionAction.REMIND) }
        assertTrue(IG in e.notifier.dismissed)
        e.at(34); e.refresh(); assertEquals(1, e.notifier.shown.size)
        e.clock.t = T0 + 35 * MIN + 1_000; e.refresh(); assertEquals(2, e.notifier.shown.size)
        assertNull(e.row(IG)!!.snoozeUntil)
        e.clock.advance(30_000); e.refresh(); assertEquals(2, e.notifier.shown.size)
    }

    @Test fun remindArmsABackupAlarm() {
        primed()
        runBlocking { e.processor.handle(IG, InterventionAction.REMIND) }
        assertEquals(e.clock.t + InterventionPolicyConst.REMIND, e.scheduler.at)
    }

    @Test fun finished_silencesTheAppForThirtyMinutes() {
        primed()
        runBlocking { e.processor.handle(IG, InterventionAction.FINISHED) }
        e.at(55); e.refresh(); assertEquals(1, e.notifier.shown.size)
        e.clock.t = T0 + 60 * MIN + 1_000; e.refresh(); assertEquals(2, e.notifier.shown.size)
    }

    @Test fun dismiss_justClearsTheNotification() {
        primed()
        runBlocking { e.processor.handle(IG, InterventionAction.DISMISS) }
        assertNull(e.row(IG)!!.snoozeUntil)
        assertTrue(IG in e.notifier.dismissed)
        e.at(35); e.refresh(); assertEquals(1, e.notifier.shown.size)   // cooldown still applies
    }

    @Test fun actionForUnknownKey_doesNotCrash() {
        runBlocking { e.processor.handle("com.nope", InterventionAction.REMIND) }
    }

    // ---- permission ----
    @Test fun permissionRevoked_stopsClaimingMonitoring_writesNothing_andWarns() {
        e.limit(IG, 30); session(IG, 0, null); e.at(10); e.refresh()
        val before = e.row(IG)!!.usageMs
        e.access.granted = false
        e.at(40)
        val r = e.refresh()
        assertFalse(r.permissionGranted)
        assertEquals(before, e.row(IG)!!.usageMs)                  // no fabricated progress
        assertTrue(e.notifier.shown.isEmpty())
        assertTrue(e.notifier.revokedShown > 0)
    }

    @Test fun permissionRestored_clearsTheWarning_andCatchesUpFromSystemStats() {
        e.limit(IG, 30); session(IG, 0, null); e.at(10); e.refresh()
        e.access.granted = false; e.at(20); e.refresh()
        e.access.granted = true; e.at(25)
        val r = e.refresh()
        assertTrue(r.permissionGranted)
        assertTrue(e.notifier.revokedCleared > 0)
        assertEquals(25 * MIN, e.row(IG)!!.usageMs)                // Android kept recording meanwhile
    }

    @Test fun noPermissionAndNothingConfigured_doesNotNag() {
        runBlocking { e.settings.update { it.copy(monitoringEnabled = false) } }
        e.access.granted = false
        e.refresh()
        assertEquals(0, e.notifier.revokedShown)
    }

    // ---- recovery ----
    @Test fun restart_rebuildsTheSameTotals_andDoesNotReAlert() {
        e.limit(IG, 30); session(IG, 0, null)
        e.at(35); e.refresh()
        assertEquals(1, e.notifier.shown.size)
        e.engine = e.newEngine()                                   // process killed and restarted
        e.at(36); e.refresh()
        assertEquals(36 * MIN, e.row(IG)!!.usageMs)
        assertEquals(1, e.notifier.shown.size)
        assertEquals(1, e.row(IG)!!.interventions)
    }

    @Test fun counterIsReconciledNotAccumulated_soDriftIsImpossible() {
        e.limit(IG, 60); session(IG, 0, null)
        repeat(50) { e.at(it.toLong()); e.refresh() }               // many overlapping refreshes
        assertEquals(49 * MIN, e.row(IG)!!.usageMs)
    }

    // ---- reset time ----
    @Test fun customResetTime_eventsBeforeTheResetBelongToThePreviousDay() {
        runBlocking { e.settings.update { it.copy(resetMinuteOfDay = 4 * 60) } }   // day starts 04:00
        e.limit(IG, 60)
        val dayStart = DayKeys.startMillis(e.dayKey(240), 240)
        e.source.all += listOf(
            resumed(dayStart - 60 * MIN, IG), paused(dayStart - 10 * MIN, IG),     // 03:00-03:50 -> yesterday
            resumed(dayStart + MIN, IG), paused(dayStart + 21 * MIN, IG),           // 04:01-04:21 -> today
        )
        e.clock.t = dayStart + 5 * HOUR
        e.refresh()
        assertEquals(20 * MIN, e.row(IG, e.dayKey(240))!!.usageMs)
        assertNull(e.row(IG, DayKeys.shift(e.dayKey(240), -1)))
    }

    @Test fun rollover_startsTheNewDayFromZero_keepingYesterday() {
        e.limit(IG, 60)
        session(IG, 0, 20); e.at(30); e.refresh()
        val yesterday = e.dayKey()
        // 23:00-23:05 happens after our last refresh but before the reset: it still belongs to yesterday.
        e.source.all += listOf(resumed(T0 + 14 * HOUR, IG), paused(T0 + 14 * HOUR + 5 * MIN, IG))
        e.clock.t = T0 + 24 * HOUR + 5 * MIN                         // next calendar day, 09:05
        e.refresh()
        assertEquals(25 * MIN, e.row(IG, yesterday)!!.usageMs)      // 20 + the late-evening 5
        assertEquals(0L, e.row(IG, e.dayKey())!!.usageMs)
        assertNotEquals(yesterday, e.dayKey())
    }

    @Test fun appOpenAcrossMidnightResetBoundary_isSplitCorrectly() {
        e.limit(IG, 600)
        val nextDay = DayKeys.startMillis(DayKeys.shift(e.dayKey(), 1), 0)
        e.source.all += resumed(nextDay - 10 * MIN, IG)
        e.clock.t = nextDay + 15 * MIN
        e.refresh()
        assertEquals(15 * MIN, e.row(IG, e.dayKey())!!.usageMs)      // only the new day's share
    }

    // ---- scheduling hints ----
    @Test fun foregroundApp_armsAnAlarmForTheMomentTheLimitWillBeHit() {
        e.limit(IG, 30); session(IG, 0, null); e.at(10); e.refresh()
        assertEquals(e.clock.t + 20 * MIN + 1_000, e.scheduler.at)
    }

    @Test fun nothingForeground_cancelsTheBackupAlarm() {
        e.limit(IG, 30); session(IG, 0, 5); e.at(10)
        e.refresh()
        assertNull(e.scheduler.at)
    }

    @Test fun pollingTightensOnlyNearTheLimit() {
        e.limit(IG, 30); session(IG, 0, null)
        e.at(10); assertEquals(20_000L, e.refresh().nextDelayMs)
        e.clock.t = T0 + 30 * MIN - 3_000; assertEquals(3_500L, e.refresh().nextDelayMs)
    }

    @Test fun incrementalRefresh_readsOnlyNewEvents() {
        e.limit(IG, 30); session(IG, 0, null)
        e.at(5); e.refresh()
        e.source.all += paused(T0 + 6 * MIN, IG)
        e.at(7); e.refresh()
        assertEquals(6 * MIN, e.row(IG)!!.usageMs)
    }

    private object InterventionPolicyConst { const val REMIND = 5 * 60_000L }
}
