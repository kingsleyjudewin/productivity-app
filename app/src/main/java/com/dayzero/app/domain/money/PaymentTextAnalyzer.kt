package com.dayzero.app.domain.money

import com.dayzero.app.domain.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode

/** Per-app tweaks layered on the shared analyzer. */
data class AnalyzerProfile(
    /** Extra phrases that confirm an outgoing payment for this app (lower-case regex). */
    val extraExpense: List<Regex> = emptyList(),
    val extraIncome: List<Regex> = emptyList(),
)

/**
 * Conservative, rule-based reading of a payment notification. It prefers saying "UNKNOWN, please
 * verify" over guessing, and it only ever reads text that is already in memory.
 *
 * Order of decisions (each step can stop the process):
 *  1. OTP / credential shaped text  -> dropped (privacy).
 *  2. Reminders, collect requests, upcoming debits -> not a payment.
 *  3. Promo/cashback wording and balance figures are stripped so they cannot be mistaken for the amount.
 *  4. Status: failed / pending.
 *  5. Direction: income / refund / expense, with refund dominating.
 *  6. Amount: exactly one distinct amount, else amount = 0 and the result is UNKNOWN.
 */
object PaymentTextAnalyzer {
    private val OPT = setOf(RegexOption.IGNORE_CASE)

