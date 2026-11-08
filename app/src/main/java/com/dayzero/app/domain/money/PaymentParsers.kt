package com.dayzero.app.domain.money

interface PaymentParser {
    val name: String
    fun supports(pkg: String): Boolean
    fun parse(n: PaymentNotification): ParseOutcome
}

/**
 * A parser is the shared analyzer plus app-specific hints. The hints below are modelled on wording
 * commonly seen in these apps; they have NOT been verified against live notifications from the real
 * apps. Anything the rules cannot read with confidence becomes UNKNOWN for the user to verify.
 */
open class ProfileParser(
    override val name: String,
    private val packages: Set<String>,
    private val profile: AnalyzerProfile = AnalyzerProfile(),
) : PaymentParser {
    override fun supports(pkg: String) = pkg in packages
    override fun parse(n: PaymentNotification) = PaymentTextAnalyzer.analyze(n.combined(), profile)
}

private fun re(s: String) = Regex(s, RegexOption.IGNORE_CASE)

class GooglePayParser : ProfileParser(
    "Google Pay", setOf("com.google.android.apps.nbu.paisa.user"),
    AnalyzerProfile(extraExpense = listOf(re("""\bpaid\s+(?:₹|rs)"""))),
)

class PhonePeParser : ProfileParser(
    "PhonePe", setOf("com.phonepe.app"),
    // PhonePe commonly titles confirmations "Payment Successful"; handled by the shared soft rule.
    AnalyzerProfile(extraExpense = listOf(re("""\bpaid\s+(?:₹|rs)"""))),
)

class PaytmParser : ProfileParser(
    "Paytm", setOf("net.one97.paytm"),
    AnalyzerProfile(extraExpense = listOf(re("""(?:₹|rs\.?)\s*[0-9][0-9,.]*\s+paid\s+to""")), extraIncome = listOf(re("""\bmoney\s+received\b"""))),
)

/** Fallback for any other app the user selects (BHIM, CRED, banks, ...). */
class GenericUpiParser : ProfileParser("Generic", emptySet()) {
    override fun supports(pkg: String) = true
}

class ParserRegistry(
    private val parsers: List<PaymentParser> = listOf(GooglePayParser(), PhonePeParser(), PaytmParser(), GenericUpiParser()),
) {
    fun forPackage(pkg: String): PaymentParser = parsers.first { it.supports(pkg) }
    fun parse(n: PaymentNotification): ParseOutcome = forPackage(n.pkg).parse(n)
    val supportedNames: List<String> get() = parsers.map { it.name }
}
