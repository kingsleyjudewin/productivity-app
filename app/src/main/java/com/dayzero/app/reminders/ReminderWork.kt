package com.dayzero.app.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dayzero.app.data.repo.ReminderEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint { fun engine(): ReminderEngine }

/**
 * Safety net behind the alarms: re-arms every alarm from the database and fires anything that
 * came due while the process was dead or the OS delayed an alarm.
 */
class ReminderSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, ReminderEntryPoint::class.java).engine().syncAll()
        return Result.success()
    }
}

object ReminderWork {
    private const val PERIODIC = "reminder-sync-periodic"
    private const val NOW = "reminder-sync-now"

    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<ReminderSyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun syncNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ReminderSyncWorker>().build(),
        )
    }
}
