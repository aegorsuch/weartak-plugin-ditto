// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core DittoPowerPolicy.kt at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.policy

internal data class DittoPowerState(
    val worn: Boolean?,
    val charging: Boolean,
    val battery: Int?,
    val lowBatteryThreshold: Int,
    val sleeping: Boolean,
    val powerSave: Boolean,
    val alerting: Boolean,
    val stayConnectedOffWrist: Boolean,
) {
    init {
        require(battery == null || battery in 0..100)
        require(lowBatteryThreshold in 0..100)
    }
}

internal enum class DittoPowerSuppression { OFF_WRIST, POWER }

internal class DittoPowerPolicy {
    private var urgentUntil = Long.MIN_VALUE

    @Synchronized
    fun resumeForAlert(elapsedMillis: Long) {
        require(elapsedMillis in 0..Long.MAX_VALUE - ALERT_GRACE_MS)
        urgentUntil = elapsedMillis + ALERT_GRACE_MS
    }

    @Synchronized
    fun suppressionReason(state: DittoPowerState, elapsedMillis: Long): DittoPowerSuppression? {
        require(elapsedMillis >= 0)
        return when {
            state.charging || state.alerting || elapsedMillis < urgentUntil -> null
            state.sleeping || state.powerSave ||
                state.battery?.let { it <= state.lowBatteryThreshold } == true -> DittoPowerSuppression.POWER
            state.worn == false && !state.stayConnectedOffWrist -> DittoPowerSuppression.OFF_WRIST
            else -> null
        }
    }

    companion object {
        const val ALERT_GRACE_MS = 60_000L
    }
}
