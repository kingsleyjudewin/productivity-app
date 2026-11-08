package com.dayzero.app.monitor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.dayzero.app.domain.usage.EventKind
import com.dayzero.app.domain.usage.RawEvent
import com.dayzero.app.domain.usage.UsageAccessChecker
import com.dayzero.app.domain.usage.UsageEventSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

object UsageAccess {
    @Suppress("DEPRECATION")
    fun isGranted(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

@Singleton
class AndroidUsageAccess @Inject constructor(@ApplicationContext private val ctx: Context) : UsageAccessChecker {
    override fun isGranted() = UsageAccess.isGranted(ctx)
}

/**
 * Reads UsageStatsManager events. Only package name, class name, type and time are kept; no
 * window content, text or screenshots are ever touched.
 */
@Singleton
class AndroidUsageEventSource @Inject constructor(@ApplicationContext private val ctx: Context) : UsageEventSource {
    override fun events(from: Long, to: Long): List<RawEvent> {
        val um = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val out = ArrayList<RawEvent>()
        val q = try { um.queryEvents(from, to) } catch (_: SecurityException) { return emptyList() }
        val e = UsageEvents.Event()
        while (q.hasNextEvent()) {
            q.getNextEvent(e)
            val kind = when (e.eventType) {
                1 -> EventKind.RESUMED          // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
                2 -> EventKind.PAUSED           // ACTIVITY_PAUSED / MOVE_TO_BACKGROUND
                15 -> EventKind.SCREEN_ON       // SCREEN_INTERACTIVE
                16 -> EventKind.SCREEN_OFF      // SCREEN_NON_INTERACTIVE
                17 -> EventKind.KEYGUARD_SHOWN
                26 -> EventKind.SHUTDOWN        // DEVICE_SHUTDOWN
                else -> continue
            }
            out += RawEvent(e.timeStamp, e.packageName ?: "", e.className ?: "", kind)
        }
        return out
    }
}
