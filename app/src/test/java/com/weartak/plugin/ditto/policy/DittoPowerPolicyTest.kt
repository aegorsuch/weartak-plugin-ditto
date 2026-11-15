// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoPowerPolicyTest.kt at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.policy

import org.junit.Assert.*
import org.junit.Test

class DittoPowerPolicyTest {
    private val policy = DittoPowerPolicy()
    private val healthy = DittoPowerState(
        worn = true,
        charging = false,
        battery = 80,
        lowBatteryThreshold = 20,
        sleeping = false,
        powerSave = false,
        alerting = false,
        stayConnectedOffWrist = false,
    )

    @Test
    fun `power and wrist gates recover and retain core priority`() {
        val offWrist = healthy.copy(worn = false)
        assertEquals(DittoPowerSuppression.OFF_WRIST, policy.suppressionReason(offWrist, 0))
        assertNull(policy.suppressionReason(offWrist.copy(stayConnectedOffWrist = true), 0))
        for (blocked in listOf(
            offWrist.copy(battery = 20),
            offWrist.copy(sleeping = true),
            offWrist.copy(powerSave = true),
        )) {
            assertEquals(DittoPowerSuppression.POWER, policy.suppressionReason(blocked, 0))
            assertEquals(
                DittoPowerSuppression.POWER,
                policy.suppressionReason(blocked.copy(stayConnectedOffWrist = true), 0),
            )
            assertNull(policy.suppressionReason(blocked.copy(charging = true), 0))
            assertNull(policy.suppressionReason(blocked.copy(alerting = true), 0))
        }
        assertNull(policy.suppressionReason(healthy.copy(battery = 21), 0))
        assertEquals(
            DittoPowerSuppression.POWER,
            policy.suppressionReason(healthy.copy(battery = 35, lowBatteryThreshold = 35), 0),
        )
        assertNull(policy.suppressionReason(healthy, 0))
    }

    @Test
    fun `unknown wrist and battery alone do not suppress as in core`() {
        assertNull(policy.suppressionReason(healthy.copy(worn = null, battery = null), 0))
        assertEquals(
            DittoPowerSuppression.POWER,
            policy.suppressionReason(healthy.copy(worn = null, battery = null, powerSave = true), 0),
        )
    }

    @Test
    fun `alert grace bypasses suppression until exact expiry and extends on another alert`() {
        val blocked = healthy.copy(worn = false, battery = 1, sleeping = true, powerSave = true)
        assertEquals(DittoPowerSuppression.POWER, policy.suppressionReason(blocked, 1_000))
        policy.resumeForAlert(1_000)
        assertNull(policy.suppressionReason(blocked, 60_999))
        policy.resumeForAlert(60_999)
        assertNull(policy.suppressionReason(blocked, 120_998))
        assertEquals(DittoPowerSuppression.POWER, policy.suppressionReason(blocked, 120_999))
    }

    @Test
    fun `invalid battery thresholds and elapsed times fail explicitly`() {
        assertThrows(IllegalArgumentException::class.java) { healthy.copy(battery = -1) }
        assertThrows(IllegalArgumentException::class.java) { healthy.copy(battery = 101) }
        assertThrows(IllegalArgumentException::class.java) { healthy.copy(lowBatteryThreshold = 101) }
        assertThrows(IllegalArgumentException::class.java) { policy.suppressionReason(healthy, -1) }
        assertThrows(IllegalArgumentException::class.java) { policy.resumeForAlert(-1) }
        assertThrows(IllegalArgumentException::class.java) { policy.resumeForAlert(Long.MAX_VALUE) }
        assertNull(policy.suppressionReason(healthy, Long.MAX_VALUE))
    }
}
