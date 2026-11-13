// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoPointIngress.kt at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.document

import android.content.SharedPreferences
import org.json.JSONObject

/** Shared-key writer ownership is an application rule, not a cryptographic signature. */
internal class DittoPointIngress(preferences: SharedPreferences) {
    private val records = PointRecordStore(preferences, MAX_ENTRIES, MAX_BYTES, MAX_BYTES, ::validateRecord)
    internal class Point private constructor(val document: DittoCotDocument, val deleted: Boolean) {
        companion object {
            fun decode(raw: Map<String, Any?>): Point {
                val metadata = raw[DittoPointLedger.METADATA] as? Map<*, *>
                    ?: throw IllegalArgumentException("Missing WearTAK point contract")
                require((metadata["version"] as? Number)?.toDouble() == 1.0) { "Unsupported WearTAK point version" }
                val deleted = metadata["deleted"] as? Boolean
                    ?: throw IllegalArgumentException("Missing WearTAK point lifecycle")
                require(raw["_r"] == deleted) { "Inconsistent WearTAK point lifecycle" }
                val doc = DittoCotDocument.decode(raw + ("_r" to false))
                require(DittoCotDocument.isMarkerType(doc.type)) { "WearTAK deletion only supports points" }
                require(metadata["owner"] == doc.author && doc.author.isNotBlank()) { "WearTAK point owner mismatch" }
                require(metadata["revision"] == doc.fingerprint) { "WearTAK point revision mismatch" }
                if (deleted) {
                    val at = (metadata["deletedAt"] as? Number)?.toDouble()
                    require(at != null && at.isFinite() && at >= doc.time &&
                        at <= MAX_TIMESTAMP && at == at.toLong().toDouble()) { "Invalid point deletion time" }
                }
                return Point(doc, deleted)
            }
        }
    }

    fun decode(raw: Map<String, Any?>): Point = decodePoint(raw)

    @Synchronized
    fun accept(point: Point): Boolean {
        val doc = point.document
        val previous = records.read(doc.key)
        if (previous != null) {
            require(previous.getString("owner") == doc.author) { "Cross-owner Ditto point revision" }
            if (previous.getBoolean("deleted")) {
                return point.deleted && previous.getLong("time") == doc.time &&
                    previous.getString("revision") == doc.fingerprint
            }
            val time = previous.getLong("time")
            if (doc.time < time) return false
            if (doc.time == time && previous.getString("revision") != doc.fingerprint) return false
        }
        val record = JSONObject().put("owner", doc.author).put("time", doc.time)
            .put("revision", doc.fingerprint).put("deleted", point.deleted)
        if (records.read(doc.key)?.toString() == record.toString()) return true
        records.save(doc.key, record)
        return true
    }

    @Synchronized
    fun deleted(doc: DittoCotDocument): Boolean = records.read(doc.key)?.getBoolean("deleted") == true

    companion object {
        const val MAX_ENTRIES = 8192
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_TIMESTAMP = 253402300799999.0
        private val hash = Regex("[0-9a-f]{64}")

        fun decodePoint(raw: Map<String, Any?>): Point = Point.decode(raw)

        private fun validateRecord(key: String, record: JSONObject) {
            val owner = record.get("owner") as? String
            val time = (record.get("time") as? Number)?.toDouble()
            val revision = record.get("revision") as? String
            check(hash.matches(key) && owner != null && owner.isNotBlank() && owner.length <= 512 &&
                owner.none(Char::isISOControl) && !DittoCotXml.containsSensitiveEnrollmentText(owner) &&
                time != null && time.isFinite() &&
                time > 0 && time <= MAX_TIMESTAMP && time == time.toLong().toDouble() &&
                revision != null && hash.matches(revision) && record.get("deleted") is Boolean
            ) { "Invalid persisted point watermark" }
        }
    }
}
