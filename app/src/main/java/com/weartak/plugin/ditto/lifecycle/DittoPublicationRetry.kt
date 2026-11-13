// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoPublicationRetry.kt at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.lifecycle

import com.weartak.plugin.ditto.document.DittoPointLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal class DittoPublicationRetry(
    scope: CoroutineScope,
    ledger: DittoPointLedger,
    allowed: Flow<Boolean>,
    write: (Map<String, Any?>) -> Unit,
    onError: (Exception) -> Unit,
) : AutoCloseable {
    val job: Job = scope.launch {
        combine(ledger.changes, allowed) { _, enabled -> enabled }.collectLatest { enabled ->
            var wait = INITIAL_MS
            while (enabled && ledger.hasPending()) {
                delay(wait)
                ledger.retry(write, onError)
                wait = (wait * 2).coerceAtMost(MAX_MS)
            }
        }
    }

    override fun close() {
        job.cancel()
    }

    companion object {
        const val INITIAL_MS = 10_000L
        const val MAX_MS = 300_000L
    }
}
