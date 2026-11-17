// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoService.kt at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.lifecycle

internal fun interface SyncStopCall {
    fun stopSync()
}

internal fun interface SyncStartCall {
    fun startSync()
}

internal sealed interface StartSyncResult {
    data object BLOCKED_MISSING_PROVISIONING_AND_HANDOFF : StartSyncResult
    data object BLOCKED_RUNTIME_RELEASED : StartSyncResult
    data object BLOCKED_RUNTIME_FACTORY_UNAVAILABLE : StartSyncResult
    data object ALREADY_REQUESTED : StartSyncResult
    data object CALL_RETURNED_WITHOUT_ERROR : StartSyncResult
    class InstanceCreationFailed(val cause: Exception) : StartSyncResult {
        override fun toString(): String = "InstanceCreationFailed"
    }

    class CallFailed(val cause: Exception) : StartSyncResult {
        override fun toString(): String = "CallFailed"
    }
}

internal sealed interface StopSyncResult {
    data object CallReturnedWithoutError : StopSyncResult
    data object AlreadyRequested : StopSyncResult
    class CallFailed(val cause: Exception) : StopSyncResult {
        override fun toString(): String = "CallFailed"
    }
}

internal class DittoLifecycleAdapter(
    private val stopCall: SyncStopCall,
    private val startCall: SyncStartCall? = null,
    private val startAllowed: () -> Boolean = { false },
) {
    private var startRequested = false
    private var startResult: StartSyncResult? = null
    private var stopRequested = false
    private var stopResult: StopSyncResult? = null

    @Synchronized
    fun requestStart(): StartSyncResult {
        if (!startAllowed()) return StartSyncResult.BLOCKED_MISSING_PROVISIONING_AND_HANDOFF
        if (stopRequested) return StartSyncResult.BLOCKED_RUNTIME_RELEASED
        if (startRequested) return startResult ?: StartSyncResult.ALREADY_REQUESTED
        val call = startCall ?: return StartSyncResult.BLOCKED_RUNTIME_FACTORY_UNAVAILABLE

        startRequested = true
        val result = try {
            call.startSync()
            StartSyncResult.CALL_RETURNED_WITHOUT_ERROR
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            StartSyncResult.CallFailed(error)
        }
        startResult = result
        return result
    }

    // Vendor stopSync stops network transports; it does not close the local store or establish quiescence.
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
