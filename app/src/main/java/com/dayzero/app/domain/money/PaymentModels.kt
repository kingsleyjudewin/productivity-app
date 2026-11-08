package com.dayzero.app.domain.money

import com.dayzero.app.domain.TransactionType

/**
 * The only parts of a notification we ever look at. Built in memory, parsed, and discarded;
 * the raw text is never stored or logged.
 */
data class PaymentNotification(
    val pkg: String,
    val sbnKey: String,
    val postTime: Long,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val lines: List<String> = emptyList(),
) {
    fun combined(): String =
        listOfNotNull(title, text, bigText, subText, lines.joinToString(" ").ifBlank { null })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" | ")
}

data class ParsedPayment(
    val type: TransactionType,
    /** Integer paise. 0 means "an amount was present but could not be read unambiguously". */
    val amountMinor: Long,
    val merchant: String?,
    val reference: String?,
)

sealed interface ParseOutcome {
    /** Not a transaction we should record (promo, reminder, OTP, unrelated). */
    data object NotPayment : ParseOutcome
    data class Parsed(val payment: ParsedPayment) : ParseOutcome
}

enum class TxCategory(val label: String) {
    FOOD("Food"), TRANSPORT("Transport"), SHOPPING("Shopping"), EDUCATION("Education"),
    ENTERTAINMENT("Entertainment"), BILLS("Bills"), OTHER("Other");

    companion object {
        fun fromName(name: String?): TxCategory? = entries.firstOrNull { it.name == name }
    }
}

object KnownPaymentApps {
    /** Suggested UPI apps. Any other installed app can also be chosen and uses the generic parser. */
    val apps: List<Pair<String, String>> = listOf(
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.phonepe.app" to "PhonePe",
        "net.one97.paytm" to "Paytm",
        "in.org.npci.upiapp" to "BHIM",
        "com.dreamplug.androidapp" to "CRED",
        "com.mobikwik_new" to "MobiKwik",
        "com.freecharge.android" to "Freecharge",
    )
    val packages: Set<String> = apps.map { it.first }.toSet()
}
