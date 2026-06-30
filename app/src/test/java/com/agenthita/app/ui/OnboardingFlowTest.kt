package com.agenthita.app.ui

import com.agenthita.app.ui.OnboardingRouter.Destination
import com.agenthita.app.ui.OnboardingRouter.Destination.ACCESSIBILITY_PERMISSION
import com.agenthita.app.ui.OnboardingRouter.Destination.DASHBOARD
import com.agenthita.app.ui.OnboardingRouter.Destination.GEMMA_TERMS
import com.agenthita.app.ui.OnboardingRouter.Destination.GUARDIAN_SETUP
import com.agenthita.app.ui.OnboardingRouter.Destination.HITA_TERMS
import com.agenthita.app.ui.OnboardingRouter.State
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the onboarding flow routing logic.
 *
 * Required sequence:
 *   HITA_TERMS → ACCESSIBILITY_PERMISSION → GUARDIAN_SETUP → GEMMA_TERMS → DASHBOARD
 *
 * Each step must gate every step that follows it — no step can be skipped
 * by completing a later one out of order.
 */
class OnboardingFlowTest {

    private fun route(
        hitaTerms: Boolean = false,
        accessibility: Boolean = false,
        guardianSetup: Boolean = false,
        gemmaTerms: Boolean = false
    ): Destination = OnboardingRouter.nextDestination(
        State(hitaTerms, accessibility, guardianSetup, gemmaTerms)
    )

    // ── Step 1: Hita terms ────────────────────────────────────────────────────

    @Test
    fun `fresh install routes to hita terms`() {
        assertEquals(HITA_TERMS, route())
    }

    @Test
    fun `hita terms gate blocks all subsequent steps`() {
        // Completing later steps without terms still routes back to terms
        assertEquals(HITA_TERMS, route(accessibility = true))
        assertEquals(HITA_TERMS, route(guardianSetup = true))
        assertEquals(HITA_TERMS, route(gemmaTerms = true))
        assertEquals(HITA_TERMS, route(accessibility = true, guardianSetup = true, gemmaTerms = true))
    }

    // ── Step 2: Accessibility permission ─────────────────────────────────────

    @Test
    fun `after hita terms routes to accessibility permission`() {
        assertEquals(ACCESSIBILITY_PERMISSION, route(hitaTerms = true))
    }

    @Test
    fun `accessibility gate blocks guardian setup and beyond`() {
        assertEquals(ACCESSIBILITY_PERMISSION, route(hitaTerms = true, guardianSetup = true))
        assertEquals(ACCESSIBILITY_PERMISSION, route(hitaTerms = true, gemmaTerms = true))
        assertEquals(ACCESSIBILITY_PERMISSION, route(hitaTerms = true, guardianSetup = true, gemmaTerms = true))
    }

    // ── Step 3: Guardian setup ────────────────────────────────────────────────

    @Test
    fun `after accessibility granted routes to guardian setup`() {
        assertEquals(GUARDIAN_SETUP, route(hitaTerms = true, accessibility = true))
    }

    @Test
    fun `guardian setup gate blocks gemma terms and dashboard`() {
        assertEquals(GUARDIAN_SETUP, route(hitaTerms = true, accessibility = true, gemmaTerms = true))
    }

    @Test
    fun `crash recovery — accessibility complete but guardian setup missing routes to guardian setup`() {
        // Regression for: old build crashed after isOnboardingComplete=true was persisted
        // but before GuardianSetupActivity was launched, leaving guardian setup never seen.
        assertEquals(
            GUARDIAN_SETUP,
            route(hitaTerms = true, accessibility = true, guardianSetup = false)
        )
    }

    // ── Step 4: Gemma terms ───────────────────────────────────────────────────

    @Test
    fun `after guardian setup routes to gemma terms`() {
        assertEquals(GEMMA_TERMS, route(hitaTerms = true, accessibility = true, guardianSetup = true))
    }

    @Test
    fun `gemma terms gate blocks dashboard`() {
        assertEquals(
            GEMMA_TERMS,
            route(hitaTerms = true, accessibility = true, guardianSetup = true, gemmaTerms = false)
        )
    }

    // ── Step 5: Dashboard ─────────────────────────────────────────────────────

    @Test
    fun `all steps complete routes to dashboard`() {
        assertEquals(
            DASHBOARD,
            route(hitaTerms = true, accessibility = true, guardianSetup = true, gemmaTerms = true)
        )
    }

    // ── Full sequence ─────────────────────────────────────────────────────────

    @Test
    fun `complete forward flow visits every required step in order`() {
        val visited = mutableListOf<Destination>()
        var state = State()

        while (true) {
            val dest = OnboardingRouter.nextDestination(state)
            visited.add(dest)
            state = when (dest) {
                HITA_TERMS               -> state.copy(hasAcceptedHitaTerms = true)
                ACCESSIBILITY_PERMISSION -> state.copy(isOnboardingComplete = true)
                GUARDIAN_SETUP           -> state.copy(isGuardianSetupComplete = true)
                GEMMA_TERMS              -> state.copy(hasAcceptedGemmaTerms = true)
                DASHBOARD                -> break
            }
        }

        assertEquals(
            listOf(HITA_TERMS, ACCESSIBILITY_PERMISSION, GUARDIAN_SETUP, GEMMA_TERMS, DASHBOARD),
            visited
        )
    }

    @Test
    fun `no step can be reached without all prior steps completed`() {
        // Verify each destination only appears when exactly the preceding steps are done
        val checkpoints = listOf(
            State()                                                                      to HITA_TERMS,
            State(hasAcceptedHitaTerms = true)                                           to ACCESSIBILITY_PERMISSION,
            State(hasAcceptedHitaTerms = true, isOnboardingComplete = true)              to GUARDIAN_SETUP,
            State(hasAcceptedHitaTerms = true, isOnboardingComplete = true,
                  isGuardianSetupComplete = true)                                         to GEMMA_TERMS,
            State(hasAcceptedHitaTerms = true, isOnboardingComplete = true,
                  isGuardianSetupComplete = true, hasAcceptedGemmaTerms = true)          to DASHBOARD
        )
        for ((state, expected) in checkpoints) {
            assertEquals(
                "Expected $expected for state $state",
                expected,
                OnboardingRouter.nextDestination(state)
            )
        }
    }
}
