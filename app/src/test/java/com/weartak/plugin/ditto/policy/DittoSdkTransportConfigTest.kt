// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoTransportPolicyTest.kt at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.policy

import live.ditto.transports.DittoTransportConfig
import org.junit.Assert.*
import org.junit.Test

class DittoSdkTransportConfigTest {
    @Test
    fun `SDK config follows network changes without altering BLE and other settings`() {
        val config = DittoTransportConfig::class.java.getConstructor().newInstance()
        config.peerToPeer.bluetoothLe.enabled = true
        config.connect.retryIntervalMs = 12_345
        config.connect.tcpServers = mutableSetOf("example.invalid:1234")
        val endpoints = mutableSetOf("wss://example.invalid/sync")

        DittoSdkTransportConfig.apply(config, false, true, false, endpoints)
        assertFalse(config.peerToPeer.wifiAware.enabled)
        assertTrue(config.peerToPeer.lan.enabled)
        assertTrue(config.connect.websocketUrls.isEmpty())
        assertTrue(config.peerToPeer.bluetoothLe.enabled)

        DittoSdkTransportConfig.apply(config, true, false, true, endpoints)
        assertTrue(config.peerToPeer.wifiAware.enabled)
        assertFalse(config.peerToPeer.lan.enabled)
        assertEquals(setOf("wss://example.invalid/sync"), config.connect.websocketUrls)
        assertTrue(config.peerToPeer.bluetoothLe.enabled)
        assertEquals(12_345L, config.connect.retryIntervalMs)
        assertEquals(setOf("example.invalid:1234"), config.connect.tcpServers)
        endpoints.clear()
        assertEquals(setOf("wss://example.invalid/sync"), config.connect.websocketUrls)

        config.peerToPeer.bluetoothLe.enabled = false
        DittoSdkTransportConfig.apply(config, true, true, true, endpoints)
        assertFalse(config.peerToPeer.bluetoothLe.enabled)
        assertTrue(config.connect.websocketUrls.isEmpty())
    }
}
