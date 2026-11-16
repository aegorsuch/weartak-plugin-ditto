// SPDX-License-Identifier: GPL-3.0-only
package com.weartak.plugin.ditto.lifecycle

import android.content.Context
import live.ditto.Ditto
import live.ditto.DittoDependencies
import live.ditto.DittoIdentity
import live.ditto.android.DefaultAndroidDittoDependencies
import java.math.BigInteger

internal class DittoSharedKeyProvisioning(
    private val appId: String,
    private val sharedKey: String,
    private val siteId: BigInteger?,
) {
    init {
        require(appId.isNotBlank())
        require(sharedKey.isNotBlank())
    }

    internal fun createIdentity(dependencies: DittoDependencies): DittoIdentity =
        siteId?.let { DittoIdentity.SharedKey(dependencies, appId, sharedKey, it) }
            ?: DittoIdentity.SharedKey(dependencies, appId, sharedKey)

    override fun toString(): String = "DittoSharedKeyProvisioning([REDACTED])"
}

internal class DittoSdkRuntimeFactory(
    context: Context,
    private val provisioning: DittoSharedKeyProvisioning,
) : DittoRuntimeInstanceFactory {
    private val applicationContext = context.applicationContext ?: context

    @Suppress("DEPRECATION")
    override fun create(): DittoRuntimeHandle {
        val dependencies = DefaultAndroidDittoDependencies(applicationContext)
        val identity = provisioning.createIdentity(dependencies)
        return DittoSdkRuntimeHandle(Ditto(dependencies, identity))
    }
}
