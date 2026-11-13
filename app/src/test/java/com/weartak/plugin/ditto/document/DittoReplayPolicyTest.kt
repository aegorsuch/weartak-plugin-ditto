package com.weartak.plugin.ditto.document

import org.junit.Assert.*
import org.junit.Test

class DittoReplayPolicyTest {
    private val hash = "a".repeat(64)
    private val otherHash = "b".repeat(64)
    private val previous = DittoReplayPolicy.Watermark(1_000, 2_000, hash)

    private fun eligible(
        time: Long = 1_000,
        fingerprint: String = hash,
        sensitive: Boolean = false,
        chat: Boolean = false,
        expired: Boolean = false,
        prior: DittoReplayPolicy.Watermark? = previous,
        seen: Boolean? = null,
    ) = DittoReplayPolicy.shouldIngest(time, fingerprint, sensitive, chat, expired, prior, seen)

    @Test
    fun `older and equal conflicting revisions are suppressed while newer revisions pass`() {
        assertTrue(eligible(prior = null))
        assertFalse(eligible(time = 999))
        assertFalse(eligible(fingerprint = otherHash))
        assertTrue(eligible(time = 1_001, fingerprint = otherHash))
    }

    @Test
    fun `nonchat can rehydrate after restart and transition between live and expired once`() {
        assertTrue(eligible(seen = null))
        assertFalse(eligible(seen = false))
        assertTrue(eligible(expired = true, seen = false))
        assertFalse(eligible(expired = true, seen = true))
        assertTrue(eligible(expired = false, seen = true))
    }

    @Test
    fun `chat never replays equal timestamp or ingests expired state`() {
        assertFalse(eligible(chat = true, seen = null))
        assertFalse(eligible(chat = true, seen = false))
        assertFalse(eligible(chat = true, expired = true, prior = null))
        assertTrue(eligible(time = 1_001, chat = true))
    }

    @Test
    fun `sensitive equal timestamp is suppressed with empty stored fingerprint`() {
        val sensitive = DittoReplayPolicy.Watermark(1_000, 2_000, "")
        assertFalse(eligible(fingerprint = "", sensitive = true, prior = sensitive))
        assertTrue(eligible(time = 1_001, fingerprint = "", sensitive = true, prior = sensitive))
    }

    @Test
    fun `parse journal values and reject corrupt entries without echoing them`() {
        assertEquals(previous, DittoReplayPolicy.parseWatermark("1000:2000:$hash"))
        assertEquals("", DittoReplayPolicy.parseWatermark("1000:2000:").fingerprint)
        val malformed = listOf(
            "", "1000:2000", "0:2000:$hash", "1000:999:$hash", "1000:2000:bad",
            "1000:2000:${hash.uppercase()}", "1000:253402300800000:$hash",
            "secret:2000:$hash", "1000:2000:$hash:extra", "0".repeat(1_000),
        )
        malformed.forEach { raw ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                DittoReplayPolicy.parseWatermark(raw)
            }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
        assertFalse(previous.toString().contains(hash))
    }

    @Test
    fun `invalid caller state fails closed`() {
        assertThrows(IllegalArgumentException::class.java) { eligible(time = 0) }
        assertThrows(IllegalArgumentException::class.java) { eligible(fingerprint = "") }
        assertThrows(IllegalArgumentException::class.java) { eligible(sensitive = true) }
        assertThrows(IllegalArgumentException::class.java) { eligible(prior = null, seen = false) }
        assertThrows(IllegalArgumentException::class.java) {
            eligible(prior = DittoReplayPolicy.Watermark(1_000, 999, hash))
        }
    }
}
