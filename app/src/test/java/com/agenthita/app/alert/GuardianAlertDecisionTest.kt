package com.agenthita.app.alert

import org.junit.Assert.assertEquals
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
}
