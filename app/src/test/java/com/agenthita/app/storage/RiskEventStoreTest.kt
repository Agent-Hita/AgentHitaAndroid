package com.agenthita.app.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RiskEventStoreTest {

    private val DAY_MS = 86_400_000L
    private val NOW_MS = 1_000L * DAY_MS  // arbitrary fixed "now"

    private lateinit var dao: FakeRiskEventDao
    private lateinit var store: RiskEventStore

    @Before
    fun setUp() {
        dao = FakeRiskEventDao()
        store = RiskEventStore(dao)
    }

    // ── Default retention cutoffs ─────────────────────────────────────────────

    @Test
    fun `LOW cutoff is 30 days before now`() = runBlocking {
        store.pruneByTier(nowMs = NOW_MS)
        assertEquals(NOW_MS - 30 * DAY_MS, dao.lastLowCutoff)
    }

    @Test
    fun `MEDIUM cutoff is 60 days before now`() = runBlocking {
        store.pruneByTier(nowMs = NOW_MS)
        assertEquals(NOW_MS - 60 * DAY_MS, dao.lastMediumCutoff)
    }

    @Test
    fun `HIGH cutoff is 180 days before now`() = runBlocking {
        store.pruneByTier(nowMs = NOW_MS)
        assertEquals(NOW_MS - 180 * DAY_MS, dao.lastHighCutoff)
    }

    // ── All three tiers are called every run ──────────────────────────────────

    @Test
    fun `all three tier deletes are called on every prune`() = runBlocking {
        store.pruneByTier(nowMs = NOW_MS)
        assertEquals(1, dao.lowDeleteCount)
        assertEquals(1, dao.mediumDeleteCount)
        assertEquals(1, dao.highDeleteCount)
    }

    @Test
    fun `multiple prune calls each invoke all three deletes`() = runBlocking {
        repeat(3) { store.pruneByTier(nowMs = NOW_MS) }
        assertEquals(3, dao.lowDeleteCount)
        assertEquals(3, dao.mediumDeleteCount)
        assertEquals(3, dao.highDeleteCount)
    }

    // ── Cutoff ordering: lower tier = more recent cutoff ─────────────────────

    @Test
    fun `LOW cutoff is more recent than MEDIUM cutoff`() = runBlocking {
        store.pruneByTier(nowMs = NOW_MS)
        assertTrue(
            "LOW cutoff (${dao.lastLowCutoff}) must be more recent than MEDIUM (${dao.lastMediumCutoff})",
            dao.lastLowCutoff!! > dao.lastMediumCutoff!!
        )
    }

    @Test
    fun `MEDIUM cutoff is more recent than HIGH cutoff`() = runBlocking {
        store.pruneByTier(nowMs = NOW_MS)
        assertTrue(
            "MEDIUM cutoff (${dao.lastMediumCutoff}) must be more recent than HIGH (${dao.lastHighCutoff})",
            dao.lastMediumCutoff!! > dao.lastHighCutoff!!
        )
    }

    // ── Custom day overrides ──────────────────────────────────────────────────

    @Test
    fun `custom day values produce correct cutoffs`() = runBlocking {
        store.pruneByTier(lowMaxDays = 7, mediumMaxDays = 14, highMaxDays = 90, nowMs = NOW_MS)
        assertEquals(NOW_MS - 7  * DAY_MS, dao.lastLowCutoff)
        assertEquals(NOW_MS - 14 * DAY_MS, dao.lastMediumCutoff)
        assertEquals(NOW_MS - 90 * DAY_MS, dao.lastHighCutoff)
    }

    @Test
    fun `same day value for all tiers produces identical cutoffs`() = runBlocking {
        store.pruneByTier(lowMaxDays = 10, mediumMaxDays = 10, highMaxDays = 10, nowMs = NOW_MS)
        val expected = NOW_MS - 10 * DAY_MS
        assertEquals(expected, dao.lastLowCutoff)
        assertEquals(expected, dao.lastMediumCutoff)
        assertEquals(expected, dao.lastHighCutoff)
    }

    // ── Boundary: nowMs = 0 (epoch) ───────────────────────────────────────────

    @Test
    fun `cutoffs are negative when nowMs is less than the retention window`() = runBlocking {
        // nowMs of 1 day means the LOW cutoff (30 days back) goes negative — DAO still called
        store.pruneByTier(nowMs = DAY_MS)
        assertEquals(DAY_MS - 30 * DAY_MS, dao.lastLowCutoff)
        assertEquals(1, dao.lowDeleteCount)
    }

    // ── Failure propagation ───────────────────────────────────────────────────

    @Test(expected = RuntimeException::class)
    fun `exception from LOW delete propagates to caller`() = runBlocking {
        dao.throwOnLow = RuntimeException("db locked")
        store.pruneByTier(nowMs = NOW_MS)
    }

    @Test(expected = RuntimeException::class)
    fun `exception from MEDIUM delete propagates to caller`() = runBlocking {
        dao.throwOnMedium = RuntimeException("db locked")
        store.pruneByTier(nowMs = NOW_MS)
    }

    @Test(expected = RuntimeException::class)
    fun `exception from HIGH delete propagates to caller`() = runBlocking {
        dao.throwOnHigh = RuntimeException("db locked")
        store.pruneByTier(nowMs = NOW_MS)
    }

    @Test
    fun `MEDIUM and HIGH deletes are not called when LOW throws`() = runBlocking {
        dao.throwOnLow = RuntimeException("db locked")
        try { store.pruneByTier(nowMs = NOW_MS) } catch (_: RuntimeException) {}
        assertEquals(0, dao.mediumDeleteCount)
        assertEquals(0, dao.highDeleteCount)
    }

    // ── Fake DAO ──────────────────────────────────────────────────────────────

    private class FakeRiskEventDao : RiskEventDao {

        var lastLowCutoff:    Long? = null
        var lastMediumCutoff: Long? = null
        var lastHighCutoff:   Long? = null

        var lowDeleteCount    = 0
        var mediumDeleteCount = 0
        var highDeleteCount   = 0

        var throwOnLow:    RuntimeException? = null
        var throwOnMedium: RuntimeException? = null
        var throwOnHigh:   RuntimeException? = null

        override suspend fun deleteLowOlderThan(cutoffMs: Long) {
            throwOnLow?.let { throw it }
            lastLowCutoff = cutoffMs
            lowDeleteCount++
        }

        override suspend fun deleteMediumOlderThan(cutoffMs: Long) {
            throwOnMedium?.let { throw it }
            lastMediumCutoff = cutoffMs
            mediumDeleteCount++
        }

        override suspend fun deleteHighOlderThan(cutoffMs: Long) {
            throwOnHigh?.let { throw it }
            lastHighCutoff = cutoffMs
            highDeleteCount++
        }

        // Unused in these tests — minimal stubs
        override suspend fun insert(event: RiskEvent): Long = 0L
        override fun getRecentEvents(): Flow<List<RiskEvent>> = flowOf(emptyList())
        override suspend fun getById(id: Long): RiskEvent? = null
        override suspend fun getEventsForContact(hash: String): List<RiskEvent> = emptyList()
        override suspend fun markAlertSent(id: Long) {}
        override suspend fun updateGemmaAnalysis(id: Long, analysis: String) {}
        override suspend fun getUnsentEventsForContact(hash: String): List<RiskEvent> = emptyList()
        override suspend fun markAllUnsentAlertsSent(hash: String) {}
        override suspend fun markFalsePositive(id: Long) {}
    }
}
