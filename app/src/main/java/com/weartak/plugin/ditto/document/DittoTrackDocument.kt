// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoService.kt and DittoTrackExpiry.kt
// at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.document

internal object DittoTrackDocument {
    /**
     * Inputs must already be privacy-filtered and mutually consistent. The caller owns
     * location freshness, permissions, identity, stale-time policy, and detail construction.
     * detailXml must be the full core-style detail, before adding weartak_location.
     */
    data class Input(
        val deviceUid: String,
        val peerKey: String,
        val callsign: String,
        val timeMillis: Long,
        val staleTimeMillis: Long,
        val locationTimeMillis: Long,
        val latitude: Double,
        val longitude: Double,
        val hae: Double?,
        val ce: Float?,
        val le: Float?,
        val speed: Float?,
        val detailXml: String,
    ) {
        override fun toString(): String = "DittoTrackDocument.Input(redacted)"
    }

    fun encode(input: Input): Map<String, Any?> {
        listOf(input.deviceUid, input.peerKey, input.callsign).forEach {
            require(it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl) &&
                !DittoCotXml.containsSensitiveEnrollmentText(it)) { "Invalid track identity" }
        }
        val maxTime = 253402300799999L
        require(input.timeMillis in 1..maxTime && input.staleTimeMillis in input.timeMillis..maxTime &&
            input.locationTimeMillis in 1..maxTime) { "Invalid track time" }
        require(input.latitude.isFinite() && input.latitude in -90.0..90.0 &&
            input.longitude.isFinite() && input.longitude in -180.0..180.0 &&
            (input.hae == null || input.hae.isFinite()) &&
            (input.speed == null || input.speed.isFinite())) { "Invalid track location" }
        fun error(value: Float?): Double {
            val result = if (value == null || value.isNaN()) 9999999.0f else value
            require(result.isFinite()) { "Invalid track error" }
            return result.toDouble()
        }
        val hae = input.hae ?: 0.0
        val ce = error(input.ce)
        val le = error(input.le)
        val speed = input.speed ?: 0.0f
        require(input.detailXml.length <= DittoCotDocument.MAX_PAYLOAD_BYTES &&
            input.detailXml.toByteArray(Charsets.UTF_8).size <= DittoCotDocument.MAX_PAYLOAD_BYTES &&
            input.detailXml.endsWith("</detail>")) { "Invalid track detail" }
        val root = DittoCotXml.parseDetail(input.detailXml)
        require(root.getElementsByTagName("weartak_location").length == 0 &&
            !DittoCotXml.containsSensitiveEnrollment(input.detailXml, root)) { "Invalid track detail" }
        val detail = if (input.timeMillis - input.locationTimeMillis > 3_000) {
            input.detailXml.removeSuffix("</detail>") + DittoCotXml.element(
                "weartak_location", listOf("timeMillis" to input.locationTimeMillis),
            ) + "</detail>"
        } else input.detailXml
        require(detail.toByteArray(Charsets.UTF_8).size <= DittoCotDocument.MAX_PAYLOAD_BYTES) {
            "Oversized track detail"
        }
        val document = mapOf(
            "_id" to input.deviceUid, "_r" to false, "_v" to 2,
            "authorCs" to input.callsign, "AuthorId" to input.deviceUid,
            "authorType" to "a-f-G-U-C", "visible" to true,
            "a" to input.peerKey, "b" to input.timeMillis,
            "c" to input.callsign, "d" to input.deviceUid, "e" to input.callsign,
            "f" to true, "g" to "2.0",
            "h" to ce, "i" to hae, "j" to input.latitude, "k" to le, "l" to input.longitude,
            "n" to input.timeMillis, "o" to input.staleTimeMillis,
            "p" to "m-g", "q" to "Undefined", "r" to detail,
            "r1" to speed.toString(), "r2" to hae.toString(), "w" to "a-f-G-U-C",
        )
        DittoCotDocument.decode(document)
        return document
    }

    /** Caller must supply its own trusted track and valid current time; this is a shallow copy. */
    fun expire(document: Map<String, Any?>, nowMillis: Long): Map<String, Any?> {
        require(nowMillis in 1..253402300799999L) { "Invalid track expiry time" }
        return document + mapOf("b" to nowMillis, "n" to nowMillis, "o" to nowMillis)
    }
}
