package com.weartak.plugin.ditto.document

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.xml.sax.InputSource

class DittoCotDocumentTest {
    private fun document(
        uid: String = "point-1",
        type: String = "a-h-G",
        detail: String = "<detail><contact callsign='ALPHA'/></detail>",
    ): Map<String, Any?> = mapOf(
        "_id" to uid, "_v" to 2, "_r" to false,
        "a" to "remote-peer", "d" to "author",
        "b" to 1_700_000_000_000L, "n" to 1_700_000_000_000L,
        "o" to 1_700_000_060_000L, "w" to type,
        "g" to "2.0", "p" to "h-g-i-g-o", "q" to "Undefined",
        "j" to 38.0, "l" to -77.0, "i" to 100.0, "h" to 5.0, "k" to 7.0,
        "r" to detail,
    )

    @Test
    fun `v2 marker pli and emergency become bounded cot with escaped fields`() {
        assertEquals(
            "<event version='2.0' uid='point-1' type='a-h-G' " +
                "time='2023-11-14T22:13:20Z' start='2023-11-14T22:13:20Z' " +
                "stale='2023-11-14T22:14:20Z' how='h-g-i-g-o' access='Undefined'>" +
                "<point lat='38.0' lon='-77.0' hae='100.0' ce='5.0' le='7.0'/>" +
                "<detail><contact callsign='ALPHA'/></detail></event>",
            DittoCotDocument.decode(document()).xml,
        )
        for (type in listOf("a-h-G", "a-f-G-U-C", "b-a-g", "b-a-o-can")) {
            val decoded = DittoCotDocument.decode(document(uid = "x'&<", type = type))
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(decoded.xml))).documentElement
            assertEquals("event", root.tagName)
            assertEquals("x'&<", root.getAttribute("uid"))
            assertEquals(type, root.getAttribute("type"))
            assertEquals("38.0", root.getElementsByTagName("point").item(0).attributes.getNamedItem("lat").nodeValue)
            assertEquals("ALPHA", root.getElementsByTagName("contact").item(0).attributes.getNamedItem("callsign").nodeValue)
            assertEquals("author", decoded.author)
            assertFalse(decoded.toString().contains("remote-peer"))
            assertFalse(decoded.toString().contains("ALPHA"))
        }
    }

    @Test
    fun `legacy author and document identity remain stable and conflicts rejected`() {
        val input = document() + mapOf("d" to "point-1", "AuthorId" to "legacy-author")
        val decoded = DittoCotDocument.decode(input)
        val revision = DittoCotDocument.decode(input + ("r" to "<detail><contact callsign='BRAVO'/></detail>"))
        assertEquals("legacy-author", decoded.author)
        assertEquals(decoded.sourceId, revision.sourceId)
        assertEquals(decoded.key, revision.key)
        assertNotEquals(decoded.fingerprint, revision.fingerprint)
        assertThrows(IllegalArgumentException::class.java) {
            DittoCotDocument.decode(document() + ("AuthorId" to "different-author"))
        }
        assertNotEquals(decoded.key, DittoCotDocument.decode(input + ("a" to "other-peer")).key)
    }

    @Test
    fun `rejects invalid schema lifecycle times coordinates and types`() {
        val invalid = listOf(
            mapOf("_v" to 1), mapOf("_r" to true), mapOf("_r" to null),
            mapOf("b" to Double.NaN), mapOf("b" to 1.5),
            mapOf("b" to 253402300800000L), mapOf("o" to 1L),
            mapOf("j" to 90.01), mapOf("l" to -180.01), mapOf("i" to Double.POSITIVE_INFINITY),
            mapOf("_id" to "bad\u0000id"), mapOf("w" to "unsupported"),
        )
        invalid.forEach { change ->
            assertThrows(IllegalArgumentException::class.java) { DittoCotDocument.decode(document() + change) }
        }
    }

    @Test
    fun `rejects malformed or entity bearing details`() {
        val unsafe = listOf(
            "<event/>",
            "<detail><event/></detail>",
            "<detail><detail/></detail>",
            "<detail>",
            "<?xml version='1.0'?><detail/>",
            "<!DOCTYPE detail [<!ENTITY x SYSTEM 'file:///nonexistent'>]><detail>&x;</detail>",
        )
        unsafe.forEach { detail ->
            assertThrows(IllegalArgumentException::class.java) { DittoCotDocument.decode(document(detail = detail)) }
        }
    }

    @Test
    fun `rejects enrollment payloads including encoded attributes and split text`() {
        val sensitive = listOf(
            "<detail><remarks>#WearTakCommand enrollserver placeholder</remarks></detail>",
            "<detail><remarks>&#35;weartakcommand enrollserver placeholder</remarks></detail>",
            "<detail><remarks>#weartak<part/>command enrollserver placeholder</remarks></detail>",
            "<detail><data value='&#35;weartakcommand enrollserver placeholder'/></detail>",
        )
        sensitive.forEach { detail ->
            assertThrows(IllegalArgumentException::class.java) { DittoCotDocument.decode(document(detail = detail)) }
        }
    }

    @Test
    fun `rejects sensitive enrollment text in envelope fields without echoing it`() {
        val secret = "#weartakcommand enrollserver placeholder-secret"
        for (field in listOf("_id", "a", "d", "AuthorId", "g", "p", "q")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                DittoCotDocument.decode(document() + (field to secret))
            }
            assertFalse(error.message.orEmpty().contains("placeholder-secret"))
        }
    }

    @Test
    fun `limits final cot by utf8 bytes including exact boundary`() {
        val base = document(detail = "<detail></detail>")
        val initialSize = DittoCotDocument.decode(base).xml.toByteArray(Charsets.UTF_8).size
        val padding = DittoCotDocument.MAX_PAYLOAD_BYTES - initialSize
        val exact = base + ("r" to "<detail>${"x".repeat(padding)}</detail>")
        assertEquals(DittoCotDocument.MAX_PAYLOAD_BYTES, DittoCotDocument.decode(exact).xml.toByteArray(Charsets.UTF_8).size)
        val oversized = base + ("r" to "<detail>${"x".repeat(padding)}\u00e9</detail>")
        assertThrows(IllegalArgumentException::class.java) { DittoCotDocument.decode(oversized) }
    }
}
