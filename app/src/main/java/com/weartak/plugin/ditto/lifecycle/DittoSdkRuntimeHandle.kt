// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.ditto.lifecycle

import live.ditto.Ditto

internal interface DittoRuntimeHandle : SyncStopCall {
    fun close()
}

internal class DittoSdkRuntimeHandle(private val ditto: Ditto) : DittoRuntimeHandle {
    override fun stopSync() {
        ditto.stopSync()
    }

    override fun close() {
        ditto.close()
    }
}
