package com.agenthita.app.consent

import com.agenthita.sdk.detection.UserCategory

object NotificationPreferenceDecision {

    /**
     * Pure function — no Android dependency.
     * Returns the default "notify on HIGH-risk only" preference for a protected-person
     * category, applied once at first-time setup. Only self-protecting adults default to
     * high-risk-only; VULNERABLE_ADULT, ADOLESCENT and CHILD default to notifying on both
     * MEDIUM and HIGH, so a category change never silently narrows alerts for anyone but
     * a self-protecting adult. The toggle itself remains freely changeable afterward via
     * NotificationSettingsActivity for every category — this only decides the starting value.
     */
    fun defaultNotifyOnlyHighRisk(category: UserCategory): Boolean =
        category == UserCategory.SELF_PROTECTING_ADULT

    /**
     * Pure function — no Android dependency.
     * Returns true when the notification preference should be reset to the category default:
     * on first-time setup, or whenever the protected-person category actually changes (e.g.
     * switching "who are you protecting" from CHILD to SELF_PROTECTING_ADULT or back). Resaving
     * the same category — changing the guardian email, for instance — must never reset a manual
     * toggle change made afterward in NotificationSettingsActivity.
     */
    fun shouldResetToDefault(
        previousCategory: UserCategory,
        newCategory: UserCategory,
        isFirstTimeSetup: Boolean
    ): Boolean = isFirstTimeSetup || previousCategory != newCategory
}
