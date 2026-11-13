package com.weartak.plugin.ditto.lifecycle

import android.content.Context
import android.content.SharedPreferences
import com.weartak.plugin.ditto.document.DittoPointIngress
import com.weartak.plugin.ditto.document.DittoPointLedger
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DittoPublicationRetryTest {
    private val now = 1_700_000_000_000L

    private fun preferences(): SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("synthetic-retry-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    private fun pending(preferences: SharedPreferences): DittoPointLedger {
        val ledger = DittoPointLedger(preferences)
        ledger.created("synthetic-point", "alice")
        val document = mapOf(
            "_id" to "synthetic-point", "_v" to 2, "_r" to false, "a" to "synthetic-peer", "d" to "alice",
            "b" to now, "n" to now, "o" to now + 60_000L, "w" to "a-h-G",
            "g" to "2.0", "p" to "h-g-i-g-o", "q" to "Undefined",
            "j" to 38.0, "l" to -77.0, "i" to 100.0, "h" to 5.0, "k" to 7.0,
            "r" to "<detail><contact callsign='SYNTHETIC'/></detail>",
        )
        assertThrows(IOException::class.java) {
            ledger.publish(document) { throw IOException("synthetic write failure") }
        }
        return ledger
    }

    @Test
    fun `empty ledger schedules no retry timer`() = runTest {
        val ledger = DittoPointLedger(preferences())
        var calls = 0
        val retry = DittoPublicationRetry(this, ledger, MutableStateFlow(true), { calls++ }) { throw it }
        try {
            runCurrent()
            advanceUntilIdle()
            assertEquals(0L, testScheduler.currentTime)
            assertEquals(0, calls)
        } finally {
            retry.close()
        }
    }

    @Test
    fun `pending publication waits for explicit permission and retry delay`() = runTest {
        val ledger = pending(preferences())
        val allowed = MutableStateFlow(false)
        val writes = mutableListOf<Map<String, Any?>>()
        val retry = DittoPublicationRetry(this, ledger, allowed, writes::add) { throw it }
        try {
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(writes.isEmpty())
            allowed.value = true
            runCurrent()
            advanceTimeBy(9_999)
            runCurrent()
            assertTrue(writes.isEmpty())
            allowed.value = false
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(writes.isEmpty())
            allowed.value = true
            runCurrent()
            advanceTimeBy(9_999)
            runCurrent()
            assertTrue(writes.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, writes.size)
            assertEquals(false, writes.single()["_r"])
            assertFalse(ledger.hasPending())
            advanceUntilIdle()
            assertEquals(1, writes.size)
        } finally {
            retry.close()
        }
    }

    @Test
    fun `failed publications back off to five minute cap`() = runTest {
        val ledger = pending(preferences())
        val attempts = mutableListOf<Long>()
        val errors = mutableListOf<Exception>()
        val retry = DittoPublicationRetry(this, ledger, MutableStateFlow(true), {
            attempts += testScheduler.currentTime
            throw IOException("synthetic write failure")
        }, errors::add)
        try {
            runCurrent()
            advanceTimeBy(1_000_001)
            runCurrent()
            assertEquals(listOf(10_000L, 30_000L, 70_000L, 150_000L, 310_000L, 610_000L, 910_000L), attempts)
            assertEquals(attempts.size, errors.size)
            assertTrue(ledger.hasPending())
        } finally {
            retry.close()
        }
    }

    @Test
    fun `failure backs off and deletion interrupts delay without losing tombstone`() = runTest {
        val prefs = preferences()
        val ledger = pending(prefs)
        val allowed = MutableStateFlow(true)
        val attempts = mutableListOf<Pair<Long, Boolean>>()
        val errors = mutableListOf<Exception>()
        val retry = DittoPublicationRetry(this, ledger, allowed, {
            attempts += testScheduler.currentTime to (it["_r"] as Boolean)
            throw IOException("synthetic write failure")
        }, errors::add)
        try {
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(listOf(10_000L to false, 30_000L to false), attempts)
            ledger.delete("synthetic-point", "alice", now + 1)
            runCurrent()
            advanceTimeBy(9_999)
            runCurrent()
            assertEquals(2, attempts.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(40_000L to true, attempts.last())
            assertEquals(3, errors.size)
            assertTrue(ledger.hasPending())
        } finally {
            retry.close()
            runCurrent()
        }
        val restarted = DittoPointLedger(prefs)
        val writes = mutableListOf<Map<String, Any?>>()
        restarted.retry(writes::add) { throw it }
        assertTrue(DittoPointIngress(preferences()).decode(writes.single()).deleted)
        assertFalse(restarted.hasPending())
    }

    @Test
    fun `close cancels pending timer without acknowledging publication`() = runTest {
        val prefs = preferences()
        val ledger = pending(prefs)
        val allowed = MutableStateFlow(true)
        var calls = 0
        val retry = DittoPublicationRetry(this, ledger, allowed, { calls++ }) { throw it }
        runCurrent()
        advanceTimeBy(9_999)
        retry.close()
        runCurrent()
        advanceUntilIdle()
        assertTrue(retry.job.isCancelled)
        assertEquals(0, calls)
        assertTrue(DittoPointLedger(prefs).hasPending())
    }
}
