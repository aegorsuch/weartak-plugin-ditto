// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoNetworkMonitor.kt at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.policy

import live.ditto.transports.DittoTransportConfig

internal object DittoSdkTransportConfig {
    fun apply(
        config: DittoTransportConfig,
        wifiAwareSupported: Boolean,
        wifiAvailable: Boolean,
        validatedInternetAvailable: Boolean,
        configuredWebsocketUrls: Set<String>,
    ) {
        val urls = configuredWebsocketUrls.toMutableSet()
        val selection = DittoTransportPolicy.select(
            DittoTransportInputs(
                wifiAwareSupported = wifiAwareSupported,
                wifiAvailable = wifiAvailable,
                validatedInternetAvailable = validatedInternetAvailable,
                websocketConfigured = urls.isNotEmpty(),
            ),
        )
        config.peerToPeer.wifiAware.enabled = selection.wifiAwareEnabled
        config.peerToPeer.lan.enabled = selection.lanEnabled
        config.connect.websocketUrls = if (selection.websocketEnabled) urls else mutableSetOf()
    }
}
