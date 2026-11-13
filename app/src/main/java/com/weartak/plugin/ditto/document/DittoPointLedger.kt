// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoPointLedger.kt at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.document

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Persistent local ownership and publication intent; shared-key ownership is not a signature. */
internal class DittoPointLedger(preferences: SharedPreferences) {
    private val records = PointRecordStore(preferences, MAX_ENTRIES, MAX_TOTAL_BYTES, MAX_RECORD_BYTES, ::validateRecord)
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    val generation: Long get() = changes.value

    @Synchronized
    fun hasPending(): Boolean = records.keys().any { records.read(it)!!.optBoolean("pending") }

    @Synchronized
    fun owns(uid: String): Boolean = records.contains(DittoCotDocument.digest(uid))

    @Synchronized
    fun created(uid: String, owner: String) {
        require(uid.isNotBlank() && uid.length <= 512 && uid.none(Char::isISOControl) &&
            !DittoCotXml.containsSensitiveEnrollmentText(uid) &&
            owner.isNotBlank() && owner.length <= 512 && owner.none(Char::isISOControl) &&
            !DittoCotXml.containsSensitiveEnrollmentText(owner)) {
            "Invalid local point identity"
        }
        val key = DittoCotDocument.digest(uid)
        require(!records.contains(key)) { "Ditto point UID already used; create a new UID" }
        records.save(key, JSONObject().put("uid", uid).put("owner", owner).put("deleted", false))
    }

    @Synchronized
    fun publish(document: Map<String, Any?>, write: (Map<String, Any?>) -> Unit): Boolean {
        val decoded = DittoCotDocument.decode(document)
        val key = DittoCotDocument.digest(decoded.uid)
        val record = records.read(key) ?: return false
        require(record.getString("owner") == decoded.author) { "Ditto local point owner mismatch" }
        if (record.getBoolean("deleted")) return false
        require(DittoCotDocument.isMarkerType(decoded.type)) { "Not a supported Ditto point" }
        val previous = record.optJSONObject("document")
        if (previous != null) {
            val prior = DittoCotDocument.decode(toMap(previous))
            require(decoded.peer == prior.peer) { "Ditto point origin changed; create a new UID" }
            if (decoded.time < prior.time) return false
            require(decoded.time != prior.time || decoded.fingerprint == prior.fingerprint) {
                "Conflicting equal-time local point revision"
            }
        }
        val metadata = mapOf(
            "version" to 1, "owner" to decoded.author,
            "revision" to decoded.fingerprint, "deleted" to false,
        )
        val doc = document + (METADATA to metadata)
        record.put("document", JSONObject(doc)).put("pending", true)
        records.save(key, record)
        mutableChanges.value++
        flush(key, record, write)
        return true
    }

    @Synchronized
    fun delete(uid: String, owner: String, now: Long) {
        val key = DittoCotDocument.digest(uid)
        val record = records.read(key) ?: return
        require(record.getString("owner") == owner) { "Cannot delete another local point owner" }
        if (record.getBoolean("deleted")) return
        val document = record.optJSONObject("document")
        if (document != null) {
            val metadata = document.getJSONObject(METADATA)
            metadata.put("deleted", true).put("deletedAt", maxOf(now, document.getLong("b")))
            document.put("_r", true).put("visible", false).put("f", false)
            record.put("pending", true)
        }
        record.put("deleted", true)
        records.save(key, record)
        mutableChanges.value++
    }

    @Synchronized
    fun retry(write: (Map<String, Any?>) -> Unit, onError: (Exception) -> Unit) {
        records.keys().forEach { key ->
            try {
                val record = requireNotNull(records.read(key))
                if (record.optBoolean("pending")) flush(key, record, write)
            } catch (error: Exception) {
                onError(error)
            }
        }
    }

    private fun flush(key: String, record: JSONObject, write: (Map<String, Any?>) -> Unit) {
        write(toMap(record.getJSONObject("document")))
        record.put("pending", false)
        records.save(key, record)
        mutableChanges.value++
    }

    companion object {
        const val METADATA = "weartakPoint"
        const val MAX_ENTRIES = 4096
        const val MAX_RECORD_BYTES = 384 * 1024
        const val MAX_TOTAL_BYTES = 8 * 1024 * 1024

        private fun validateRecord(key: String, record: JSONObject) {
            val uid = record.get("uid") as? String
            val owner = record.get("owner") as? String
            val deleted = record.get("deleted") as? Boolean
            val pending = if (record.has("pending")) record.get("pending") as? Boolean else false
            check(uid != null && uid.isNotBlank() && uid.length <= 512 && uid.none(Char::isISOControl) &&
                !DittoCotXml.containsSensitiveEnrollmentText(uid) && key == DittoCotDocument.digest(uid) &&
                owner != null && owner.isNotBlank() && owner.length <= 512 &&
                owner.none(Char::isISOControl) && !DittoCotXml.containsSensitiveEnrollmentText(owner) &&
                deleted != null && pending != null
            ) { "Invalid persisted local point" }
            if (!record.has("document")) {
                check(!pending) { "Invalid pending point without document" }
                return
            }
            val raw = record.get("document") as? JSONObject
            check(raw != null) { "Invalid persisted local point document" }
            val point = DittoPointIngress.decodePoint(toMap(raw))
            check(point.document.uid == uid && point.document.author == owner && point.deleted == deleted) {
                "Invalid persisted local point ownership"
            }
        }

        private fun toMap(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith {
            when (val value = json.get(it)) {
                is JSONObject -> toMap(value)
                JSONObject.NULL -> null
                else -> value
            }
        }
    }
}
