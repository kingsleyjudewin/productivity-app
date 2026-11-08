package com.dayzero.app.di

import android.content.Context
import androidx.room.Room
import com.dayzero.app.data.local.DayZeroDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): DayZeroDatabase =
        Room.databaseBuilder(ctx, DayZeroDatabase::class.java, DayZeroDatabase.NAME)
            .addMigrations(*DayZeroDatabase.MIGRATIONS)
            .build()

    @Provides fun taskDao(db: DayZeroDatabase) = db.taskDao()
    @Provides fun taskHistoryDao(db: DayZeroDatabase) = db.taskHistoryDao()
    @Provides fun appUsageDao(db: DayZeroDatabase) = db.appUsageDao()
    @Provides fun usageLimitDao(db: DayZeroDatabase) = db.usageLimitDao()
    @Provides fun budgetDao(db: DayZeroDatabase) = db.budgetDao()
    @Provides fun transactionDao(db: DayZeroDatabase) = db.transactionDao()
    @Provides fun notificationRuleDao(db: DayZeroDatabase) = db.notificationRuleDao()
    @Provides fun settingsDao(db: DayZeroDatabase) = db.settingsDao()
    @Provides fun notificationSeenDao(db: DayZeroDatabase) = db.notificationSeenDao()
}
