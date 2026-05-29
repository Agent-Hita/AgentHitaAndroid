package com.agenthita.app.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordLexiconTest {

    // ── Coverage: all categories present ──────────────────────────────────────

    @Test
    fun `all seven harm categories have entries in the lexicon`() {
        val required = listOf(
            HarmCategory.ROMANCE_SCAM,
            HarmCategory.SEXTORTION,
            HarmCategory.GROOMING,
            HarmCategory.FINANCIAL_SCAM,
            HarmCategory.IDENTITY_PHISHING,
            HarmCategory.LURING,
            HarmCategory.HARASSMENT
        )
        required.forEach { category ->
            val entries = WordLexicon.weights[category]
            assertNotNull("WordLexicon missing category: $category", entries)
            assertTrue(
                "WordLexicon.$category has fewer than 5 entries (got ${entries!!.size})",
                entries.size >= 5
            )
        }
    }

    @Test
    fun `all weight values are in range 0_0 to 1_0`() {
        WordLexicon.weights.forEach { (category, dict) ->
            dict.forEach { (word, weight) ->
                assertTrue(
                    "Weight out of range for $category/$word: $weight",
                    weight in 0f..1f
                )
            }
        }
    }

    // ── Scoring: zero on clean text ───────────────────────────────────────────

    @Test
    fun `score returns 0 for empty string`() {
        HarmCategory.values().forEach { category ->
            assertEquals(0f, WordLexicon.score("", category), 0.001f)
        }
    }

    @Test
    fun `score returns 0 for benign greeting`() {
        val text = "Hey how are you doing today"
        HarmCategory.values().filter { it != HarmCategory.DISAPPEARING_MESSAGES }.forEach { category ->
            val score = WordLexicon.score(text, category)
            assertEquals("Expected 0 for '$text' in $category, got $score", 0f, score, 0.001f)
        }
    }

    // ── Scoring: high-signal words register ───────────────────────────────────

    @Test
    fun `blackmail scores high in SEXTORTION`() {
        val score = WordLexicon.score("I will blackmail you", HarmCategory.SEXTORTION)
        assertTrue("Expected score > 0.2 for blackmail in SEXTORTION, got $score", score > 0.2f)
    }

    @Test
    fun `extort scores high in SEXTORTION`() {
        val score = WordLexicon.score("I will extort you", HarmCategory.SEXTORTION)
        assertTrue("Expected score > 0.2 for extort in SEXTORTION, got $score", score > 0.2f)
    }

    @Test
    fun `nude scores high in SEXTORTION`() {
        val score = WordLexicon.score("send me a nude photo", HarmCategory.SEXTORTION)
        assertTrue("Expected score > 0.2 for nude in SEXTORTION, got $score", score > 0.2f)
    }

    @Test
    fun `bitcoin scores in FINANCIAL_SCAM`() {
        val score = WordLexicon.score("send bitcoin immediately", HarmCategory.FINANCIAL_SCAM)
        assertTrue("Expected score > 0.2 for bitcoin in FINANCIAL_SCAM, got $score", score > 0.2f)
    }

    @Test
    fun `password scores high in IDENTITY_PHISHING`() {
        val score = WordLexicon.score("give me your password", HarmCategory.IDENTITY_PHISHING)
        assertTrue("Expected score > 0.2 for password in IDENTITY_PHISHING, got $score", score > 0.2f)
    }

    @Test
    fun `ssn scores high in IDENTITY_PHISHING`() {
        val score = WordLexicon.score("what is your ssn number", HarmCategory.IDENTITY_PHISHING)
        assertTrue("Expected score > 0.2 for ssn in IDENTITY_PHISHING, got $score", score > 0.2f)
    }

    @Test
    fun `kill scores in HARASSMENT`() {
        val score = WordLexicon.score("I will kill you", HarmCategory.HARASSMENT)
        assertTrue("Expected score > 0.2 for kill in HARASSMENT, got $score", score > 0.2f)
    }

    @Test
    fun `doxx scores in HARASSMENT`() {
        val score = WordLexicon.score("I will doxx you online", HarmCategory.HARASSMENT)
        assertTrue("Expected score > 0.2 for doxx in HARASSMENT, got $score", score > 0.2f)
    }

    @Test
    fun `stranded scores in ROMANCE_SCAM`() {
        val score = WordLexicon.score("I am stranded please help", HarmCategory.ROMANCE_SCAM)
        assertTrue("Expected score > 0.1 for stranded in ROMANCE_SCAM, got $score", score > 0.1f)
    }

    @Test
    fun `sneak scores in GROOMING`() {
        val score = WordLexicon.score("let us sneak away together", HarmCategory.GROOMING)
        assertTrue("Expected score > 0.1 for sneak in GROOMING, got $score", score > 0.1f)
    }

    // ── Scoring: bounded at 1.0 ───────────────────────────────────────────────

    @Test
    fun `score is capped at 1_0 for dense high-weight text`() {
        val text = "blackmail extort nude nudes naked expose exposed leak leaked ruined"
        val score = WordLexicon.score(text, HarmCategory.SEXTORTION)
        assertTrue("Score must be <= 1.0, got $score", score <= 1.0f)
    }

    // ── Scoring: case insensitive ─────────────────────────────────────────────

    @Test
    fun `score is case insensitive`() {
        val lower = WordLexicon.score("blackmail", HarmCategory.SEXTORTION)
        val upper = WordLexicon.score("BLACKMAIL", HarmCategory.SEXTORTION)
        val mixed = WordLexicon.score("BlackMail", HarmCategory.SEXTORTION)
        assertEquals(lower, upper, 0.001f)
        assertEquals(lower, mixed, 0.001f)
    }

    // ── Cross-category isolation ──────────────────────────────────────────────

    @Test
    fun `sextortion keyword does not bleed into unrelated category`() {
        val score = WordLexicon.score("nude", HarmCategory.FINANCIAL_SCAM)
        assertEquals(0f, score, 0.001f)
    }
}
