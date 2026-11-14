package com.weartak.plugin.ditto.group

import com.weartak.plugin.ditto.document.DittoChatDocument
import com.weartak.plugin.ditto.document.DittoCotDocument
import com.weartak.plugin.ditto.document.DittoMapDocument
import com.weartak.plugin.ditto.document.DittoTrackDocument
import java.io.ByteArrayInputStream
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivateGroupsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val id = ByteArray(16) { (it + 1).toByte() }
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val wrappingKey = SecretKeySpec(ByteArray(32) { (it + 40).toByte() }, "AES")

    private class MemoryBlob : GroupBlobStore {
        var bytes: ByteArray? = null
        var fail = false
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(bytes: ByteArray) {
            if (fail) throw IllegalStateException("synthetic write failure")
            this.bytes = bytes.copyOf()
        }
    }

    private fun group(storage: MemoryBlob): PrivateGroups = PrivateGroups.open(storage, wrappingKey)

    private fun chatXml() =
        "<event version='2.0' uid='synthetic-event' type='b-t-f' how='h-g-i-g-o' " +
            "time='2023-11-14T22:13:20Z' start='2023-11-14T22:13:20Z' stale='2023-11-14T22:14:20Z'>" +
            "<point lat='38.0' lon='-77.0' hae='100.0' ce='5.0' le='7.0'/>" +
            "<detail><__chat id='team' chatroom='Team' senderCallsign='ALICE' messageId='message-1'>" +
            "<chatgrp id='team' uid0='alice' uid1='bob'/></__chat>" +
            "<remarks to='team'>synthetic private message</remarks></detail></event>"

    private fun trackInput() = DittoTrackDocument.Input(
        deviceUid = "watch", peerKey = "peer", callsign = "WATCH",
        timeMillis = 1_700_000_000_000L, staleTimeMillis = 1_700_000_060_000L,
        locationTimeMillis = 1_700_000_000_000L,
        latitude = 38.0, longitude = -77.0, hae = 100.0, ce = 5f, le = 7f, speed = null,
        detailXml = "<detail><contact callsign='WATCH'/></detail>",
    )

    private fun mapInput(collection: DittoMapDocument.Collection) = DittoMapDocument.Input(
        collection = collection,
        uid = "point", authorUid = "author", authorCallsign = "WATCH",
        eventCallsign = "POINT", peerKey = "peer", timeMillis = 1_700_000_000_000L,
        staleMillis = 1_700_000_060_000L,
        latitude = 38.0, longitude = -77.0, hae = 100.0, ce = 5.0, le = 7.0,
        how = "h-g-i-g-o", access = null, detailXml = "<detail/>",
        type = if (collection == DittoMapDocument.Collection.ALERT) "b-a-g" else "a-h-G",
    )

    @Test
    fun `generated private groups have distinct opaque ids and usable keys`() {
        val groups = group(MemoryBlob())
        val first = groups.createMembership("SpringLake")
        val second = groups.createMembership("SpringLake")
        assertNotEquals(first, second)
        groups.select(first)
        val payload = "synthetic".toByteArray()
        val encrypted = groups.encrypt(PrivateContentKind.MAP, payload)
        assertArrayEquals(payload, groups.decrypt(encrypted, PrivateContentKind.MAP))
    }

    @Test
    fun `encrypted membership and selection survive restart without fallback`() {
        val storage = MemoryBlob()
        var groups = group(storage)
        assertEquals(COMMUNITY, groups.selected())
        assertThrows(IllegalStateException::class.java) {
            groups.encrypt(PrivateContentKind.CHAT, "private".toByteArray())
        }
        groups.importMembership("SpringLake", id, key)
        val groupId = groups.options().last().id
        assertFalse(String(storage.bytes!!, Charsets.ISO_8859_1).contains("SpringLake"))
        groups.select(groupId)
        groups = group(storage)
        assertEquals(groupId, groups.selected())
        assertEquals("SpringLake (${groupId.take(6)})", groups.options().last().label)
        assertThrows(IllegalArgumentException::class.java) { groups.select("unknown") }
        assertEquals(groupId, groups.selected())
        assertThrows(IllegalArgumentException::class.java) {
            groups.importMembership("SpringLake", id, ByteArray(32) { 99 })
        }
        groups.select(COMMUNITY)
        assertThrows(IllegalStateException::class.java) {
            groups.encrypt(PrivateContentKind.CHAT, "private".toByteArray())
        }
    }

    @Test
    fun `encryption of synthetic chat is private and old document readers reject envelope`() {
        val sender = group(MemoryBlob())
        val receiver = group(MemoryBlob())
        sender.importMembership("SpringLake", id, key)
        receiver.importMembership("SpringLake", id, key)
        sender.select(sender.options().last().id)
        receiver.select(receiver.options().last().id)
        val native = DittoChatDocument.encode(chatXml(), "synthetic-peer")
        val plaintext = JSONObject(native).toString().toByteArray()
        val envelope = sender.encrypt(PrivateContentKind.CHAT, plaintext).toDocument()
        assertFalse(envelope.toString().contains("synthetic private message"))
        assertFalse(envelope.toString().contains("38.0"))
        assertThrows(IllegalArgumentException::class.java) { DittoChatDocument.decode(envelope) }
        assertThrows(IllegalArgumentException::class.java) { DittoCotDocument.decode(envelope) }
        val decrypted = receiver.decrypt(PrivateEnvelope.fromDocument(envelope), PrivateContentKind.CHAT)
        val json = JSONObject(String(decrypted))
        val decoded = DittoChatDocument.decode(json.keys().asSequence().associateWith { json.get(it) })
        assertTrue(decoded.xml.contains("synthetic private message"))
        assertNotEquals(
            envelope["nonce"],
            sender.encrypt(PrivateContentKind.CHAT, plaintext).toDocument()["nonce"],
        )
    }

    @Test
    fun `wrong key context group and tampering reject ciphertext`() {
        val sender = group(MemoryBlob())
        sender.importMembership("SpringLake", id, key)
        sender.select(sender.options().last().id)
        val envelope = sender.encrypt(PrivateContentKind.LOCATION, "synthetic location".toByteArray()).toDocument()
        val wrongKey = group(MemoryBlob())
        wrongKey.importMembership("SpringLake", id, ByteArray(32) { 42 })
        wrongKey.select(wrongKey.options().last().id)
        assertThrows(GeneralSecurityException::class.java) {
            wrongKey.decrypt(PrivateEnvelope.fromDocument(envelope), PrivateContentKind.LOCATION)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sender.decrypt(PrivateEnvelope.fromDocument(envelope), PrivateContentKind.MAP)
        }
        val edited = envelope.toMutableMap()
        edited["kind"] = "MAP"
        assertThrows(GeneralSecurityException::class.java) {
            sender.decrypt(PrivateEnvelope.fromDocument(edited), PrivateContentKind.MAP)
        }
        val senderEdited = envelope.toMutableMap()
        senderEdited["sender"] = "f".repeat(32)
        assertThrows(GeneralSecurityException::class.java) {
            sender.decrypt(PrivateEnvelope.fromDocument(senderEdited), PrivateContentKind.LOCATION)
        }
        val altered = envelope.toMutableMap()
        val cipher = java.util.Base64.getDecoder().decode(altered["ciphertext"] as String)
        cipher[0] = (cipher[0].toInt() xor 1).toByte()
        altered["ciphertext"] = java.util.Base64.getEncoder().encodeToString(cipher)
        assertThrows(GeneralSecurityException::class.java) {
            sender.decrypt(PrivateEnvelope.fromDocument(altered), PrivateContentKind.LOCATION)
        }
        val other = group(MemoryBlob())
        other.importMembership("Other", ByteArray(16) { 5 }, key)
        other.select(other.options().last().id)
        assertThrows(IllegalArgumentException::class.java) {
            other.decrypt(PrivateEnvelope.fromDocument(envelope), PrivateContentKind.LOCATION)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateEnvelope.fromDocument(envelope + ("extra" to "unsafe"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateEnvelope.fromDocument(envelope + ("nonce" to "invalid"))
        }
    }

    @Test
    fun `synthetic location and map documents round trip only inside private envelopes`() {
        val groups = group(MemoryBlob())
        groups.importMembership("SpringLake", id, key)
        groups.select(groups.options().last().id)
        val now = 1_700_000_000_000L
        val location = DittoTrackDocument.encode(DittoTrackDocument.Input(
            deviceUid = "watch", peerKey = "peer", callsign = "WATCH",
            timeMillis = now, staleTimeMillis = now + 60_000, locationTimeMillis = now,
            latitude = 38.0, longitude = -77.0, hae = 100.0, ce = 5f, le = 7f, speed = null,
            detailXml = "<detail><contact callsign='WATCH'/></detail>",
        ))
        val map = DittoMapDocument.encode(DittoMapDocument.Input(
            collection = DittoMapDocument.Collection.MAPITEM,
            uid = "point", authorUid = "author", authorCallsign = "WATCH",
            eventCallsign = "POINT", peerKey = "peer", timeMillis = now, staleMillis = now + 60_000,
            latitude = 38.0, longitude = -77.0, hae = 100.0, ce = 5.0, le = 7.0,
            how = "h-g-i-g-o", access = null, detailXml = "<detail/>", type = "a-h-G",
        ))
        for ((kind, native) in listOf(PrivateContentKind.LOCATION to location, PrivateContentKind.MAP to map)) {
            val encrypted = groups.encrypt(kind, JSONObject(native).toString().toByteArray()).toDocument()
            assertFalse(encrypted.toString().contains("38.0"))
            assertThrows(IllegalArgumentException::class.java) { DittoCotDocument.decode(encrypted) }
            val json = JSONObject(String(groups.decrypt(PrivateEnvelope.fromDocument(encrypted), kind)))
            val decrypted = json.keys().asSequence().associateWith { json.get(it) }
            assertEquals(native["_id"], DittoCotDocument.decode(decrypted).uid)
        }
    }

    @Test
    fun `atomic file stores only encrypted state and restores selection`() {
        val probe = AtomicGroupBlobStore(File(temporaryFolder.root, "probe"))
        probe.write(byteArrayOf(1))
        probe.write(byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), probe.read())
        val storage = AtomicGroupBlobStore(File(temporaryFolder.root, "groups"))
        var groups = PrivateGroups.open(storage, wrappingKey)
        groups.importMembership("SpringLake", id, key)
        val groupId = groups.options().last().id
        groups.select(groupId)
        assertFalse(String(storage.read()!!, Charsets.ISO_8859_1).contains("SpringLake"))
        groups = PrivateGroups.open(storage, wrappingKey)
        assertEquals(groupId, groups.selected())
    }

    @Test
    fun `write failure and corrupt state fail closed`() {
        val storage = MemoryBlob()
        val groups = group(storage)
        storage.fail = true
        assertThrows(IllegalStateException::class.java) { groups.importMembership("SpringLake", id, key) }
        assertThrows(IllegalStateException::class.java) { groups.selected() }
        assertThrows(IllegalStateException::class.java) {
            groups.encrypt(PrivateContentKind.CHAT, byteArrayOf(1))
        }
        storage.fail = false
        val wrongWrappingKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        assertThrows(GeneralSecurityException::class.java) { PrivateGroups.open(storage, wrongWrappingKey) }
        storage.bytes!![storage.bytes!!.lastIndex] = (storage.bytes!!.last().toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { group(storage) }
    }

    @Test
    fun `failed group switch persistence leaves selection unchanged`() {
        val storage = MemoryBlob()
        val groups = group(storage)
        groups.importMembership("SpringLake", id, key)
        val groupId = groups.options().last().id
        groups.select(groupId)
        storage.fail = true
        assertThrows(IllegalStateException::class.java) { groups.select(COMMUNITY) }
        assertThrows(IllegalStateException::class.java) { groups.selected() }
        assertThrows(IllegalStateException::class.java) {
            groups.encrypt(PrivateContentKind.MAP, byteArrayOf(1))
        }
        storage.fail = false
        assertEquals(groupId, group(storage).selected())
    }

    @Test
    fun `user controls create and import selected groups with bounded invitation`() {
        val groups = group(MemoryBlob())
        val controls = GroupControls(groups)
        val created = controls.create("Lake")
        assertEquals(created, controls.selected())
        val exported = controls.invitationForExport(created)
        assertThrows(IllegalArgumentException::class.java) { controls.invitationForExport("f".repeat(32)) }
        val other = GroupControls(group(MemoryBlob()))
        other.importInvitation("Lake", ByteArrayInputStream(exported))
        assertEquals(created, other.selected())
        assertArrayEquals(exported, other.invitationForExport(created))
        exported.fill(0)
        val invitation = "WTG1".toByteArray() + id + key
        controls.importInvitation("SpringLake", ByteArrayInputStream(invitation))
        val imported = controls.selected()
        assertNotEquals(created, imported)
        assertEquals("SpringLake (${imported.take(6)})", controls.options().last().label)
        assertThrows(IllegalArgumentException::class.java) {
            controls.importInvitation("Bad", ByteArrayInputStream(invitation.copyOf(51)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            controls.importInvitation("Bad", ByteArrayInputStream(invitation + byteArrayOf(0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            controls.importInvitation("Bad", ByteArrayInputStream("BAD1".toByteArray() + id + key))
        }
        assertThrows(IllegalArgumentException::class.java) {
            controls.importInvitation("SpringLake", ByteArrayInputStream("WTG1".toByteArray() + id + ByteArray(32) { 42 }))
        }
        assertEquals(imported, controls.selected())
    }

    @Test
    fun `chat publication boundary encrypts and rejects stale prepared output`() {
        val storage = MemoryBlob()
        val groups = group(storage)
        groups.importMembership("SpringLake", id, key, select = true)
        val privateId = groups.selected()
        val privateDocument = groups.release(groups.prepareChat(chatXml(), "synthetic-peer"))
        val released = groups.prepareChat(chatXml(), "synthetic-peer")
        groups.release(released)
        assertThrows(StalePublication::class.java) { groups.release(released) }
        val prepared = groups.prepareChat(chatXml(), "synthetic-peer")
        assertEquals("weartak-private-2", privateDocument["format"])
        assertFalse(privateDocument.toString().contains("synthetic private message"))
        assertThrows(IllegalArgumentException::class.java) { DittoChatDocument.decode(privateDocument) }
        groups.select(COMMUNITY)
        groups.select(privateId)
        assertThrows(StalePublication::class.java) { groups.release(prepared) }
        assertEquals(privateId, group(storage).selected())

        val failedStorage = MemoryBlob()
        val failed = group(failedStorage)
        failed.importMembership("SpringLake", id, key, select = true)
        failedStorage.fail = true
        assertThrows(IllegalStateException::class.java) { failed.encodeChat(chatXml(), "synthetic-peer") }
        assertThrows(IllegalStateException::class.java) { failed.encodeTrack(trackInput()) }
        assertThrows(IllegalStateException::class.java) {
            failed.encodeMapItem(mapInput(DittoMapDocument.Collection.MAPITEM))
        }
        assertThrows(IllegalStateException::class.java) { failed.selected() }

        val community = group(MemoryBlob())
        community.importMembership("SpringLake", id, key)
        assertEquals("message-1", community.encodeChat(chatXml(), "synthetic-peer")["_id"])
        community.select(community.options().last().id)
        assertEquals("weartak-private-2", community.encodeChat(chatXml(), "synthetic-peer")["format"])
    }

    @Test
    fun `track mapitem and alert boundaries encrypt without plaintext fallback`() {
        val storage = MemoryBlob()
        val groups = group(storage)
        groups.importMembership("SpringLake", id, key, select = true)

        val track = groups.release(groups.prepareTrack(trackInput()))
        val trackPrepared = groups.prepareTrack(trackInput())
        assertEquals("weartak-private-2", track["format"])
        assertFalse(track.toString().contains("38.0"))
        assertThrows(IllegalArgumentException::class.java) { DittoCotDocument.decode(track) }
        groups.select(COMMUNITY)
        val groupId = groups.options().last().id
        groups.select(groupId)
        assertThrows(StalePublication::class.java) { groups.release(trackPrepared) }

        for (collection in DittoMapDocument.Collection.entries) {
            val native = DittoMapDocument.encode(mapInput(collection))
            val prepared = when (collection) {
                DittoMapDocument.Collection.MAPITEM -> groups.prepareMapItem(mapInput(collection))
                DittoMapDocument.Collection.ALERT -> groups.prepareAlert(mapInput(collection))
            }
            val output = groups.release(prepared)
            assertEquals("weartak-private-2", output["format"])
            assertFalse(output.toString().contains("38.0"))
            assertThrows(IllegalArgumentException::class.java) { DittoCotDocument.decode(output) }
            val plaintext = groups.decrypt(PrivateEnvelope.fromDocument(output), PrivateContentKind.MAP)
            val json = JSONObject(String(plaintext))
            assertEquals(native["_id"], json.getString("_id"))
            plaintext.fill(0)
        }
        assertEquals("weartak-private-2", groups.encodeTrack(trackInput())["format"])
        assertEquals("weartak-private-2", groups.encodeAlert(mapInput(DittoMapDocument.Collection.ALERT))["format"])
    }

    @Test
    fun `sender envelope cap is persisted before output and survives restart`() {
        val storage = MemoryBlob()
        var groups = group(storage)
        groups.importMembership("SpringLake", id, key, select = true)
        val bytes = storage.bytes!!
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
        decrypt.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        decrypt.updateAAD("weartak-ditto-group-state-v1".toByteArray())
        val json = JSONObject(String(decrypt.doFinal(bytes.copyOfRange(13, bytes.size))))
        json.getJSONArray("groups").getJSONObject(0).put("sent", PrivateGroups.MAX_ENVELOPES_PER_SENDER - 1)
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
        encrypt.init(Cipher.ENCRYPT_MODE, wrappingKey)
        encrypt.updateAAD("weartak-ditto-group-state-v1".toByteArray())
        storage.bytes = byteArrayOf(1) + encrypt.iv + encrypt.doFinal(json.toString().toByteArray())
        groups = group(storage)
        groups.encrypt(PrivateContentKind.CHAT, byteArrayOf(1))
        assertThrows(IllegalStateException::class.java) {
            groups.encrypt(PrivateContentKind.CHAT, byteArrayOf(1))
        }
        val restarted = group(storage)
        assertThrows(IllegalStateException::class.java) {
            restarted.encrypt(PrivateContentKind.CHAT, byteArrayOf(1))
        }
    }

    @Test
    fun `group switches preserve separate sender counters`() {
        val storage = MemoryBlob()
        var groups = group(storage)
        groups.importMembership("First", id, key, select = true)
        val first = groups.selected()
        val secondBytes = ByteArray(16) { (it + 60).toByte() }
        val second = secondBytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        groups.importMembership("Second", secondBytes, ByteArray(32) { (it + 70).toByte() })

        val bytes = storage.bytes!!
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
        decrypt.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        decrypt.updateAAD("weartak-ditto-group-state-v1".toByteArray())
        val root = JSONObject(String(decrypt.doFinal(bytes.copyOfRange(13, bytes.size))))
        root.getJSONArray("groups").getJSONObject(0).put("sent", PrivateGroups.MAX_ENVELOPES_PER_SENDER)
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
        encrypt.init(Cipher.ENCRYPT_MODE, wrappingKey)
        encrypt.updateAAD("weartak-ditto-group-state-v1".toByteArray())
        storage.bytes = byteArrayOf(1) + encrypt.iv + encrypt.doFinal(root.toString().toByteArray())

        groups = group(storage)
        groups.select(second)
        assertTrue(groups.encrypt(PrivateContentKind.CHAT, byteArrayOf(1)).ciphertext.isNotEmpty())
        groups.select(first)
        assertThrows(IllegalStateException::class.java) {
            groups.encrypt(PrivateContentKind.CHAT, byteArrayOf(1))
        }
    }

    @Test
    fun `previous local state format migrates memberships and counters`() {
        val storage = MemoryBlob()
        val groups = group(storage)
        groups.importMembership("SpringLake", id, key, select = true)
        groups.encrypt(PrivateContentKind.CHAT, byteArrayOf(1))
        val bytes = storage.bytes!!
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
        decrypt.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        decrypt.updateAAD("weartak-ditto-group-state-v1".toByteArray())
        val root = JSONObject(String(decrypt.doFinal(bytes.copyOfRange(13, bytes.size))))
        root.put("version", 2).put("locked", true).remove("generation")
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
        encrypt.init(Cipher.ENCRYPT_MODE, wrappingKey)
        encrypt.updateAAD("weartak-ditto-group-state-v1".toByteArray())
        storage.bytes = byteArrayOf(1) + encrypt.iv + encrypt.doFinal(root.toString().toByteArray())

        val migrated = group(storage)
        assertEquals(groups.selected(), migrated.selected())
        migrated.select(COMMUNITY)
        migrated.select(groups.selected())
        migrated.encrypt(PrivateContentKind.CHAT, byteArrayOf(2))
        val updated = storage.bytes!!
        decrypt.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, updated.copyOfRange(1, 13)))
        decrypt.updateAAD("weartak-ditto-group-state-v1".toByteArray())
        val migratedRoot = JSONObject(String(decrypt.doFinal(updated.copyOfRange(13, updated.size))))
        assertEquals(3, migratedRoot.getInt("version"))
        assertEquals(2L, migratedRoot.getJSONArray("groups").getJSONObject(0).getLong("sent"))
    }
}
