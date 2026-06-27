package com.agenthita.app.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianAlertDecisionTest {

    // ── alertsLabel ───────────────────────────────────────────────────────────

    @Test
    fun `alertsLabel returns ON when enabled`() {
        assertEquals("Guardian alerts (ON)", GuardianAlertDecision.alertsLabel(true))
    }

    @Test
    fun `alertsLabel returns OFF when disabled`() {
        assertEquals("Guardian alerts (OFF)", GuardianAlertDecision.alertsLabel(false))
    }

    // ── computeChanges — no-op cases ──────────────────────────────────────────

    @Test
    fun `no changes when alerts were off and remain off with no email`() {
        val result = GuardianAlertDecision.computeChanges(
            previousEmail = null,
            wasEnabled    = false,
            newEmail      = null,
            isNowEnabled  = false
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `no changes when same email remains enabled`() {
        val result = GuardianAlertDecision.computeChanges(
            previousEmail = "guardian@example.com",
            wasEnabled    = true,
            newEmail      = "guardian@example.com",
            isNowEnabled  = true
        )
        assertTrue(result.isEmpty())
    }

    // ── computeChanges — ADDED ────────────────────────────────────────────────

    @Test
    fun `ADDED when alerts are enabled for the first time`() {
        val result = GuardianAlertDecision.computeChanges(
            previousEmail = null,
            wasEnabled    = false,
            newEmail      = "guardian@example.com",
            isNowEnabled  = true
        )
        assertEquals(1, result.size)
        assertEquals(GuardianAlertChange("guardian@example.com", "ADDED"), result[0])
    }

    @Test
    fun `ADDED when previously disabled alerts are re-enabled with same email`() {
        val result = GuardianAlertDecision.computeChanges(
            previousEmail = "guardian@example.com",
            wasEnabled    = false,
            newEmail      = "guardian@example.com",
            isNowEnabled  = true
        )
        assertEquals(1, result.size)
        assertEquals(GuardianAlertChange("guardian@example.com", "ADDED"), result[0])
    }

    // ── computeChanges — REMOVED ──────────────────────────────────────────────

    @Test
    fun `REMOVED when alerts are disabled`() {
        val result = GuardianAlertDecision.computeChanges(
            previousEmail = "guardian@example.com",
            wasEnabled    = true,
            newEmail      = null,
            isNowEnabled  = false
        )
        assertEquals(1, result.size)
        assertEquals(GuardianAlertChange("guardian@example.com", "REMOVED"), result[0])
    }

    @Test
    fun `REMOVED uses the previous email not the new one when disabling`() {
        val result = GuardianAlertDecision.computeChanges(
            previousEmail = "old@example.com",
            wasEnabled    = true,
            newEmail      = null,
            isNowEnabled  = false
        )
        assertEquals("old@example.com", result[0].email)
        assertEquals("REMOVED", result[0].action)
    }

    // ── computeChanges — REMOVED + ADDED (email change) ──────────────────────

    @Test
    fun `REMOVED old and ADDED new when email changes while alerts stay enabled`() {
        val result = GuardianAlertDecision.computeChanges(
            previousEmail = "old@example.com",
            wasEnabled    = true,
            newEmail      = "new@example.com",
            isNowEnabled  = true
        )
        assertEquals(2, result.size)
        assertEquals(GuardianAlertChange("old@example.com", "REMOVED"), result[0])
        assertEquals(GuardianAlertChange("new@example.com", "ADDED"), result[1])
    }

    @Test
    fun `REMOVED old and ADDED new when email changes and alerts become enabled`() {
        val result = GuardianAlertDecision.computeChanges(
            previousEmail = "old@example.com",
            wasEnabled    = true,
            newEmail      = "new@example.com",
            isNowEnabled  = true
        )
        val actions = result.map { it.action }
        assertTrue(actions.contains("REMOVED"))
        assertTrue(actions.contains("ADDED"))
    }

    // ── computeChanges — order guarantee ─────────────────────────────────────

    @Test
    fun `REMOVED is emitted before ADDED when both occur`() {
        val result = GuardianAlertDecision.computeChanges(
            previousEmail = "old@example.com",
            wasEnabled    = true,
            newEmail      = "new@example.com",
            isNowEnabled  = true
        )
        assertEquals("REMOVED", result[0].action)
        assertEquals("ADDED", result[1].action)
    }

    // ── shouldAutoEnable ──────────────────────────────────────────────────────

    @Test
    fun `shouldAutoEnable is true on first setup with valid email`() {
        assertTrue(GuardianAlertDecision.shouldAutoEnable(previousEmail = null, emailValid = true))
    }

    @Test
    fun `shouldAutoEnable is true when previous email was empty string`() {
        assertTrue(GuardianAlertDecision.shouldAutoEnable(previousEmail = "", emailValid = true))
    }

    @Test
    fun `shouldAutoEnable is false when email is invalid`() {
        assertFalse(GuardianAlertDecision.shouldAutoEnable(previousEmail = null, emailValid = false))
    }

    @Test
    fun `shouldAutoEnable is false on re-entry with existing email`() {
        assertFalse(GuardianAlertDecision.shouldAutoEnable(previousEmail = "guardian@example.com", emailValid = true))
    }

    // ── first-time setup scenario ─────────────────────────────────────────────
    // No prior email. User enters email, toggle auto-enables → ADDED sent.

    @Test
    fun `first setup - auto-enable fires and ADDED is sent`() {
        val previousEmail = null
        val emailValid    = true
        val newEmail      = "guardian@example.com"
        val switchChecked = GuardianAlertDecision.shouldAutoEnable(previousEmail, emailValid) // true

        assertTrue(switchChecked)

        val result = GuardianAlertDecision.computeChanges(
            previousEmail = previousEmail,
            wasEnabled    = false,
            newEmail      = newEmail,
            isNowEnabled  = switchChecked
        )
        assertEquals(1, result.size)
        assertEquals(GuardianAlertChange(newEmail, "ADDED"), result[0])
    }

    @Test
    fun `first setup - invalid email means no auto-enable and no notification`() {
        val previousEmail = null
        val emailValid    = false
        val switchChecked = GuardianAlertDecision.shouldAutoEnable(previousEmail, emailValid) // false

        assertFalse(switchChecked)

        val result = GuardianAlertDecision.computeChanges(
            previousEmail = previousEmail,
            wasEnabled    = false,
            newEmail      = null,
            isNowEnabled  = false
        )
        assertTrue(result.isEmpty())
    }

    // ── re-entry: user manually turns toggle OFF ──────────────────────────────
    // Email already saved. User explicitly unchecks the toggle and saves.
    // Auto-enable must NOT fire. Toggle OFF must be persisted. REMOVED sent.

    @Test
    fun `re-entry - auto-enable does not fire when email already saved`() {
        val previousEmail = "guardian@example.com"
        val emailValid    = true
        val autoEnable    = GuardianAlertDecision.shouldAutoEnable(previousEmail, emailValid)

        assertFalse(autoEnable)
    }

    @Test
    fun `re-entry - user turns toggle OFF and REMOVED is sent`() {
        val result = GuardianAlertDecision.computeChanges(
            previousEmail = "guardian@example.com",
            wasEnabled    = true,
            newEmail      = "guardian@example.com",
            isNowEnabled  = false  // user explicitly unchecked
        )
        assertEquals(1, result.size)
        assertEquals(GuardianAlertChange("guardian@example.com", "REMOVED"), result[0])
    }

    @Test
    fun `re-entry - toggle stays OFF after being manually disabled - no ADDED sent`() {
        val result = GuardianAlertDecision.computeChanges(
            previousEmail = "guardian@example.com",
            wasEnabled    = false,  // was already off from previous save
            newEmail      = "guardian@example.com",
            isNowEnabled  = false   // user leaves it off
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `re-entry - user re-enables toggle after manually disabling it - ADDED sent`() {
        val result = GuardianAlertDecision.computeChanges(
            previousEmail = "guardian@example.com",
            wasEnabled    = false,
            newEmail      = "guardian@example.com",
            isNowEnabled  = true   // user re-checks the toggle
        )
        assertEquals(1, result.size)
        assertEquals(GuardianAlertChange("guardian@example.com", "ADDED"), result[0])
    }
}
