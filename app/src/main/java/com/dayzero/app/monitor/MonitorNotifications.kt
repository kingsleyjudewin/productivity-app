package com.dayzero.app.monitor

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dayzero.app.MainActivity
import com.dayzero.app.R
import com.dayzero.app.domain.usage.Intervention
import com.dayzero.app.domain.usage.InterventionAction
import com.dayzero.app.domain.usage.InterventionNotifier
import com.dayzero.app.reminders.Channels
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

object MonitorIntents {
    const val EXTRA_KEY = "usage_key"
    const val ACTION_REMIND = "com.dayzero.app.action.USAGE_REMIND"
    const val ACTION_DISMISS = "com.dayzero.app.action.USAGE_DISMISS"
    const val ACTION_FINISHED = "com.dayzero.app.action.USAGE_FINISHED"
    const val ACTION_CHECK = "com.dayzero.app.action.USAGE_CHECK"

    fun name(a: InterventionAction) = when (a) {
        InterventionAction.REMIND -> ACTION_REMIND
        InterventionAction.DISMISS -> ACTION_DISMISS
        InterventionAction.FINISHED -> ACTION_FINISHED
    }

    fun parse(action: String?): InterventionAction? = when (action) {
        ACTION_REMIND -> InterventionAction.REMIND
        ACTION_DISMISS -> InterventionAction.DISMISS
        ACTION_FINISHED -> InterventionAction.FINISHED
        else -> null
    }

    fun actionPending(ctx: Context, key: String, a: InterventionAction): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, InterventionActionReceiver::class.java).setAction(name(a))
                .setData(Uri.parse("dayzero://intervention/${Uri.encode(key)}/${a.name}"))
                .putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

object MonitorNotifIds {
    const val SERVICE = 9_000_000
    const val PERMISSION = 9_000_001
    /** Stable per app, so a repeat intervention replaces rather than stacks. */
    fun intervention(key: String): Int = 1_000_000 + (key.hashCode() and 0xFFFFF)
}

@Singleton
class AndroidInterventionNotifier @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : InterventionNotifier {

    override fun show(i: Intervention) {
        val n = NotificationCompat.Builder(ctx, Channels.INTERVENTION)
            .setSmallIcon(R.drawable.ic_stat_dayzero)
            .setContentTitle("${i.emoji}  ${i.headline}")
            .setContentText(i.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(i.detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setColor(0xFF000000.toInt())
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L) // a stale scolding helps nobody
            .setContentIntent(MonitorIntents.openApp(ctx))
            .addAction(0, "REMIND IN 5 MIN", MonitorIntents.actionPending(ctx, i.key, InterventionAction.REMIND))
            .addAction(0, "DISMISS", MonitorIntents.actionPending(ctx, i.key, InterventionAction.DISMISS))
            .addAction(0, "I'M FINISHED", MonitorIntents.actionPending(ctx, i.key, InterventionAction.FINISHED))
            .build()
        post(MonitorNotifIds.intervention(i.key), n)
    }

    override fun dismiss(key: String) {
        NotificationManagerCompat.from(ctx).cancel(MonitorNotifIds.intervention(key))
    }

    override fun showPermissionRevoked() {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, Channels.MONITOR_STATUS)
            .setSmallIcon(R.drawable.ic_stat_dayzero)
            .setContentTitle("DAYZERO — MONITORING PAUSED")
            .setContentText("Usage access is off, so screen time is not being tracked. Tap to turn it on.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Usage access is off, so screen time is not being tracked. Tap to turn it on."))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .build()
        post(MonitorNotifIds.PERMISSION, n)
    }

    override fun clearPermissionRevoked() {
        NotificationManagerCompat.from(ctx).cancel(MonitorNotifIds.PERMISSION)
    }

    private fun post(id: Int, n: Notification) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try { nm.notify(id, n) } catch (_: SecurityException) { }
    }
}
