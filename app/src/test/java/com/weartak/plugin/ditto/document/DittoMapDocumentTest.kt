package com.weartak.plugin.ditto.document

import org.junit.Assert.*
import org.junit.Test

class DittoMapDocumentTest {
    private val point = DittoMapDocument.Input(
        collection = DittoMapDocument.Collection.MAPITEM,
        uid = "synthetic-point", authorUid = "synthetic-watch", authorCallsign = "WATCH",
        eventCallsign = "POINT", peerKey = "synthetic-peer",
        timeMillis = 1_700_000_000_000L, staleMillis = 1_700_000_060_000L,
        latitude = 38.0, longitude = -77.0, hae = 100.0, ce = 5.0, le = 7.0,
        how = "h-g-i-g-o", access = "Restricted",
        detailXml = "<detail><contact callsign='POINT'/></detail>", type = "a-h-G",
    )

    @Test
    fun `mapitem aliases match core and preserve event and author identities`() {
        val result = DittoMapDocument.encode(point)
        assertEquals(mapOf<String, Any?>(
            "_id" to "synthetic-point", "_r" to false, "_v" to 2,
            "authorCs" to "WATCH", "AuthorId" to "synthetic-watch", "authorType" to "a-h-G",
            "visible" to true, "a" to "synthetic-peer", "b" to 1_700_000_000_000L,
            "c" to "POINT", "d" to "synthetic-watch", "e" to "WATCH", "f" to true,
            "g" to "2.0", "h" to 5.0, "i" to 100.0, "j" to 38.0, "k" to 7.0, "l" to -77.0,
            "n" to 1_700_000_000_000L, "o" to 1_700_000_060_000L,
            "p" to "h-g-i-g-o", "q" to "Restricted",
            "r" to "<detail><contact callsign='POINT'/></detail>", "w" to "a-h-G",
        ), result)
        val decoded = DittoCotDocument.decode(result)
        assertEquals("synthetic-watch", decoded.author)
        assertEquals("synthetic-point", decoded.uid)
        assertFalse(point.toString().contains("synthetic-point"))
        assertFalse(result.containsKey(DittoPointLedger.METADATA))
    }

    @Test
    fun `alert omits mapitem aliases and applies core missing how and access defaults`() {
        val alert = point.copy(
            collection = DittoMapDocument.Collection.ALERT, type = "b-a-g",
            how = null, access = null, detailXml = "<detail><emergency/></detail>",
        )
        val result = DittoMapDocument.encode(alert)
        assertEquals(mapOf<String, Any?>(
            "_id" to "synthetic-point", "_r" to false, "_v" to 2,
            "authorCs" to "WATCH", "AuthorId" to "synthetic-watch", "authorType" to "b-a-g",
            "visible" to true, "a" to "synthetic-peer", "b" to 1_700_000_000_000L,
            "d" to "synthetic-watch", "e" to "WATCH", "g" to "2.0",
            "h" to 5.0, "i" to 100.0, "j" to 38.0, "k" to 7.0, "l" to -77.0,
            "n" to 1_700_000_000_000L, "o" to 1_700_000_060_000L,
            "p" to "m-g", "q" to "Undefined",
            "r" to "<detail><emergency/></detail>", "w" to "b-a-g",
        ), result)
        assertFalse(result.containsKey("c"))
        assertFalse(result.containsKey("f"))
        assertEquals("b-a-g", DittoCotDocument.decode(result).type)
        assertEquals("m-g", DittoMapDocument.encode(alert.copy(how = ""))["p"])
        assertEquals("h-g-i-g-o", DittoMapDocument.encode(alert.copy(how = "h-g-i-g-o"))["p"])
    }

    @Test
    fun `event callsign fallback and supported route and cancellation types`() {
        assertEquals("WATCH", DittoMapDocument.encode(point.copy(eventCallsign = null))["c"])
        assertEquals("WATCH", DittoMapDocument.encode(point.copy(eventCallsign = ""))["c"])
        assertEquals("b-m-r", DittoMapDocument.encode(point.copy(type = "b-m-r"))["w"])
        assertEquals("b-a-o-can", DittoMapDocument.encode(point.copy(
            collection = DittoMapDocument.Collection.ALERT, type = "b-a-o-can",
        ))["w"])
    }

    @Test
    fun `invalid unsupported and sensitive inputs fail closed`() {
        val invalid = listOf(
            point.copy(uid = ""), point.copy(peerKey = "#weartakcommand enrollserver"),
            point.copy(authorCallsign = "bad\u0000callsign"), point.copy(eventCallsign = " "),
            point.copy(how = null), point.copy(access = " "),
            point.copy(timeMillis = 0), point.copy(staleMillis = point.timeMillis - 1),
            point.copy(latitude = 90.01), point.copy(longitude = Double.NaN),
            point.copy(hae = Double.POSITIVE_INFINITY), point.copy(ce = Double.NaN),
            point.copy(type = "t-x-d-d"), point.copy(type = "a-f-G-U-C"), point.copy(type = "b-a-g"),
            point.copy(collection = DittoMapDocument.Collection.ALERT, type = "a-h-G"),
            point.copy(detailXml = "<detail>"),
            point.copy(detailXml = "<detail><remarks>&#35;weartakcommand enrollserver</remarks></detail>"),
        )
        invalid.forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { DittoMapDocument.encode(bad) }
        }
    }
}
