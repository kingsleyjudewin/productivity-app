package com.dayzero.app.di

import com.dayzero.app.domain.usage.InterventionNotifier
import com.dayzero.app.domain.usage.UsageAccessChecker
import com.dayzero.app.domain.usage.UsageCheckScheduler
import com.dayzero.app.domain.usage.UsageEventSource
import com.dayzero.app.monitor.AlarmUsageCheckScheduler
import com.dayzero.app.monitor.AndroidInterventionNotifier
import com.dayzero.app.monitor.AndroidUsageAccess
import com.dayzero.app.monitor.AndroidUsageEventSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MonitorModule {
    @Binds @Singleton abstract fun events(impl: AndroidUsageEventSource): UsageEventSource
    @Binds @Singleton abstract fun access(impl: AndroidUsageAccess): UsageAccessChecker
    @Binds @Singleton abstract fun notifier(impl: AndroidInterventionNotifier): InterventionNotifier
    @Binds @Singleton abstract fun scheduler(impl: AlarmUsageCheckScheduler): UsageCheckScheduler
}
