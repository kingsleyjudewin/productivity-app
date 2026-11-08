package com.dayzero.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert suspend fun insert(task: TaskEntity): Long
    @Update suspend fun update(task: TaskEntity)
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun get(id: Long): TaskEntity?
    @Query("SELECT * FROM tasks ORDER BY deadline ASC") fun observeAll(): Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE status != 'DONE'") suspend fun getActive(): List<TaskEntity>

    /** Tasks due in the window, plus anything older that is still unfinished (i.e. overdue). */
    @Query(
        "SELECT * FROM tasks WHERE (deadline >= :start AND deadline < :end) " +
            "OR (status != 'DONE' AND deadline < :start) ORDER BY deadline ASC",
    )
    fun observeForDay(start: Long, end: Long): Flow<List<TaskEntity>>
}

@Dao
interface TaskHistoryDao {
    @Insert suspend fun insert(entry: TaskHistoryEntity)
    @Query("SELECT * FROM task_history WHERE dayKey BETWEEN :fromKey AND :toKey ORDER BY timestamp DESC, id DESC")
    fun observeBetween(fromKey: String, toKey: String): Flow<List<TaskHistoryEntity>>
    @Query("SELECT * FROM task_history ORDER BY timestamp DESC, id DESC") fun observeAll(): Flow<List<TaskHistoryEntity>>
    @Query("SELECT * FROM task_history WHERE taskId = :taskId ORDER BY timestamp DESC, id DESC")
    fun observeForTask(taskId: Long): Flow<List<TaskHistoryEntity>>
}

@Dao
interface AppUsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(usage: AppUsageEntity)
    @Query("SELECT * FROM app_usage WHERE packageName = :pkg AND dayKey = :dayKey")
    suspend fun get(pkg: String, dayKey: String): AppUsageEntity?
    @Query("SELECT * FROM app_usage WHERE dayKey = :dayKey ORDER BY usageMs DESC")
    fun observeForDay(dayKey: String): Flow<List<AppUsageEntity>>
    @Query("SELECT COALESCE(SUM(usageMs), 0) FROM app_usage WHERE dayKey = :dayKey AND packageName != '*total*'")
    fun observeTotalMs(dayKey: String): Flow<Long>
    @Query("SELECT COALESCE(SUM(usageMs), 0) FROM app_usage WHERE dayKey = :dayKey AND packageName = '*total*'")
    fun observeTotalScreenMs(dayKey: String): Flow<Long>
    @Query("SELECT * FROM app_usage WHERE dayKey BETWEEN :fromKey AND :toKey")
    fun observeBetween(fromKey: String, toKey: String): Flow<List<AppUsageEntity>>
}

@Dao
interface UsageLimitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(limit: UsageLimitEntity)
    @Delete suspend fun delete(limit: UsageLimitEntity)
    @Query("DELETE FROM usage_limits WHERE packageName = :pkg") suspend fun deleteByPackage(pkg: String)
    @Query("SELECT * FROM usage_limits ORDER BY appLabel COLLATE NOCASE")
    fun observeAll(): Flow<List<UsageLimitEntity>>
    @Query("SELECT * FROM usage_limits WHERE enabled = 1") suspend fun getEnabled(): List<UsageLimitEntity>
}

@Dao
interface BudgetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(budget: BudgetEntity)
    @Query("SELECT * FROM budgets WHERE dayKey = :dayKey") fun observe(dayKey: String): Flow<BudgetEntity?>
    @Query("SELECT * FROM budgets WHERE dayKey = :dayKey") suspend fun get(dayKey: String): BudgetEntity?
    /** The limit in force on a day: its own row, else the latest earlier one, else none (null). */
    @Query("SELECT limitMinor FROM budgets WHERE dayKey <= :dayKey ORDER BY dayKey DESC LIMIT 1")
    suspend fun limitFor(dayKey: String): Long?
    @Query("SELECT limitMinor FROM budgets WHERE dayKey <= :dayKey ORDER BY dayKey DESC LIMIT 1")
    fun observeLimitFor(dayKey: String): Flow<Long?>
    @Query("SELECT * FROM budgets WHERE dayKey BETWEEN :fromKey AND :toKey")
    fun observeBetween(fromKey: String, toKey: String): Flow<List<BudgetEntity>>
}

@Dao
interface TransactionDao {
    /** Returns -1 when the unique dedupeKey already exists (duplicate notification). */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(t: TransactionEntity): Long
    @Update suspend fun update(t: TransactionEntity)
    @Query("DELETE FROM transactions WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM transactions WHERE id = :id") suspend fun get(id: Long): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE dayKey = :dayKey ORDER BY timestamp DESC, id DESC")
    fun observeForDay(dayKey: String): Flow<List<TransactionEntity>>
    @Query("SELECT * FROM transactions WHERE dayKey BETWEEN :fromKey AND :toKey ORDER BY timestamp DESC, id DESC")
    fun observeBetween(fromKey: String, toKey: String): Flow<List<TransactionEntity>>
    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM transactions WHERE dayKey = :dayKey AND type = 'EXPENSE'")
    suspend fun spentFor(dayKey: String): Long
    @Query("SELECT * FROM transactions WHERE dedupeKey = :key LIMIT 1") suspend fun findByDedupeKey(key: String): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE amountMinor = :amount AND timestamp BETWEEN :from AND :to ORDER BY timestamp DESC")
    suspend fun findByAmountBetween(amount: Long, from: Long, to: Long): List<TransactionEntity>
    /** Real transactions only: failed and pending attempts are not counted. */
    @Query("SELECT COUNT(*) FROM transactions WHERE dayKey = :dayKey AND type IN ('EXPENSE','INCOME','REFUND','UNKNOWN')")
    fun observeCount(dayKey: String): Flow<Int>
    @Query("SELECT COUNT(*) FROM transactions WHERE type = 'UNKNOWN'") fun observeUnknownCount(): Flow<Int>
    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM transactions WHERE dayKey = :dayKey AND type = 'EXPENSE'")
    fun observeSpent(dayKey: String): Flow<Long>
    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM transactions WHERE dayKey BETWEEN :fromKey AND :toKey AND type = 'EXPENSE'")
    fun observeSpentBetween(fromKey: String, toKey: String): Flow<Long>
}

@Dao
interface NotificationSeenDao {
    /** Returns -1 if this exact notification was already handled. Doubles as an atomic check-and-set. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(e: NotificationSeenEntity): Long
    @Query("DELETE FROM notification_seen WHERE fingerprint = :fp") suspend fun delete(fp: String)
    @Query("DELETE FROM notification_seen WHERE seenAt < :before") suspend fun prune(before: Long)
}

@Dao
interface NotificationRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(rule: NotificationRuleEntity)
    @Delete suspend fun delete(rule: NotificationRuleEntity)
    @Query("SELECT * FROM notification_rules ORDER BY appLabel COLLATE NOCASE")
    fun observeAll(): Flow<List<NotificationRuleEntity>>
    @Query("SELECT * FROM notification_rules WHERE packageName = :pkg AND enabled = 1")
    suspend fun getEnabled(pkg: String): NotificationRuleEntity?
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(settings: UserSettingsEntity)
    @Query("SELECT * FROM user_settings WHERE id = 1") fun observe(): Flow<UserSettingsEntity?>
    @Query("SELECT * FROM user_settings WHERE id = 1") suspend fun get(): UserSettingsEntity?
}
