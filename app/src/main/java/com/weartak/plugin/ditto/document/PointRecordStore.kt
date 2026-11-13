// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoPointIngress.kt and DittoPointLedger.kt
// at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.document

import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject

internal class PointRecordStore(
    private val preferences: SharedPreferences,
    private val maxEntries: Int,
    private val maxBytes: Int,
    private val maxRecordBytes: Int,
    private val validate: (String, JSONObject) -> Unit,
) {
    private val records = mutableMapOf<String, String>()
    private var failedKey: String? = null

    init {
        preferences.all.forEach { (key, value) ->
            check(value is String) { "Invalid point storage value" }
            records[key] = value
        }
        check(records.size <= maxEntries) { "Point storage entry capacity exceeded" }
        check(records.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= maxBytes) {
            "Point storage byte capacity exceeded"
        }
        records.forEach { (key, value) -> validateRecord(key, value) }
    }

    val size: Int get() = records.size
    fun keys(): List<String> = records.keys.toList()
    fun contains(key: String): Boolean = records.containsKey(key)
    fun read(key: String): JSONObject? = records[key]?.let { parse(it) }

    fun save(key: String, record: JSONObject) {
        val value = record.toString()
        validateRecord(key, value)
        val bytes = value.toByteArray(Charsets.UTF_8).size
        val oldSize = records[key]?.toByteArray(Charsets.UTF_8)?.size ?: 0
        val total = records.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        check(key in records || records.size < maxEntries) { "Point storage entry capacity reached" }
        check(total - oldSize + bytes <= maxBytes) { "Point storage byte capacity reached" }
        failedKey?.let { failed ->
            val repair = preferences.edit()
            records[failed]?.let { repair.putString(failed, it) } ?: repair.remove(failed)
            check(repair.commit()) { "Could not repair unacknowledged point state" }
            failedKey = null
        }
        if (!preferences.edit().putString(key, value).commit()) {
            failedKey = key
            throw IllegalStateException("Could not persist point state")
        }
        records[key] = value
    }

    private fun validateRecord(key: String, value: String) {
        check(value.toByteArray(Charsets.UTF_8).size <= maxRecordBytes) { "Point record too large" }
        try {
            validate(key, parse(value))
        } catch (_: JSONException) {
            throw IllegalStateException("Invalid persisted point record")
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("Invalid persisted point record")
        }
    }

    private fun parse(value: String): JSONObject = try {
        JSONObject(value)
    } catch (_: JSONException) {
        throw IllegalStateException("Invalid persisted point record")
    }
}
