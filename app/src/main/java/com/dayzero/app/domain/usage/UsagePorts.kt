package com.dayzero.app.domain.usage

interface UsageEventSource {
    /** Usage events with timestamps in [from, to], oldest first. */
    fun events(from: Long, to: Long): List<RawEvent>
}

interface UsageAccessChecker { fun isGranted(): Boolean }

interface InterventionNotifier {
    fun show(i: Intervention)
    fun dismiss(key: String)
    fun showPermissionRevoked()
    fun clearPermissionRevoked()
}

/** One backup alarm that re-checks usage at the moment a limit is predicted to be reached. */
interface UsageCheckScheduler {
    fun scheduleAt(atMillis: Long)
    fun cancel()
}
