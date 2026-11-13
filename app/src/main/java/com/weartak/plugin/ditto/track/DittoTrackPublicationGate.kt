// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoTrackPublicationGate.kt and StationaryMotionPolicy.kt
// at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.track

import android.location.Location
import kotlin.math.abs

internal class DittoTrackPublicationGate {
    data class Position(val latitude: Double, val longitude: Double, val accuracyMeters: Float?) {
        override fun toString(): String = "Position(redacted)"
    }

    data class Physiology(val heartRateBpm: Int?, val skinTempF: Float?, val exertion: Double?) {
        override fun toString(): String = "Physiology(redacted)"
    }

    data class Sample(
        val position: Position,
        val battery: Int,
        val identity: List<String>,
        val remarks: String,
        val physiology: Physiology?,
        val intervalSeconds: Int,
    ) {
        override fun toString(): String = "Sample(redacted)"
    }

    private var last: Sample? = null
    private var lastElapsed = 0L

    fun reset() {
        last = null
    }

    fun due(sample: Sample, elapsed: Long): Boolean {
        validate(sample, elapsed)
        val previous = last ?: return true
        if (sample.intervalSeconds != previous.intervalSeconds ||
            elapsed - lastElapsed >= sample.intervalSeconds.coerceAtLeast(1) * 1_000L ||
            elapsed < lastElapsed
        ) return true
        val distance = FloatArray(1)
        Location.distanceBetween(previous.position.latitude, previous.position.longitude,
            sample.position.latitude, sample.position.longitude, distance)
        if (moved(sample.position.accuracyMeters?.toDouble(), distance[0].toDouble(),
                previous.position.accuracyMeters?.toDouble())) return true
        return sample.identity != previous.identity ||
            stableRemarks(sample.remarks) != stableRemarks(previous.remarks) ||
            changed(sample.battery.toDouble(), previous.battery.toDouble(), 5.0) ||
            changed(sample.physiology?.heartRateBpm?.toDouble(), previous.physiology?.heartRateBpm?.toDouble(), 5.0) ||
            changed(sample.physiology?.skinTempF?.toDouble(), previous.physiology?.skinTempF?.toDouble(), 0.5) ||
            changed(sample.physiology?.exertion, previous.physiology?.exertion, 0.05)
    }

    /** Call only after the actual track write succeeds; serialize this with due and the write. */
    fun published(sample: Sample, elapsed: Long) {
        validate(sample, elapsed)
        last = sample.copy(identity = sample.identity.toList())
        lastElapsed = elapsed
    }

    private fun validate(sample: Sample, elapsed: Long) {
        require(elapsed >= 0 && sample.position.latitude.isFinite() && sample.position.latitude in -90.0..90.0 &&
            sample.position.longitude.isFinite() && sample.position.longitude in -180.0..180.0) {
            "Invalid track gate input"
        }
    }

    private fun changed(a: Double?, b: Double?, threshold: Double): Boolean =
        if (a == null || b == null) a != b else abs(a - b) + 1e-9 >= threshold

    private fun stableRemarks(remarks: String) = remarks.split("; ").filterNot {
        it.startsWith("HR: ") || it.startsWith("Skin: ") || it.startsWith("Exert: ")
    }

    companion object {
        internal fun moved(accuracy: Double?, distance: Double?, anchorAccuracy: Double?): Boolean {
            fun usable(value: Double?) = value != null && value.isFinite() && value in 0.0..25.0
            return usable(accuracy) && usable(anchorAccuracy) && distance != null && distance.isFinite() &&
                distance > maxOf(15.0, 2 * (requireNotNull(accuracy) + requireNotNull(anchorAccuracy)))
        }
    }
}
