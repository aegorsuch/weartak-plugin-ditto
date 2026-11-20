package com.weartak.plugin.ditto

import android.content.Context
import android.os.Build
import com.weartak.plugin.sdk.ditto.v1.DittoContractV1

internal class TrackerHostTrust(
    context: Context,
    expectedSigner: ByteArray = context.getString(R.string.tracker_release_signer_sha256)
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
) {
    private val pinnedHost = CommunityHostTrust(context.packageManager, DittoContractV1.TRACKER_PACKAGE, expectedSigner)

    fun matches(callingUid: Int): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            pinnedHost.matches(callingUid, DittoContractV1.TRACKER_PACKAGE)
}
