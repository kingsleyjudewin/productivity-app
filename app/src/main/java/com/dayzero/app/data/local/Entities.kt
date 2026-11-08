package com.dayzero.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dayzero.app.domain.Priority
import com.dayzero.app.domain.TaskStatus
import com.dayzero.app.domain.TransactionSource
import com.dayzero.app.domain.TransactionType

@Entity(tableName = "tasks", indices = [Index("deadline"), Index("status")])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val priority: Priority = Priority.NORMAL,
    val category: String? = null,
    val startTime: Long,
    val deadline: Long,
    /** Never changes after creation, so rescheduling keeps the original commitment visible. */
    val originalDeadline: Long,
    val estimatedMinutes: Int = 30,
    val status: TaskStatus = TaskStatus.NOT_STARTED,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastCheckInAt: Long? = null,
    /** Check-in alerts already sent (or deliberately skipped). Drives the reminder ladder. */
    @ColumnInfo(defaultValue = "0") val reminderCount: Int = 0,
    /** When the next check-in is due; null means none is pending. Alarms are derived from this. */
    val nextAlertAt: Long? = null,
    /** Set once the overdue notice for the current deadline has fired, so it never repeats. */
    val overdueNotifiedAt: Long? = null,
)

/** Audit trail. Deliberately has no foreign key so history outlives a deleted task. */
@Entity(tableName = "task_history", indices = [Index("taskId"), Index("dayKey")])
data class TaskHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val taskName: String,
    val event: String,
    val oldValue: String? = null,
    val newValue: String? = null,
    val timestamp: Long,
    val dayKey: String,
)

@Entity(
    tableName = "app_usage",
    indices = [Index(value = ["packageName", "dayKey"], unique = true), Index("dayKey")],
)
data class AppUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val dayKey: String,
    val usageMs: Long = 0,
    val interventions: Int = 0,
    val lastInterventionAt: Long? = null,
    /** Interventions are suppressed until this time (REMIND / FINISHED); null = no snooze. */
    val snoozeUntil: Long? = null,
) {
    companion object {
        /** Pseudo-package holding the day's total screen-on time and overall-limit state. */
        const val TOTAL = "*total*"
    }
}

@Entity(tableName = "usage_limits")
data class UsageLimitEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val limitMinutes: Int,
    val enabled: Boolean = true,
)

/** The budget that applied on a given day, so changing the limit never rewrites history. */
@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val dayKey: String,
    val limitMinor: Long,
    val currency: String,
    /**
     * Set when the "budget exceeded" alert has fired for this crossing; cleared if spending drops
     * back under the limit. This is what stops the same crossing alerting twice.
     */
    val exceededNotifiedAt: Long? = null,
)

@Entity(
    tableName = "transactions",
    indices = [Index("dayKey"), Index(value = ["dedupeKey"], unique = true)],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMinor: Long,
    val type: TransactionType,
    val merchant: String? = null,
    val timestamp: Long,
    val dayKey: String,
    val sourcePackage: String? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
    val note: String? = null,
    /** "ref:<app>:<payment reference>" when the notification carried one; unique, so a reference is stored once. */
    val dedupeKey: String? = null,
    val category: String? = null,
    /** UPI / bank reference number extracted from the notification, if any. */
    val txnRef: String? = null,
    /** What the parser first detected, kept when the user corrects an automatic transaction. */
    val originalAmountMinor: Long? = null,
    val originalType: TransactionType? = null,
    /** The user changed this record by hand; later notifications may no longer overwrite it. */
    @ColumnInfo(defaultValue = "0") val userEdited: Boolean = false,
)

/**
 * One-way fingerprints of notifications already handled (app + notification key + post time +
 * a SHA-256 of the text). The text itself is never stored. This is what makes re-delivery after a
 * restart or listener reconnect harmless, even for a transaction the user later deleted.
 */
@Entity(tableName = "notification_seen", indices = [Index("seenAt")])
data class NotificationSeenEntity(
    @PrimaryKey val fingerprint: String,
    val seenAt: Long,
)

@Entity(tableName = "notification_rules")
data class NotificationRuleEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val enabled: Boolean = true,
    val spentKeywords: String = "paid,sent,debited,payment of,paid to",
    val receivedKeywords: String = "received,credited,paid you",
    val refundKeywords: String = "refund,reversed",
    val ignoreKeywords: String = "failed,pending,declined,reminder,request,otp,due,scheduled",
)

@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val dailyBudgetMinor: Long = 0,
    val currency: String = "INR",
    val resetMinuteOfDay: Int = 0,
    val defaultLimitMinutes: Int = 30,
    val taskReminderIntervalMin: Int = 15,
    /** Delay before the first reminder after the start-time alert. */
    @ColumnInfo(defaultValue = "30") val firstReminderMin: Int = 30,
    val persistentTaskNotification: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val overlayInterventions: Boolean = false,
    val interventionCooldownMin: Int = 10,
    /** Overall daily screen-time limit in minutes; 0 = no overall limit. */
    @ColumnInfo(defaultValue = "0") val dailyScreenLimitMinutes: Int = 0,
    /** User wants real-time monitoring (foreground service). Effective only with usage access. */
    @ColumnInfo(defaultValue = "1") val monitoringEnabled: Boolean = true,
)
