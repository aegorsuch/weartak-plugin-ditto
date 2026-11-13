// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.ditto.lifecycle

import live.ditto.Ditto

internal class DittoSdkStopCall(private val ditto: Ditto) : SyncStopCall {
    override fun stopSync() {
        ditto.stopSync()
    }
}
