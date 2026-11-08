package com.dayzero.app.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dayzero.app.data.repo.InterventionActionProcessor
import com.dayzero.app.data.repo.ScreenTimeEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch { try { block() } finally { pending.finish() } }
}

@AndroidEntryPoint
class InterventionActionReceiver : BroadcastReceiver() {
    @Inject lateinit var processor: InterventionActionProcessor

    override fun onReceive(context: Context, intent: Intent) {
        val action = MonitorIntents.parse(intent.action) ?: return
        val key = intent.getStringExtra(MonitorIntents.EXTRA_KEY) ?: return
        async { processor.handle(key, action) }
    }
}

/** Fired by the backup alarm at the predicted moment a limit (or snooze) is reached. */
@AndroidEntryPoint
class UsageCheckReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: ScreenTimeEngine

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MonitorIntents.ACTION_CHECK) return
        async { engine.refresh() }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MonitorEntryPoint {
    fun screenEngine(): ScreenTimeEngine
    fun controller(): MonitorController
}

/** Every 15 min: reconcile with Android's usage stats and try to keep the service alive. */
class UsageSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, MonitorEntryPoint::class.java)
        ep.screenEngine().refresh()
        ep.controller().sync()
        return Result.success()
    }
}

object UsageWork {
    private const val PERIODIC = "usage-sync-periodic"
    fun schedulePeriodic(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UsageSyncWorker>(15, TimeUnit.MINUTES).build(),
        )
    }
}
