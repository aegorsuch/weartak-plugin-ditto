// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.ditto

import android.content.Context
import live.ditto.transports.DittoSyncPermissions

internal data class MeshPermissionSnapshot(
    val optedIn: Boolean,
    val missing: List<String>,
)

internal fun interface MeshPermissionProbe {
    fun missingPermissions(): List<String>
}

internal class DittoMeshPermissionProbe(context: Context) : MeshPermissionProbe {
    private val appContext = context.applicationContext

    override fun missingPermissions(): List<String> {
        val permissions = DittoSyncPermissions(appContext)
        return permissions.missingPermissions(permissions.requiredPermissions()).toList()
    }
}

internal class MeshPermissionGate(private val probe: MeshPermissionProbe) {
    private var requestInFlight = false
    private var optedIn = false
    val isRequestInFlight: Boolean
        get() = requestInFlight

    fun beginEnable(): List<String>? {
        if (requestInFlight) return null
        val missing = probe.missingPermissions()
        if (missing.isEmpty()) {
            optedIn = true
            return emptyList()
        }
        optedIn = false
        requestInFlight = true
        return missing
    }

    fun completeRequest(): MeshPermissionSnapshot {
        val missing = probe.missingPermissions()
        if (requestInFlight) optedIn = missing.isEmpty()
        requestInFlight = false
        if (missing.isNotEmpty()) optedIn = false
        return MeshPermissionSnapshot(optedIn, missing)
    }

    fun disable() {
        optedIn = false
        requestInFlight = false
    }

    fun refresh(): MeshPermissionSnapshot {
        val missing = probe.missingPermissions()
        if (missing.isNotEmpty()) optedIn = false
        return MeshPermissionSnapshot(optedIn, missing)
    }
}
