// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoCotDocument.kt at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.document

import java.security.MessageDigest
import java.time.Instant

internal data class DittoCotDocument(
    val uid: String,
    val peer: String,
    val author: String,
    val time: Long,
    val stale: Long,
    val type: String,
    val xml: String,
) {
    val sourceId: String get() = "ditto:${digest(peer)}"
    val key: String get() = digest("$peer\u0000$uid")
    val fingerprint: String get() = digest(xml)

    override fun toString(): String = "DittoCotDocument(redacted)"

    companion object {
        const val MAX_PAYLOAD_BYTES = 256 * 1024
        private const val MAX_TIMESTAMP = 253402300799999.0
        private val atomType = Regex("a-[a-z](-[A-Za-z0-9]+)+")
        private val markerTypes = setOf("b-m-p-s-p-i", "b-m-p-s-m", "u-d-c-c", "u-d-e", "u-d-f", "u-d-r", "b-i-v")
        private val additionalTypes = setOf(
            "b-m-p-s-p-i", "b-m-p-s-m", "u-d-c-c", "u-d-e", "u-d-f", "u-d-r", "b-i-v",
            "b-a-g", "b-t-f", "b-m-r", "t-x-d-d",
        )

        fun decode(doc: Map<String, Any?>): DittoCotDocument {
            require((doc["_v"] as? Number)?.toDouble() == 2.0) { "Unsupported Ditto schema version" }
            require(doc["_r"] == false) { "Removed or unspecified Ditto document lifecycle" }
            fun text(key: String, max: Int = 512): String =
                (doc[key] as? String)?.also {
                    require(it.isNotBlank() && it.length <= max && it.none(Char::isISOControl) &&
                        !DittoCotXml.containsSensitiveEnrollmentText(it)) {
                        "Invalid Ditto field $key"
                    }
                } ?: throw IllegalArgumentException("Missing Ditto field $key")
            fun timestamp(key: String): Long {
                val value = (doc[key] as? Number)?.toDouble()
                    ?: throw IllegalArgumentException("Missing Ditto timestamp $key")
                require(value.isFinite() && value > 0 && value <= MAX_TIMESTAMP &&
                    value == value.toLong().toDouble()) { "Invalid Ditto timestamp $key" }
                return value.toLong()
            }
            fun number(key: String): Double =
                (doc[key] as? Number)?.toDouble()?.also {
                    require(it.isFinite()) { "Invalid Ditto coordinate $key" }
                } ?: throw IllegalArgumentException("Missing Ditto coordinate $key")

            val uid = text("_id")
            val sender = text("d")
            val peer = text("a")
            val legacyAuthor = doc["AuthorId"]?.let { text("AuthorId") }
            require(sender == uid || legacyAuthor == null || legacyAuthor == sender) {
                "Ditto document author mismatch"
            }
            val author = legacyAuthor ?: sender
            val time = timestamp("b")
            val start = timestamp("n")
            val stale = timestamp("o")
            require(stale >= time) { "Ditto stale precedes event time" }
            val type = text("w")
            require(atomType.matches(type) || type.startsWith("a-f-G-U-C-") ||
                type.startsWith("a-f-G-E-") || type == "b-a-o" || type.startsWith("b-a-o-") ||
                type in additionalTypes) { "Unsupported Ditto CoT type" }

            val detail = doc["r"] as? String
                ?: throw IllegalArgumentException("Missing Ditto detail")
            require(detail.length <= MAX_PAYLOAD_BYTES &&
                detail.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Oversized Ditto detail" }
            val root = DittoCotXml.parseDetail(detail)
            require(!DittoCotXml.containsSensitiveEnrollment(detail, root)) {
                "Sensitive enrollment commands are not accepted"
            }

            val lat = number("j")
            val lon = number("l")
            require(lat in -90.0..90.0 && lon in -180.0..180.0) { "Invalid Ditto location" }
            val attributes = listOf(
                "version" to DittoCotXml.escape(text("g")),
                "uid" to DittoCotXml.escape(uid), "type" to DittoCotXml.escape(type),
                "time" to Instant.ofEpochMilli(time), "start" to Instant.ofEpochMilli(start),
                "stale" to Instant.ofEpochMilli(stale), "how" to DittoCotXml.escape(text("p")),
                "access" to DittoCotXml.escape(text("q")),
            )
            val point = DittoCotXml.element("point", listOf(
                "lat" to lat, "lon" to lon, "hae" to number("i"),
                "ce" to number("h"), "le" to number("k"),
            ))
            val xml = DittoCotXml.element("event", attributes, point + detail)
            require(xml.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Oversized Ditto CoT" }
            DittoCotXml.requireValidEvent(xml)
            return DittoCotDocument(uid, peer, author, time, stale, type, xml)
        }

        fun isMarkerType(type: String): Boolean {
            val isUserPosition = type == "a-f-G-U-C" || type.startsWith("a-f-G-U-C-") ||
                type == "a-f-G-E" || type.startsWith("a-f-G-E-")
            return (atomType.matches(type) && !isUserPosition) || type in markerTypes
        }

        fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
