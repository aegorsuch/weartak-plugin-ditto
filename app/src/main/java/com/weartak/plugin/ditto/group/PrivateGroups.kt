// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.ditto.group

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.weartak.plugin.ditto.document.DittoReplayPolicy
import com.weartak.plugin.ditto.document.DittoMapDocument
import com.weartak.plugin.ditto.document.DittoTrackDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

internal interface GroupBlobStore {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

internal class AtomicGroupBlobStore(file: File) : GroupBlobStore {
    private val base = file
    private val pending = File(file.path + ".new")

    override fun read(): ByteArray? {
        if (!base.exists()) return null
        return FileInputStream(base).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(out.size() + count <= MAX_STATE_BYTES) { "Group state too large" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    override fun write(bytes: ByteArray) {
        require(bytes.size <= MAX_STATE_BYTES)
        try {
            FileOutputStream(pending).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Files.move(
                pending.toPath(), base.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(pending.toPath())
        }
        val saved = read()
        check(saved != null && MessageDigest.isEqual(saved, bytes)) { "Could not verify group state" }
    }
}

private const val MAX_STATE_BYTES = 2 * 1024 * 1024
private const val MAX_GROUPS = 20
private const val MAX_REPLAY_RECORDS = 8192
internal const val COMMUNITY = "community"
private val groupIdPattern = Regex("[0-9a-f]{32}")
private val replayKeyPattern = Regex("[0-9a-f]{64}")
private val b64 = Base64.getEncoder()
private val b64Decoder = Base64.getDecoder()

internal data class GroupOption(val id: String, val label: String)

internal class StalePublication : IllegalStateException("Prepared output belongs to a previous group selection")
internal class StaleReplayScope : IllegalStateException("Replay scope belongs to a previous group selection")

internal class ReplayScope internal constructor(
    internal val groupId: String,
    internal val generation: Long,
    internal val owner: Any,
) {
    override fun toString(): String = "ReplayScope(redacted)"
}

internal class ReplayAdmission internal constructor(
    internal val scope: ReplayScope,
    internal val key: String,
    internal val watermark: DittoReplayPolicy.Watermark,
    internal val expired: Boolean,
    internal val previous: DittoReplayPolicy.Watermark?,
) {
    private var recorded = false

    @Synchronized
    internal fun claim(): Boolean {
        if (recorded) return false
        recorded = true
        return true
    }

    override fun toString(): String = "ReplayAdmission(redacted)"
}

internal class PreparedPublication internal constructor(
    internal val generation: Long,
    internal val scope: String,
    private val payload: Map<String, Any?>,
) {
    private var released = false

    @Synchronized
    internal fun claim(): Boolean {
        if (released) return false
        released = true
        return true
    }

    internal fun document(): Map<String, Any?> = payload.toMap()

    override fun toString(): String = "PreparedPublication(redacted)"
}

/** One store instance must exclusively own its backing file. */
internal class PrivateGroups private constructor(
    private val storage: GroupBlobStore,
    private val wrappingKey: SecretKey,
    encryptedState: ByteArray?,
) {
    private class Membership(
        val name: String, val id: String, val key: ByteArray, val sender: String, val sent: Long,
    )
    private class State(
        val selected: String,
        val groups: List<Membership>,
        val generation: Long,
        val writes: Long,
        val replay: Map<String, Map<String, ReplayRecord>> = emptyMap(),
    )
    private class ReplayRecord(val watermark: DittoReplayPolicy.Watermark)

    private var state: State
    private var failed = false
    private val replayOwner = Any()
    private val replaySeen = mutableMapOf<String, MutableMap<String, Boolean>>()

    init {
        state = if (encryptedState == null) {
            persist(State(COMMUNITY, emptyList(), 0, 0))
        } else {
            val (loaded, needsMigration) = decodeState(openState(encryptedState))
            if (needsMigration) persist(loaded) else loaded
        }
    }

    @Synchronized
    fun selected(): String {
        checkHealthy()
        return state.selected
    }

    @Synchronized
    fun replayScope(): ReplayScope {
        checkHealthy()
        return ReplayScope(state.selected, state.generation, replayOwner)
    }

    /** Prepare a replay decision; caller must record success only after local ingress succeeds. */
    @Synchronized
    fun prepareReplay(
        scope: ReplayScope,
        key: String,
        time: Long,
        retainUntil: Long,
        fingerprint: String,
        sensitive: Boolean,
        chat: Boolean,
        expired: Boolean,
    ): ReplayAdmission? {
        checkHealthy()
        checkReplayScope(scope)
        require(replayKeyPattern.matches(key)) { "Invalid replay key" }
        val previous = state.replay[scope.groupId]?.get(key)
        val watermark = DittoReplayPolicy.Watermark(
            time = time,
            retainUntil = maxOf(retainUntil, previous?.watermark?.retainUntil ?: retainUntil),
            fingerprint = fingerprint,
        )
        DittoReplayPolicy.parseWatermark(
            "${watermark.time}:${watermark.retainUntil}:${watermark.fingerprint}",
        )
        val seenExpired = previous?.let { replaySeen[scope.groupId]?.get(key) }
        if (!DittoReplayPolicy.shouldIngest(
                time, fingerprint, sensitive, chat, expired, previous?.watermark, seenExpired,
            )
        ) return null
        return ReplayAdmission(scope, key, watermark, expired, previous?.watermark)
    }

    /** Call only after corresponding local ingress has completed successfully. */
    @Synchronized
    fun recordIngressSuccess(admission: ReplayAdmission, nowMillis: Long) {
        checkHealthy()
        checkReplayScope(admission.scope)
        require(nowMillis in 1..253402300799999L) { "Invalid replay journal time" }
        DittoReplayPolicy.parseWatermark(
            "${admission.watermark.time}:${admission.watermark.retainUntil}:" +
                admission.watermark.fingerprint,
        )
        check(state.replay[admission.scope.groupId]?.get(admission.key)?.watermark == admission.previous) {
            "Replay admission superseded"
        }

        var records = state.replay
        val existing = records[admission.scope.groupId]?.containsKey(admission.key) == true
        if (!existing && records.values.sumOf { it.size } >= MAX_REPLAY_RECORDS) {
            records = records.mapValues { (_, entries) ->
                entries.filterValues { it.watermark.retainUntil > nowMillis }
            }.filterValues { it.isNotEmpty() }
            check(records.values.sumOf { it.size } < MAX_REPLAY_RECORDS) {
                "Ditto replay journal full"
            }
        }

        val groupRecords = records[admission.scope.groupId].orEmpty().toMutableMap()
        groupRecords[admission.key] = ReplayRecord(admission.watermark)
        records = records.toMutableMap().apply { put(admission.scope.groupId, groupRecords) }
        check(admission.claim()) { "Replay admission already recorded" }
        update(State(
            state.selected,
            state.groups,
            state.generation,
            state.writes,
            records,
        ))
        replaySeen.entries.toList().forEach { (groupId, seen) ->
            seen.keys.retainAll(records[groupId]?.keys.orEmpty())
            if (seen.isEmpty()) replaySeen.remove(groupId)
        }
        replaySeen.getOrPut(admission.scope.groupId) { mutableMapOf() }[admission.key] = admission.expired
    }

    @Synchronized
    fun options(): List<GroupOption> {
        checkHealthy()
        return listOf(GroupOption(COMMUNITY, "Community")) +
            state.groups.map { GroupOption(it.id, "${it.name} (${it.id.take(6)})") }
    }

    @Synchronized
    fun createMembership(name: String, select: Boolean = false): String {
        checkHealthy()
        val random = SecureRandom()
        val id = ByteArray(16)
        val key = ByteArray(32)
        do {
            random.nextBytes(id)
        } while (id.all { it == 0.toByte() })
        do {
            random.nextBytes(key)
        } while (key.all { it == 0.toByte() })
        try {
            importMembership(name, id, key, select)
            return id.joinToString("") { "%02x".format(it.toInt() and 255) }
        } finally {
            key.fill(0)
        }
    }

    /** Credential bytes must come from an independently trusted, user-approved import channel. */
    @Synchronized
    fun importMembership(name: String, id: ByteArray, key: ByteArray, select: Boolean = false) {
        checkHealthy()
        require(validName(name) && id.size == 16 && id.any { it != 0.toByte() } &&
            key.size == 32 && key.any { it != 0.toByte() }) { "Invalid group invitation" }
        val idText = id.joinToString("") { "%02x".format(it.toInt() and 255) }
        val existing = state.groups.firstOrNull { it.id == idText }
        if (existing != null) {
            require(existing.name == name && MessageDigest.isEqual(existing.key, key)) {
                "Conflicting group invitation"
            }
            if (select) select(idText)
            return
        }
        if (select && idText != state.selected) checkCanAdvanceGeneration()
        require(state.groups.size < MAX_GROUPS) { "Group limit reached" }
        val sender = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val group = Membership(name, idText, key.copyOf(), sender, 0)
        val selected = if (select) idText else state.selected
        val generation = if (select && selected != state.selected) state.generation + 1 else state.generation
        val selectionChanged = selected != state.selected
        update(State(selected, state.groups + group, generation, state.writes, state.replay))
        if (selectionChanged) replaySeen.clear()
    }

    @Synchronized
    fun select(id: String) {
        checkHealthy()
        require(id == COMMUNITY || state.groups.any { it.id == id }) { "Unknown group" }
        if (state.selected != id) {
            checkCanAdvanceGeneration()
            update(State(id, state.groups, state.generation + 1, state.writes, state.replay))
            replaySeen.clear()
        }
    }

    @Synchronized
    fun invitationForExport(expectedGroupId: String): ByteArray {
        checkHealthy()
        val group = selectedMembership()
        require(group.id == expectedGroupId) { "Group selection changed" }
        val id = ByteArray(16) { i -> group.id.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return "WTG1".toByteArray(Charsets.US_ASCII) + id + group.key
    }

    /** This API never emits community plaintext. */
    @Synchronized
    fun encrypt(kind: PrivateContentKind, plaintext: ByteArray): PrivateEnvelope {
        checkHealthy()
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_PRIVATE_PAYLOAD_BYTES) { "Invalid private payload" }
        val group = selectedMembership()
        check(group.sent < MAX_ENVELOPES_PER_SENDER) { "Private group sending limit reached" }
        val updated = Membership(group.name, group.id, group.key, group.sender, group.sent + 1)
        update(State(
            state.selected,
            state.groups.map { if (it.id == group.id) updated else it },
            state.generation,
            state.writes,
            state.replay,
        ))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, senderKey(group.key, group.id, group.sender))
        cipher.updateAAD(aad(group.id, group.sender, kind))
        val encrypted = cipher.doFinal(plaintext)
        return PrivateEnvelope(group.id, group.sender, kind, cipher.iv, encrypted)
    }

    @Synchronized
    fun decrypt(envelope: PrivateEnvelope, expectedKind: PrivateContentKind): ByteArray {
        checkHealthy()
        val group = selectedMembership()
        require(envelope.groupId == group.id && envelope.kind == expectedKind) { "Wrong private group or content kind" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, senderKey(group.key, group.id, envelope.sender), GCMParameterSpec(128, envelope.nonce))
        cipher.updateAAD(aad(group.id, envelope.sender, expectedKind))
        val plaintext = cipher.doFinal(envelope.ciphertext)
        return plaintext
    }

    @Synchronized
    fun prepareChat(xml: String, peer: String): PreparedPublication =
        prepare(PrivateContentKind.CHAT, com.weartak.plugin.ditto.document.DittoChatDocument.encode(xml, peer))

    @Synchronized
    fun prepareTrack(input: DittoTrackDocument.Input): PreparedPublication =
        prepare(PrivateContentKind.LOCATION, DittoTrackDocument.encode(input))

    @Synchronized
    fun prepareMapItem(input: DittoMapDocument.Input): PreparedPublication {
        require(input.collection == DittoMapDocument.Collection.MAPITEM) { "Expected mapitem input" }
        return prepare(PrivateContentKind.MAP, DittoMapDocument.encode(input))
    }

    @Synchronized
    fun prepareAlert(input: DittoMapDocument.Input): PreparedPublication {
        require(input.collection == DittoMapDocument.Collection.ALERT) { "Expected alert input" }
        return prepare(PrivateContentKind.MAP, DittoMapDocument.encode(input))
    }

    @Synchronized
    fun release(prepared: PreparedPublication): Map<String, Any?> {
        checkHealthy()
        if (!prepared.claim() || prepared.scope != state.selected || prepared.generation != state.generation) {
            throw StalePublication()
        }
        return prepared.document()
    }

    @Synchronized
    fun encodeChat(xml: String, peer: String): Map<String, Any?> = release(prepareChat(xml, peer))

    @Synchronized
    fun encodeTrack(input: DittoTrackDocument.Input): Map<String, Any?> = release(prepareTrack(input))

    @Synchronized
    fun encodeMapItem(input: DittoMapDocument.Input): Map<String, Any?> = release(prepareMapItem(input))

    @Synchronized
    fun encodeAlert(input: DittoMapDocument.Input): Map<String, Any?> = release(prepareAlert(input))

    private fun prepare(kind: PrivateContentKind, document: Map<String, Any?>): PreparedPublication {
        checkHealthy()
        val payload = if (state.selected == COMMUNITY) {
            document
        } else {
            val plaintext = JSONObject(document).toString().toByteArray(Charsets.UTF_8)
            try {
                encrypt(kind, plaintext).toDocument()
            } finally {
                plaintext.fill(0)
            }
        }
        return PreparedPublication(state.generation, state.selected, payload)
    }

    private fun checkCanAdvanceGeneration() {
        check(state.generation < Long.MAX_VALUE) { "Group selection generation exhausted" }
    }

    private fun selectedMembership(): Membership =
        state.groups.firstOrNull { it.id == state.selected }
            ?: throw IllegalStateException("Private group not selected")

    private fun update(next: State) {
        state = persist(next)
    }

    private fun persist(value: State): State {
        try {
            check(value.writes < MAX_STORAGE_WRITES) { "Private group storage limit reached" }
            check(value.replay.values.sumOf { it.size } <= MAX_REPLAY_RECORDS) {
                "Ditto replay journal full"
            }
            val next = State(
                value.selected,
                value.groups,
                value.generation,
                value.writes + 1,
                value.replay,
            )
            val groups = JSONArray()
            next.groups.forEach {
                groups.put(JSONObject().put("name", it.name).put("id", it.id)
                    .put("key", b64.encodeToString(it.key)).put("sender", it.sender).put("sent", it.sent))
            }
            val replay = JSONArray()
            next.replay.toSortedMap().forEach { (groupId, entries) ->
                entries.toSortedMap().forEach { (key, record) ->
                    replay.put(JSONObject()
                        .put("group", groupId)
                        .put("key", key)
                        .put("time", record.watermark.time)
                        .put("retainUntil", record.watermark.retainUntil)
                        .put("fingerprint", record.watermark.fingerprint))
                }
            }
            val json = JSONObject().put("version", 4).put("selected", next.selected).put("groups", groups)
                .put("generation", next.generation).put("writes", next.writes)
                .put("replay", replay)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            cipher.updateAAD(STATE_AAD)
            val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            check(cipher.iv.size == 12) { "Invalid storage nonce" }
            check(encrypted.size + 13 <= MAX_STATE_BYTES) { "Group state too large" }
            storage.write(byteArrayOf(1) + cipher.iv + encrypted)
            return next
        } catch (_: Exception) {
            failed = true
            throw IllegalStateException("Could not persist private group state")
        }
    }

    private fun openState(bytes: ByteArray): ByteArray {
        require(bytes.size in 29..MAX_STATE_BYTES && bytes[0] == 1.toByte()) { "Invalid group state" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(STATE_AAD)
        return cipher.doFinal(bytes.copyOfRange(13, bytes.size))
    }

    private fun decodeState(bytes: ByteArray): Pair<State, Boolean> {
        try {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            val version = root.getInt("version")
            require(
                (version == 2 &&
                    root.keys().asSequence().toSet() == setOf("version", "selected", "groups", "locked", "writes")) ||
                    (version == 3 &&
                        root.keys().asSequence().toSet() == setOf("version", "selected", "groups", "generation", "writes")) ||
                    (version == 4 &&
                        root.keys().asSequence().toSet() == setOf(
                            "version", "selected", "groups", "generation", "writes", "replay",
                        )),
            )
            val selected = root.getString("selected")
            val generation = if (version == 2) 0 else root.getLong("generation")
            val writes = root.getLong("writes")
            require(generation >= 0 && writes in 1..MAX_STORAGE_WRITES)
            val array = root.getJSONArray("groups")
            require(array.length() <= MAX_GROUPS)
            val groups = (0 until array.length()).map {
                val item = array.getJSONObject(it)
                require(item.keys().asSequence().toSet() == setOf("name", "id", "key", "sender", "sent"))
                val name = item.getString("name")
                val id = item.getString("id")
                val key = b64Decoder.decode(item.getString("key"))
                val sender = item.getString("sender")
                val sent = item.getLong("sent")
                require(validName(name) && groupIdPattern.matches(id) && id.any { c -> c != '0' } &&
                    key.size == 32 && key.any { byte -> byte != 0.toByte() } &&
                    groupIdPattern.matches(sender) && sent in 0..MAX_ENVELOPES_PER_SENDER)
                Membership(name, id, key, sender, sent)
            }
            require(groups.map { it.id }.distinct().size == groups.size)
            require(selected == COMMUNITY || groups.any { it.id == selected })
            val replay = mutableMapOf<String, MutableMap<String, ReplayRecord>>()
            if (version == 4) {
                val replayArray = root.getJSONArray("replay")
                require(replayArray.length() <= MAX_REPLAY_RECORDS)
                repeat(replayArray.length()) { index ->
                    val item = replayArray.getJSONObject(index)
                    require(item.keys().asSequence().toSet() ==
                        setOf("group", "key", "time", "retainUntil", "fingerprint"))
                    val groupId = item.getString("group")
                    val key = item.getString("key")
                    require((groupId == COMMUNITY || groups.any { it.id == groupId }) &&
                        replayKeyPattern.matches(key))
                    val watermark = DittoReplayPolicy.parseWatermark(
                        "${item.getLong("time")}:${item.getLong("retainUntil")}:" +
                            item.getString("fingerprint"),
                    )
                    val groupRecords = replay.getOrPut(groupId) { mutableMapOf() }
                    require(groupRecords.put(key, ReplayRecord(watermark)) == null)
                }
            }
            return State(selected, groups, generation, writes, replay) to (version < 4)
        } catch (_: Exception) {
            throw IllegalStateException("Invalid private group state")
        } finally {
            bytes.fill(0)
        }
    }

    private fun checkHealthy() {
        check(!failed) { "Private group storage unavailable" }
    }

    private fun checkReplayScope(scope: ReplayScope) {
        if (scope.owner !== replayOwner ||
            scope.groupId != state.selected || scope.generation != state.generation
        ) {
            throw StaleReplayScope()
        }
    }

    companion object {
        private val STATE_AAD = "weartak-ditto-group-state-v1".toByteArray(Charsets.UTF_8)
        private const val KEY_ALIAS = "weartak-ditto-group-wrap-v1"
        private const val MAX_STORAGE_WRITES = 1_000_000L
        internal const val MAX_ENVELOPES_PER_SENDER = 100_000L
        private var applicationInstance: PrivateGroups? = null

        @Synchronized
        fun forApp(context: Context): PrivateGroups =
            applicationInstance ?: open(context.applicationContext).also { applicationInstance = it }

        fun open(context: Context): PrivateGroups {
            val storage = AtomicGroupBlobStore(File(context.noBackupFilesDir, "ditto-private-groups.bin"))
            val encrypted = storage.read()
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val hasKey = keyStore.containsAlias(KEY_ALIAS)
            check((encrypted == null) == !hasKey) { "Private group storage or key missing" }
            val existing = if (hasKey) {
                keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                    ?: throw IllegalStateException("Invalid private group storage key")
            } else null
            val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
                generateKey()
            }
            return PrivateGroups(storage, key, encrypted)
        }

        internal fun open(storage: GroupBlobStore, key: SecretKey): PrivateGroups =
            PrivateGroups(storage, key, storage.read())

        internal fun validName(name: String): Boolean =
            name.isNotBlank() && name.length <= 48 && name == name.trim() &&
                name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in " ._-" }
    }
}

internal enum class PrivateContentKind { CHAT, LOCATION, MAP }
internal const val MAX_PRIVATE_PAYLOAD_BYTES = 256 * 1024

internal class PrivateEnvelope private constructor(
    val groupId: String,
    val sender: String,
    val kind: PrivateContentKind,
    nonce: ByteArray,
    ciphertext: ByteArray,
) {
    internal val nonce = nonce.copyOf()
    internal val ciphertext = ciphertext.copyOf()

    override fun toString(): String = "PrivateEnvelope(redacted)"

    fun toDocument(): Map<String, Any> = mapOf(
        "format" to "weartak-private-2", "group" to groupId, "sender" to sender, "kind" to kind.name,
        "nonce" to b64.encodeToString(nonce), "ciphertext" to b64.encodeToString(ciphertext),
    )

    companion object {
        internal operator fun invoke(
            groupId: String,
            sender: String,
            kind: PrivateContentKind,
            nonce: ByteArray,
            ciphertext: ByteArray,
        ): PrivateEnvelope {
            require(groupIdPattern.matches(groupId) && groupIdPattern.matches(sender) && nonce.size == 12 &&
                ciphertext.size in 17..MAX_PRIVATE_PAYLOAD_BYTES + 16) { "Invalid private envelope" }
            return PrivateEnvelope(groupId, sender, kind, nonce, ciphertext)
        }

        fun fromDocument(document: Map<String, Any?>): PrivateEnvelope {
            require(document.keys == setOf("format", "group", "sender", "kind", "nonce", "ciphertext") &&
                document["format"] == "weartak-private-2") { "Invalid private envelope" }
            val id = document["group"] as? String ?: throw IllegalArgumentException("Invalid private envelope")
            val sender = document["sender"] as? String ?: throw IllegalArgumentException("Invalid private envelope")
            val kindName = document["kind"] as? String ?: throw IllegalArgumentException("Invalid private envelope")
            val kind = PrivateContentKind.entries.firstOrNull { it.name == kindName }
                ?: throw IllegalArgumentException("Invalid private envelope")
            val nonce = document["nonce"] as? String ?: throw IllegalArgumentException("Invalid private envelope")
            val ciphertext = document["ciphertext"] as? String ?: throw IllegalArgumentException("Invalid private envelope")
            require(nonce.length == 16 && ciphertext.length <= ((MAX_PRIVATE_PAYLOAD_BYTES + 18) / 3) * 4) {
                "Invalid private envelope"
            }
            return PrivateEnvelope(id, sender, kind, b64Decoder.decode(nonce), b64Decoder.decode(ciphertext))
        }
    }
}

private fun aad(id: String, sender: String, kind: PrivateContentKind): ByteArray =
    "weartak-ditto-private-v2:$id:$sender:${kind.name}".toByteArray(Charsets.UTF_8)

private fun senderKey(groupKey: ByteArray, groupId: String, sender: String): SecretKeySpec {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec("weartak-ditto-hkdf-v2".toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val prk = mac.doFinal(groupKey)
    try {
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val info = "sender:$groupId:$sender".toByteArray(Charsets.UTF_8)
        val derived = mac.doFinal(info + byteArrayOf(1))
        return SecretKeySpec(derived, "AES").also { derived.fill(0) }
    } finally {
        prk.fill(0)
    }
}
