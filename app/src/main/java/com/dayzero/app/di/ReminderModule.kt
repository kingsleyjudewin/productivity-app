package com.dayzero.app.di

import com.dayzero.app.domain.Clock
import com.dayzero.app.domain.RealClock
import com.dayzero.app.domain.ReminderScheduler
import com.dayzero.app.domain.TaskNotifier
import com.dayzero.app.reminders.AlarmReminderScheduler
import com.dayzero.app.reminders.AndroidTaskNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderModule {
    @Binds @Singleton abstract fun clock(impl: RealClock): Clock
    @Binds @Singleton abstract fun scheduler(impl: AlarmReminderScheduler): ReminderScheduler
    @Binds @Singleton abstract fun notifier(impl: AndroidTaskNotifier): TaskNotifier
}
