package com.dayzero.app

import android.app.Application
import com.dayzero.app.monitor.UsageWork
import com.dayzero.app.reminders.Channels
import com.dayzero.app.reminders.ReminderWork
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DayZeroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Channels.ensure(this)
        ReminderWork.schedulePeriodic(this)
        UsageWork.schedulePeriodic(this)
        ReminderWork.syncNow(this) // cheap, idempotent re-arm every time the process starts
    }
}
