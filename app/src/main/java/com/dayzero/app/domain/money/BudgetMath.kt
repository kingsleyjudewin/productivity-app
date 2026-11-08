package com.dayzero.app.domain.money

import com.dayzero.app.domain.MoneyFormat

enum class BudgetState { NO_BUDGET, WITHIN, OVER }

data class BudgetStatus(
    val spentMinor: Long,
    val limitMinor: Long,
    val state: BudgetState,
    /** Never negative. */
    val remainingMinor: Long,
    val overByMinor: Long,
) {
    fun label(currency: String): String = when (state) {
        BudgetState.NO_BUDGET -> "No budget set"
        BudgetState.WITHIN -> "Within budget"
        BudgetState.OVER -> "Over by ${MoneyFormat.format(overByMinor, currency)}"
    }

    companion object {
        fun of(spentMinor: Long, limitMinor: Long): BudgetStatus = when {
            limitMinor <= 0 -> BudgetStatus(spentMinor, 0, BudgetState.NO_BUDGET, 0, 0)
            spentMinor > limitMinor -> BudgetStatus(spentMinor, limitMinor, BudgetState.OVER, 0, spentMinor - limitMinor)
            else -> BudgetStatus(spentMinor, limitMinor, BudgetState.WITHIN, limitMinor - spentMinor, 0)
        }
    }
}

enum class TxFilter(val label: String) {
    TODAY("Today"), YESTERDAY("Yesterday"), LAST_7("7 days"), MONTH("Month"), ALL("All");

    fun range(todayKey: String): Pair<String, String> = when (this) {
        TODAY -> todayKey to todayKey
        YESTERDAY -> com.dayzero.app.domain.DayKeys.shift(todayKey, -1).let { it to it }
        LAST_7 -> com.dayzero.app.domain.DayKeys.shift(todayKey, -6) to todayKey
        MONTH -> todayKey.substring(0, 8) + "01" to todayKey
        ALL -> "0000-01-01" to "9999-12-31"
    }
}

object AlertText {
    const val TITLE = "BUDGET EXCEEDED"
    fun body(overMinor: Long, currency: String) =
        "You have crossed your daily spending limit by ${MoneyFormat.format(overMinor, currency)}."
}

interface BudgetNotifier {
    fun showExceeded(overMinor: Long, currency: String)
    fun dismissExceeded()
}
