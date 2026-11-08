package com.dayzero.app.usage

import com.dayzero.app.domain.usage.EventKind
import com.dayzero.app.domain.usage.RawEvent
import com.dayzero.app.domain.usage.UsageAggregator
import com.dayzero.app.support.*
import org.junit.Assert.*
import org.junit.Test

class UsageAggregatorTest {
    private val start = T0
    private val end = T0 + 24 * HOUR
    private fun agg(processingStart: Long = start - 30 * MIN, windowStart: Long = start, windowEnd: Long = end) =
        UsageAggregator(windowStart, windowEnd, processingStart)

    private fun UsageAggregator.run(vararg e: RawEvent, now: Long) = also { feed(e.toList()) }.snapshot(now)

    @Test fun singleSession() {
        val s = agg().run(resumed(T0, IG), paused(T0 + 10 * MIN, IG), now = T0 + HOUR)
        assertEquals(10 * MIN, s.perApp[IG])
        assertTrue(s.foreground.isEmpty())
    }

    @Test fun openSessionIsCountedUpToNow() {
        val s = agg().run(resumed(T0, IG), now = T0 + 7 * MIN)
        assertEquals(7 * MIN, s.perApp[IG])
        assertEquals(setOf(IG), s.foreground)
    }

    @Test fun applicationSwitching_attributesTimeToTheRightApp() {
        val s = agg().run(
            resumed(T0, IG), paused(T0 + 10 * MIN, IG), resumed(T0 + 10 * MIN, YT), paused(T0 + 25 * MIN, YT),
            resumed(T0 + 30 * MIN, IG), paused(T0 + 35 * MIN, IG), now = T0 + HOUR,
        )
        assertEquals(15 * MIN, s.perApp[IG])
        assertEquals(15 * MIN, s.perApp[YT])
    }

    @Test fun navigatingBetweenActivitiesOfOneApp_isNotDoubleCounted() {
        val s = agg().run(
            resumed(T0, IG, "A"), resumed(T0 + MIN, IG, "B"), paused(T0 + 2 * MIN, IG, "A"), paused(T0 + 5 * MIN, IG, "B"),
            now = T0 + HOUR,
        )
        assertEquals(5 * MIN, s.perApp[IG])
    }

    @Test fun overlappingActivityHandoff_isContinuous() {
        // New activity resumes before the old one pauses: still one continuous session.
        val s = agg().run(resumed(T0, IG, "A"), resumed(T0 + 5 * MIN, IG, "B"), paused(T0 + 5 * MIN, IG, "A"), paused(T0 + 8 * MIN, IG, "B"), now = T0 + HOUR)
        assertEquals(8 * MIN, s.perApp[IG])
    }

    @Test fun screenLock_stopsTheClock_evenIfThePauseArrivesLate() {
        val s = agg().run(
            screenOn(T0), resumed(T0, IG), screenOff(T0 + 5 * MIN), paused(T0 + 5 * MIN + 40, IG), now = T0 + 3 * HOUR,
        )
        assertEquals(5 * MIN, s.perApp[IG])
        assertFalse(s.screenOn)
    }

    @Test fun keyguardShown_closesOpenSessions() {
        val s = agg().run(
            resumed(T0, IG), RawEvent(T0 + 4 * MIN, "android", "", EventKind.KEYGUARD_SHOWN), now = T0 + 2 * HOUR,
        )
        assertEquals(4 * MIN, s.perApp[IG])
    }

    @Test fun deviceShutdown_closesSessions() {
        val s = agg().run(resumed(T0, IG), RawEvent(T0 + 6 * MIN, "android", "", EventKind.SHUTDOWN), now = T0 + 2 * HOUR)
        assertEquals(6 * MIN, s.perApp[IG])
    }

    @Test fun afterUnlock_theAppAccruesAgain() {
        val s = agg().run(
            screenOn(T0), resumed(T0, IG), screenOff(T0 + 5 * MIN),
            screenOn(T0 + 20 * MIN), resumed(T0 + 21 * MIN, IG), paused(T0 + 24 * MIN, IG), now = T0 + HOUR,
        )
        assertEquals(8 * MIN, s.perApp[IG])
    }

    @Test fun totalScreenOnTime() {
        val s = agg().run(screenOn(T0), screenOff(T0 + 10 * MIN), screenOn(T0 + 20 * MIN), now = T0 + 25 * MIN)
        assertEquals(15 * MIN, s.screenOnMs)
        assertTrue(s.screenOn)
    }

    @Test fun screenStateUnknownAtStart_isInferredFromFirstActivity() {
        val s = agg().run(resumed(T0 + MIN, IG), now = T0 + 11 * MIN)
        assertEquals(10 * MIN, s.screenOnMs)
    }

    @Test fun lookbackEventsEstablishStateButAreNotCredited() {
        // IG was already open 10 minutes before the day began.
        val s = agg().run(resumed(T0 - 10 * MIN, IG), paused(T0 + 5 * MIN, IG), now = T0 + HOUR)
        assertEquals(5 * MIN, s.perApp[IG])
    }

    @Test fun pauseWithNoSeenResume_isCreditedFromTheStartOfObservation() {
        val s = agg(processingStart = T0 - 30 * MIN).run(paused(T0 + 5 * MIN, IG), now = T0 + HOUR)
        assertEquals(5 * MIN, s.perApp[IG])
    }

    @Test fun creditIsClampedToTheDayWindow() {
        val s = agg(windowEnd = T0 + HOUR).run(resumed(T0 + 50 * MIN, IG), paused(T0 + 90 * MIN, IG), now = T0 + 2 * HOUR)
        assertEquals(10 * MIN, s.perApp[IG])
        val open = agg(windowEnd = T0 + HOUR).run(resumed(T0 + 50 * MIN, IG), now = T0 + 5 * HOUR)
        assertEquals(10 * MIN, open.perApp[IG])
    }

    @Test fun duplicateEvents_areIgnored() {
        val a = agg()
        val e = listOf(resumed(T0, IG), paused(T0 + 10 * MIN, IG))
        a.feed(e); a.feed(e); a.feed(e + e)
        assertEquals(10 * MIN, a.snapshot(T0 + HOUR).perApp[IG])
    }

    @Test fun incrementalFeedingWithOverlap_equalsOneFullPass() {
        val events = listOf(
            screenOn(T0), resumed(T0, IG, "A"), resumed(T0 + MIN, IG, "B"), paused(T0 + 2 * MIN, IG, "A"), paused(T0 + 5 * MIN, IG, "B"),
            resumed(T0 + 6 * MIN, YT), paused(T0 + 9 * MIN, YT), screenOff(T0 + 10 * MIN), screenOn(T0 + 30 * MIN),
            resumed(T0 + 31 * MIN, IG), paused(T0 + 40 * MIN, IG),
        )
        val full = agg().run(*events.toTypedArray(), now = T0 + HOUR)
        for (cut in 1 until events.size) {
            val inc = agg()
            inc.feed(events.subList(0, cut))
            // The OS re-delivers events at the cursor timestamp, and the caller may re-send more.
            inc.feed(events.subList(maxOf(0, cut - 2), events.size))
            val snap = inc.snapshot(T0 + HOUR)
            assertEquals("cut=$cut", full.perApp, snap.perApp)
            assertEquals("cut=$cut", full.screenOnMs, snap.screenOnMs)
        }
    }

    @Test fun zeroLengthSession_isHarmless() {
        val s = agg().run(resumed(T0, IG), paused(T0, IG), now = T0 + HOUR)
        assertEquals(null, s.perApp[IG])
    }
}