    private val CURRENCY_AMOUNT = Regex("""(?:₹|\brs\.?|\binr)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", OPT)
    private val BALANCE = Regex(
        """(?:avl\.?\s*|available\s+)?(?:bal(?:ance)?|limit)\s*(?:is|:|-)?\s*(?:₹|rs\.?|inr)?\s*[0-9][0-9,]*(?:\.[0-9]{1,2})?""", OPT,
    )
    private val PROMO_PHRASE = Regex(
        """(?:you\s+)?(?:earned|received|got|won|unlocked)\s+[^|.]*?(?:cashback|reward|scratch|points|coins|bonus)[^|.]*|""" +
            """(?:₹|rs\.?|inr)?\s*[0-9][0-9,]*(?:\.[0-9]{1,2})?\s*(?:cashback|reward)[^|.]*|(?:cashback|reward)\s*(?:of)?\s*(?:₹|rs\.?|inr)\s*[0-9][0-9,]*(?:\.[0-9]{1,2})?""", OPT,
    )

    private val OTP_CODE = Regex("""(?:\b(?:otp|code|pin|passcode)\b\s*(?:is|:|-)?\s*\d{4,8}\b)|(?:\b\d{4,8}\s+is\s+(?:your\s+)?(?:otp|code|one[- ]time))""", OPT)

    // Completed-payment negatives: nothing has moved yet, or never will.
    private val NOT_A_PAYMENT = Regex(
        """\b(reminder|requested|requests?\s+(?:money|payment|₹|rs)|collect\s+request|payment\s+request|has\s+requested|""" +
            """will\s+be\s+debited|due\s+(?:on|by|today|soon|date)|is\s+due|bill\s+due|scheduled|upcoming|autopay\s+(?:due|setup|mandate)|""" +
            """mandate|pay\s+now|pay\s+before|overdue|invite|refer)\b""", OPT,
    )
    private val PROMO_ONLY = Regex("""\b(offer|cashback|reward|scratch|coupon|discount|win|earn|get\s+up\s+to|flat\s+\d+)\b""", OPT)

    private val FAILED = Regex(
        """\b(failed|failure|unsuccessful|declined|rejected|cancel(?:l)?ed|timed\s*out|expired|not\s+(?:successful|completed|processed)|could\s+not\s+be\s+(?:completed|processed)|unable\s+to)\b""", OPT,
    )
    private val PENDING = Regex("""\b(pending|processing|in\s+progress|initiated|awaiting|being\s+processed|waiting\s+for)\b""", OPT)

    private val INCOME_PHRASE = Regex("""\b(paid\s+you|sent\s+you|sent\s+to\s+you|has\s+paid\s+you)\b""", OPT)
    private val INCOME = Regex("""\b(received|credited|deposited|money\s+in|added\s+to\s+your)\b""", OPT)
    private val REFUND = Regex("""\b(refund(?:ed)?|reversal|reversed|chargeback|returned\s+to\s+your)\b""", OPT)
    private val EXPENSE = Regex("""\b(paid|sent|debited|spent|purchase(?:d)?|payment\s+of|deducted|transferred|withdrawn)\b""", OPT)
    private val PAYMENT_OK = Regex("""\b(payment|transaction|txn)\s+(?:is\s+)?(?:successful|completed|done|made|success)\b""", OPT)
    private val TO_SOMEONE = Regex("""\bto\s+\S""", OPT)
    private val TXN_SIGNAL = Regex("""\b(upi|transaction|txn|payment|a/c|acct|account|bank|wallet|vpa)\b""", OPT)

    private val REFERENCE = Regex(
        """(?:upi\s*)?(?:ref(?:erence)?|txn|transaction|utr|rrn)\s*(?:id|no\.?|number)?\s*(?:#|:|-|is)?\s*([A-Za-z0-9]{9,22})""", OPT,
    )

    private val MERCHANT_TO = Regex(
        """\bto\s+(?:vpa\s+)?([A-Za-z0-9&'@_\-][A-Za-z0-9&'@_\-\. ]{1,38}?)(?=\s+(?:on|via|using|from|with|ref|upi|at|for|successfully|is|was|has|through|by)\b|[,|(]|\.(?:\s|$)|$)""", OPT,
    )
    private val MERCHANT_AT = Regex(
        """\bat\s+([A-Za-z0-9&'_\-][A-Za-z0-9&'_\-\. ]{1,38}?)(?=\s+(?:on|via|using|with|ref|upi|for|successfully)\b|[,|(]|\.(?:\s|$)|$)""", OPT,
    )
    private val MERCHANT_FROM = Regex(
        """\bfrom\s+(?:vpa\s+)?([A-Za-z0-9&'@_\-][A-Za-z0-9&'@_\-\. ]{1,38}?)(?=\s+(?:on|via|using|to|with|ref|upi|for|successfully|is|was)\b|[,|(]|\.(?:\s|$)|$)""", OPT,
    )
    private val GENERIC_NAMES = setOf("your account", "account", "a/c", "bank", "wallet", "you", "your", "your bank account", "upi")

    fun analyze(raw: String, profile: AnalyzerProfile = AnalyzerProfile()): ParseOutcome {
        val text = raw.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
        if (text.isEmpty()) return ParseOutcome.NotPayment

        if (OTP_CODE.containsMatchIn(text)) return ParseOutcome.NotPayment          // 1
        if (NOT_A_PAYMENT.containsMatchIn(text)) return ParseOutcome.NotPayment      // 2

        // 3. Remove noise that contains money-looking numbers that are not the payment.
        val cleaned = text.replace(PROMO_PHRASE, " ").replace(BALANCE, " ").replace(Regex("""\s+"""), " ").trim()

        val amounts = CURRENCY_AMOUNT.findAll(cleaned).mapNotNull { toMinor(it.groupValues[1]) }.toSet()
        if (amounts.isEmpty()) return ParseOutcome.NotPayment

        // 5 (first half): income phrases are claimed before "paid"/"sent" can be read as outgoing.
        val incomePhrase = INCOME_PHRASE.containsMatchIn(cleaned) || profile.extraIncome.any { it.containsMatchIn(cleaned) }
        val forExpense = cleaned.replace(INCOME_PHRASE, " ")
        val income = incomePhrase || INCOME.containsMatchIn(cleaned)
        val refund = REFUND.containsMatchIn(cleaned)
        val expense = EXPENSE.containsMatchIn(forExpense) || profile.extraExpense.any { it.containsMatchIn(forExpense) } ||
            (PAYMENT_OK.containsMatchIn(cleaned) && TO_SOMEONE.containsMatchIn(cleaned) && !income)

        val failed = FAILED.containsMatchIn(cleaned)
        val pending = PENDING.containsMatchIn(cleaned)
        val anySignal = income || refund || expense || failed || pending || TXN_SIGNAL.containsMatchIn(cleaned)

        // Promo text with no real transaction wording at all is not a payment.
        if (!expense && !income && !refund && !failed && !pending && PROMO_ONLY.containsMatchIn(cleaned)) return ParseOutcome.NotPayment
        if (!anySignal) return ParseOutcome.NotPayment

        val amount = if (amounts.size == 1) amounts.first() else 0L

        val type = when {
            failed -> TransactionType.FAILED                                      // 4
            pending -> TransactionType.PENDING
            refund -> TransactionType.REFUND                                      // refund dominates
            income && expense -> TransactionType.UNKNOWN                          // genuinely ambiguous
            income -> TransactionType.INCOME
            expense -> TransactionType.EXPENSE
            else -> TransactionType.UNKNOWN
        }.let { if (amount == 0L && it != TransactionType.FAILED && it != TransactionType.PENDING) TransactionType.UNKNOWN else it }

        val merchant = when (type) {
            TransactionType.INCOME, TransactionType.REFUND -> merchantFrom(cleaned) ?: merchantTo(cleaned)
            else -> merchantTo(cleaned) ?: merchantAt(cleaned) ?: merchantFrom(cleaned)
        }
        val ref = REFERENCE.find(cleaned)?.groupValues?.get(1)?.takeIf { r -> r.any(Char::isDigit) }
        return ParseOutcome.Parsed(ParsedPayment(type, amount, merchant, ref))
    }

    /** "100", "1,23,456.50" -> paise. Null if not a sane positive amount. */
    fun toMinor(s: String): Long? {
        val v = s.replace(",", "").trimEnd('.').toBigDecimalOrNull() ?: return null
        if (v.signum() <= 0 || v > BigDecimal("100000000")) return null
        return v.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    private fun merchantTo(t: String) = clean(MERCHANT_TO.find(t)?.groupValues?.get(1))
    private fun merchantAt(t: String) = clean(MERCHANT_AT.find(t)?.groupValues?.get(1))
    private fun merchantFrom(t: String) = clean(MERCHANT_FROM.find(t)?.groupValues?.get(1))

    private fun clean(raw: String?): String? {
        var m = raw?.trim()?.trimEnd('.', ',', '-') ?: return null
        if (m.contains('@')) m = m.substringBefore('@')           // UPI handle -> readable part
        m = m.trim().replace(Regex("""\s+"""), " ")
        if (m.length < 2 || m.lowercase() in GENERIC_NAMES || m.all { it.isDigit() }) return null
        return m.take(40)
    }
}
