// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoService.kt at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.lifecycle

internal fun interface SyncStopCall {
    fun stopSync()
}

internal enum class StartSyncResult {
    BLOCKED_MISSING_PROVISIONING_AND_HANDOFF,
}

internal sealed interface StopSyncResult {
    data object CallReturnedWithoutError : StopSyncResult
    data object AlreadyRequested : StopSyncResult
    class CallFailed(val cause: Exception) : StopSyncResult {
        override fun toString(): String = "CallFailed"
    }
}

internal class DittoLifecycleAdapter(private val stopCall: SyncStopCall) {
    private var stopRequested = false
    private var stopResult: StopSyncResult? = null

    fun requestStart(): StartSyncResult = StartSyncResult.BLOCKED_MISSING_PROVISIONING_AND_HANDOFF

    // Neither the stop flag nor a successful call establishes quiescence.
    @Synchronized
    fun requestStop(): StopSyncResult {
        if (stopRequested) return stopResult ?: StopSyncResult.AlreadyRequested
        stopRequested = true
        val result = try {
            stopCall.stopSync()
            StopSyncResult.CallReturnedWithoutError
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            StopSyncResult.CallFailed(error)
        }
        stopResult = result
        return result
    }
}
