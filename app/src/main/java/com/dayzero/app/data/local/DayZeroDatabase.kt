package com.dayzero.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TaskEntity::class,
        TaskHistoryEntity::class,
        AppUsageEntity::class,
        UsageLimitEntity::class,
        BudgetEntity::class,
        TransactionEntity::class,
        NotificationRuleEntity::class,
        UserSettingsEntity::class,
        NotificationSeenEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class DayZeroDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun taskHistoryDao(): TaskHistoryDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun usageLimitDao(): UsageLimitDao
    abstract fun budgetDao(): BudgetDao
    abstract fun transactionDao(): TransactionDao
    abstract fun notificationRuleDao(): NotificationRuleDao
    abstract fun settingsDao(): SettingsDao
    abstract fun notificationSeenDao(): NotificationSeenDao

    companion object {
        const val NAME = "dayzero.db"
        /** Add Migration objects here as the schema evolves; destructive fallback is never used. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN reminderCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN nextAlertAt INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN overdueNotifiedAt INTEGER")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN firstReminderMin INTEGER NOT NULL DEFAULT 30")
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_usage ADD COLUMN snoozeUntil INTEGER")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN dailyScreenLimitMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN monitoringEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN category TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN txnRef TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN originalAmountMinor INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN originalType TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN userEdited INTEGER NOT NULL DEFAULT 0")
                // Transaction types were renamed to match the product vocabulary; convert, never drop.
                db.execSQL("UPDATE transactions SET type = 'EXPENSE' WHERE type = 'SPENT'")
                db.execSQL("UPDATE transactions SET type = 'INCOME' WHERE type = 'RECEIVED'")
                db.execSQL("UPDATE transactions SET originalType = NULL")
                db.execSQL("ALTER TABLE budgets ADD COLUMN exceededNotifiedAt INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS `notification_seen` (`fingerprint` TEXT NOT NULL, `seenAt` INTEGER NOT NULL, PRIMARY KEY(`fingerprint`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_seen_seenAt` ON `notification_seen` (`seenAt`)")
                // The single global daily budget becomes the first per-day budget row, in force from
                // the beginning of time, so no day ever loses its limit and none is rewritten later.
                db.execSQL(
                    "INSERT OR IGNORE INTO budgets (dayKey, limitMinor, currency, exceededNotifiedAt) " +
                        "SELECT '0000-01-01', dailyBudgetMinor, currency, NULL FROM user_settings WHERE id = 1 AND dailyBudgetMinor > 0",
                )
            }
        }

        val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
