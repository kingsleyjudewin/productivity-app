package com.dayzero.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dayzero.app.data.repo.NotificationActionProcessor
import com.dayzero.app.data.repo.ReminderEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Runs [block] off the main thread while keeping the receiver alive until it completes. */
private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try { block() } finally { pending.finish() }
    }
}

@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: ReminderEngine

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmReminderScheduler.ACTION_ALARM) return
        val id = intent.getLongExtra(TaskIntents.EXTRA_TASK_ID, -1L)
        if (id < 0) return
        val kind = runCatching { AlarmKind.valueOf(intent.getStringExtra(AlarmReminderScheduler.EXTRA_KIND).orEmpty()) }
            .getOrNull() ?: return
        async {
            when (kind) {
                AlarmKind.CHECK_IN -> engine.onCheckInDue(id)
                AlarmKind.OVERDUE -> engine.onOverdueDue(id)
            }
        }
    }
}

/** Handles DONE / IN PROGRESS / NOT STARTED taps, even when the app is closed. */
@AndroidEntryPoint
class TaskActionReceiver : BroadcastReceiver() {
    @Inject lateinit var processor: NotificationActionProcessor

    override fun onReceive(context: Context, intent: Intent) {
        val action = TaskIntents.parse(intent.action) ?: return
        val id = intent.getLongExtra(TaskIntents.EXTRA_TASK_ID, -1L)
        if (id < 0) return
        async { processor.handle(id, action) }
    }
}

/** Restores reminders after reboot, app update, or a clock/timezone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                ReminderWork.syncNow(context)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        dagger.hilt.android.EntryPointAccessors
                            .fromApplication(context.applicationContext, com.dayzero.app.monitor.MonitorEntryPoint::class.java)
                            .controller().sync()
                    } finally { pending.finish() }
                }
            }
        }
    }
}
