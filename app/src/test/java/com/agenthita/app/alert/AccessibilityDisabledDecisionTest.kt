package com.agenthita.app.alert

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityDisabledDecisionTest {

    // ── shouldNotifyMonitoringStopped — the genuine disablement case ──────────

    @Test
    fun `notifies on a genuine enabled to disabled transition with a guardian configured`() {
        assertTrue(
            AccessibilityDisabledDecision.shouldNotifyMonitoringStopped(
                currentlyEnabled = false,
                wasKnownEnabled = true,
                alreadyNotifiedForThisDisablement = false,
                guardianConfigured = true
            )
        )
    }

    // ── must NOT fire on a temporary OS freeze ────────────────────────────────
    // A frozen process never reaches this function at all in practice (it's
    // still present in ENABLED_ACCESSIBILITY_SERVICES), but the decision
    // function itself must also refuse to fire if ever called with
    // currentlyEnabled = true, regardless of the other flags.

    @Test
    fun `does not notify when still enabled, regardless of other flags`() {
        assertFalse(
            AccessibilityDisabledDecision.shouldNotifyMonitoringStopped(
                currentlyEnabled = true,
                wasKnownEnabled = true,
                alreadyNotifiedForThisDisablement = false,
                guardianConfigured = true
            )
        )
    }

    // ── must not fire repeatedly for the same disablement ─────────────────────

    @Test
    fun `does not notify again once already notified for this disablement`() {
        assertFalse(
            AccessibilityDisabledDecision.shouldNotifyMonitoringStopped(
                currentlyEnabled = false,
                wasKnownEnabled = true,
                alreadyNotifiedForThisDisablement = true,
                guardianConfigured = true
            )
        )
    }

    // ── must not fire if it was never known to be enabled ─────────────────────
    // (e.g. onboarding never completed, or a prior check already recorded the
    // disabled state — this is what prevents re-notifying every 6 hours while
    // it stays off.)

    @Test
    fun `does not notify when it was not previously known to be enabled`() {
        assertFalse(
            AccessibilityDisabledDecision.shouldNotifyMonitoringStopped(
                currentlyEnabled = false,
                wasKnownEnabled = false,
                alreadyNotifiedForThisDisablement = false,
                guardianConfigured = true
            )
        )
    }

    // ── must not fire with no guardian to notify ──────────────────────────────

    @Test
    fun `does not notify when no guardian is configured`() {
        assertFalse(
            AccessibilityDisabledDecision.shouldNotifyMonitoringStopped(
                currentlyEnabled = false,
                wasKnownEnabled = true,
                alreadyNotifiedForThisDisablement = false,
                guardianConfigured = false
            )
        )
    }

    // ── shouldResetTracking — re-enabling allows future detection ─────────────

    @Test
    fun `resets tracking when re-enabled after being known disabled`() {
        assertTrue(AccessibilityDisabledDecision.shouldResetTracking(currentlyEnabled = true, wasKnownEnabled = false))
    }

    @Test
    fun `does not reset when already known enabled and still enabled`() {
        assertFalse(AccessibilityDisabledDecision.shouldResetTracking(currentlyEnabled = true, wasKnownEnabled = true))
    }

    @Test
    fun `does not reset while still disabled`() {
        assertFalse(AccessibilityDisabledDecision.shouldResetTracking(currentlyEnabled = false, wasKnownEnabled = false))
    }

    // ── full lifecycle scenario ────────────────────────────────────────────────
    // Enabled -> disabled (notify) -> stays disabled (no repeat) -> re-enabled
    // (reset) -> disabled again (notify again).

    @Test
    fun `full lifecycle - disable, repeat check, re-enable, disable again`() {
        // 1. Genuine disablement: notify.
        assertTrue(
            AccessibilityDisabledDecision.shouldNotifyMonitoringStopped(
                currentlyEnabled = false, wasKnownEnabled = true,
                alreadyNotifiedForThisDisablement = false, guardianConfigured = true
            )
        )
        // 2. Next periodic check, still disabled, already notified: must not repeat.
        assertFalse(
            AccessibilityDisabledDecision.shouldNotifyMonitoringStopped(
                currentlyEnabled = false, wasKnownEnabled = false,
                alreadyNotifiedForThisDisablement = true, guardianConfigured = true
            )
        )
        // 3. Re-enabled: tracking resets.
        assertTrue(AccessibilityDisabledDecision.shouldResetTracking(currentlyEnabled = true, wasKnownEnabled = false))

        // 4. Disabled again after reset (wasKnownEnabled back to true, notified back to false): notify again.
        assertTrue(
            AccessibilityDisabledDecision.shouldNotifyMonitoringStopped(
                currentlyEnabled = false, wasKnownEnabled = true,
                alreadyNotifiedForThisDisablement = false, guardianConfigured = true
            )
        )
    }
}
