package com.weartak.plugin.ditto.document

import android.content.Context
import android.content.SharedPreferences
import com.weartak.plugin.ditto.track.DittoTrackPublicationGate
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DittoDocumentFlowTest {
    private val now = 1_700_000_000_000L

    private fun preferences(): SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("synthetic-flow-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    private fun chatXml(message: String) =
        "<event version='2.0' uid='synthetic-event' type='b-t-f' how='h-g-i-g-o' " +
            "time='2023-11-14T22:13:20Z' start='2023-11-14T22:13:20Z' stale='2023-11-14T22:14:20Z'>" +
            "<point lat='38.0' lon='-77.0' hae='100.0' ce='5.0' le='7.0'/>" +
            "<detail><__chat id='team' chatroom='Team' senderCallsign='ALICE' messageId='message-1'>" +
            "<chatgrp id='team' uid0='alice' uid1='bob'/></__chat>" +
            "<remarks to='team'>$message</remarks></detail></event>"

    private fun pointInput() = DittoMapDocument.Input(
        collection = DittoMapDocument.Collection.MAPITEM,
        uid = "synthetic-point", authorUid = "synthetic-author", authorCallsign = "WATCH",
        eventCallsign = "POINT", peerKey = "synthetic-peer",
        timeMillis = now, staleMillis = now + 60_000,
        latitude = 38.0, longitude = -77.0, hae = 100.0, ce = 5.0, le = 7.0,
        how = "h-g-i-g-o", access = null, detailXml = "<detail><contact callsign='POINT'/></detail>",
        type = "a-h-G",
    )

    @Test
    fun `chat encoding decoding freshness and replay decisions compose`() {
        fun incoming(message: String) = DittoChatDocument.decode(
            DittoChatDocument.encode(chatXml(message), "synthetic-peer"),
        )
        val first = incoming("Hello &amp; team")
        assertTrue(DittoChatDocument.isFreshForIngress(first, now))
        assertTrue(DittoReplayPolicy.shouldIngest(first.time, first.fingerprint, false, true, false, null, null))

        val saved = DittoReplayPolicy.parseWatermark("${first.time}:${first.stale}:${first.fingerprint}")
        val repeated = incoming("Hello &amp; team")
        assertEquals(first.fingerprint, repeated.fingerprint)
        assertFalse(DittoReplayPolicy.shouldIngest(
            repeated.time, repeated.fingerprint, false, true, false, saved, null,
        ))
        val conflict = incoming("Changed message")
        assertNotEquals(first.fingerprint, conflict.fingerprint)
        assertFalse(DittoReplayPolicy.shouldIngest(
            conflict.time, conflict.fingerprint, false, true, false, saved, null,
        ))
        assertFalse(DittoChatDocument.isFreshForIngress(repeated, repeated.stale))
        assertThrows(IllegalArgumentException::class.java) {
            DittoChatDocument.isFreshForIngress(repeated, repeated.time - 300_001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            incoming("&#35;weartakcommand enrollserver placeholder")
        }
    }

    @Test
    fun `encoded point publication and tombstone survive failures and simulated restarts`() {
        val localPrefs = preferences()
        val remotePrefs = preferences()
        var local = DittoPointLedger(localPrefs)
        val encoded = DittoMapDocument.encode(pointInput())
        local.created("synthetic-point", "synthetic-author")
        assertThrows(IOException::class.java) {
            local.publish(encoded) { throw IOException("synthetic write failure") }
        }
        assertTrue(local.hasPending())

        val writes = mutableListOf<Map<String, Any?>>()
        local = DittoPointLedger(localPrefs)
        local.retry(writes::add) { throw it }
        assertFalse(local.hasPending())
        val live = writes.single()
        var remote = DittoPointIngress(remotePrefs)
        assertTrue(remote.accept(remote.decode(live)))
        assertThrows(IllegalArgumentException::class.java) {
            local.delete("synthetic-point", "different-author", now + 1)
        }
        local.delete("synthetic-point", "synthetic-author", now + 1)
        val errors = mutableListOf<Exception>()
        local.retry({ throw IOException("synthetic tombstone write failure") }, errors::add)
        assertEquals(1, errors.size)
        assertTrue(local.hasPending())

        local = DittoPointLedger(localPrefs)
        assertTrue(local.hasPending())
        local.retry(writes::add) { throw it }
        assertFalse(local.hasPending())
        val tombstone = writes.last()
        assertTrue(remote.accept(remote.decode(tombstone)))
        remote = DittoPointIngress(remotePrefs)
        assertTrue(remote.deleted(DittoCotDocument.decode(encoded)))
        assertTrue(remote.accept(remote.decode(tombstone)))
        val later = DittoMapDocument.encode(pointInput().copy(timeMillis = now + 2, staleMillis = now + 60_002))
        val laterDoc = DittoCotDocument.decode(later)
        val attemptedResurrection = later + (DittoPointLedger.METADATA to mapOf(
            "version" to 1, "owner" to laterDoc.author, "revision" to laterDoc.fingerprint, "deleted" to false,
        ))
        assertFalse(remote.accept(remote.decode(attemptedResurrection)))
        assertFalse(local.publish(later) { fail("Deleted point must not publish") })
    }

    @Test
    fun `point persistence failure prevents write and same instance repairs before retry`() {
        val prefs = FailOncePreferences(preferences())
        val ledger = DittoPointLedger(prefs)
        ledger.created("synthetic-point", "synthetic-author")
        prefs.failNext = true
        var calls = 0
        val encoded = DittoMapDocument.encode(pointInput())
        assertThrows(IllegalStateException::class.java) { ledger.publish(encoded) { calls++ } }
        assertEquals(0, calls)
        assertTrue(ledger.publish(encoded) { calls++ })
        assertEquals(1, calls)
        assertFalse(DittoPointLedger(prefs).hasPending())
    }

    @Test
    fun `track gate encoding decoding and expiry remain consistent across failed write`() {
        val gate = DittoTrackPublicationGate()
        val sample = DittoTrackPublicationGate.Sample(
            position = DittoTrackPublicationGate.Position(38.0, -77.0, 5f),
            battery = 80, identity = listOf("WATCH", "team", "synthetic-watch"),
            remarks = "", physiology = null, intervalSeconds = 60,
        )
        val track = DittoTrackDocument.encode(DittoTrackDocument.Input(
            deviceUid = "synthetic-watch", peerKey = "synthetic-peer", callsign = "WATCH",
            timeMillis = now, staleTimeMillis = now + 135_000, locationTimeMillis = now,
            latitude = 38.0, longitude = -77.0, hae = 100.0, ce = 5f, le = 7f, speed = null,
            detailXml = "<detail><contact callsign='WATCH'/></detail>",
        ))
        val written = mutableListOf<Map<String, Any?>>()
        var failWrite = true
        fun attempt(elapsed: Long) {
            if (!gate.due(sample, elapsed)) return
            if (failWrite) throw IOException("synthetic write failure")
            written.add(track)
            gate.published(sample, elapsed)
        }
        assertThrows(IOException::class.java) { attempt(1_000) }
        assertTrue(written.isEmpty())
        assertTrue(gate.due(sample, 1_001))
        failWrite = false
        attempt(1_001)
        assertFalse(gate.due(sample, 1_002))
        assertEquals("synthetic-watch", DittoCotDocument.decode(written.single()).uid)

        val expired = DittoTrackDocument.expire(written.single(), now + 1_000)
        val decoded = DittoCotDocument.decode(expired)
        assertEquals("synthetic-watch", decoded.uid)
        assertEquals("synthetic-peer", decoded.peer)
        assertEquals(now + 1_000, decoded.time)
        assertEquals(decoded.time, decoded.stale)
        assertEquals(now + 135_000, track["o"])
        assertTrue(gate.due(sample, 61_001))
    }

    private class FailOncePreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var failNext = false

        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }

                override fun remove(key: String?): SharedPreferences.Editor {
                    editor.remove(key)
                    return this
                }

                override fun commit(): Boolean {
                    val result = editor.commit()
                    if (failNext) {
                        failNext = false
                        return false
                    }
                    return result
                }
            }
        }
    }
}
