package com.agenthita.app.telemetry

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * ISO 3166-1 alpha-2 country code, shared by TelemetryManager (event dimension)
 * and GuardianAlertSender (alert payload) — extracted so both derive it the
 * same way instead of duplicating the SIM-then-locale fallback logic.
 * Prefers the SIM's network country (most reliable); falls back to the
 * device locale. Returns "unknown" if neither is available.
 */
object CountryDetector {
    fun detect(context: Context): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val simCountry = tm.simCountryIso?.uppercase()?.takeIf { it.length == 2 }
            simCountry ?: Locale.getDefault().country.uppercase().takeIf { it.length == 2 } ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
