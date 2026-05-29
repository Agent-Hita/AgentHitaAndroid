package com.agenthita.app.detection

/**
 * Category-specific word-level lexicon for order-independent scoring.
 *
 * Each category holds a map of stem/root words → weight [0.0, 1.0].
 * High-weight words are strong individual signals (e.g. "nude", "extort").
 * Low-weight words only matter when several appear together (e.g. "trust",
 * "situation", "forever").
 *
 * Design rules:
 *  - Words should be roots/stems so they match inflections (transfer, transferred)
 *  - Avoid single-letter words and extremely common words (is, the, you)
 *  - A word may appear in multiple categories with different weights
 *  - The score() function sums matched weights and normalises by the same
 *    divisor (3.0) used by phrase detectors, keeping scales consistent
 *
 * Maintenance: add new words here only. No code logic changes needed.
 */
object WordLexicon {

    val weights: Map<HarmCategory, Map<String, Float>> = mapOf(

        HarmCategory.ROMANCE_SCAM to mapOf(
            // Financial request language
            "transfer"    to 0.55f,
            "transferred" to 0.55f,
            "lend"        to 0.55f,
            "lending"     to 0.55f,
            "borrow"      to 0.50f,
            "borrowing"   to 0.50f,
            "loan"        to 0.50f,
            "repay"       to 0.50f,
            "repayment"   to 0.50f,
            "funds"       to 0.45f,
            "wired"       to 0.55f,
            // Crisis / hardship framing
            "stranded"    to 0.65f,
            "emergency"   to 0.50f,
            "struggling"  to 0.40f,
            "desperate"   to 0.50f,
            "stuck"       to 0.30f,
            "trapped"     to 0.35f,
            "hospital"    to 0.45f,
            "accident"    to 0.40f,
            "situation"   to 0.20f,
            "trouble"     to 0.25f,
            // Premature intimacy / false emotional bond
            "forever"     to 0.35f,
            "soulmate"    to 0.60f,
            "destiny"     to 0.45f,
            "soulmates"   to 0.60f,
            "miss"        to 0.20f,
            "missing"     to 0.20f,
            // Isolation / trust exploitation
            "trust"       to 0.22f,
            "alone"       to 0.22f,
            "nobody"      to 0.22f,
            "nobody"      to 0.22f
        ),

        HarmCategory.SEXTORTION to mapOf(
            // Explicit content
            "nude"        to 0.90f,
            "nudes"       to 0.90f,
            "naked"       to 0.75f,
            "explicit"    to 0.70f,
            "intimate"    to 0.50f,
            // Pressure / coercion
            "prove"       to 0.40f,
            "shy"         to 0.35f,
            "hesitant"    to 0.35f,
            "overthinking" to 0.35f,
            "further"     to 0.38f,
            // Secrecy / cover-up
            "secret"      to 0.45f,
            "judge"       to 0.30f,
            "judging"     to 0.30f,
            // Threat vocabulary
            "expose"      to 0.65f,
            "exposed"     to 0.65f,
            "regret"      to 0.50f,
            "leak"        to 0.55f,
            "leaked"      to 0.55f,
            "ruined"      to 0.45f,
            "blackmail"   to 0.95f,
            "extort"      to 0.95f
        ),

        HarmCategory.GROOMING to mapOf(
            // Age / vulnerability probing
            "grade"       to 0.40f,
            "school"      to 0.30f,
            "young"       to 0.30f,
            "mature"      to 0.30f,
            // Inappropriate intimacy with a minor
            "special"     to 0.25f,
            "understand"  to 0.20f,
            "protect"     to 0.25f,
            // Location / isolation
            "alone"       to 0.35f,
            "sneak"       to 0.60f,
            "sneaking"    to 0.60f,
            "secret"      to 0.45f,
            // Sexual escalation
            "teach"       to 0.35f,
            "normal"      to 0.30f,
            "natural"     to 0.28f,
            "scared"      to 0.35f,
            "shy"         to 0.35f,
            "nervous"     to 0.30f
        ),

        HarmCategory.FINANCIAL_SCAM to mapOf(
            // Urgency vocabulary
            "urgent"      to 0.55f,
            "immediately" to 0.50f,
            "overdue"     to 0.65f,
            "penalty"     to 0.55f,
            "penalties"   to 0.55f,
            "suspended"   to 0.60f,
            "suspension"  to 0.60f,
            "expired"     to 0.45f,
            "expire"      to 0.40f,
            // Payment / financial instrument words
            "bitcoin"     to 0.75f,
            "crypto"      to 0.65f,
            "cryptocurrency" to 0.70f,
            "zelle"       to 0.60f,
            "venmo"       to 0.55f,
            "cashapp"     to 0.60f,
            "wire"        to 0.50f,
            "wiring"      to 0.50f,
            // Authority impersonation
            "warrant"     to 0.65f,
            "lawsuit"     to 0.60f,
            "arrested"    to 0.55f,
            "irs"         to 0.60f,
            "medicare"    to 0.55f,
            "verification" to 0.40f,
            "verify"      to 0.40f,
            "billing"     to 0.35f,
            "charges"     to 0.30f
        ),

        HarmCategory.IDENTITY_PHISHING to mapOf(
            "password"    to 0.80f,
            "passwords"   to 0.80f,
            "credentials" to 0.85f,
            "otp"         to 0.85f,
            "pin"         to 0.55f,
            "verification" to 0.55f,
            "code"        to 0.40f,
            "ssn"         to 0.90f,
            "passport"    to 0.70f,
            "identity"    to 0.40f,
            "login"       to 0.55f,
            "reset"       to 0.40f,
            "phishing"    to 0.95f,
            "suspicious"  to 0.35f,
            "compromised" to 0.60f,
            "hacked"      to 0.55f
        ),

        HarmCategory.LURING to mapOf(
            "opportunity"  to 0.35f,
            "modelling"    to 0.55f,
            "modeling"     to 0.55f,
            "casting"      to 0.55f,
            "audition"     to 0.50f,
            "influencer"   to 0.40f,
            "sponsored"    to 0.35f,
            "easy money"   to 0.70f,
            "quick money"  to 0.70f,
            "income"       to 0.30f,
            "recruitment"  to 0.35f,
            "hired"        to 0.30f,
            "meet"         to 0.25f,
            "private"      to 0.30f
        ),

        HarmCategory.HARASSMENT to mapOf(
            "kill"        to 0.80f,
            "hurt"        to 0.60f,
            "destroy"     to 0.55f,
            "ruin"        to 0.55f,
            "stalk"       to 0.75f,
            "stalking"    to 0.75f,
            "threaten"    to 0.70f,
            "threatened"  to 0.70f,
            "threat"      to 0.65f,
            "doxx"        to 0.85f,
            "doxxed"      to 0.85f,
            "attack"      to 0.55f,
            "abuse"       to 0.60f,
            "abusive"     to 0.60f,
            "coerce"      to 0.70f,
            "coercing"    to 0.70f,
            "control"     to 0.35f,
            "manipulate"  to 0.55f
        )
    )

    /**
     * Tokenises [text] into lowercase words, looks each up in the category
     * dictionary, and returns a normalised score [0.0, 1.0].
     *
     * Normalisation divisor is 3.0 — the same value phrase detectors use —
     * so word scores are directly comparable to phrase scores.
     *
     * Returns 0.0 if the category has no lexicon or no words match.
     */
    fun score(text: String, category: HarmCategory): Float {
        val dict = weights[category] ?: return 0f
        val tokens = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        val total = tokens.sumOf { (dict[it] ?: 0f).toDouble() }.toFloat()
        return (total / 3f).coerceIn(0f, 1f)
    }
}
