package com.weartak.plugin.ditto.document

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class DittoChatDocumentTest {
    private fun nativeChat(): Map<String, Any?> = mapOf(
        "_id" to "message-1", "_v" to 2, "_r" to false,
        "a" to "remote-peer", "b" to 1_700_000_000_000L,
        "d" to "alice", "e" to "ALICE", "msg" to "Hello <team> & friends",
        "room" to "Team & Friends", "roomId" to "team", "parent" to "RootContactGroup",
        "authorType" to "a-f-G-U-C", "authorLoc" to "41.879815,-87.642469,156.933,HAE,19.4,9999999.0",
    )

    private fun element(document: DittoCotDocument, name: String): Element =
        DittoCotXml.parseEvent(document.xml).getElementsByTagName(name).item(0) as Element

    private fun extendedDetail(message: String = "Hello &lt;team&gt; &amp; friends"): String =
        "<detail><__chat id='team' chatroom='Team &amp; Friends' senderCallsign='ALICE' " +
            "messageId='message-1' parent='RootContactGroup'><chatgrp id='team' " +
            "uid0='alice' uid1='bob'/></__chat><remarks to='team'>$message</remarks></detail>"

    private fun outboundChat(): String =
        "<event version='2.0' uid='custom-event' type='b-t-f' how='h-g-i-g-o' access='Restricted' " +
            "time='2023-11-14T22:13:20Z' start='2023-11-14T22:13:21Z' stale='2023-11-14T22:14:20Z'>" +
            "<point lat='41.879815' lon='-87.642469' hae='156.933' ce='19.4' le='3.0'/>" +
            "<detail><__chat id='team' chatroom='Team &amp; Friends' senderCallsign='ALICE' " +
            "messageId='message-1' parent='Group'><chatgrp id='team' uid0='alice' uid1='bob'/></__chat>" +
            "<link uid='alice' type='a-f-G-E' relation='p-p'/>" +
            "<remarks to='team'>Hello &lt;team&gt; &amp; friends</remarks><custom value='retained'/>" +
            "</detail></event>"

    @Test
    fun `native chat maps fields and escaped text into interoperable cot`() {
        val result = DittoChatDocument.decode(nativeChat())
        assertEquals("GeoChat.alice.team.message-1", result.uid)
        assertEquals("alice", result.author)
        assertEquals(1_700_086_400_000L, result.stale)
        assertEquals("b-t-f", result.type)
        assertEquals(
            "<event version='2.0' uid='GeoChat.alice.team.message-1' type='b-t-f' " +
                "time='2023-11-14T22:13:20Z' start='2023-11-14T22:13:20Z' " +
                "stale='2023-11-15T22:13:20Z' how='h-g-i-g-o' access='Undefined'>" +
                "<point lat='41.879815' lon='-87.642469' hae='156.933' ce='19.4' le='9999999.0'/>" +
                "<detail><__chat id='team' chatroom='Team &amp; Friends' senderCallsign='ALICE' " +
                "messageId='message-1' parent='RootContactGroup' groupOwner='false'>" +
                "<chatgrp id='team' uid0='alice' uid1='team'/></__chat>" +
                "<link uid='alice' type='a-f-G-U-C' relation='p-p'/>" +
                "<remarks source='alice' to='team' time='2023-11-14T22:13:20Z'>" +
                "Hello &lt;team&gt; &amp; friends</remarks></detail></event>",
            result.xml,
        )
        val group = element(result, "chatgrp")
        val chat = element(result, "__chat")
        val point = element(result, "point")
        assertEquals("team", group.getAttribute("uid1"))
        assertEquals("Team & Friends", chat.getAttribute("chatroom"))
        assertEquals("Hello <team> & friends", element(result, "remarks").textContent)
        assertEquals("156.933", point.getAttribute("hae"))
        assertEquals("19.4", point.getAttribute("ce"))
        assertEquals("9999999.0", point.getAttribute("le"))
        assertFalse(result.toString().contains("Hello"))
    }

    @Test
    fun `group members are deduplicated and native broadcasts remain valid`() {
        val group = element(DittoChatDocument.decode(nativeChat(), listOf("alice", "bob", "bob", "charlie")), "chatgrp")
        assertEquals("alice", group.getAttribute("uid0"))
        assertEquals("bob", group.getAttribute("uid1"))
        assertEquals("charlie", group.getAttribute("uid2"))
        assertFalse(group.hasAttribute("uid3"))
        val broadcast = nativeChat() + mapOf("roomId" to "All Chat Rooms", "room" to "All Chat Rooms")
        assertEquals("All Chat Rooms", element(DittoChatDocument.decode(broadcast), "__chat").getAttribute("id"))
    }

    @Test
    fun `official geopoint optional values use core fallbacks`() {
        fun point(location: String) = element(DittoChatDocument.decode(nativeChat() + ("authorLoc" to location)), "point")
        val unknown = point(" 41 , -87 , NaN , HAE , NaN , -1 ")
        assertEquals("9999999.0", unknown.getAttribute("hae"))
        assertEquals("999999.0", unknown.getAttribute("ce"))
        assertEquals("999999.0", unknown.getAttribute("le"))
        assertEquals("9999999.0", point("41,-87,100,AGL").getAttribute("hae"))
        assertEquals("9999999.0", point("41,-87").getAttribute("hae"))
        assertThrows(IllegalArgumentException::class.java) { point("NaN,-87") }
        assertThrows(IllegalArgumentException::class.java) { point("41,181") }
    }

    @Test
    fun `extended detail preserves event identity and shorter ttl`() {
        val raw = nativeChat() + mapOf(
            "eventUid" to "custom-event", "r" to extendedDetail(),
            "n" to 1_700_000_000_000L, "o" to 1_700_000_100_000L,
        )
        val result = DittoChatDocument.decode(raw)
        assertEquals("custom-event", result.uid)
        assertEquals(1_700_000_100_000L, result.stale)
        assertEquals("bob", element(result, "chatgrp").getAttribute("uid1"))
        assertEquals("Hello <team> & friends", element(result, "remarks").textContent)
        assertThrows(IllegalArgumentException::class.java) {
            DittoChatDocument.decode(raw + ("o" to 1_700_086_400_001L))
        }
    }

    @Test
    fun `invalid ambiguous and mismatched chat is rejected`() {
        val raw = nativeChat()
        val invalid = listOf(
            raw + ("_v" to 1), raw + ("_r" to true),
            raw + ("b" to 253402214400000L), raw + ("msg" to ""),
            raw + ("r" to extendedDetail("different message")),
            raw + ("r" to extendedDetail().replace("uid0='alice'", "uid0='mallory'")),
            raw + ("r" to extendedDetail().replace("senderCallsign='ALICE'", "senderCallsign='MALLORY'")),
            raw + ("r" to extendedDetail().replace("parent='RootContactGroup'", "parent='OtherGroup'")),
            raw + ("r" to extendedDetail().replace("uid1='bob'", "").replace("to='team'", "to='alice'")),
            raw + ("r" to extendedDetail().replace("</detail>", "<remarks>duplicate</remarks></detail>")),
            raw + ("r" to null),
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { DittoChatDocument.decode(it) } }
    }

    @Test
    fun `sensitive chat and entity payloads are rejected before use`() {
        val raw = nativeChat()
        assertThrows(IllegalArgumentException::class.java) {
            DittoChatDocument.decode(raw + ("msg" to "text #weartakcommand enrollserver placeholder"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DittoChatDocument.decode(raw + ("r" to "<!DOCTYPE detail><detail/>"))
        }
        for (field in listOf("_id", "d", "e", "a", "room", "roomId", "parent", "authorType", "eventUid")) {
            assertThrows(IllegalArgumentException::class.java) {
                DittoChatDocument.decode(raw + (field to "#weartakcommand enrollserver placeholder-secret"))
            }
        }
    }

    @Test
    fun `outbound chat maps exact native and extension fields without dropping detail`() {
        val encoded = DittoChatDocument.encode(outboundChat(), "synthetic-peer")
        val expected = mapOf<String, Any?>(
            "_id" to "message-1", "_v" to 2, "_r" to false,
            "a" to "synthetic-peer", "b" to 1_700_000_000_000L,
            "d" to "alice", "e" to "ALICE", "msg" to "Hello <team> & friends",
            "room" to "Team & Friends", "roomId" to "team", "parent" to "Group",
            "authorType" to "a-f-G-E", "authorLoc" to "41.879815,-87.642469,156.933",
            "eventUid" to "custom-event", "n" to 1_700_000_001_000L,
            "o" to 1_700_000_060_000L, "g" to "2.0",
            "h" to 19.4, "i" to 156.933, "j" to 41.879815,
            "k" to 3.0, "l" to -87.642469, "p" to "h-g-i-g-o", "q" to "Restricted",
        )
        assertEquals(expected, encoded - "r")
        val detail = DittoCotXml.parseDetail(encoded["r"] as String)
        assertEquals("retained", (detail.getElementsByTagName("custom").item(0) as Element).getAttribute("value"))
        val result = DittoChatDocument.decode(encoded)
        assertEquals("custom-event", result.uid)
        assertEquals("alice", result.author)
        assertEquals("bob", element(result, "chatgrp").getAttribute("uid1"))
        assertEquals("Hello <team> & friends", element(result, "remarks").textContent)
    }

    @Test
    fun `outbound chat uses core parent author type and access fallbacks`() {
        val xml = outboundChat().replace(" parent='Group'", "").replace(" access='Restricted'", "")
            .replace("<link uid='alice' type='a-f-G-E' relation='p-p'/>", "")
        val encoded = DittoChatDocument.encode(xml, "synthetic-peer")
        assertEquals("RootContactGroup", encoded["parent"])
        assertEquals("a-f-G-U-C", encoded["authorType"])
        assertEquals("Undefined", encoded["q"])
        assertEquals("custom-event", DittoChatDocument.decode(encoded).uid)
    }

    @Test
    fun `outbound ttl boundary and invalid routing are rejected`() {
        val valid = outboundChat()
        val boundary = valid.replace("2023-11-14T22:14:20Z", "2023-11-15T22:13:20Z")
        assertEquals(1_700_086_400_000L, DittoChatDocument.encode(boundary, "peer")["o"])
        val invalid = listOf(
            valid.replace("2023-11-14T22:14:20Z", "2023-11-15T22:13:20.001Z"),
            valid.replace("type='b-t-f'", "type='a-f-G-U-C'"),
            valid.replace(" start='2023-11-14T22:13:21Z'", ""),
            valid.replace("lat='41.879815'", "lat='NaN'"),
            valid.replace("uid1='bob'", "").replace("to='team'", "to='alice'"),
            valid.replace("</detail>", "<remarks>duplicate</remarks></detail>"),
            valid.replace("</event>", "<point lat='0'/></event>"),
            valid.replace("</event>", "<__chat/></event>"),
        )
        invalid.forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { DittoChatDocument.encode(bad, "peer") }
        }
        assertThrows(IllegalArgumentException::class.java) { DittoChatDocument.encode(valid, "") }
    }

    @Test
    fun `outbound sensitive malformed and oversized input is rejected without echoing content`() {
        val valid = outboundChat()
        val sensitive = valid.replace("Hello &lt;team&gt; &amp; friends",
            "&#35;weartakcommand enrollserver placeholder-secret")
        val error = assertThrows(IllegalArgumentException::class.java) {
            DittoChatDocument.encode(sensitive, "peer")
        }
        assertFalse(error.message.orEmpty().contains("placeholder-secret"))
        assertThrows(IllegalArgumentException::class.java) {
            DittoChatDocument.encode(valid, "#weartakcommand enrollserver")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DittoChatDocument.encode("<!DOCTYPE event><event/>", "peer")
        }
        val oversized = valid.replace("</detail>", "${"x".repeat(DittoCotDocument.MAX_PAYLOAD_BYTES)}</detail>")
        assertThrows(IllegalArgumentException::class.java) { DittoChatDocument.encode(oversized, "peer") }
    }

    @Test
    fun `ingress freshness honors future and stale boundaries`() {
        val doc = DittoChatDocument.decode(nativeChat())
        assertTrue(DittoChatDocument.isFreshForIngress(doc, doc.time - 300_000))
        assertThrows(IllegalArgumentException::class.java) {
            DittoChatDocument.isFreshForIngress(doc, doc.time - 300_001)
        }
        assertTrue(DittoChatDocument.isFreshForIngress(doc, doc.stale - 1))
        assertFalse(DittoChatDocument.isFreshForIngress(doc, doc.stale))
        assertFalse(DittoChatDocument.isFreshForIngress(doc, doc.stale + 1))
        val shorter = DittoChatDocument.decode(nativeChat() + ("o" to doc.time + 1_000))
        assertTrue(DittoChatDocument.isFreshForIngress(shorter, doc.time + 999))
        assertFalse(DittoChatDocument.isFreshForIngress(shorter, doc.time + 1_000))
    }

    @Test
    fun `ingress freshness fails closed on invalid caller clock or unvalidated document`() {
        val doc = DittoChatDocument.decode(nativeChat())
        assertThrows(IllegalArgumentException::class.java) { DittoChatDocument.isFreshForIngress(doc, 0) }
        assertThrows(IllegalArgumentException::class.java) { DittoChatDocument.isFreshForIngress(doc, Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) {
            DittoChatDocument.isFreshForIngress(doc.copy(type = "a-h-G"), doc.time)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DittoChatDocument.isFreshForIngress(doc.copy(stale = doc.time - 1), doc.time)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DittoChatDocument.isFreshForIngress(doc.copy(stale = doc.stale + 1), doc.time)
        }
    }
}
