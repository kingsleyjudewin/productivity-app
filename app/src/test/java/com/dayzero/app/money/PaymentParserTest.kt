package com.dayzero.app.money

import com.dayzero.app.domain.TransactionType
import com.dayzero.app.domain.money.ParseOutcome
import com.dayzero.app.domain.money.ParsedPayment
import com.dayzero.app.domain.money.ParserRegistry
import com.dayzero.app.domain.money.PaymentNotification
import org.junit.Assert.*
import org.junit.Test

/**
 * SYNTHETIC samples written from commonly seen wording. They are NOT captured from real Google Pay,
 * PhonePe or Paytm notifications, so these tests prove the rules, not real-app compatibility.
 */
class PaymentParserTest {
    private val reg = ParserRegistry()
    private val GPAY = "com.google.android.apps.nbu.paisa.user"
    private val PHONEPE = "com.phonepe.app"
    private val PAYTM = "net.one97.paytm"

    private fun n(pkg: String, title: String?, text: String?, big: String? = null) =
        PaymentNotification(pkg, "k", 1L, title, text, big)

    private fun parsed(pkg: String, title: String?, text: String?): ParsedPayment =
        (reg.parse(n(pkg, title, text)) as? ParseOutcome.Parsed)?.payment ?: fail("expected a payment for: $title / $text").let { error("unreachable") }

    private fun ignored(pkg: String, title: String?, text: String?) =
        assertEquals("$title / $text", ParseOutcome.NotPayment, reg.parse(n(pkg, title, text)))

