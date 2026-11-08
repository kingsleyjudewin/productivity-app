package com.dayzero.app.domain

import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/** Money is stored as Long minor units (paise/cents) to avoid floating point drift. */
object MoneyFormat {
    fun symbol(code: String): String =
        runCatching { Currency.getInstance(code).getSymbol(Locale("en", "IN")) }.getOrDefault(code)

    fun format(minor: Long, code: String): String {
        val sign = if (minor < 0) "-" else ""
        val whole = abs(minor) / 100
        val frac = abs(minor) % 100
        val body = if (frac == 0L) "$whole" else "$whole.${frac.toString().padStart(2, '0')}"
        return sign + symbol(code) + body
    }

    /** Parses user input like "300" or "300.50" into minor units; null if invalid or negative. */
    fun parseMinor(input: String): Long? {
        val value = input.trim().toBigDecimalOrNull() ?: return null
        if (value.signum() < 0) return null
        return value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
    }
}
