package com.noop.ble

import com.noop.data.DeviceMetricKind
import com.noop.data.StreamBatch
import com.noop.garmin.GarminLiveIntake
import com.noop.garmin.GarminLiveSnapshot
import com.noop.garmin.GarminRealtimeReading
import com.noop.garmin.GarminService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GarminLiveFeed]: a Garmin watch's live channels reach the SAME live sink + repository funnels as every
 * other source. Twin of StrandTests/GarminLiveFeedTests.swift; the quality-line literals are the Swift
 * output for the same beats.
 */
class GarminLiveFeedTest {

    private var clock = 1_793_546_940L
    private val live = mutableListOf<Pair<Int, List<Int>>>()
    private var cleared = 0
    private var snapshot: GarminLiveSnapshot? = null
    private val persisted = mutableListOf<StreamBatch>()
    private val logs = mutableListOf<String>()

    private fun feed() = GarminLiveFeed(
        liveSink = { hr, rr -> live += hr to rr },
        clearHeartRate = { cleared += 1 },
        publishSnapshot = { snapshot = it },
        persist = { persisted += it },
        log = { logs += it },
        detail = { true },
        now = { clock },
    )

    @Test
    fun theStoredKindsAreDeviceMetricKinds() {
        for (kind in listOf(GarminLiveIntake.KIND_BODY_BATTERY, GarminLiveIntake.KIND_RESPIRATION, GarminLiveIntake.KIND_SPO2)) {
            assertNotNull(kind, DeviceMetricKind.entries.firstOrNull { it.wire == kind })
        }
    }

    @Test
    fun heartRateAndBeatsReachTheSharedLiveIntake() {
        val f = feed()
        f.handle(GarminLiveEvent.Channel(GarminService.REALTIME_HEART_RATE, true))
        f.handle(GarminLiveEvent.Reading(GarminRealtimeReading.HeartRate(62, 55, 3, byteArrayOf(-1, -1))))
        f.handle(GarminLiveEvent.Reading(GarminRealtimeReading.RrInterval(968, 7)))
        assertEquals(55, snapshot?.restingHeartRate)
        // An unreadable sample (off the wrist) publishes nothing; the last number stands until the channel closes.
        f.handle(GarminLiveEvent.Reading(GarminRealtimeReading.HeartRate(null, null, 3, ByteArray(0))))
        assertEquals(listOf(62 to emptyList(), 62 to listOf(968)), live)
        f.handle(GarminLiveEvent.Channel(GarminService.REALTIME_HEART_RATE, false))
        assertEquals(1, cleared)
        assertEquals(listOf(62), persisted.flatMap { it.hr }.map { it.bpm })
        assertEquals(listOf(968), persisted.flatMap { it.rr }.map { it.rrMs })
        assertTrue(logs.contains("Garmin: receiving live heart rate - first sample 62 bpm"))
    }

    @Test
    fun watchMetricsAreStoredAtMostOncePerMinute() {
        val f = feed()
        for (second in 0 until 125) {
            clock = 1_793_546_940L + second
            f.handle(GarminLiveEvent.Reading(GarminRealtimeReading.BodyBattery(64)))
            f.handle(GarminLiveEvent.Reading(GarminRealtimeReading.Respiration(14)))
        }
        f.handle(GarminLiveEvent.LinkDown)
        val rows = persisted.flatMap { it.deviceMetric }
        assertEquals(listOf(1_793_546_940L, 1_793_547_000L, 1_793_547_060L),
            rows.filter { it.kind == DeviceMetricKind.BODY_BATTERY }.map { it.ts })
        assertEquals(3, rows.count { it.kind == DeviceMetricKind.RESPIRATION_BPM })
        assertTrue(rows.all { it.source == "garmin_live" })
        assertNull(snapshot)
    }

    @Test
    fun rrQualityIsReportedWhenTheChannelCloses() {
        val f = feed()
        f.handle(GarminLiveEvent.Channel(GarminService.REALTIME_HRV, true))
        for (ms in listOf(1000, 1010, 990, 1005, 400, 995, 1000, 1002, 998, 1001)) {
            f.handle(GarminLiveEvent.Reading(GarminRealtimeReading.RrInterval(ms, null)))
        }
        f.handle(GarminLiveEvent.Channel(GarminService.REALTIME_HRV, false))
        assertTrue(logs.toString(), logs.contains("Garmin: rr quality (channel closed): beats=10 kept=9 rejected=1 (10.0%)"))
    }

    @Test
    fun qualityLineMatchesTheSwiftText() {
        assertEquals("rr quality (x): beats=7 kept=5 rejected=2 (28.6%)",
            GarminLiveFeed.qualityLine(listOf(800.0, 810.0, 2500.0, 805.0, 790.0, 200.0, 800.0), "x"))
    }
}
