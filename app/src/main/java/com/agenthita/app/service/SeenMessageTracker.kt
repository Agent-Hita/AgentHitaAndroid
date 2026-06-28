package com.agenthita.app.service

/**
 * Tracks which messages have already been scored for each conversation.
 *
 * Messages are identified by an opaque hash; callers supply the hash function so
 * real code uses SHA-256 while tests can use a plain identity function.
 *
 * The load/save lambdas decouple this class from Android SharedPreferences,
 * making it testable with plain JUnit4 (same pattern as GuardianAlertThrottle).
 *
 * The seen set for a conversation is intentionally NOT cleared when the user
 * leaves — new messages are by definition not yet in the set, so they will always
 * be returned by filterUnseen on re-entry. Clearing on exit would cause every
 * re-entry to re-score old messages and fire duplicate guardian alerts.
 */
class SeenMessageTracker(
    private val load: (convKey: String) -> Set<String>,
    private val save: (convKey: String, hashes: Set<String>) -> Unit,
    private val maxPerConv: Int = DEFAULT_MAX_PER_CONV
) {
    /**
     * Returns the subset of [messages] whose hash (via [hash]) is not yet in the
     * seen set for [convKey].
     */
    fun filterUnseen(convKey: String, messages: List<String>, hash: (String) -> String): List<String> {
        val seen = load(convKey)
        return messages.filter { hash(it) !in seen }
    }

    /**
     * Adds the hash of each entry in [messages] to the seen set for [convKey].
     * Trims the oldest ~10% of entries when [maxPerConv] is reached.
     */
    fun markSeen(convKey: String, messages: List<String>, hash: (String) -> String) {
        val current = load(convKey).toMutableSet()
        if (current.size >= maxPerConv) {
            val trimCount = maxPerConv / 10
            val iter = current.iterator()
            repeat(trimCount) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }
        messages.forEach { current.add(hash(it)) }
        save(convKey, current)
    }

    companion object {
        const val DEFAULT_MAX_PER_CONV = 200
    }
}
