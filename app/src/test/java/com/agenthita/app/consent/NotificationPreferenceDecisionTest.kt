package com.agenthita.app.consent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPreferenceDecisionTest {

    // ── defaultNotifyOnlyHighRisk ────────────────────────────────────────────

    @Test
    fun `self-protecting adult defaults to high-risk-only notifications`() {
        assertTrue(
            NotificationPreferenceDecision.defaultNotifyOnlyHighRisk(UserCategory.SELF_PROTECTING_ADULT)
        )
    }

    @Test
    fun `vulnerable adult defaults to notifying on both MEDIUM and HIGH`() {
        assertFalse(
            NotificationPreferenceDecision.defaultNotifyOnlyHighRisk(UserCategory.VULNERABLE_ADULT)
        )
    }

    @Test
    fun `adolescent defaults to notifying on both MEDIUM and HIGH`() {
        assertFalse(
            NotificationPreferenceDecision.defaultNotifyOnlyHighRisk(UserCategory.ADOLESCENT)
        )
    }

    @Test
    fun `child defaults to notifying on both MEDIUM and HIGH`() {
        assertFalse(
            NotificationPreferenceDecision.defaultNotifyOnlyHighRisk(UserCategory.CHILD)
        )
    }

    @Test
    fun `only SELF_PROTECTING_ADULT defaults to true across all categories`() {
        val defaultsToHighRiskOnly = UserCategory.entries.filter {
            NotificationPreferenceDecision.defaultNotifyOnlyHighRisk(it)
        }
        assertTrue(defaultsToHighRiskOnly == listOf(UserCategory.SELF_PROTECTING_ADULT))
    }

    // ── shouldResetToDefault ──────────────────────────────────────────────────

    @Test
    fun `resets on first-time setup even when category matches the fallback default`() {
        assertTrue(
            NotificationPreferenceDecision.shouldResetToDefault(
                previousCategory = UserCategory.SELF_PROTECTING_ADULT,
                newCategory = UserCategory.SELF_PROTECTING_ADULT,
                isFirstTimeSetup = true
            )
        )
    }

    @Test
    fun `resets when who-are-you-protecting changes from child to adult`() {
        assertTrue(
            NotificationPreferenceDecision.shouldResetToDefault(
                previousCategory = UserCategory.CHILD,
                newCategory = UserCategory.SELF_PROTECTING_ADULT,
                isFirstTimeSetup = false
            )
        )
    }

    @Test
    fun `resets when who-are-you-protecting changes from adult to child`() {
        assertTrue(
            NotificationPreferenceDecision.shouldResetToDefault(
                previousCategory = UserCategory.SELF_PROTECTING_ADULT,
                newCategory = UserCategory.CHILD,
                isFirstTimeSetup = false
            )
        )
    }

    @Test
    fun `resets when category changes between two non-adult types`() {
        assertTrue(
            NotificationPreferenceDecision.shouldResetToDefault(
                previousCategory = UserCategory.ADOLESCENT,
                newCategory = UserCategory.VULNERABLE_ADULT,
                isFirstTimeSetup = false
            )
        )
    }

    @Test
    fun `does not reset on re-entry when category is unchanged`() {
        assertFalse(
            NotificationPreferenceDecision.shouldResetToDefault(
                previousCategory = UserCategory.SELF_PROTECTING_ADULT,
                newCategory = UserCategory.SELF_PROTECTING_ADULT,
                isFirstTimeSetup = false
            )
        )
    }

    @Test
    fun `does not reset on re-entry for an unchanged child category`() {
        assertFalse(
            NotificationPreferenceDecision.shouldResetToDefault(
                previousCategory = UserCategory.CHILD,
                newCategory = UserCategory.CHILD,
                isFirstTimeSetup = false
            )
        )
    }

    // ── end-to-end scenario: re-entry, category change, manual toggle respected ──

    @Test
    fun `re-entry scenario - switching adult to child resets to notify-on-both`() {
        val previous = UserCategory.SELF_PROTECTING_ADULT
        val new = UserCategory.CHILD
        val shouldReset = NotificationPreferenceDecision.shouldResetToDefault(previous, new, isFirstTimeSetup = false)

        assertTrue(shouldReset)
        assertFalse(NotificationPreferenceDecision.defaultNotifyOnlyHighRisk(new))
    }

    @Test
    fun `re-entry scenario - unchanged category preserves a manually-set toggle`() {
        val previous = UserCategory.SELF_PROTECTING_ADULT
        val new = UserCategory.SELF_PROTECTING_ADULT
        val shouldReset = NotificationPreferenceDecision.shouldResetToDefault(previous, new, isFirstTimeSetup = false)

        assertFalse(shouldReset)
    }
}
