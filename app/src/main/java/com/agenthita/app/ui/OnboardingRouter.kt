package com.agenthita.app.ui

/**
 * Pure routing logic for the onboarding flow — no Android dependencies.
 *
 * Maps the full consent state to the next required screen so that:
 *  - the Activities stay thin (just read state, delegate here)
 *  - the routing rules are unit-testable on the JVM without Robolectric
 *
 * Destinations in order:
 *   HITA_TERMS → ACCESSIBILITY_PERMISSION → GUARDIAN_SETUP → GEMMA_TERMS → DASHBOARD
 */
object OnboardingRouter {

    enum class Destination {
        HITA_TERMS,
        ACCESSIBILITY_PERMISSION,
        GUARDIAN_SETUP,
        GEMMA_TERMS,
        DASHBOARD
    }

    data class State(
        val hasAcceptedHitaTerms: Boolean = false,
        val isOnboardingComplete: Boolean = false,
        val isGuardianSetupComplete: Boolean = false,
        val hasAcceptedGemmaTerms: Boolean = false
    )

    fun nextDestination(state: State): Destination = when {
        !state.hasAcceptedHitaTerms    -> Destination.HITA_TERMS
        !state.isOnboardingComplete    -> Destination.ACCESSIBILITY_PERMISSION
        !state.isGuardianSetupComplete -> Destination.GUARDIAN_SETUP
        !state.hasAcceptedGemmaTerms   -> Destination.GEMMA_TERMS
        else                           -> Destination.DASHBOARD
    }
}
