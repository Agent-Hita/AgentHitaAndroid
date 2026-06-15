package com.agenthita.app.alert

data class GuardianAlertChange(val email: String, val action: String)

object GuardianAlertDecision {

    /**
     * Pure function — no Android dependency.
     * Returns the list of backend notifications that must be posted when the
     * guardian configuration changes. The Activity calls this and fires the
     * network requests for each entry.
     */
    fun computeChanges(
        previousEmail: String?,
        wasEnabled: Boolean,
        newEmail: String?,
        isNowEnabled: Boolean
    ): List<GuardianAlertChange> {
        val changes = mutableListOf<GuardianAlertChange>()
        if (wasEnabled && previousEmail != null && (!isNowEnabled || previousEmail != newEmail)) {
            changes += GuardianAlertChange(previousEmail, "REMOVED")
        }
        if (isNowEnabled && newEmail != null && (!wasEnabled || previousEmail != newEmail)) {
            changes += GuardianAlertChange(newEmail, "ADDED")
        }
        return changes
    }

    fun alertsLabel(isEnabled: Boolean): String =
        if (isEnabled) "Guardian alerts (ON)" else "Guardian alerts (OFF)"
}
