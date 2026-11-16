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
    fun `runtime cleanup reports synthetic failures and never repeats calls`() {
        val fakeType = Class.forName("live.ditto.Ditto")
        val fake = fakeType.getConstructor().newInstance()
        fakeType.getField("failStop").setBoolean(fake, true)
        fakeType.getField("failClose").setBoolean(fake, true)
        val bridgeType = Class.forName(
            "com.weartak.plugin.ditto.lifecycle.DittoSdkRuntimeHandle",
        )
        val bridge = bridgeType.getDeclaredConstructor(fakeType).newInstance(fake) as DittoRuntimeHandle
        val owner = DittoRuntimeOwner(bridge)

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
}
