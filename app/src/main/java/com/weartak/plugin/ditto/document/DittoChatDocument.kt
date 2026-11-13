// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoChatDocument.kt, CotMessageParser.kt and DittoCotReceiver.kt
// at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.document

import java.time.Instant
import java.time.DateTimeException
import java.io.StringWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

internal object DittoChatDocument {
    const val TTL_MILLIS = 86_400_000L
    private const val MAX_FUTURE_MILLIS = 300_000L
    private const val MAX_TIMESTAMP = 253402300799999L

    /** Apply after decoding and immediately before ingress; this does not suppress replay. */
    fun isFreshForIngress(document: DittoCotDocument, nowMillis: Long): Boolean {
        require(document.type == "b-t-f" && document.time in 1..MAX_TIMESTAMP &&
            document.stale in document.time..MAX_TIMESTAMP &&
            document.stale - document.time <= TTL_MILLIS && nowMillis in 1..MAX_TIMESTAMP) {
            "Invalid chat freshness input"
        }
        require(document.time <= nowMillis + MAX_FUTURE_MILLIS) { "Future-dated Ditto event" }
        return document.stale > nowMillis
    }

    /** Caller supplies a privacy-filtered GeoChat and the current provisioned peer key. */
    fun encode(xml: String, peer: String): Map<String, Any?> {
        require(xml.length <= DittoCotDocument.MAX_PAYLOAD_BYTES &&
            xml.toByteArray(Charsets.UTF_8).size <= DittoCotDocument.MAX_PAYLOAD_BYTES) {
            "Oversized GeoChat"
        }
        val event = DittoCotXml.parseEvent(xml)
        require(!DittoCotXml.containsSensitiveEnrollment(xml, event) &&
            !DittoCotXml.containsSensitiveEnrollmentText(peer)) {
            "Sensitive enrollment commands cannot be encoded"
        }
        require(event.getAttribute("type") == "b-t-f") { "Invalid GeoChat type" }

        fun single(parent: Element, name: String): Element {
            val elements = parent.getElementsByTagName(name)
            require(elements.length == 1) { "Invalid GeoChat structure" }
            return elements.item(0) as Element
        }
        val point = single(event, "point")
        val detail = single(event, "detail")
        require(point.parentNode === event && detail.parentNode === event) { "Invalid GeoChat structure" }
        val chat = single(event, "__chat")
        val group = single(event, "chatgrp")
        val remarks = single(event, "remarks")
        require(single(detail, "__chat") === chat && single(detail, "chatgrp") === group &&
            single(detail, "remarks") === remarks && single(chat, "chatgrp") === group) {
            "Invalid GeoChat structure"
        }
        fun time(name: String): Long = try {
            Instant.parse(event.getAttribute(name)).toEpochMilli()
        } catch (_: DateTimeException) {
            throw IllegalArgumentException("Invalid GeoChat timestamp")
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Invalid GeoChat timestamp")
        }
        fun coordinate(name: String): Double = point.getAttribute(name).toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid GeoChat coordinate")

        val serialized = StringWriter()
        try {
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            }.transform(DOMSource(detail), StreamResult(serialized))
        } catch (_: TransformerException) {
            throw IllegalStateException("Cannot serialize GeoChat detail")
        }
        val sender = group.getAttribute("uid0")
        val latitude = coordinate("lat")
        val longitude = coordinate("lon")
        val altitude = coordinate("hae")
        val document = mapOf<String, Any?>(
            "_id" to chat.getAttribute("messageId"), "_v" to 2, "_r" to false,
            "a" to peer, "b" to time("time"), "d" to sender, "e" to chat.getAttribute("senderCallsign"),
            "msg" to remarks.textContent, "room" to chat.getAttribute("chatroom"),
            "roomId" to chat.getAttribute("id"),
            "parent" to chat.getAttribute("parent").ifBlank { "RootContactGroup" },
            "authorType" to ((event.getElementsByTagName("link").item(0) as? Element)
                ?.getAttribute("type")?.takeIf { it.isNotBlank() } ?: "a-f-G-U-C"),
            "authorLoc" to "$latitude,$longitude,$altitude",
            "eventUid" to event.getAttribute("uid"), "r" to serialized.toString(),
            "n" to time("start"), "o" to time("stale"),
            "g" to event.getAttribute("version"),
            "h" to coordinate("ce"), "i" to altitude, "j" to latitude,
            "k" to coordinate("le"), "l" to longitude,
            "p" to event.getAttribute("how"),
            "q" to event.getAttribute("access").ifBlank { "Undefined" },
        )
        decode(document)
        return document
    }

    fun decode(raw: Map<String, Any?>, knownGroupMembers: List<String> = emptyList()): DittoCotDocument {
        fun text(key: String, limit: Int = 512): String =
            (raw[key] as? String)?.also {
                require(it.isNotBlank() && it.length <= limit && it.none(Char::isISOControl) &&
                    !DittoCotXml.containsSensitiveEnrollmentText(it)) {
                    "Invalid Ditto chat field $key"
                }
            } ?: throw IllegalArgumentException("Missing Ditto chat field $key")
        val id = text("_id")
        val sender = text("d")
        val callsign = text("e")
        val peer = text("a")
        val room = text("room")
        val roomId = text("roomId")
        val parent = text("parent")
        val message = (raw["msg"] as? String)?.also {
            require(it.isNotBlank() && it.length <= DittoCotDocument.MAX_PAYLOAD_BYTES &&
                it.toByteArray(Charsets.UTF_8).size <= DittoCotDocument.MAX_PAYLOAD_BYTES) {
                "Invalid Ditto chat message"
            }
            require(!DittoCotXml.containsSensitiveEnrollmentText(it)) {
                "Sensitive enrollment commands are not accepted"
            }
        } ?: throw IllegalArgumentException("Missing Ditto chat message")
        val time = (raw["b"] as? Number)?.toDouble()
            ?: throw IllegalArgumentException("Missing Ditto chat timestamp")
        require(time.isFinite() && time > 0 && time <= 253402214399999.0 &&
            time == time.toLong().toDouble()) { "Invalid Ditto chat timestamp" }
        val expiry = time.toLong() + TTL_MILLIS
        val location = text("authorLoc").split(',').map(String::trim)
        require(location.size >= 2) { "Invalid Ditto chat author location" }
        val latitude = location[0].toDoubleOrNull() ?: Double.NaN
        val longitude = location[1].toDoubleOrNull() ?: Double.NaN
        fun optionalNumber(index: Int) = location.getOrNull(index)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val altitude = optionalNumber(2)?.takeIf {
            location.getOrNull(3).isNullOrBlank() || location[3] == "HAE"
        } ?: 9_999_999.0
        fun error(index: Int) = optionalNumber(index)?.takeIf { it >= 0.0 } ?: 999999.0
        require(latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0) { "Invalid Ditto chat author location" }

        val authorType = text("authorType")
        val eventUid = if ("eventUid" in raw) text("eventUid") else "GeoChat.$sender.$roomId.$id"
        val detail = if ("r" in raw) raw["r"] as? String
            ?: throw IllegalArgumentException("Invalid Ditto chat detail")
        else {
            val recipients = knownGroupMembers.filter { it != sender }.distinct().ifEmpty { listOf(roomId) }
            val chat = DittoCotXml.element("__chat", listOf(
                "id" to DittoCotXml.escape(roomId), "chatroom" to DittoCotXml.escape(room),
                "senderCallsign" to DittoCotXml.escape(callsign), "messageId" to DittoCotXml.escape(id),
                "parent" to DittoCotXml.escape(parent), "groupOwner" to "false",
            ), DittoCotXml.element("chatgrp", listOf(
                "id" to DittoCotXml.escape(roomId), "uid0" to DittoCotXml.escape(sender),
            ) + recipients.mapIndexed { index, uid -> "uid${index + 1}" to DittoCotXml.escape(uid) }))
            DittoCotXml.element("detail", content = chat + DittoCotXml.element("link", listOf(
                "uid" to DittoCotXml.escape(sender), "type" to DittoCotXml.escape(authorType), "relation" to "p-p",
            )) + DittoCotXml.element("remarks", listOf(
                "source" to DittoCotXml.escape(sender), "to" to DittoCotXml.escape(roomId),
                "time" to Instant.ofEpochMilli(time.toLong()),
            ), DittoCotXml.escape(message)))
        }
        val document = DittoCotDocument.decode(raw + mapOf(
            "_id" to eventUid, "d" to eventUid, "a" to peer, "AuthorId" to sender, "w" to "b-t-f",
            "n" to (raw["n"] ?: raw["b"]), "o" to (raw["o"] ?: expiry),
            "g" to (raw["g"] ?: "2.0"), "p" to (raw["p"] ?: "h-g-i-g-o"),
            "q" to (raw["q"] ?: "Undefined"), "r" to detail,
            "j" to (raw["j"] ?: latitude), "l" to (raw["l"] ?: longitude),
            "i" to (raw["i"] ?: altitude), "h" to (raw["h"] ?: error(4)),
            "k" to (raw["k"] ?: error(5)),
        ))
        require(document.stale <= expiry) { "Ditto chat exceeds 24-hour TTL" }
        val root = DittoCotXml.parseEvent(document.xml)
        require(root.getElementsByTagName("__chat").length == 1 &&
            root.getElementsByTagName("chatgrp").length == 1 &&
            root.getElementsByTagName("remarks").length == 1) { "Ambiguous Ditto chat detail" }
        val chat = root.getElementsByTagName("__chat").item(0) as Element
        val group = chat.getElementsByTagName("chatgrp").item(0) as? Element
            ?: throw IllegalArgumentException("Invalid GeoChat document in Ditto chat")
        val remarks = root.getElementsByTagName("remarks").item(0) as Element
        require(validRouting(root, chat, group, remarks)) { "Invalid GeoChat document in Ditto chat" }
        require(chat.getAttribute("messageId") == id && group.getAttribute("uid0") == sender &&
            chat.getAttribute("id") == roomId && chat.getAttribute("chatroom") == room &&
            chat.getAttribute("senderCallsign") == callsign &&
            chat.getAttribute("parent").ifBlank { "RootContactGroup" } == parent &&
            remarks.textContent == message) { "Ditto chat envelope/detail mismatch" }
        return document
    }

    private fun validRouting(root: Element, chat: Element, group: Element, remarks: Element): Boolean {
        val sender = group.getAttribute("uid0")
        val room = chat.getAttribute("id")
        if (sender.isBlank() || room.isBlank() ||
            group.getAttribute("id").let { it.isNotBlank() && it != room } ||
            chat.getAttribute("senderCallsign").isBlank() || chat.getAttribute("messageId").isBlank() ||
            chat.getAttribute("chatroom").isBlank() || remarks.textContent.isNullOrBlank()
        ) return false
        val recipients = mutableSetOf<String>()
        val attributes = group.attributes
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            if (attribute.nodeName.matches(Regex("uid[1-9][0-9]*")) &&
                attribute.nodeValue.isNotBlank() && attribute.nodeValue != sender) {
                recipients += attribute.nodeValue
            }
        }
        val destinations = root.getElementsByTagName("marti").item(0) as? Element
        destinations?.getElementsByTagName("dest")?.let { nodes ->
            for (i in 0 until nodes.length) {
                val recipient = (nodes.item(i) as? Element)?.getAttribute("uid").orEmpty()
                if (recipient.isNotBlank() && recipient != sender) recipients += recipient
            }
        }
        val to = remarks.getAttribute("to")
        if (to.isNotBlank() && to != sender && to != "All Chat Rooms") recipients += to
        return room == "All Chat Rooms" || to == "All Chat Rooms" || recipients.isNotEmpty()
    }
}
