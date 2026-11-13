package com.weartak.plugin.ditto.document

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DittoPointLifecycleTest {
    private fun preferences(): SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("synthetic-point-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    private fun document(
        at: Long = 1_700_000_000_000L,
        author: String = "alice",
        detail: String = "<detail><contact callsign='ALPHA'/></detail>",
    ): Map<String, Any?> = mapOf(
        "_id" to "point-1", "_v" to 2, "_r" to false, "a" to "remote-peer", "d" to author,
        "b" to at, "n" to at, "o" to at + 60_000L, "w" to "a-h-G",
        "g" to "2.0", "p" to "h-g-i-g-o", "q" to "Undefined",
        "j" to 38.0, "l" to -77.0, "i" to 100.0, "h" to 5.0, "k" to 7.0, "r" to detail,
    )

    private fun published(raw: Map<String, Any?>): Map<String, Any?> {
        val doc = DittoCotDocument.decode(raw)
        return raw + (DittoPointLedger.METADATA to mapOf(
            "version" to 1, "owner" to doc.author, "revision" to doc.fingerprint, "deleted" to false,
        ))
    }

    private fun tombstone(raw: Map<String, Any?>): Map<String, Any?> {
        val live = published(raw)
        val metadata = live.getValue(DittoPointLedger.METADATA) as Map<*, *>
        return live + mapOf(
            "_r" to true,
            DittoPointLedger.METADATA to metadata + mapOf("deleted" to true, "deletedAt" to raw.getValue("b")),
        )
    }

    @Test
    fun `pending publication and tombstone survive restart and retry`() {
        val prefs = preferences()
        val ledger = DittoPointLedger(prefs)
        ledger.created("point-1", "alice")
        assertThrows(IOException::class.java) {
            ledger.publish(document()) { throw IOException("synthetic write failure") }
        }
        assertTrue(ledger.hasPending())
        assertEquals(1L, ledger.generation)
        val restarted = DittoPointLedger(prefs)
        val writes = mutableListOf<Map<String, Any?>>()
        val errors = mutableListOf<Exception>()
        restarted.retry({ throw IOException("synthetic retry failure") }, errors::add)
        assertEquals(1, errors.size)
        assertTrue(restarted.hasPending())
        assertEquals(0L, restarted.generation)
        errors.clear()
        restarted.retry(writes::add, errors::add)
        assertTrue(errors.isEmpty())
        assertEquals(1, writes.size)
        assertFalse(restarted.hasPending())
        assertEquals(1L, restarted.generation)
        assertEquals(false, writes.single()["_r"])

        restarted.delete("point-1", "alice", 1_700_000_000_001L)
        assertTrue(restarted.hasPending())
        assertEquals(2L, restarted.generation)
        val afterDeletion = DittoPointLedger(prefs)
        assertTrue(afterDeletion.hasPending())
        afterDeletion.retry(writes::add, errors::add)
        assertTrue(errors.isEmpty())
        assertFalse(afterDeletion.hasPending())
        assertEquals(true, writes.last()["_r"])
        assertEquals(false, writes.last()["visible"])
        assertEquals(false, writes.last()["f"])
        val point = DittoPointIngress(preferences()).decode(writes.last())
        assertTrue(point.deleted)
        assertFalse(afterDeletion.publish(document()) { fail("Deleted point must not publish") })
        assertThrows(IllegalArgumentException::class.java) { afterDeletion.created("point-1", "alice") }
    }

    @Test
    fun `unpublished deletion creates no tombstone and ownership is enforced`() {
        val ledger = DittoPointLedger(preferences())
        ledger.created("point-1", "alice")
        assertThrows(IllegalArgumentException::class.java) { ledger.delete("point-1", "mallory", 1L) }
        assertThrows(IllegalArgumentException::class.java) { ledger.publish(document(author = "mallory")) {} }
        ledger.delete("point-1", "alice", 1L)
        assertFalse(ledger.hasPending())
        ledger.retry({ fail("Unpublished point must not write") }, { throw it })
        assertFalse(ledger.publish(document()) { fail("Deleted point must not publish") })
    }

    @Test
    fun `local revisions cannot roll back change origin or conflict at equal time`() {
        val ledger = DittoPointLedger(preferences())
        ledger.created("point-1", "alice")
        ledger.publish(document()) {}
        assertFalse(ledger.publish(document(at = 1_699_999_999_999L)) { fail("Old revision must not publish") })
        assertThrows(IllegalArgumentException::class.java) {
            ledger.publish(document(detail = "<detail><contact callsign='BRAVO'/></detail>")) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            ledger.publish(document(at = 1_700_000_000_001L) + ("a" to "other-peer")) {}
        }
        assertThrows(IllegalArgumentException::class.java) { ledger.publish(document() + ("w" to "a-f-G-U-C")) {} }
    }

    @Test
    fun `point watermarks reject rollback conflicts and resurrection across restart`() {
        val prefs = preferences()
        val ingress = DittoPointIngress(prefs)
        val live = published(document())
        assertTrue(ingress.accept(ingress.decode(live)))
        assertFalse(ingress.accept(ingress.decode(published(document(at = 1_699_999_999_999L)))))
        val conflict = published(document(detail = "<detail><contact callsign='BRAVO'/></detail>"))
        assertFalse(ingress.accept(ingress.decode(conflict)))
        assertThrows(IllegalArgumentException::class.java) {
            ingress.accept(ingress.decode(published(document(author = "mallory"))))
        }
        val removed = tombstone(document())
        assertTrue(ingress.accept(ingress.decode(removed)))
        val restarted = DittoPointIngress(prefs)
        assertTrue(restarted.deleted(DittoCotDocument.decode(document())))
        assertTrue(restarted.accept(restarted.decode(removed)))
        assertFalse(restarted.accept(restarted.decode(published(document(at = 1_700_000_000_002L)))))
        assertFalse(restarted.accept(restarted.decode(tombstone(document(at = 1_700_000_000_002L)))))
    }

    @Test
    fun `ingress rejects inconsistent or forged point metadata`() {
        val ingress = DittoPointIngress(preferences())
        val live = published(document())
        val metadata = live.getValue(DittoPointLedger.METADATA) as Map<*, *>
        val invalid = listOf(
            live - DittoPointLedger.METADATA,
            live + ("_r" to true),
            live + (DittoPointLedger.METADATA to metadata + ("owner" to "mallory")),
            live + (DittoPointLedger.METADATA to metadata + ("revision" to "wrong")),
            live + (DittoPointLedger.METADATA to metadata + ("version" to 2)),
            live + ("w" to "a-f-G-U-C"),
            tombstone(document()) + (DittoPointLedger.METADATA to metadata + mapOf("deleted" to true, "deletedAt" to 1L)),
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { ingress.decode(it) } }
    }

    @Test
    fun `failed persistence cannot acknowledge watermark or deletion and is repaired on retry`() {
        val ingressPrefs = FailOncePreferences(preferences())
        val ingress = DittoPointIngress(ingressPrefs)
        val point = ingress.decode(published(document()))
        ingressPrefs.failNext = true
        assertThrows(IllegalStateException::class.java) { ingress.accept(point) }
        assertTrue(ingress.accept(point))
        assertTrue(DittoPointIngress(ingressPrefs).accept(point))

        val localPrefs = FailOncePreferences(preferences())
        val ledger = DittoPointLedger(localPrefs)
        ledger.created("point-1", "alice")
        ledger.publish(document()) {}
        localPrefs.failNext = true
        assertThrows(IllegalStateException::class.java) { ledger.delete("point-1", "alice", 1L) }
        assertFalse(ledger.hasPending())
        ledger.delete("point-1", "alice", 1L)
        assertTrue(DittoPointLedger(localPrefs).hasPending())
    }

    @Test
    fun `invalid persistent state blocks startup without revealing data`() {
        val prefs = preferences()
        prefs.edit().putString("bad-key", "private-synthetic").commit()
        val error = assertThrows(IllegalStateException::class.java) { DittoPointIngress(prefs) }
        assertFalse(error.message.orEmpty().contains("private-synthetic"))
        assertThrows(IllegalStateException::class.java) { DittoPointLedger(prefs) }

        val wrongType = preferences()
        wrongType.edit().putInt("bad-key", 1).commit()
        assertThrows(IllegalStateException::class.java) { DittoPointIngress(wrongType) }

        val local = preferences()
        val ledger = DittoPointLedger(local)
        ledger.created("point-1", "alice")
        ledger.publish(document()) {}
        val localKey = local.all.keys.single()
        val corruptLocal = JSONObject(local.getString(localKey, null)!!).put("owner", "mallory")
        local.edit().putString(localKey, corruptLocal.toString()).commit()
        assertThrows(IllegalStateException::class.java) { DittoPointLedger(local) }

        val incoming = preferences()
        val ingress = DittoPointIngress(incoming)
        ingress.accept(ingress.decode(published(document())))
        val incomingKey = incoming.all.keys.single()
        val corruptWatermark = JSONObject(incoming.getString(incomingKey, null)!!).put("revision", "invalid")
        incoming.edit().putString(incomingKey, corruptWatermark.toString()).commit()
        assertThrows(IllegalStateException::class.java) { DittoPointIngress(incoming) }
    }

    @Test
    fun `local identity and persisted owner reject sensitive enrollment text`() {
        val sensitive = "#weartakcommand enrollserver placeholder-secret"
        val local = preferences()
        val ledger = DittoPointLedger(local)
        assertThrows(IllegalArgumentException::class.java) { ledger.created(sensitive, "alice") }
        assertThrows(IllegalArgumentException::class.java) { ledger.created("point-1", sensitive) }
        ledger.created("point-1", "alice")
        val key = local.all.keys.single()
        val corrupt = JSONObject(local.getString(key, null)!!).put("owner", sensitive)
        local.edit().putString(key, corrupt.toString()).commit()
        val error = assertThrows(IllegalStateException::class.java) { DittoPointLedger(local) }
        assertFalse(error.message.orEmpty().contains("placeholder-secret"))
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
