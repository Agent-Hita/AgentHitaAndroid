package com.agenthita.app.detection

// Timer durations above this threshold are treated as a global default setting
// (e.g. WhatsApp's 90-day default) rather than a targeted per-contact activation.
internal const val DISAPPEARING_SHORT_TIMER_THRESHOLD_DAYS = 7

/**
 * Parses a timer duration in days from a disappearing-message banner string.
 * Handles "24 hours", "7 days", "4 weeks", "90 days", "1 year" etc.
 * Returns null when no recognisable duration is present — callers treat null
 * as unknown/short (conservative: flag rather than suppress).
 */
internal fun parseDurationDays(text: String): Int? {
    val match = Regex("""(\d+)\s*(second|minute|hour|day|week|month|year)s?""", RegexOption.IGNORE_CASE)
        .find(text) ?: return null
    val amount = match.groupValues[1].toIntOrNull() ?: return null
    return when (match.groupValues[2].lowercase()) {
        "second" -> 0
        "minute" -> 0
        "hour"   -> if (amount >= 24) 1 else 0
        "day"    -> amount
        "week"   -> amount * 7
        "month"  -> amount * 30
        "year"   -> amount * 365
        else     -> null
    }
}

/**
 * Returns the risk level to assign to a disappearing-messages banner detection,
 * or null if the activation should be suppressed entirely.
 *
 *  - Short timer (≤ threshold) or unknown duration → HIGH: targeted per-contact activation.
 *  - Long timer + active threat in session → MEDIUM: amplifier signal.
 *  - Long timer + no threat → null: likely global default, not harmful on its own.
 */
internal fun disappearingRiskLevel(durationDays: Int?, hasExistingThreat: Boolean): RiskLevel? {
    val isLongDefault = durationDays != null && durationDays > DISAPPEARING_SHORT_TIMER_THRESHOLD_DAYS
    return when {
        !isLongDefault    -> RiskLevel.HIGH
        hasExistingThreat -> RiskLevel.MEDIUM
        else              -> null
    }
}
