package com.weartak.plugin.ditto.tracker

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.weartak.plugin.ditto.TrackerDittoDiscovery
import com.weartak.plugin.ditto.TrackerHostTrust
import com.weartak.plugin.sdk.ditto.v1.DittoContractV1

class TrackerDittoServiceV1 : Service() {
    private val discovery by lazy { TrackerDittoDiscovery(TrackerHostTrust(this)) }

    override fun onBind(intent: Intent?): IBinder? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            intent?.action != DittoContractV1.TRACKER_ACTION ||
            intent.component != ComponentName(DittoContractV1.PLUGIN_PACKAGE, DittoContractV1.TRACKER_SERVICE)
        ) return null

        return discovery
    }
}
