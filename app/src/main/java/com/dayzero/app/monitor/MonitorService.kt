package com.dayzero.app.monitor

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dayzero.app.R
import com.dayzero.app.data.repo.ScreenTimeEngine
import com.dayzero.app.data.repo.SettingsRepository
import com.dayzero.app.domain.usage.UsageFormat
import com.dayzero.app.reminders.Channels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Justification for a foreground service: Android gives apps no callback when another app comes
 * to the front, so noticing "Instagram just hit 30 minutes" in time needs a periodic look. To keep
 * that cheap it only runs while the screen is ON, sleeps ~20 s between looks, and shortens the
 * sleep to land on the limit moment. Screen off => no work at all. Its notification is silent
 * and honest: it disappears if usage access is revoked.
 */
@AndroidEntryPoint
class MonitorService : Service() {
    @Inject lateinit var engine: ScreenTimeEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var lastText = ""
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { loop?.cancel(); scope.launch { engine.refresh() } } // final flush
                Intent.ACTION_SCREEN_ON -> startLoop()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Channels.ensure(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, MonitorNotifIds.SERVICE, notification("Watching your screen time"), type)
        if (getSystemService(PowerManager::class.java).isInteractive) startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                val r = engine.refresh()
                if (!r.permissionGranted) { stopSelf(); return@launch } // engine already posted the warning
                val text = "Screen time today: ${UsageFormat.minutes(r.totalScreenMs)}"
                if (text != lastText) {
                    lastText = text
                    if (NotificationManagerCompat.from(this@MonitorService).areNotificationsEnabled()) {
                        try { NotificationManagerCompat.from(this@MonitorService).notify(MonitorNotifIds.SERVICE, notification(text)) } catch (_: SecurityException) { }
                    }
                }
                delay(r.nextDelayMs)
            }
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, Channels.MONITOR)
            .setSmallIcon(R.drawable.ic_stat_dayzero)
            .setContentTitle("DAYZERO")
            .setContentText(text)
            .setOngoing(true).setSilent(true).setShowWhen(false)
            .setContentIntent(MonitorIntents.openApp(this))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    override fun onDestroy() {
        running = false
        loop?.cancel(); scope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    companion object {
        /** True only while the service process is genuinely alive; the UI uses it for honest status. */
        @Volatile var running = false
    }
}

@Singleton
class MonitorController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
) {
    /** Starts or stops the service to match the user's preference and the permission state. */
    suspend fun sync() {
        val s = settings.current()
        if (s.monitoringEnabled && UsageAccess.isGranted(ctx)) start() else stop()
    }

    fun start() {
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
        } catch (_: IllegalStateException) {
            // Android 12+ forbids starting a foreground service from the background in some cases.
            // The 15-minute worker and backup alarms keep working; the service resumes next time
            // the app is opened.
        }
    }

    fun stop() { ctx.stopService(Intent(ctx, MonitorService::class.java)) }
}
