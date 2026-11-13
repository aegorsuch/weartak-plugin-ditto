// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoChatRetention.kt
// at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.document

/** Pure calculations only; no subscriptions, persistence, or eviction are performed. */
internal object DittoChatRetentionPolicy {
    private const val MAX_TIMESTAMP = 253402300799999L

    fun initialDelayMillis(previousCutoff: Long, now: Long): Long {
        require(previousCutoff in 0..MAX_TIMESTAMP && now in 1..MAX_TIMESTAMP) {
            "Invalid chat retention time"
        }
        val age = if (previousCutoff > 0 && now >= previousCutoff) now - previousCutoff
            else DittoChatDocument.TTL_MILLIS
        return (DittoChatDocument.TTL_MILLIS - age).coerceAtLeast(0)
    }

    fun expiredQuery(cutoff: Long): String {
        requireCutoff(cutoff)
        return "o <= $cutoff || b <= ${cutoff - DittoChatDocument.TTL_MILLIS}"
    }

    fun subscriptionQuery(cutoff: Long): String {
        requireCutoff(cutoff)
        return "SELECT * FROM chat WHERE (o IS MISSING OR o IS NULL OR o > $cutoff) " +
            "AND b > ${cutoff - DittoChatDocument.TTL_MILLIS}"
    }

    private fun requireCutoff(cutoff: Long) {
        require(cutoff in 1..MAX_TIMESTAMP) { "Invalid chat retention time" }
    }
}
