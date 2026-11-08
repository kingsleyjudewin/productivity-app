package com.dayzero.app.domain.usage

import kotlin.random.Random

data class InterventionState(val count: Int, val lastAt: Long?, val snoozeUntil: Long?)

data class Intervention(
    /** Package name, or [com.dayzero.app.data.local.AppUsageEntity.TOTAL] for the overall limit. */
    val key: String,
    val label: String,
    val headline: String,
    val emoji: String,
    val detail: String,
    val isTotal: Boolean,
)

enum class InterventionAction { REMIND, DISMISS, FINISHED }

/**
 * Pure decision logic for "should we interrupt the user right now?". Kept free of Android so the
 * anti-spam guarantees are unit-tested:
 *  - nothing below the limit;
 *  - only while the user is actually using the app (or just left it);
 *  - a snooze (REMIND / FINISHED) is authoritative: nothing before it expires, and it fires once
 *    after, regardless of cooldown;
 *  - otherwise at most one per cooldown period;
 *  - hard cap per app per day.
 */
object InterventionPolicy {
    const val MAX_PER_DAY = 5
    const val REMIND_MS = 5 * 60_000L
    const val FINISHED_QUIET_MS = 30 * 60_000L
    const val RECENT_MS = 60_000L

    fun shouldFire(
        usageMs: Long, limitMs: Long, state: InterventionState, active: Boolean, now: Long, cooldownMs: Long,
    ): Boolean {
        if (limitMs <= 0 || usageMs < limitMs) return false
        if (!active) return false
        if (state.count >= MAX_PER_DAY) return false
        state.snoozeUntil?.let { return now >= it }
        val last = state.lastAt ?: return true
        return now - last >= cooldownMs
    }
}

object InterventionMessages {
    private val headlines = listOf(
        "STOP SCROLLING.", "GET BACK TO WORK.", "YOUR GOALS ARE WAITING.", "TIME TO FOCUS.",
        "YOU KNOW WHAT YOU SHOULD BE DOING.", "YOU'VE SPENT ENOUGH TIME HERE.",
    )
    private val emojis = listOf("🚫", "🔥", "⏰", "🎯", "💪", "🛑", "👀", "⚡")

    fun pick(random: Random, limitMinutes: Int, isTotal: Boolean): Pair<String, String> {
        val pool = headlines + (if (limitMinutes in 5..120 && !isTotal) {
            listOf("$limitMinutes MINUTES GONE. MAKE THE NEXT $limitMinutes COUNT.")
        } else emptyList())
        return pool[random.nextInt(pool.size)] to emojis[random.nextInt(emojis.size)]
    }
}

object SuggestedApps {
    /** Well-known feed apps, suggested first when installed. */
    val packages: Set<String> = setOf(
        "com.instagram.android", "com.google.android.youtube", "com.facebook.katana",
        "com.reddit.frontpage", "com.twitter.android", "com.snapchat.android",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "org.telegram.messenger",
        "com.pinterest", "com.linkedin.android", "com.instagram.barcelona", "com.facebook.lite",
        "com.instagram.lite", "com.discord",
    )
}

object UsageFormat {
    fun minutes(ms: Long): String = minutesOf((ms / 60_000).toInt())

    /** "42 min" under an hour, "1h 05m" above. */
    fun minutesOf(min: Int): String =
        if (min < 60) "$min min" else "${min / 60}h ${(min % 60).toString().padStart(2, '0')}m"

    /** Home/Screen sub-label for the overall limit: "18 min left", "Over limit", or null when no limit. */
    fun limitSub(usedMs: Long, limitMin: Int): String? {
        if (limitMin <= 0) return null
        val left = limitMin * 60_000L - usedMs
        return if (left > 0) "${minutes(left)} left" else "Over limit"
    }

    /** Compact: "42m" / "1h 05m". */
    fun short(ms: Long): String {
        val m = (ms / 60_000).toInt()
        return if (m < 60) "${m}m" else "${m / 60}h ${(m % 60).toString().padStart(2, '0')}m"
    }
}
