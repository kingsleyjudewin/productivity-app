package com.dayzero.app.support

import com.dayzero.app.data.local.AppUsageEntity
import com.dayzero.app.data.repo.InterventionActionProcessor
import com.dayzero.app.data.repo.ScreenTimeEngine
import com.dayzero.app.domain.DayKeys
import com.dayzero.app.domain.usage.EventKind
import com.dayzero.app.domain.usage.Intervention
import com.dayzero.app.domain.usage.InterventionNotifier
import com.dayzero.app.domain.usage.RawEvent
import com.dayzero.app.domain.usage.UsageAccessChecker
import com.dayzero.app.domain.usage.UsageCheckScheduler
import com.dayzero.app.domain.usage.UsageEventSource
import kotlinx.coroutines.runBlocking

const val IG = "com.instagram.android"
const val YT = "com.google.android.youtube"

fun resumed(ts: Long, pkg: String, cls: String = "Main") = RawEvent(ts, pkg, cls, EventKind.RESUMED)
fun paused(ts: Long, pkg: String, cls: String = "Main") = RawEvent(ts, pkg, cls, EventKind.PAUSED)
fun screenOn(ts: Long) = RawEvent(ts, "android", "", EventKind.SCREEN_ON)
fun screenOff(ts: Long) = RawEvent(ts, "android", "", EventKind.SCREEN_OFF)

class FakeEventSource : UsageEventSource {
    val all = mutableListOf<RawEvent>()
    var calls = 0
    override fun events(from: Long, to: Long): List<RawEvent> { calls++; return all.filter { it.ts in from..to }.sortedBy { it.ts } }
}

class FakeAccess(var granted: Boolean = true) : UsageAccessChecker { override fun isGranted() = granted }

class FakeInterventionNotifier : InterventionNotifier {
    val shown = mutableListOf<Intervention>()
    val dismissed = mutableListOf<String>()
    var revokedShown = 0
    var revokedCleared = 0
    override fun show(i: Intervention) { shown += i }
    override fun dismiss(key: String) { dismissed += key }
    override fun showPermissionRevoked() { revokedShown++ }
    override fun clearPermissionRevoked() { revokedCleared++ }
}

class FakeUsageScheduler : UsageCheckScheduler {
    var at: Long? = null
    var cancels = 0
    override fun scheduleAt(atMillis: Long) { at = atMillis }
    override fun cancel() { at = null; cancels++ }
}

/** Real Room + real engine; fakes only where Android itself would be. */
class ScreenEnv {
    val base = TestEnv()
    val db get() = base.db
    val clock get() = base.clock
    val settings get() = base.settings
    val source = FakeEventSource()
    val access = FakeAccess()
    val notifier = FakeInterventionNotifier()
    val scheduler = FakeUsageScheduler()
    var engine = newEngine()
    val processor = InterventionActionProcessor(base.db, base.db.appUsageDao(), base.settings, notifier, scheduler, base.clock)

    fun newEngine() = ScreenTimeEngine(
        base.db, base.db.appUsageDao(), base.db.usageLimitDao(), base.settings, source, access, notifier, scheduler, base.clock,
    )

    fun limit(pkg: String, minutes: Int, label: String = pkg.substringAfterLast('.')) = runBlocking {
        db.usageLimitDao().upsert(com.dayzero.app.data.local.UsageLimitEntity(pkg, label, minutes))
    }

    fun refresh() = runBlocking { engine.refresh() }
    fun dayKey(reset: Int = 0) = DayKeys.keyFor(clock.t, reset)
    fun row(pkg: String, key: String = dayKey()): AppUsageEntity? = runBlocking { db.appUsageDao().get(pkg, key) }
    fun at(minutesAfterT0: Long) { clock.t = T0 + minutesAfterT0 * MIN }
    fun close() = base.close()
}
