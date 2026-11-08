package com.dayzero.app.domain.money

import com.dayzero.app.data.local.TransactionEntity
import com.dayzero.app.domain.TransactionSource
import com.dayzero.app.domain.TransactionType

sealed interface Match {
    /** Same transaction, nothing to change. */
    data class Duplicate(val existing: TransactionEntity) : Match
    /** Same transaction seen again with better information (e.g. PENDING -> success, reference arrives). */
    data class Update(val existing: TransactionEntity, val merged: TransactionEntity) : Match
}

/**
 * Decides whether an incoming parsed notification is a transaction we already have.
 * Notification ids alone are never trusted. Evidence, strongest first:
 *   1. same payment reference (handled by the caller via the unique dedupeKey);
 *   2. same source app + same amount + compatible merchant inside a short time window.
 * Two different references can never match: genuine back-to-back identical payments stay separate.
 */
object DuplicateMatcher {
    const val SAME_WINDOW_MS = 3 * 60_000L
    const val MANUAL_WINDOW_MS = 10 * 60_000L
    const val PENDING_WINDOW_MS = 30 * 60_000L

    fun merchantsCompatible(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return true
        return norm(a) == norm(b) || norm(a).contains(norm(b)) || norm(b).contains(norm(a))
    }

    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun find(candidates: List<TransactionEntity>, p: ParsedPayment, pkg: String, ts: Long): Match? {
        for (e in candidates) {
            if (e.amountMinor != p.amountMinor) continue
            if (!merchantsCompatible(e.merchant, p.merchant)) continue
            val a = e.txnRef; val b = p.reference
            if (a != null && b != null && a != b) continue                 // different references = different payments
            val age = kotlin.math.abs(ts - e.timestamp)
            val manual = e.source == TransactionSource.MANUAL
            if (!manual && e.sourcePackage != pkg) continue
            val short = age <= (if (manual) MANUAL_WINDOW_MS else SAME_WINDOW_MS)
            val long = age <= PENDING_WINDOW_MS

            when (p.type) {
                TransactionType.EXPENSE -> when {
                    e.type == TransactionType.PENDING && long -> return upgrade(e, p, ts)
                    e.type in SETTLED && short -> return same(e, p)
                    e.type == TransactionType.FAILED -> continue           // a retry after a failure is new
                }
                TransactionType.PENDING ->
                    if ((e.type == TransactionType.PENDING || e.type == TransactionType.EXPENSE) && long) return Match.Duplicate(e)
                TransactionType.FAILED -> when {
                    e.type == TransactionType.PENDING && long -> return fail(e)
                    e.type == TransactionType.FAILED && short -> return Match.Duplicate(e)
                }
                TransactionType.UNKNOWN ->
                    if (e.type in SETTLED + TransactionType.UNKNOWN && short) return Match.Duplicate(e)
                else -> if (e.type == p.type && short) return same(e, p)   // INCOME, REFUND
            }
        }
        return null
    }

    private val SETTLED = setOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.REFUND)

    /** A repeat of something we have; only fill in details we were missing. */
    private fun same(e: TransactionEntity, p: ParsedPayment): Match {
        val merged = e.copy(merchant = e.merchant ?: p.merchant, txnRef = e.txnRef ?: p.reference)
        return if (merged == e) Match.Duplicate(e) else Match.Update(e, merged)
    }

    private fun upgrade(e: TransactionEntity, p: ParsedPayment, ts: Long) = Match.Update(
        e, e.copy(
            type = TransactionType.EXPENSE, merchant = e.merchant ?: p.merchant, txnRef = e.txnRef ?: p.reference,
            timestamp = if (e.userEdited) e.timestamp else ts,
        ),
    ).let { if (e.userEdited) same(e, p) else it }

    private fun fail(e: TransactionEntity): Match =
        if (e.userEdited) Match.Duplicate(e) else Match.Update(e, e.copy(type = TransactionType.FAILED))
}

/** Same payment reference seen again: windows do not apply, the reference is the identity. */
fun DuplicateMatcher.sameReference(e: TransactionEntity, p: ParsedPayment, ts: Long): Match {
    if (e.userEdited) return fill(e, p)
    return when {
        e.type == TransactionType.PENDING && p.type == TransactionType.EXPENSE ->
            Match.Update(e, e.copy(type = TransactionType.EXPENSE, merchant = e.merchant ?: p.merchant, timestamp = ts))
        e.type == TransactionType.PENDING && p.type == TransactionType.FAILED -> Match.Update(e, e.copy(type = TransactionType.FAILED))
        e.type == TransactionType.UNKNOWN && p.type != TransactionType.UNKNOWN && p.type != TransactionType.PENDING ->
            Match.Update(e, e.copy(type = p.type, amountMinor = if (e.amountMinor == 0L) p.amountMinor else e.amountMinor, merchant = e.merchant ?: p.merchant))
        else -> fill(e, p)
    }
}

private fun fill(e: TransactionEntity, p: ParsedPayment): Match {
    val merged = e.copy(merchant = e.merchant ?: p.merchant)
    return if (merged == e) Match.Duplicate(e) else Match.Update(e, merged)
}
