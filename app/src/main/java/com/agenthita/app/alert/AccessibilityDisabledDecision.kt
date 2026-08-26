package com.agenthita.app.alert

object AccessibilityDisabledDecision {

    /**
     * Pure function — no Android dependency.
     *
     * Returns true only on a genuine ENABLED -> DISABLED transition of the
     * accessibility service: fires once per disablement (not on every periodic
     * check while it stays off), and only when a guardian is actually configured
     * to receive alerts. A device that was never enabled, or is still enabled
     * (including "enabled but the process is temporarily frozen by the OS" —
     * that case never reaches here, since it's still present in
     * ENABLED_ACCESSIBILITY_SERVICES), must never fire this.
     */
    fun shouldNotifyMonitoringStopped(
        currentlyEnabled: Boolean,
        wasKnownEnabled: Boolean,
        alreadyNotifiedForThisDisablement: Boolean,
        guardianConfigured: Boolean
    ): Boolean =
        !currentlyEnabled && wasKnownEnabled && !alreadyNotifiedForThisDisablement && guardianConfigured

    /**
     * True when tracking state should reset back to "known enabled" — either the
     * service is enabled again (a future disablement must be detectable and
     * notifiable again), or it was already reported and there's nothing further
     * to track until the state actually changes.
     */
    fun shouldResetTracking(currentlyEnabled: Boolean, wasKnownEnabled: Boolean): Boolean =
        currentlyEnabled && !wasKnownEnabled
}