    @Test fun googlePay_paidToMerchant() {
        val p = parsed(GPAY, "Payment successful", "You paid ₹250 to Swiggy")
        assertEquals(TransactionType.EXPENSE, p.type); assertEquals(25000L, p.amountMinor); assertEquals("Swiggy", p.merchant)
    }
    @Test fun indianGrouping_andPaise() {
        val p = parsed(GPAY, null, "Paid ₹1,23,456.50 to Ravi Kumar")
        assertEquals(12345650L, p.amountMinor); assertEquals("Ravi Kumar", p.merchant)
    }
    @Test fun phonePe_successWithReference() {
        val p = parsed(PHONEPE, "Payment of ₹100 successful", "Paid to Chai Point. UPI Ref 412345678901")
        assertEquals(TransactionType.EXPENSE, p.type); assertEquals(10000L, p.amountMinor)
        assertEquals("Chai Point", p.merchant); assertEquals("412345678901", p.reference)
    }
    @Test fun paytm_amountFirstPhrase() {
        val p = parsed(PAYTM, "Paytm", "Rs. 75 paid to Metro Bakery")
        assertEquals(TransactionType.EXPENSE, p.type); assertEquals(7500L, p.amountMinor)
    }
    @Test fun upiHandle_becomesReadableName() {
        assertEquals("rahul", parsed(GPAY, null, "Paid ₹50 to rahul@okaxis").merchant)
    }
    @Test fun incomeVariants() {
        assertEquals(TransactionType.INCOME, parsed(GPAY, null, "Rahul paid you ₹500").type)
        assertEquals(TransactionType.INCOME, parsed(PHONEPE, null, "₹500 received from Rahul").type)
        assertEquals(TransactionType.INCOME, parsed(PAYTM, "Money received", "₹120 credited to your account").type)
    }
    @Test fun paidYouIsNeverAnExpense() {
        val p = parsed(GPAY, null, "Anita paid you ₹900")
        assertEquals(TransactionType.INCOME, p.type); assertEquals(90000L, p.amountMinor)
    }
    @Test fun refund() {
        val p = parsed(PHONEPE, "Refund processed", "Refund of ₹300 from Amazon credited to your account")
        assertEquals(TransactionType.REFUND, p.type); assertEquals(30000L, p.amountMinor)
    }
    @Test fun failedAndPending() {
        assertEquals(TransactionType.FAILED, parsed(GPAY, "Payment failed", "Payment of ₹100 to Shop failed").type)
        assertEquals(TransactionType.FAILED, parsed(PHONEPE, null, "Your payment of ₹100 was declined").type)
        assertEquals(TransactionType.PENDING, parsed(GPAY, null, "Payment of ₹100 to Shop is pending").type)
        assertEquals(TransactionType.PENDING, parsed(PAYTM, null, "₹100 payment is being processed").type)
    }
    @Test fun failedTakesPriorityOverPaidWording() {
        assertEquals(TransactionType.FAILED, parsed(GPAY, null, "Unable to pay ₹100 to Shop. Payment failed").type)
    }
    @Test fun otpIsDropped() {
        ignored(GPAY, null, "Your OTP is 482913 to pay ₹500")
        ignored(PHONEPE, "Verification", "482913 is your OTP for payment of ₹500")
    }
    @Test fun remindersRequestsAndUpcomingAreNotPayments() {
        ignored(GPAY, null, "Reminder: pay ₹1,000 electricity bill due tomorrow")
        ignored(PHONEPE, null, "Rahul requested ₹200 from you")
        ignored(PAYTM, null, "₹499 will be debited on 5th for your subscription")
        ignored(GPAY, null, "Collect request of ₹300 from Shop")
    }
    @Test fun promosAreNotPayments() {
        ignored(GPAY, "Offer", "Get up to ₹100 cashback on your next recharge")
        ignored(PHONEPE, null, "You earned ₹10 cashback")
        ignored(PAYTM, null, "Win rewards worth ₹5000 this weekend")
    }
    @Test fun cashbackInsideAPaymentDoesNotChangeTheAmount() {
        val p = parsed(GPAY, null, "Paid ₹200 to Cafe. You earned ₹5 cashback")
        assertEquals(20000L, p.amountMinor)
    }
    @Test fun balanceFigureIsNotTheAmount() {
        val p = parsed(PAYTM, null, "Paid ₹100 to Shop. Avl bal ₹5,000")
        assertEquals(10000L, p.amountMinor)
    }
    @Test fun noAmountMeansNoPayment() { ignored(GPAY, null, "Payment successful") }
    @Test fun textWithoutMoneyOrSignalIsIgnored() { ignored(GPAY, "Hello", "Your account summary is ready ₹") }
    @Test fun twoDifferentAmounts_isUnknownNotGuessed() {
        val p = parsed(GPAY, null, "Paid ₹100 to A and ₹200 to B")
        assertEquals(TransactionType.UNKNOWN, p.type); assertEquals(0L, p.amountMinor)
    }
    @Test fun ambiguousDirection_isUnknown() {
        val p = parsed(GPAY, null, "₹100 paid and received")
        assertEquals(TransactionType.UNKNOWN, p.type)
    }
    @Test fun debitedWithoutPayee_isExpenseWithNoMerchant() {
        val p = parsed(GPAY, null, "₹40 debited from your account")
        assertEquals(TransactionType.EXPENSE, p.type); assertNull(p.merchant)
    }
    @Test fun unknownApp_usesGenericParser() {
        val p = parsed("in.org.npci.upiapp", null, "Paid ₹60 to Auto Driver")
        assertEquals(TransactionType.EXPENSE, p.type); assertEquals(6000L, p.amountMinor)
    }
    @Test fun bigTextAndLinesAreCombined() {
        val r = reg.parse(PaymentNotification(GPAY, "k", 1, "Google Pay", "Tap to view", "You paid ₹80 to Bus Co", lines = listOf("UPI Ref 998877665544")))
        val p = (r as ParseOutcome.Parsed).payment
        assertEquals(8000L, p.amountMinor); assertEquals("998877665544", p.reference)
    }
    @Test fun amountsAreIntegerPaiseNeverFloats() {
        assertEquals(1010L, parsed(GPAY, null, "Paid ₹10.10 to X").amountMinor)
        assertEquals(1L, parsed(GPAY, null, "Paid ₹0.01 to X").amountMinor)
    }
    @Test fun registryPicksAppSpecificParser() {
        assertEquals("Google Pay", reg.forPackage(GPAY).name)
        assertEquals("PhonePe", reg.forPackage(PHONEPE).name)
        assertEquals("Paytm", reg.forPackage(PAYTM).name)
        assertEquals("Generic", reg.forPackage("com.other").name)
    }
}
