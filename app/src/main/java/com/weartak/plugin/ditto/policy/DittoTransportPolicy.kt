// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoNetworkMonitor.kt at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.policy

internal data class DittoTransportInputs(
    val wifiAwareSupported: Boolean,
    val wifiAvailable: Boolean,
    val validatedInternetAvailable: Boolean,
    val websocketConfigured: Boolean,
)

internal data class DittoTransportSelection(
    val wifiAwareEnabled: Boolean,
    val lanEnabled: Boolean,
    val websocketEnabled: Boolean,
)

internal object DittoTransportPolicy {
    fun select(inputs: DittoTransportInputs) = DittoTransportSelection(
        wifiAwareEnabled = inputs.wifiAwareSupported,
        lanEnabled = inputs.wifiAvailable,
        websocketEnabled = inputs.validatedInternetAvailable && inputs.websocketConfigured,
    )
}
