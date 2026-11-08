package com.dayzero.app.payments

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dayzero.app.data.repo.PaymentIngestor
import com.dayzero.app.domain.money.PaymentNotification
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives every posted notification from the system (Android offers no per-app filter), so the very
 * first thing it does is compare the package with the user's selected payment apps and drop
 * everything else without reading a single field. Nothing leaves the device; nothing raw is stored.
 */
@AndroidEntryPoint
class PaymentListenerService : NotificationListenerService() {
    @Inject lateinit var ingestor: PaymentIngestor
    @Inject lateinit var selection: PaymentSelectionCache
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        if (!selection.isSelected(pkg)) return                 // cheap, in-memory; others are never read
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val n = toModel(sbn)
        scope.launch { runCatching { ingestor.process(n) } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun toModel(sbn: StatusBarNotification): PaymentNotification {
            val e = sbn.notification.extras
            return PaymentNotification(
                pkg = sbn.packageName, sbnKey = sbn.key ?: "${sbn.id}", postTime = sbn.postTime,
                title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                bigText = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                subText = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() } ?: emptyList(),
            )
        }
    }
}
