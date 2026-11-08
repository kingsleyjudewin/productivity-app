package com.dayzero.app.domain

enum class Priority(val label: String) {
    CRITICAL("Critical"), HIGH("High"), NORMAL("Normal"), LOW("Low")
}

enum class TaskStatus { NOT_STARTED, IN_PROGRESS, DONE }

/**
 * Only EXPENSE counts toward the daily spend. FAILED and PENDING are kept for transparency (and so
 * a later success can upgrade a PENDING one) but never counted. UNKNOWN needs the user to verify.
 */
enum class TransactionType { EXPENSE, INCOME, REFUND, FAILED, PENDING, UNKNOWN }

enum class TransactionSource { AUTO, MANUAL }
