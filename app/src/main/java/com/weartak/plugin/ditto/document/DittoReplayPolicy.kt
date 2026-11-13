// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoCotReceiver.kt
// at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.document

/** Pure comparison only; the caller must perform all other ingress checks and delivery ordering. */
internal object DittoReplayPolicy {
    private const val MAX_TIMESTAMP = 253402300799999L
    private val hash = Regex("[0-9a-f]{64}")

    data class Watermark(val time: Long, val retainUntil: Long, val fingerprint: String) {
        override fun toString(): String = "Watermark(redacted)"
    }

    fun parseWatermark(raw: String): Watermark {
        require(raw.length <= 128) { "Invalid replay watermark" }
        val parts = raw.split(':', limit = 3)
        require(parts.size == 3) { "Invalid replay watermark" }
        val time = parts[0].toLongOrNull() ?: throw IllegalArgumentException("Invalid replay watermark")
        val retainUntil = parts[1].toLongOrNull() ?: throw IllegalArgumentException("Invalid replay watermark")
        return Watermark(time, retainUntil, parts[2]).also(::validate)
    }

    fun shouldIngest(
        time: Long,
        fingerprint: String,
        sensitive: Boolean,
        chat: Boolean,
        expired: Boolean,
        previous: Watermark?,
        seenExpired: Boolean?,
    ): Boolean {
        require(time in 1..MAX_TIMESTAMP &&
            (if (sensitive) fingerprint.isEmpty() else hash.matches(fingerprint)) &&
            (previous != null || seenExpired == null)) { "Invalid replay candidate" }
        previous?.let(::validate)
        if (chat && expired) return false
        previous ?: return true
        if (time < previous.time) return false
        return time != previous.time || !(sensitive || previous.fingerprint != fingerprint ||
            seenExpired == expired || chat)
    }

    private fun validate(watermark: Watermark) {
        require(watermark.time in 1..MAX_TIMESTAMP &&
            watermark.retainUntil in watermark.time..MAX_TIMESTAMP &&
            (watermark.fingerprint.isEmpty() || hash.matches(watermark.fingerprint))) {
            "Invalid replay watermark"
        }
    }
}
