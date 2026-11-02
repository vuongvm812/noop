package com.noop.ble

import com.noop.analytics.HrvAnalyzer
import com.noop.data.DeviceMetricKind
import com.noop.data.DeviceMetricRow
import com.noop.data.HrRow
import com.noop.data.RrRow
import com.noop.data.StreamBatch
import com.noop.garmin.GarminLiveIntake
import com.noop.garmin.GarminLiveIntakeEffect
import com.noop.garmin.GarminLiveSnapshot
import com.noop.garmin.GarminRealtimeReading
import com.noop.garmin.GarminService
import java.util.Locale

/** What the live-channel layer reports: a reading, a channel's handle opening or closing, or the link going down. */
sealed class GarminLiveEvent {
    data class Reading(val reading: GarminRealtimeReading) : GarminLiveEvent()
    data class Channel(val service: GarminService, val open: Boolean) : GarminLiveEvent()
    object LinkDown : GarminLiveEvent()
}

/**
 * Feeds a Garmin watch's live channels into NOOP's shared live state and store through the same
 * funnels every other source uses. Twin of Strand/BLE/GarminLiveFeed.swift:
 *   - heart rate -> [liveSink] (the live HR seam) and an hrSample row per reading;
 *   - beat intervals -> [liveSink]'s R-R list (the intake the live HRV, strain and stress math reads) and
 *     rrInterval rows, unfiltered: the existing artifact filtering runs downstream as for any strap;
 *   - Body Battery / respiration / SpO2 / steps / floors -> [publishSnapshot], plus at most one deviceMetricSample
 *     row per kind per minute for the first three ([GarminLiveIntake]).
 * Rows are buffered and flushed in batches like [StandardHrSource]. Runs on the main looper with the source.
 */
class GarminLiveFeed(
    private val liveSink: (hr: Int, rr: List<Int>) -> Unit,
    private val clearHeartRate: () -> Unit,
    private val publishSnapshot: (GarminLiveSnapshot?) -> Unit,
    private val persist: (StreamBatch) -> Unit,
    private val log: (String) -> Unit,
    /** The RR-quality readout is per-session detail: logged only while this returns true. */
    private val detail: () -> Boolean = { false },
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val intake = GarminLiveIntake()
    private val hr = ArrayList<HrRow>()
    private val rr = ArrayList<RrRow>()
    private val metrics = ArrayList<DeviceMetricRow>()
    private var lastFlush = now()
    private val sessionBeats = ArrayList<Double>()
    private var lastHr: Int? = null
    private var loggedFirstBeat = false
    private var loggedFirstHr = false

    fun handle(event: GarminLiveEvent) {
        val ts = now()
        when (event) {
            is GarminLiveEvent.Reading -> apply(intake.ingest(event.reading, ts), ts)
            is GarminLiveEvent.Channel -> {
                if (event.open) {
                    if (event.service == GarminService.REALTIME_HRV) { sessionBeats.clear(); loggedFirstBeat = false }
                    if (event.service == GarminService.REALTIME_HEART_RATE) loggedFirstHr = false
                    return
                }
                if (event.service == GarminService.REALTIME_HRV) logQuality("channel closed")
                apply(intake.channelClosed(event.service), ts)
                flush()
            }
            is GarminLiveEvent.LinkDown -> {
                if (sessionBeats.isNotEmpty()) logQuality("link down")
                apply(intake.linkDown(), ts)
                publishSnapshot(null)
                flush()
            }
        }
    }

    private fun apply(effect: GarminLiveIntakeEffect, ts: Long) {
        effect.heartRate?.let { bpm ->
            lastHr = bpm
            liveSink(bpm, emptyList())
            hr += HrRow(ts, bpm)
            if (!loggedFirstHr) {
                loggedFirstHr = true
                log("Garmin: receiving live heart rate - first sample $bpm bpm")
            }
        }
        if (effect.rr.isNotEmpty()) {
            // The live seam takes HR and R-R together; R-R alone rides with the last HR (0 = no HR update).
            liveSink(lastHr ?: 0, effect.rr)
            rr += effect.rr.map { RrRow(ts, it) }
            sessionBeats += effect.rr.map { it.toDouble() }
            if (sessionBeats.size > QUALITY_WINDOW_CAP) repeat(sessionBeats.size - QUALITY_WINDOW_CAP) { sessionBeats.removeAt(0) }
            if (!loggedFirstBeat) {
                loggedFirstBeat = true
                log("Garmin: receiving beat intervals - first ${effect.rr[0]} ms")
            }
        }
        for (w in effect.writes) {
            val kind = DeviceMetricKind.entries.firstOrNull { it.wire == w.kind } ?: continue
            metrics += DeviceMetricRow(w.ts, kind, w.value, GarminLiveIntake.METRIC_SOURCE)
        }
        if (effect.clearHeartRate) {
            lastHr = null
            clearHeartRate()
        }
        if (effect.snapshotChanged) publishSnapshot(intake.snapshot)
        if (hr.size + rr.size + metrics.size >= FLUSH_COUNT || ts - lastFlush >= FLUSH_INTERVAL_SECONDS) flush()
    }

    private fun flush() {
        lastFlush = now()
        if (hr.isEmpty() && rr.isEmpty() && metrics.isEmpty()) return
        persist(StreamBatch(hr = hr.toList(), rr = rr.toList(), deviceMetric = metrics.toList()))
        hr.clear()
        rr.clear()
        metrics.clear()
    }

    private fun logQuality(reason: String) {
        if (detail() && sessionBeats.isNotEmpty()) log("Garmin: " + qualityLine(sessionBeats, reason))
        sessionBeats.clear()
    }

    companion object {
        const val FLUSH_COUNT = 30
        const val FLUSH_INTERVAL_SECONDS = 30L
        const val QUALITY_WINDOW_CAP = 4_000

        /** The share of beats NOOP's own artifact filter ([HrvAnalyzer.cleanRR]) drops. Same text as Swift. */
        fun qualityLine(beats: List<Double>, reason: String): String {
            val kept = HrvAnalyzer.cleanRR(beats).size
            val rejected = beats.size - kept
            val pct = if (beats.isEmpty()) 0.0 else rejected * 100.0 / beats.size
            return "rr quality ($reason): beats=${beats.size} kept=$kept rejected=$rejected " +
                "(${String.format(Locale.US, "%.1f", pct)}%)"
        }
    }
}
