package com.dayzero.app.ui.screen

import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.dayzero.app.monitor.UsageAccess

data class PermissionStatus(val usageAccess: Boolean, val notifications: Boolean, val batteryUnrestricted: Boolean) {
    val ready get() = usageAccess && notifications

    companion object {
        /** Re-read every time the app resumes; never cached, because the user can flip these anytime. */
        fun read(ctx: Context) = PermissionStatus(
            usageAccess = UsageAccess.isGranted(ctx),
            notifications = NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            batteryUnrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
        )
    }
}
