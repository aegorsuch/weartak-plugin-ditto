// SPDX-License-Identifier: GPL-3.0-only
package com.weartak.plugin.ditto.lifecycle

internal sealed interface DittoCloseResult {
    data object CallReturnedWithoutError : DittoCloseResult
    class CallFailed(val cause: Exception) : DittoCloseResult {
        override fun toString(): String = "CallFailed"
    }
}

internal sealed interface DittoRuntimeReleaseResult {
    data object NoRuntime : DittoRuntimeReleaseResult
    data object AlreadyRequested : DittoRuntimeReleaseResult
    data class CallsCompleted(
        val stopSync: StopSyncResult,
        val close: DittoCloseResult,
    ) : DittoRuntimeReleaseResult
}

internal class DittoRuntimeOwner(
    private val runtime: DittoRuntimeHandle? = null,
) {
    private val lifecycle = runtime?.let(::DittoLifecycleAdapter)
    private var releaseRequested = false
    private var releaseResult: DittoRuntimeReleaseResult? = null

    fun requestStart(): StartSyncResult =
        StartSyncResult.BLOCKED_MISSING_PROVISIONING_AND_HANDOFF

    @Synchronized
    fun releaseLocalResources(): DittoRuntimeReleaseResult {
        if (releaseRequested) return releaseResult ?: DittoRuntimeReleaseResult.AlreadyRequested
        releaseRequested = true
        val activeRuntime = runtime ?: return DittoRuntimeReleaseResult.NoRuntime

        val stopResult = requireNotNull(lifecycle).requestStop()
        val closeResult = try {
            activeRuntime.close()
            DittoCloseResult.CallReturnedWithoutError
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            DittoCloseResult.CallFailed(error)
        }
        return DittoRuntimeReleaseResult.CallsCompleted(stopResult, closeResult).also {
            releaseResult = it
        }
    }
}
