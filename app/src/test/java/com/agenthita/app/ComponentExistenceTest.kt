package com.agenthita.app

import com.agenthita.app.alert.GuardianAlertSender
import com.agenthita.app.alert.LocalNotificationManager
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.consent.AntiCoercionMonitor
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.detection.ConversationBuffer
import com.agenthita.app.detection.WordLexicon
import com.agenthita.app.service.HitaAccessibilityService
import com.agenthita.app.telemetry.TelemetryManager
import com.agenthita.app.model.ModelDownloadWorker
import com.agenthita.app.ui.DashboardActivity
import com.agenthita.app.ui.GuardianSetupActivity
import com.agenthita.app.ui.OnboardingActivity
import com.agenthita.app.ui.TermsActivity
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Compile-time existence guard.
 *
 * Each test references a class that must never be accidentally deleted.
 * If a class is removed from the codebase, this file will fail to compile
 * and the build will break — catching the deletion before it ships.
 *
 * These tests do NOT instantiate Android-dependent classes (no Context needed).
 * They only obtain the Class object, which is safe on the JVM test runtime.
 */
class ComponentExistenceTest {

    // ── Core service ──────────────────────────────────────────────────────────

    @Test
    fun `HitaAccessibilityService class exists`() {
        val klass: Class<*> = HitaAccessibilityService::class.java
        assertNotNull(klass)
    }

    // ── Detection pipeline ────────────────────────────────────────────────────

    @Test
    fun `WordLexicon class exists`() {
        val klass: Class<*> = WordLexicon::class.java
        assertNotNull(klass)
    }

    @Test
    fun `ConversationBuffer class exists`() {
        val klass: Class<*> = ConversationBuffer::class.java
        assertNotNull(klass)
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    @Test
    fun `RemoteConfig class exists`() {
        val klass: Class<*> = RemoteConfig::class.java
        assertNotNull(klass)
    }

    // ── Consent and anti-coercion ─────────────────────────────────────────────

    @Test
    fun `ConsentManager class exists`() {
        val klass: Class<*> = ConsentManager::class.java
        assertNotNull(klass)
    }

    @Test
    fun `AntiCoercionMonitor class exists`() {
        val klass: Class<*> = AntiCoercionMonitor::class.java
        assertNotNull(klass)
    }

    // ── Alerting ──────────────────────────────────────────────────────────────

    @Test
    fun `GuardianAlertSender class exists`() {
        val klass: Class<*> = GuardianAlertSender::class.java
        assertNotNull(klass)
    }

    @Test
    fun `LocalNotificationManager class exists`() {
        val klass: Class<*> = LocalNotificationManager::class.java
        assertNotNull(klass)
    }

    // ── Telemetry ─────────────────────────────────────────────────────────────

    @Test
    fun `TelemetryManager class exists`() {
        val klass: Class<*> = TelemetryManager::class.java
        assertNotNull(klass)
    }

    // ── UI screens ────────────────────────────────────────────────────────────

    @Test
    fun `TermsActivity class exists`() {
        val klass: Class<*> = TermsActivity::class.java
        assertNotNull(klass)
    }

    @Test
    fun `OnboardingActivity class exists`() {
        val klass: Class<*> = OnboardingActivity::class.java
        assertNotNull(klass)
    }

    @Test
    fun `ModelDownloadWorker class exists`() {
        val klass: Class<*> = ModelDownloadWorker::class.java
        assertNotNull(klass)
    }

    @Test
    fun `GuardianSetupActivity class exists`() {
        val klass: Class<*> = GuardianSetupActivity::class.java
        assertNotNull(klass)
    }

    @Test
    fun `DashboardActivity class exists`() {
        val klass: Class<*> = DashboardActivity::class.java
        assertNotNull(klass)
    }
}
