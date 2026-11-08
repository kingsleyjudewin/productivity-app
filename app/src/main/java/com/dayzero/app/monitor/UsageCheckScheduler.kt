package com.dayzero.app.monitor

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.dayzero.app.domain.Clock
import com.dayzero.app.domain.usage.UsageCheckScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Backup wake-up at the predicted moment a limit is hit, in case the service was killed. */
@Singleton
class AlarmUsageCheckScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val clock: Clock,
) : UsageCheckScheduler {
    private val am = ctx.getSystemService(AlarmManager::class.java)

    private fun pending(create: Boolean): PendingIntent? =
        PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, UsageCheckReceiver::class.java).setAction(MonitorIntents.ACTION_CHECK)
                .setData(Uri.parse("dayzero://usage/check")),
            PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE,
        )

    @SuppressLint("ScheduleExactAlarm")
    override fun scheduleAt(atMillis: Long) {
        val pi = pending(create = true) ?: return
        val at = maxOf(atMillis, clock.now() + 1_000)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    override fun cancel() {
        pending(create = false)?.let { am.cancel(it); it.cancel() }
    }
}
