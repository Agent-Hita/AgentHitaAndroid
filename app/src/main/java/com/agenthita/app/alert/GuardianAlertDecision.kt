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

    /**
     * Returns true if the alerts toggle should be auto-enabled.
     * Only fires on first-time setup (no email previously saved) when the new email is valid.
     * On re-entry the user's explicit toggle choice is always respected.
     */
    fun shouldAutoEnable(previousEmail: String?, emailValid: Boolean): Boolean =
        emailValid && previousEmail.isNullOrEmpty()

    fun alertsLabel(isEnabled: Boolean): String =
        if (isEnabled) "Guardian alerts (ON)" else "Guardian alerts (OFF)"
}
