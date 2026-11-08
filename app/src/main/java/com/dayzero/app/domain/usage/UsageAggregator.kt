package com.dayzero.app.domain.usage

enum class EventKind { RESUMED, PAUSED, SCREEN_ON, SCREEN_OFF, KEYGUARD_SHOWN, SHUTDOWN }

/** One Android usage event, already reduced to what the maths needs (no content, ever). */
data class RawEvent(val ts: Long, val pkg: String, val cls: String, val kind: EventKind) {
    internal val key get() = "$ts|$pkg|$cls|$kind"
}

data class UsageSnapshot(
    val perApp: Map<String, Long>,
    val screenOnMs: Long,
    /** Packages with an activity currently resumed. */
    val foreground: Set<String>,
    val lastActive: Map<String, Long>,
    val screenOn: Boolean,
)

/**
 * Turns the system's usage-event stream into per-app foreground time and total screen-on time.
 *
 * Properties that matter:
 *  - Deterministic: totals are derived from events, never from a running timer, so a restart,
 *    a late worker or a killed service cannot drift or double-count.
 *  - Incremental: [feed] can be called repeatedly with overlapping batches (events at the cursor
 *    are re-delivered by the OS); duplicates are dropped.
 *  - Credit is clamped to [windowStart, windowEnd]. Events before the window (lookback) only
 *    establish state, e.g. an app already open at the reset moment.
 *  - An app counts only while at least one of its activities is resumed, so activity-to-activity
 *    navigation inside one app is not counted twice. Screen-off, lock and shutdown close all
 *    open sessions, so a locked phone never accrues app time.
 */
class UsageAggregator(
    private val windowStart: Long,
    private val windowEnd: Long,
    /** Where event collection began; an unmatched PAUSE is assumed to follow a resume from here. */
    private val processingStart: Long,
) {
    private val resumed = HashMap<String, MutableSet<String>>()
    private val since = HashMap<String, Long>()
    private val usage = HashMap<String, Long>()
    private val seen = HashSet<String>()
    private val lastActive = HashMap<String, Long>()
    private var screenOn: Boolean? = null
    private var screenSince = 0L
    private var screenMs = 0L
    private val atLast = HashSet<String>()

    var lastTs = 0L
        private set

    fun feed(events: List<RawEvent>) {
        for (e in events) {
            if (e.ts < lastTs) continue
            if (e.ts == lastTs) { if (!atLast.add(e.key)) continue } else { lastTs = e.ts; atLast.clear(); atLast.add(e.key) }
            apply(e)
        }
    }

    private fun credit(from: Long, to: Long): Long =
        (minOf(to, windowEnd) - maxOf(from, windowStart)).coerceAtLeast(0)

    private fun addUsage(pkg: String, from: Long, to: Long) {
        val c = credit(from, to)
        if (c > 0) usage[pkg] = (usage[pkg] ?: 0L) + c
    }

    private fun closeApps(ts: Long) {
        for ((pkg, set) in resumed) {
            if (set.isNotEmpty()) { since.remove(pkg)?.let { addUsage(pkg, it, ts) }; set.clear() }
        }
    }

    private fun apply(e: RawEvent) {
        if (screenOn == null) {
            if (e.kind == EventKind.SCREEN_ON) screenOn = false
            else { screenOn = true; screenSince = maxOf(e.ts, processingStart) }
        }
        when (e.kind) {
            EventKind.RESUMED -> {
                seen += e.pkg
                val set = resumed.getOrPut(e.pkg) { HashSet() }
                if (set.isEmpty()) since[e.pkg] = e.ts
                set += e.cls
                lastActive[e.pkg] = e.ts
            }
            EventKind.PAUSED -> {
                lastActive[e.pkg] = e.ts
                val set = resumed[e.pkg]
                if (set != null && e.cls in set) {
                    set -= e.cls
                    if (set.isEmpty()) since.remove(e.pkg)?.let { addUsage(e.pkg, it, e.ts) }
                } else if (e.pkg !in seen) {
                    // Foreground before we started watching: credit from the start of observation.
                    addUsage(e.pkg, processingStart, e.ts)
                }
                seen += e.pkg
            }
            EventKind.SCREEN_ON -> if (screenOn != true) { screenOn = true; screenSince = e.ts }
            EventKind.SCREEN_OFF, EventKind.SHUTDOWN -> {
                closeApps(e.ts)
                if (screenOn == true) screenMs += credit(screenSince, e.ts)
                screenOn = false
            }
            EventKind.KEYGUARD_SHOWN -> closeApps(e.ts)
        }
    }

    /** Totals as of [now], including sessions still open. Does not mutate state. */
    fun snapshot(now: Long): UsageSnapshot {
        val per = HashMap(usage)
        val fg = HashSet<String>()
        for ((pkg, set) in resumed) {
            if (set.isEmpty()) continue
            fg += pkg
            per[pkg] = (per[pkg] ?: 0L) + credit(since.getValue(pkg), now)
        }
        var screen = screenMs
        if (screenOn == true) screen += credit(screenSince, now)
        return UsageSnapshot(per, screen, fg, HashMap(lastActive), screenOn == true)
    }
}
