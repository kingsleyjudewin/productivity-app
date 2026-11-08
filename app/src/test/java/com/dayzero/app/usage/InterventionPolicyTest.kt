package com.dayzero.app.usage

import com.dayzero.app.domain.usage.*
import com.dayzero.app.support.MIN
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class InterventionPolicyTest {
    private val limit = 30 * MIN
    private val cooldown = 10 * MIN
    private val now = 1_000_000_000L
    private val fresh = InterventionState(0, null, null)
    private fun fire(used: Long = limit, st: InterventionState = fresh, active: Boolean = true, at: Long = now, lim: Long = limit) =
        InterventionPolicy.shouldFire(used, lim, st, active, at, cooldown)

    @Test fun belowLimit_neverFires() = assertFalse(fire(used = limit - 1))
    @Test fun atLimit_fires() = assertTrue(fire(used = limit))
    @Test fun noLimitConfigured_neverFires() = assertFalse(fire(lim = 0))
    @Test fun notActive_doesNotFire() = assertFalse(fire(active = false))

    @Test fun cooldown_blocksRepeatsThenAllows() {
        val st = InterventionState(1, now, null)
        assertFalse(fire(st = st, at = now + cooldown - 1))
        assertTrue(fire(st = st, at = now + cooldown))
    }

    @Test fun snooze_isAuthoritative_overridingTheCooldown() {
        val st = InterventionState(1, now, now + 5 * MIN)
        assertFalse(fire(st = st, at = now + 5 * MIN - 1))
        assertTrue(fire(st = st, at = now + 5 * MIN))      // earlier than the 10-min cooldown
    }

    @Test fun snooze_cannotBeBypassedByCooldownExpiry() {
        val st = InterventionState(1, now, now + 30 * MIN)  // "I'm finished"
        assertFalse(fire(st = st, at = now + 15 * MIN))
    }

    @Test fun dailyCap() {
        assertFalse(fire(st = InterventionState(InterventionPolicy.MAX_PER_DAY, now - HOUR_MS, null)))
        assertTrue(fire(st = InterventionState(InterventionPolicy.MAX_PER_DAY - 1, now - HOUR_MS, null)))
    }

    @Test fun messages_areRandomButFromTheApprovedSet() {
        val seen = (1..200).map { InterventionMessages.pick(Random(it), 30, false) }
        assertTrue(seen.map { it.first }.toSet().size > 3)
        assertTrue(seen.map { it.second }.toSet().size > 3)
        assertTrue(seen.any { it.first == "STOP SCROLLING." })
        assertTrue(seen.any { it.first == "30 MINUTES GONE. MAKE THE NEXT 30 COUNT." })
    }

    @Test fun messages_sameSeedSameResult() =
        assertEquals(InterventionMessages.pick(Random(7), 15, false), InterventionMessages.pick(Random(7), 15, false))

    @Test fun totalLimit_neverUsesThePerAppMinutesTemplate() {
        assertTrue((1..200).none { InterventionMessages.pick(Random(it), 60, true).first.contains("MINUTES GONE") })
    }

    @Test fun formatting() {
        assertEquals("42 min", UsageFormat.minutes(42 * MIN))
        assertEquals("1h 05m", UsageFormat.minutes(65 * MIN))
        assertEquals("42m", UsageFormat.short(42 * MIN))
        assertEquals("2h 00m", UsageFormat.minutesOf(120))
        assertNull(UsageFormat.limitSub(5 * MIN, 0))
        assertEquals("25 min left", UsageFormat.limitSub(35 * MIN, 60))
        assertEquals("Over limit", UsageFormat.limitSub(60 * MIN, 60))
    }

    @Test fun suggestedApps_containTheNamedSocialApps() {
        assertTrue(listOf("com.instagram.android", "com.google.android.youtube", "com.facebook.katana", "com.reddit.frontpage")
            .all { it in SuggestedApps.packages })
    }

    private companion object { const val HOUR_MS = 60 * 60_000L }
}
