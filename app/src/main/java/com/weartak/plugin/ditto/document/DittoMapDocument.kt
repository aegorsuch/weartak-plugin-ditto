// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoService.kt and CotMessageParser.kt
// at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.document

internal object DittoMapDocument {
    enum class Collection { MAPITEM, ALERT }

    /**
     * Caller supplies already-filtered, mutually consistent and authorized inputs.
     * Encoding alone does not establish local ownership or publish a point.
     */
    data class Input(
        val collection: Collection,
        val uid: String,
        val authorUid: String,
        val authorCallsign: String,
        val eventCallsign: String?,
        val peerKey: String,
        val timeMillis: Long,
        val staleMillis: Long,
        val latitude: Double,
        val longitude: Double,
        val hae: Double,
        val ce: Double,
        val le: Double,
        val how: String?,
        val access: String?,
        val detailXml: String,
        val type: String,
    ) {
        override fun toString(): String = "DittoMapDocument.Input(redacted)"
    }

    fun encode(input: Input): Map<String, Any?> {
        val callsign = input.eventCallsign?.takeIf { it.isNotEmpty() } ?: input.authorCallsign
        val access = input.access?.takeIf { it.isNotEmpty() } ?: "Undefined"
        val how = input.how.orEmpty().let {
            if (input.collection == Collection.ALERT && it.isEmpty()) "m-g" else it
        }
        val textFields = listOf(input.uid, input.authorUid, input.authorCallsign, input.peerKey, how, access) +
            if (input.collection == Collection.MAPITEM) listOf(callsign) else emptyList()
        textFields
            .forEach {
                require(it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl) &&
                    !DittoCotXml.containsSensitiveEnrollmentText(it)) { "Invalid map document field" }
            }
        when (input.collection) {
            Collection.MAPITEM -> require(DittoCotDocument.isMarkerType(input.type) || input.type == "b-m-r") {
                "Unsupported mapitem type"
            }
            Collection.ALERT -> require(input.type == "b-a-g" || input.type == "b-a-o" ||
                input.type.startsWith("b-a-o-")) { "Unsupported alert type" }
        }
        val document = mutableMapOf<String, Any?>(
            "_id" to input.uid, "_r" to false, "_v" to 2,
            "authorCs" to input.authorCallsign, "AuthorId" to input.authorUid,
            "authorType" to input.type, "visible" to true,
            "a" to input.peerKey, "b" to input.timeMillis, "d" to input.authorUid,
            "e" to input.authorCallsign, "g" to "2.0",
            "h" to input.ce, "i" to input.hae, "j" to input.latitude,
            "k" to input.le, "l" to input.longitude,
            "n" to input.timeMillis, "o" to input.staleMillis,
            "p" to how, "q" to access, "r" to input.detailXml, "w" to input.type,
        )
        if (input.collection == Collection.MAPITEM) {
            document["c"] = callsign
            document["f"] = true
        }
        DittoCotDocument.decode(document)
        return document.toMap()
    }
}
