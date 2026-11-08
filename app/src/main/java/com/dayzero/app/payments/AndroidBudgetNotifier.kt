package com.dayzero.app.payments

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dayzero.app.MainActivity
import com.dayzero.app.R
import com.dayzero.app.domain.money.AlertText
import com.dayzero.app.domain.money.BudgetNotifier
import com.dayzero.app.reminders.Channels
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidBudgetNotifier @Inject constructor(@ApplicationContext private val ctx: Context) : BudgetNotifier {
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    override fun showExceeded(overMinor: Long, currency: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        Channels.ensure(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // One fixed id: if the amount changes the same notification is updated, never stacked.
        nm.notify(
            ID,
            NotificationCompat.Builder(ctx, Channels.BUDGET)
                .setSmallIcon(R.drawable.ic_stat_dayzero)
                .setContentTitle(AlertText.TITLE)
                .setContentText(AlertText.body(overMinor, currency))
                .setAutoCancel(true).setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_STATUS)
                .build(),
        )
    }

    override fun dismissExceeded() = nm.cancel(ID)

    companion object { const val ID = 9_100_000 }
}
