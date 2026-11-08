package com.dayzero.app.usage

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayzero.app.data.local.AppUsageEntity
import com.dayzero.app.domain.usage.Intervention
import com.dayzero.app.domain.usage.InterventionAction
import com.dayzero.app.monitor.AlarmUsageCheckScheduler
import com.dayzero.app.monitor.AndroidInterventionNotifier
import com.dayzero.app.monitor.MonitorIntents
import com.dayzero.app.monitor.MonitorNotifIds
import com.dayzero.app.reminders.Channels
import com.dayzero.app.support.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class MonitorAndroidTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)
    private val am get() = ctx.getSystemService(AlarmManager::class.java)
    private lateinit var notifier: AndroidInterventionNotifier

    private fun iv(key: String = IG) = Intervention(key, "Instagram", "STOP SCROLLING.", "🚫", "Instagram · 31m today · limit 30 min", false)
    private fun posted() = shadowOf(nm).allNotifications

    @Before fun setUp() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Channels.ensure(ctx)
        notifier = AndroidInterventionNotifier(ctx)
    }

    @Test fun intervention_hasMotivationalTitle_detail_andTheThreeRequiredActions() {
        notifier.show(iv())
        val n = posted().single()
        assertEquals("🚫  STOP SCROLLING.", n.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Instagram"))
        assertEquals(listOf("REMIND IN 5 MIN", "DISMISS", "I'M FINISHED"), n.actions.map { it.title.toString() })
        assertEquals(Channels.INTERVENTION, n.channelId)
        assertEquals(Notification.PRIORITY_HIGH, n.priority)
    }

    @Test fun actionButtons_carryTheAppKey_andAreUniquePerAction() {
        notifier.show(iv())
        val intents = posted().single().actions.map { shadowOf(it.actionIntent).savedIntent }
        intents.forEach { assertEquals(IG, it.getStringExtra(MonitorIntents.EXTRA_KEY)) }
        assertEquals(
            listOf(InterventionAction.REMIND, InterventionAction.DISMISS, InterventionAction.FINISHED),
            intents.map { MonitorIntents.parse(it.action) },
        )
        assertEquals(3, intents.map { it.data.toString() }.toSet().size)
    }

    @Test fun repeatIntervention_replacesInsteadOfStacking_andAppsAreSeparate() {
        notifier.show(iv()); notifier.show(iv()); notifier.show(iv())
        assertEquals(1, posted().size)
        notifier.show(iv(YT))
        assertEquals(2, posted().size)
        assertNotEquals(MonitorNotifIds.intervention(IG), MonitorNotifIds.intervention(YT))
    }

    @Test fun totalLimitKey_withSpecialCharacters_roundTrips() {
        notifier.show(iv(AppUsageEntity.TOTAL))
        val i = shadowOf(posted().single().actions[0].actionIntent).savedIntent
        assertEquals(AppUsageEntity.TOTAL, i.getStringExtra(MonitorIntents.EXTRA_KEY))
    }

    @Test fun dismiss_removesOnlyThatApp() {
        notifier.show(iv()); notifier.show(iv(YT))
        notifier.dismiss(IG)
        assertEquals(1, posted().size)
    }

    @Test fun nothingPosted_whenNotificationsBlockedOrPermissionRevoked() {
        shadowOf(nm).setNotificationsEnabled(false)
        notifier.show(iv()); assertTrue(posted().isEmpty())
        shadowOf(nm).setNotificationsEnabled(true)
        shadowOf(ctx as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.show(iv()); assertTrue(posted().isEmpty())
    }

    @Test fun permissionRevokedWarning_postsOnce_andClears() {
        notifier.showPermissionRevoked(); notifier.showPermissionRevoked()
        val n = posted().single()
        assertEquals("DAYZERO — MONITORING PAUSED", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Channels.MONITOR_STATUS, n.channelId)
        notifier.clearPermissionRevoked()
        assertTrue(posted().isEmpty())
    }

    // ---- backup alarm ----
    private fun alarms() = shadowOf(am).scheduledAlarms
    private fun scheduler() = AlarmUsageCheckScheduler(ctx, FakeClock())

    @Test fun backupAlarm_isSingleAndReplaced() {
        val s = scheduler()
        s.scheduleAt(T0 + 5 * MIN); s.scheduleAt(T0 + 9 * MIN); s.scheduleAt(T0 + 9 * MIN)
        assertEquals(1, alarms().size)
        @Suppress("DEPRECATION") assertEquals(T0 + 9 * MIN, alarms().single().triggerAtTime)
    }

    @Test fun backupAlarm_canBeCancelled_andCancelIsSafeWhenNoneExists() {
        val s = scheduler()
        s.cancel()
        s.scheduleAt(T0 + MIN); s.cancel()
        assertTrue(alarms().isEmpty())
    }

    @Test fun backupAlarm_degradesToInexactWhenExactAlarmsAreNotAllowed() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        scheduler().scheduleAt(T0 + MIN)
        assertEquals(1, alarms().size)
    }

    // ---- Home / Screen queries ----
    @Test fun homeNumbers_separateDeviceScreenTime_fromMonitoredAppTime() {
        val env = ScreenEnv()
        env.limit(IG, 30)
        env.source.all += listOf(screenOn(T0), resumed(T0, IG), paused(T0 + 10 * MIN, IG), resumed(T0 + 12 * MIN, YT), paused(T0 + 20 * MIN, YT))
        env.at(25); env.refresh()
        val key = env.dayKey()
        val dao = env.db.appUsageDao()
        assertEquals(10 * MIN, runBlocking { dao.observeTotalMs(key).first() })          // monitored apps only
        assertEquals(25 * MIN, runBlocking { dao.observeTotalScreenMs(key).first() })     // whole device
        env.close()
    }
}
