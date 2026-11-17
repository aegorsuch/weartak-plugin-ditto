// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.ditto

import org.junit.Assert.*
import org.junit.Test

class MeshPermissionGateTest {
    private class Probe(var missing: List<String>) : MeshPermissionProbe {
        override fun missingPermissions(): List<String> = missing
    }

    @Test
    fun `denied permission leaves opt-in disabled`() {
        val probe = Probe(listOf("android.permission.BLUETOOTH_SCAN"))
        val gate = MeshPermissionGate(probe)

        assertEquals(listOf("android.permission.BLUETOOTH_SCAN"), gate.beginEnable())
        assertNull(gate.beginEnable())
        val result = gate.completeRequest()

        assertFalse(result.optedIn)
        assertEquals(listOf("android.permission.BLUETOOTH_SCAN"), result.missing)
    }

    @Test
    fun `granted permissions enable only permission opt-in`() {
        val probe = Probe(listOf("android.permission.BLUETOOTH_SCAN"))
        val gate = MeshPermissionGate(probe)

        assertEquals(listOf("android.permission.BLUETOOTH_SCAN"), gate.beginEnable())
        probe.missing = emptyList()
        val result = gate.completeRequest()

        assertTrue(result.optedIn)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun `revoked permission clears opt-in on refresh`() {
        val probe = Probe(emptyList())
        val gate = MeshPermissionGate(probe)
        assertTrue(gate.beginEnable()!!.isEmpty())
        assertTrue(gate.refresh().optedIn)

        probe.missing = listOf("android.permission.BLUETOOTH_SCAN")
        val result = gate.refresh()

        assertFalse(result.optedIn)
        assertEquals(listOf("android.permission.BLUETOOTH_SCAN"), result.missing)
    }

    @Test
    fun `callback without active opt-in request never enables permissions gate`() {
        val probe = Probe(listOf("android.permission.BLUETOOTH_SCAN"))
        val gate = MeshPermissionGate(probe)
        gate.beginEnable()
        gate.disable()
        probe.missing = emptyList()

        assertFalse(gate.completeRequest().optedIn)
        assertFalse(gate.isRequestInFlight)
    }
}
