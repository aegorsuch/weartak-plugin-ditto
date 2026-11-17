package com.weartak.plugin.ditto.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DittoRuntimeOwnerTest {
    @Test
    fun `production owner blocks start and releases no absent runtime`() {
        val owner = DittoRuntimeOwner()

        assertEquals(StartSyncResult.BLOCKED_MISSING_PROVISIONING_AND_HANDOFF, owner.requestStart())
        assertEquals(DittoRuntimeReleaseResult.NoRuntime, owner.releaseLocalResources())
        assertEquals(DittoRuntimeReleaseResult.AlreadyRequested, owner.releaseLocalResources())
    }

    @Test
    fun `closed production gate does not create identity or runtime`() {
        var createCalls = 0
        val owner = DittoRuntimeOwner(
            runtimeFactory = DittoRuntimeInstanceFactory {
                createCalls++
                SyntheticRuntime()
            },
        )

        assertEquals(StartSyncResult.BLOCKED_MISSING_PROVISIONING_AND_HANDOFF, owner.requestStart())
        assertEquals(0, createCalls)
        assertEquals(DittoRuntimeReleaseResult.NoRuntime, owner.releaseLocalResources())
        assertEquals(0, createCalls)
    }

    @Test
    fun `open test gate creates and starts exactly one runtime before independent cleanup`() {
        var createCalls = 0
        val runtime = SyntheticRuntime()
        val owner = DittoRuntimeOwner(
            runtimeFactory = DittoRuntimeInstanceFactory {
                createCalls++
                runtime
            },
            activationGate = DittoRuntimeActivationGate { true },
        )

        assertEquals(StartSyncResult.CALL_RETURNED_WITHOUT_ERROR, owner.requestStart())
        assertEquals(StartSyncResult.CALL_RETURNED_WITHOUT_ERROR, owner.requestStart())
        assertEquals(1, createCalls)
        assertEquals(1, runtime.startCalls)
        assertEquals(0, runtime.stopCalls)
        assertEquals(0, runtime.closeCalls)

        val result = owner.releaseLocalResources() as DittoRuntimeReleaseResult.CallsCompleted
        assertEquals(StopSyncResult.CallReturnedWithoutError, result.stopSync)
        assertEquals(DittoCloseResult.CallReturnedWithoutError, result.close)
        assertEquals(1, runtime.stopCalls)
        assertEquals(1, runtime.closeCalls)
        assertEquals(StartSyncResult.BLOCKED_RUNTIME_RELEASED, owner.requestStart())
    }

    @Test
    fun `runtime cleanup reports synthetic failures and never repeats calls`() {
        val fakeType = Class.forName("live.ditto.Ditto")
        val fake = fakeType.getConstructor().newInstance()
        fakeType.getField("failStop").setBoolean(fake, true)
        fakeType.getField("failClose").setBoolean(fake, true)
        val bridgeType = Class.forName(
            "com.weartak.plugin.ditto.lifecycle.DittoSdkRuntimeHandle",
        )
        val bridge = bridgeType.getDeclaredConstructor(fakeType).newInstance(fake) as DittoRuntimeHandle
        val owner = DittoRuntimeOwner(initialRuntime = bridge)

        assertEquals(StartSyncResult.BLOCKED_MISSING_PROVISIONING_AND_HANDOFF, owner.requestStart())
        val result = owner.releaseLocalResources() as DittoRuntimeReleaseResult.CallsCompleted

        assertTrue(result.stopSync is StopSyncResult.CallFailed)
        assertTrue(result.close is DittoCloseResult.CallFailed)
        assertEquals(1, fakeType.getField("stopCalls").getInt(fake))
        assertEquals(1, fakeType.getField("closeCalls").getInt(fake))
        assertSame(result, owner.releaseLocalResources())
        assertEquals(1, fakeType.getField("stopCalls").getInt(fake))
        assertEquals(1, fakeType.getField("closeCalls").getInt(fake))
    }

    private class SyntheticRuntime : DittoRuntimeHandle {
        var startCalls = 0
        var stopCalls = 0
        var closeCalls = 0

        override fun startSync() {
            startCalls++
        }

        override fun stopSync() {
            stopCalls++
        }

        override fun close() {
            closeCalls++
        }
    }
}
