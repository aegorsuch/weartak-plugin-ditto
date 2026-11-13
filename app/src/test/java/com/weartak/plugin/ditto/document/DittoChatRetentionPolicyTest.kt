package com.weartak.plugin.ditto.document

import org.junit.Assert.*
import org.junit.Test

class DittoChatRetentionPolicyTest {
    private val day = DittoChatDocument.TTL_MILLIS

    @Test
    fun `startup delay matches persisted attempt and clock rollback rules`() {
        assertEquals(0L, DittoChatRetentionPolicy.initialDelayMillis(0, 1_000))
        assertEquals(day, DittoChatRetentionPolicy.initialDelayMillis(1_000, 1_000))
        assertEquals(1L, DittoChatRetentionPolicy.initialDelayMillis(1_000, 1_000 + day - 1))
        assertEquals(0L, DittoChatRetentionPolicy.initialDelayMillis(1_000, 1_000 + day))
        assertEquals(0L, DittoChatRetentionPolicy.initialDelayMillis(1_000, 1_001 + day))
        assertEquals(0L, DittoChatRetentionPolicy.initialDelayMillis(1_000, 999))
    }

    @Test
    fun `queries preserve core strict expiry and subscription boundaries`() {
        assertEquals(
            "o <= 1700000000000 || b <= 1699913600000",
            DittoChatRetentionPolicy.expiredQuery(1_700_000_000_000),
        )
        assertEquals(
            "SELECT * FROM chat WHERE (o IS MISSING OR o IS NULL OR o > 1700000000000) " +
                "AND b > 1699913600000",
            DittoChatRetentionPolicy.subscriptionQuery(1_700_000_000_000),
        )
        assertEquals("o <= 1 || b <= -86399999", DittoChatRetentionPolicy.expiredQuery(1))
    }

    @Test
    fun `invalid clocks and persisted cutoffs fail explicitly`() {
        assertThrows(IllegalArgumentException::class.java) {
            DittoChatRetentionPolicy.initialDelayMillis(-1, 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DittoChatRetentionPolicy.initialDelayMillis(0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DittoChatRetentionPolicy.expiredQuery(Long.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DittoChatRetentionPolicy.subscriptionQuery(0)
        }
    }
}
