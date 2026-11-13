package com.weartak.plugin.ditto.track

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DittoTrackPublicationGateTest {
    private val gate = DittoTrackPublicationGate()
    private val sample = DittoTrackPublicationGate.Sample(
        position = DittoTrackPublicationGate.Position(40.0, -88.0, 3f),
        battery = 80,
        identity = listOf("WATCH", "Team Member", "Cyan", "uid"),
        remarks = "MOPP1; HR: 80 bpm @ 12:00:00Z",
        physiology = DittoTrackPublicationGate.Physiology(80, 90f, 0.2),
        intervalSeconds = 60,
    )

    @Test
    fun `first fix and heartbeat are due until a successful publication is acknowledged`() {
        assertTrue(gate.due(sample, 0))
        assertTrue(gate.due(sample, 1))
        gate.published(sample, 0)
        assertFalse(gate.due(sample, 59_999))
        assertTrue(gate.due(sample, 60_000))
        assertTrue(gate.due(sample, 60_001))
        gate.published(sample, 60_001)
        assertFalse(gate.due(sample, 60_002))
    }

    @Test
    fun `movement respects core accuracy and distance thresholds`() {
        gate.published(sample, 0)
        assertFalse(gate.due(sample.copy(position = sample.position.copy(latitude = 40.00001)), 1_000))
        assertFalse(gate.due(sample.copy(position = sample.position.copy(latitude = 40.0001)), 1_000))
        assertFalse(gate.due(sample.copy(position = sample.position.copy(latitude = 40.001, accuracyMeters = 100f)), 1_000))
        assertTrue(gate.due(sample.copy(position = sample.position.copy(latitude = 40.001, accuracyMeters = 5f)), 1_000))
        assertFalse(DittoTrackPublicationGate.moved(3.0, 15.0, 3.0))
        assertTrue(DittoTrackPublicationGate.moved(3.0, 15.001, 3.0))
        assertFalse(DittoTrackPublicationGate.moved(5.0, 30.0, 10.0))
        assertTrue(DittoTrackPublicationGate.moved(5.0, 30.001, 10.0))
        assertFalse(DittoTrackPublicationGate.moved(25.01, 1_000.0, 3.0))
        assertFalse(DittoTrackPublicationGate.moved(Double.NaN, 1_000.0, 3.0))
        assertFalse(DittoTrackPublicationGate.moved(3.0, Double.NaN, 3.0))
        assertFalse(DittoTrackPublicationGate.moved(3.0, 1_000.0, null))
    }

    @Test
    fun `vital and identity changes respect thresholds and ignore sample clock jitter`() {
        gate.published(sample, 0)
        val jitter = sample.copy(remarks = "MOPP1; HR: 81 bpm @ 12:00:01Z", battery = 79,
            physiology = sample.physiology!!.copy(heartRateBpm = 81))
        assertFalse(gate.due(jitter, 1_000))
        assertTrue(gate.due(sample.copy(battery = 75), 1_000))
        assertTrue(gate.due(sample.copy(identity = listOf("NEW")), 1_000))
        assertTrue(gate.due(sample.copy(remarks = "MOPP2"), 1_000))
        assertTrue(gate.due(sample.copy(physiology = sample.physiology!!.copy(heartRateBpm = 85)), 1_000))
        assertTrue(gate.due(sample.copy(physiology = sample.physiology!!.copy(skinTempF = 90.5f)), 1_000))
        assertTrue(gate.due(sample.copy(physiology = sample.physiology!!.copy(exertion = 0.25)), 1_000))
        assertTrue(gate.due(sample.copy(physiology = null), 1_000))
    }

    @Test
    fun `interval changes rollback and reset force publication`() {
        gate.published(sample, 10_000)
        assertTrue(gate.due(sample.copy(intervalSeconds = 120), 10_001))
        assertTrue(gate.due(sample.copy(intervalSeconds = 15), 10_001))
        assertTrue(gate.due(sample, 9_999))
        gate.reset()
        assertTrue(gate.due(sample, 10_001))
        val zeroInterval = sample.copy(intervalSeconds = 0)
        gate.published(zeroInterval, 0)
        assertFalse(gate.due(zeroInterval, 999))
        assertTrue(gate.due(zeroInterval, 1_000))
    }

    @Test
    fun `invalid coordinates and elapsed clock fail explicitly without storing input`() {
        assertThrows(IllegalArgumentException::class.java) {
            gate.due(sample.copy(position = sample.position.copy(latitude = Double.NaN)), 0)
        }
        assertThrows(IllegalArgumentException::class.java) { gate.published(sample, -1) }
        assertThrows(IllegalArgumentException::class.java) {
            gate.published(sample.copy(position = sample.position.copy(longitude = 181.0)), 0)
        }
        assertTrue(gate.due(sample, 0))
    }

    @Test
    fun `acknowledged identity is snapshotted and diagnostic strings are redacted`() {
        val identity = mutableListOf("private-identity")
        val old = sample.copy(identity = identity)
        gate.published(old, 0)
        identity[0] = "changed"
        assertTrue(gate.due(old, 1))
        assertFalse(old.toString().contains("changed"))
        assertFalse(sample.position.toString().contains("40.0"))
        assertFalse(sample.physiology.toString().contains("80"))
    }
}
