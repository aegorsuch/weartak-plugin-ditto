package com.weartak.plugin.ditto

import android.os.Binder
import com.weartak.plugin.sdk.ditto.v1.DittoContractV1
import com.weartak.plugin.sdk.ditto.v1.DittoStatusV1
import com.weartak.plugin.sdk.ditto.v1.IDittoDiscoveryV1
import com.weartak.plugin.sdk.ditto.v1.IDittoOwnershipV1

internal class TrackerDittoDiscovery(private val trust: TrackerHostTrust) : IDittoDiscoveryV1.Stub() {
    override fun getStatus(): DittoStatusV1 {
        requireTracker()
        return DittoStatusV1().apply {
            protocolVersion = DittoContractV1.VERSION
            availability = DittoContractV1.AVAILABILITY_UNAVAILABLE
        }
    }

    override fun getOwnership(): IDittoOwnershipV1? {
        requireTracker()
        return null
    }

    private fun requireTracker() {
        if (!trust.matches(Binder.getCallingUid())) {
            throw SecurityException("Tracker caller not authorized")
        }
    }
}
