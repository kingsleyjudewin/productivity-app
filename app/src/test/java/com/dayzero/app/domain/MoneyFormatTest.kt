package com.dayzero.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyFormatTest {
    @Test fun parse_wholeAndDecimal() {
        assertEquals(30000L, MoneyFormat.parseMinor("300"))
        assertEquals(30050L, MoneyFormat.parseMinor("300.50"))
        assertEquals(5L, MoneyFormat.parseMinor("0.05"))
    }

    @Test fun parse_rejectsGarbageAndNegatives() {
        assertNull(MoneyFormat.parseMinor("abc"))
        assertNull(MoneyFormat.parseMinor("-5"))
        assertNull(MoneyFormat.parseMinor(""))
    }

    @Test fun format_dropsZeroDecimals() {
        assertEquals("₹180", MoneyFormat.format(18000, "INR"))
        assertEquals("₹180.05", MoneyFormat.format(18005, "INR"))
    }
}
