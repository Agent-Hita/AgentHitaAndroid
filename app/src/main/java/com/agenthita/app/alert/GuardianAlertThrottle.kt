package com.agenthita.app.alert

/**
 * Tracks the last guardian alert sent time per contact hash so emails are capped
 * to at most one immediate send per 24-hour window (configurable via RemoteConfig).
 *
 * Any risks detected while the window is active are accumulated in the DB and
 * delivered as a single aggregated alert when the window expires via a deferred
 * WorkManager job.
 *
 * The store/save/remove lambdas decouple this class from Android SharedPreferences,
 * making it testable with plain JUnit4.
 */
class GuardianAlertThrottle(
    private val store: (String) -> Long,
    private val save: (String, Long) -> Unit,
    private val remove: (String) -> Unit
) {

    /**
     * Returns true if no alert has been sent for [contactHash] yet, or if
     * at least [throttleMs] milliseconds have elapsed since the last one.
     */
    fun shouldSendNow(contactHash: String, nowMs: Long, throttleMs: Long): Boolean {
        val lastSent = store(contactHash)
        return lastSent == 0L || (nowMs - lastSent) >= throttleMs
    }

    /**
     * Milliseconds until the throttle window closes for [contactHash].
     * Returns 0 if no alert has been sent or the window has already elapsed.
     */
    fun remainingMs(contactHash: String, nowMs: Long, throttleMs: Long): Long {
        val lastSent = store(contactHash)
        if (lastSent == 0L) return 0L
        return ((lastSent + throttleMs) - nowMs).coerceAtLeast(0L)
    }

    fun recordAlertSent(contactHash: String, nowMs: Long) = save(contactHash, nowMs)

    fun clear(contactHash: String) = remove(contactHash)
}
