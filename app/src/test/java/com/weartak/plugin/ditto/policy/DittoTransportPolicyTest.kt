// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoTransportPolicyTest.kt at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class DittoTransportPolicyTest {
    @Test
    fun `local wifi and validated internet independently gate core transports`() {
        val cases = listOf(
            DittoTransportInputs(false, false, false, true) to
                DittoTransportSelection(false, false, false),
            DittoTransportInputs(true, true, false, true) to
                DittoTransportSelection(true, true, false),
            DittoTransportInputs(true, false, true, true) to
                DittoTransportSelection(true, false, true),
            DittoTransportInputs(true, true, true, false) to
                DittoTransportSelection(true, true, false),
            DittoTransportInputs(false, true, true, true) to
                DittoTransportSelection(false, true, true),
        )
        cases.forEach { (inputs, expected) ->
            assertEquals(expected, DittoTransportPolicy.select(inputs))
        }
    }

    @Test
    fun `connectivity recovery does not erase configured endpoint availability`() {
        val connected = DittoTransportInputs(true, true, true, true)
        val offline = connected.copy(wifiAvailable = false, validatedInternetAvailable = false)
        assertEquals(DittoTransportSelection(true, false, false), DittoTransportPolicy.select(offline))
        assertEquals(DittoTransportSelection(true, true, true), DittoTransportPolicy.select(connected))
    }
}
