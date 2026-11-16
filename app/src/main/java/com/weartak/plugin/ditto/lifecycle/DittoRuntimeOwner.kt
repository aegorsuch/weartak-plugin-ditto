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

internal fun interface DittoRuntimeInstanceFactory {
    fun create(): DittoRuntimeHandle
}

internal fun interface DittoRuntimeActivationGate {
    fun permitsStart(): Boolean
}

internal class DittoRuntimeOwner(
    private val runtimeFactory: DittoRuntimeInstanceFactory? = null,
    private val activationGate: DittoRuntimeActivationGate = DittoRuntimeActivationGate { false },
    initialRuntime: DittoRuntimeHandle? = null,
) {
    private var runtime = initialRuntime
    private var lifecycle = initialRuntime?.let(::lifecycleFor)
    private var startResult: StartSyncResult? = null
    private var releaseRequested = false
    private var releaseResult: DittoRuntimeReleaseResult? = null

    @Synchronized
    fun requestStart(): StartSyncResult {
        if (releaseRequested) return StartSyncResult.BLOCKED_RUNTIME_RELEASED
        startResult?.let { return it }
        if (!activationGate.permitsStart()) {
            return StartSyncResult.BLOCKED_MISSING_PROVISIONING_AND_HANDOFF
        }

        val activeRuntime = runtime ?: try {
            val factory = runtimeFactory
                ?: return StartSyncResult.BLOCKED_RUNTIME_FACTORY_UNAVAILABLE
            factory.create().also {
                runtime = it
                lifecycle = lifecycleFor(it)
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            return StartSyncResult.InstanceCreationFailed(error).also { startResult = it }
        }
        return requireNotNull(lifecycle).requestStart().also { startResult = it }
    }

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

    private fun lifecycleFor(runtime: DittoRuntimeHandle) =
        DittoLifecycleAdapter(runtime, runtime, activationGate::permitsStart)
}
