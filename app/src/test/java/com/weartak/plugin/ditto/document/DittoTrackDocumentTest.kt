package com.weartak.plugin.ditto.document

import org.junit.Assert.*
import org.junit.Test

class DittoTrackDocumentTest {
    private val input = DittoTrackDocument.Input(
        deviceUid = "synthetic-watch", peerKey = "synthetic-peer", callsign = "ALPHA",
        timeMillis = 1_700_000_000_000L, staleTimeMillis = 1_700_000_135_000L,
        locationTimeMillis = 1_699_999_998_000L,
        latitude = 38.0, longitude = -77.0, hae = 100.0,
        ce = 5.5f, le = 7f, speed = 1.25f,
        detailXml = "<detail><contact callsign='ALPHA'/><track speed='1.25'/></detail>",
    )

    @Test
    fun `encodes exact core aliases values and numeric types`() {
        val expected = mapOf<String, Any?>(
            "_id" to "synthetic-watch", "_r" to false, "_v" to 2,
            "authorCs" to "ALPHA", "AuthorId" to "synthetic-watch",
            "authorType" to "a-f-G-U-C", "visible" to true,
            "a" to "synthetic-peer", "b" to 1_700_000_000_000L,
            "c" to "ALPHA", "d" to "synthetic-watch", "e" to "ALPHA",
            "f" to true, "g" to "2.0",
            "h" to 5.5, "i" to 100.0, "j" to 38.0, "k" to 7.0, "l" to -77.0,
            "n" to 1_700_000_000_000L, "o" to 1_700_000_135_000L,
            "p" to "m-g", "q" to "Undefined",
            "r" to "<detail><contact callsign='ALPHA'/><track speed='1.25'/></detail>",
            "r1" to "1.25", "r2" to "100.0", "w" to "a-f-G-U-C",
        )
        val result = DittoTrackDocument.encode(input)
        assertEquals(expected, result)
        val decoded = DittoCotDocument.decode(result)
        assertEquals("synthetic-watch", decoded.uid)
        assertEquals("synthetic-peer", decoded.peer)
        assertFalse(input.toString().contains("synthetic-watch"))
    }

    @Test
    fun `core null and nan defaults and old fix marker match boundaries`() {
        val defaults = input.copy(hae = null, ce = null, le = Float.NaN, speed = null)
        val boundary = DittoTrackDocument.encode(defaults.copy(locationTimeMillis = input.timeMillis - 3_000))
        assertEquals(9999999.0, boundary["h"])
        assertEquals(9999999.0, boundary["k"])
        assertEquals(0.0, boundary["i"])
        assertEquals("0.0", boundary["r1"])
        assertEquals("0.0", boundary["r2"])
        assertEquals(defaults.detailXml, boundary["r"])
        val old = DittoTrackDocument.encode(defaults.copy(locationTimeMillis = input.timeMillis - 3_001))
        assertEquals(
            "<detail><contact callsign='ALPHA'/><track speed='1.25'/>" +
                "<weartak_location timeMillis='1699999996999'/></detail>",
            old["r"],
        )
        val future = DittoTrackDocument.encode(defaults.copy(locationTimeMillis = input.timeMillis + 1))
        assertEquals(defaults.detailXml, future["r"])
    }

    @Test
    fun `marker insertion targets closing root even when comment contains closing tag`() {
        val detail = "<detail><!-- </detail> --><contact callsign='ALPHA'/></detail>"
        val encoded = DittoTrackDocument.encode(input.copy(
            detailXml = detail, locationTimeMillis = input.timeMillis - 3_001,
        ))
        assertEquals("<detail><!-- </detail> --><contact callsign='ALPHA'/>" +
            "<weartak_location timeMillis='1699999996999'/></detail>", encoded["r"])
        DittoCotDocument.decode(encoded)
    }

    @Test
    fun `expiry copies document while preserving all fields except time aliases`() {
        val original = DittoTrackDocument.encode(input) + mapOf("extra" to "keep", "empty" to null)
        val expired = DittoTrackDocument.expire(original, 1_700_000_001_000L)
        assertEquals(original + mapOf(
            "b" to 1_700_000_001_000L, "n" to 1_700_000_001_000L, "o" to 1_700_000_001_000L,
        ), expired)
        assertEquals(1_700_000_000_000L, original["b"])
        assertEquals(1_700_000_135_000L, original["o"])
    }

    @Test
    fun `expiry rejects invalid timestamps before returning a document`() {
        val track = DittoTrackDocument.encode(input)
        for (invalid in listOf(0L, -1L, 253402300800000L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { DittoTrackDocument.expire(track, invalid) }
        }
        assertEquals(1L, DittoTrackDocument.expire(track, 1L)["o"])
        assertEquals(253402300799999L, DittoTrackDocument.expire(track, 253402300799999L)["o"])
    }

    @Test
    fun `old fix marker and event envelope count toward cot size limit`() {
        val empty = input.copy(
            detailXml = "<detail></detail>",
            locationTimeMillis = input.timeMillis - 3_001,
        )
        val initialSize = DittoCotDocument.decode(DittoTrackDocument.encode(empty))
            .xml.toByteArray(Charsets.UTF_8).size
        val padding = DittoCotDocument.MAX_PAYLOAD_BYTES - initialSize
        val base = input.copy(
            detailXml = "<detail>${"x".repeat(padding)}</detail>",
            locationTimeMillis = input.timeMillis - 3_001,
        )
        val document = DittoCotDocument.decode(DittoTrackDocument.encode(base))
        assertEquals(DittoCotDocument.MAX_PAYLOAD_BYTES, document.xml.toByteArray(Charsets.UTF_8).size)
        assertThrows(IllegalArgumentException::class.java) {
            DittoTrackDocument.encode(base.copy(detailXml = base.detailXml.replace("</detail>", "x</detail>")))
        }
    }

    @Test
    fun `invalid and sensitive input fails closed`() {
        val invalid = listOf(
            input.copy(deviceUid = ""), input.copy(peerKey = "bad\u0000peer"),
            input.copy(callsign = "#weartakcommand enrollserver"),
            input.copy(timeMillis = 0), input.copy(staleTimeMillis = input.timeMillis - 1),
            input.copy(locationTimeMillis = -1), input.copy(latitude = 90.01),
            input.copy(longitude = Double.NaN), input.copy(hae = Double.POSITIVE_INFINITY),
            input.copy(ce = Float.NEGATIVE_INFINITY), input.copy(speed = Float.NaN),
            input.copy(detailXml = "<detail/>"),
            input.copy(detailXml = "<detail><weartak_location timeMillis='1'/></detail>"),
            input.copy(detailXml = "<detail><remarks>&#35;weartakcommand enrollserver</remarks></detail>"),
            input.copy(detailXml = "<!DOCTYPE detail [<!ENTITY x SYSTEM 'file:///nonexistent'>]><detail>&x;</detail>"),
            input.copy(detailXml = "<detail>${"x".repeat(DittoCotDocument.MAX_PAYLOAD_BYTES)}</detail>"),
        )
        invalid.forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { DittoTrackDocument.encode(bad) }
        }
    }
}
