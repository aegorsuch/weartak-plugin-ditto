package com.weartak.plugin.ditto.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DittoLifecycleAdapterTest {
    @Test
    fun `start remains disabled and never invokes the SDK operation`() {
        var calls = 0
        val adapter = DittoLifecycleAdapter(stopCall = SyncStopCall { calls++ })

        assertEquals(StartSyncResult.BLOCKED_MISSING_PROVISIONING_AND_HANDOFF, adapter.requestStart())
        assertEquals(0, calls)
    }

    @Test
    fun `stop failure is surfaced and repeated requests cannot restart stop`() {
        val failure = IllegalStateException("synthetic stop failure")
        var calls = 0
        val adapter = DittoLifecycleAdapter(stopCall = SyncStopCall {
            calls++
            if (calls == 1) throw failure
        })

        val result = adapter.requestStop()
        assertSame(failure, (result as StopSyncResult.CallFailed).cause)
        assertEquals("CallFailed", result.toString())
        assertSame(result, adapter.requestStop())
        assertEquals(1, calls)
        assertEquals(StartSyncResult.BLOCKED_MISSING_PROVISIONING_AND_HANDOFF, adapter.requestStart())
    }

    @Test
    fun `start runs once only after gate opens`() {
        val runtime = SyntheticRuntime()
        val adapter = DittoLifecycleAdapter(
            stopCall = runtime,
            startCall = runtime,
            startAllowed = { true },
        )

        assertEquals(StartSyncResult.CALL_RETURNED_WITHOUT_ERROR, adapter.requestStart())
        assertEquals(StartSyncResult.CALL_RETURNED_WITHOUT_ERROR, adapter.requestStart())
        assertEquals(1, runtime.startCalls)
    }

    @Test
    fun `start failure is surfaced and not retried`() {
        val failure = IllegalStateException("synthetic start failure")
        val runtime = SyntheticRuntime(failStart = failure)
        val adapter = DittoLifecycleAdapter(
            stopCall = runtime,
            startCall = runtime,
            startAllowed = { true },
        )

        val result = adapter.requestStart() as StartSyncResult.CallFailed
        assertSame(failure, result.cause)
        assertEquals("CallFailed", result.toString())
        assertSame(result, adapter.requestStart())
        assertEquals(1, runtime.startCalls)
    }

    @Test
    fun `stop is marked before a reentrant request`() {
        lateinit var adapter: DittoLifecycleAdapter
        var nested: StopSyncResult? = null
        var calls = 0
        adapter = DittoLifecycleAdapter(stopCall = SyncStopCall {
            calls++
            nested = adapter.requestStop()
        })

        assertEquals(StopSyncResult.CallReturnedWithoutError, adapter.requestStop())
        assertEquals(StopSyncResult.AlreadyRequested, nested)
        assertEquals(1, calls)
    }

    @Test
    fun `fatal stop failure propagates and remains unacknowledged`() {
        var calls = 0
        val adapter = DittoLifecycleAdapter(stopCall = SyncStopCall {
            calls++
            throw AssertionError("synthetic fatal error")
        })

        assertThrows(AssertionError::class.java) { adapter.requestStop() }
        assertEquals(StopSyncResult.AlreadyRequested, adapter.requestStop())
        assertEquals(1, calls)
    }

    @Test
    fun `concurrent stop requests call SDK at most once`() {
        val entered = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        val adapter = DittoLifecycleAdapter(stopCall = SyncStopCall {
            calls++
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<StopSyncResult> { adapter.requestStop() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<StopSyncResult> {
                secondStarted.countDown()
                adapter.requestStop()
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(StopSyncResult.CallReturnedWithoutError, first.get(5, TimeUnit.SECONDS))
            assertEquals(StopSyncResult.CallReturnedWithoutError, second.get(5, TimeUnit.SECONDS))
            assertEquals(1, calls)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `SDK runtime bridge delegates stop and close without enabling start`() {
        val fakeType = Class.forName("live.ditto.Ditto")
        val fake = fakeType.getConstructor().newInstance()
        val bridgeType = Class.forName(
            "com.weartak.plugin.ditto.lifecycle.DittoSdkRuntimeHandle",
        )
        val bridge = bridgeType.getDeclaredConstructor(fakeType).newInstance(fake) as DittoRuntimeHandle
        val owner = DittoRuntimeOwner(initialRuntime = bridge)

        assertEquals(StartSyncResult.BLOCKED_MISSING_PROVISIONING_AND_HANDOFF, owner.requestStart())
        assertEquals(0, fakeType.getField("stopCalls").getInt(fake))
        assertEquals(0, fakeType.getField("closeCalls").getInt(fake))

        val result = owner.releaseLocalResources() as DittoRuntimeReleaseResult.CallsCompleted
        assertEquals(StopSyncResult.CallReturnedWithoutError, result.stopSync)
        assertEquals(DittoCloseResult.CallReturnedWithoutError, result.close)
        assertEquals(1, fakeType.getField("stopCalls").getInt(fake))
        assertEquals(1, fakeType.getField("closeCalls").getInt(fake))
        assertSame(result, owner.releaseLocalResources())
    }

    @Test
    fun `stopSync leaves local store open until close`() {
        val fakeType = Class.forName("live.ditto.Ditto")
        val fake = fakeType.getConstructor().newInstance()
        val bridgeType = Class.forName(
            "com.weartak.plugin.ditto.lifecycle.DittoSdkRuntimeHandle",
        )
        val bridge = bridgeType.getDeclaredConstructor(fakeType).newInstance(fake) as DittoRuntimeHandle
        val adapter = DittoLifecycleAdapter(bridge)

        assertEquals(StopSyncResult.CallReturnedWithoutError, adapter.requestStop())
        assertEquals(1, fakeType.getField("stopCalls").getInt(fake))
        assertEquals(0, fakeType.getField("closeCalls").getInt(fake))
        assertEquals("synthetic", fakeType.getMethod("readLocalStore").invoke(fake))

        bridge.close()
        assertEquals(1, fakeType.getField("closeCalls").getInt(fake))
        val closedRead = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            fakeType.getMethod("readLocalStore").invoke(fake)
        }
        assertTrue(closedRead.cause is IllegalStateException)
    }

    private class SyntheticRuntime(
        private val failStart: Exception? = null,
    ) : DittoRuntimeHandle {
        var startCalls = 0
        override fun startSync() {
            startCalls++
            failStart?.let { throw it }
        }
        override fun stopSync() = Unit
        override fun close() = Unit
    }
}
