package com.noop.ingest

import com.noop.data.DeviceMetricKind
import com.noop.data.DeviceMetricRow
import com.noop.data.GarminDayRollup
import com.noop.data.GarminFileIngest
import com.noop.data.GarminSleepRow
import com.noop.data.GarminSyncedFileRow
import com.noop.data.HrRow

/**
 * Garmin watch file → NOOP streams. Swift twin: Packages/StrandImport/Sources/StrandImport/GarminWatchIngest.swift; the
 * two are held byte-identical by `GarminWatchIngestTest`, which pins the Swift oracle's text.
 *
 * Heart rate (55/27) → hrSample; stress, Body Battery, SpO2 %, respiration, HRV values, the watch's sleep
 * score and intensity minutes → deviceMetricSample; SLEEP_LEVEL nights (275) → sleepSession with a stage
 * timeline; day totals (55 counters, 211 resting HR, 370 last-night HRV, sleep) → dailyMetric +
 * metricSeries, merged on write by [com.noop.data.GarminSyncStore]. Beat intervals (290) and skin
 * temperature (397/398) are unconfirmed candidates and are NOT mapped.
 */
object GarminWatchIngest {

    const val SOURCE_STRESS = "fit227"
    const val SOURCE_SPO2 = "fit269"
    const val SOURCE_RESPIRATION = "fit297"
    const val SOURCE_HRV = "fit371"
    const val SOURCE_SLEEP_SCORE = "fit346"
    const val SOURCE_INTENSITY = "fit55"

    /** Heart rates outside this range are sensor sentinels (0, 255), not beats. */
    private val HR_RANGE = 25..240

    /** Build the ingest for one decoded file. Swift twin: `GarminWatchIngest.fold`. */
    fun fold(s: GarminWellnessSamples, file: GarminSyncedFileRow, fallbackUtcOffsetS: Int): GarminFileIngest {
        val offset = s.utcOffsetS ?: fallbackUtcOffsetS

        val hrBySecond = HashMap<Long, Int>()
        for (m in s.monitoring) {
            val bpm = m.heartRate ?: continue
            if (bpm in HR_RANGE) hrBySecond[m.ts] = bpm
        }
        val hr = hrBySecond.keys.sorted().map { HrRow(it, hrBySecond.getValue(it)) }

        val metrics = LinkedHashMap<String, DeviceMetricRow>()
        // Swift twin: `GarminWatchIngest.add`.
        fun add(ts: Long, kind: DeviceMetricKind, value: Double, source: String) {
            if (!value.isFinite()) return
            metrics["${kind.wire}|$ts"] = DeviceMetricRow(ts, kind, value, source)
        }
        for (v in s.stress) add(v.ts, DeviceMetricKind.STRESS, v.value.toDouble(), SOURCE_STRESS)
        for (v in s.bodyBattery) add(v.ts, DeviceMetricKind.BODY_BATTERY, v.value.toDouble(), SOURCE_STRESS)
        for (v in s.spo2) add(v.ts, DeviceMetricKind.SPO2_PCT, v.percent.toDouble(), SOURCE_SPO2)
        for (v in s.respiration) if (v.value > 0) add(v.ts, DeviceMetricKind.RESPIRATION_BPM, v.value, SOURCE_RESPIRATION)
        for (v in s.hrvValues) if (v.value > 0) add(v.ts, DeviceMetricKind.HRV_MS, v.value, SOURCE_HRV)
        for (a in s.sleepAssessments) add(a.ts, DeviceMetricKind.SLEEP_SCORE, a.overallScore.toDouble(), SOURCE_SLEEP_SCORE)
        for (m in s.monitoring) {
            m.moderateMin?.let { if (it > 0) add(m.ts, DeviceMetricKind.INTENSITY_MODERATE_MIN, it.toDouble(), SOURCE_INTENSITY) }
            m.vigorousMin?.let { if (it > 0) add(m.ts, DeviceMetricKind.INTENSITY_VIGOROUS_MIN, it.toDouble(), SOURCE_INTENSITY) }
        }
        val deviceMetric = metrics.values.sortedWith(compareBy<DeviceMetricRow>({ it.ts }, { it.kind.wire }))

        val days = HashMap<String, GarminDayRollup>()
        // Swift twin: `GarminWatchIngest.day`.
        fun day(key: String): GarminDayRollup = days[key] ?: run {
            val start = dayStartUnix(key, offset)
            GarminDayRollup(day = key, dayStartTs = start, dayEndTs = start + 86_400)
        }
        // Swift twin: `GarminWatchIngest.dayOf`.
        fun dayOf(ts: Long) = GarminWellnessParser.localDay(ts, offset)

        for (a in GarminWellnessParser.dailyTotals(s.monitoring, offset)) {
            days[a.day] = day(a.day).copy(
                steps = a.steps, distanceM = a.distanceM,
                activeKcal = a.activeCalories?.toDouble(), totalKcal = a.calories?.toDouble(),
            )
        }
        for (v in s.restingHeartRate.sortedBy { it.ts }) {
            val key = dayOf(v.ts); days[key] = day(key).copy(restingHr = v.value)
        }
        for (h in s.hrvSummaries.sortedBy { it.ts }) {
            val avg = h.lastNightAverageMs ?: continue
            if (avg <= 0) continue
            val key = dayOf(h.ts); days[key] = day(key).copy(avgHrvMs = avg)
        }
        for (a in s.sleepAssessments.sortedBy { it.ts }) {
            val key = dayOf(a.ts); days[key] = day(key).copy(sleepScore = a.overallScore)
        }
        for (m in deviceMetric) {
            val key = dayOf(m.ts); days[key] = day(key)
        }

        val sleeps = ArrayList<GarminSleepRow>()
        for (night in s.sleepSessions) {
            if (night.end <= night.start) continue
            val asleep = night.lightS + night.deepS + night.remS
            if (asleep <= 0) continue
            val key = dayOf(night.end)
            val efficiency = efficiencyPct(asleep, night.awakeS)
            val d = day(key).copy(
                totalSleepMin = asleep / 60.0, deepMin = night.deepS / 60.0, lightMin = night.lightS / 60.0,
                remMin = night.remS / 60.0, awakeMin = night.awakeS / 60.0, efficiencyPct = efficiency,
            )
            days[key] = d
            sleeps.add(GarminSleepRow(night.start, night.end, efficiency, null, d.avgHrvMs,
                stagesJSON(s.sleepLevels, night.start, night.end)))
        }
        return GarminFileIngest(file = file, hr = hr, deviceMetric = deviceMetric,
            days = days.keys.sorted().map { days.getValue(it) }, sleeps = sleeps)
    }

    /** Asleep share of the time in bed, percent rounded to one decimal. Swift twin: `GarminWatchIngest.efficiencyPct`. */
    fun efficiencyPct(asleep: Long, awake: Long): Double? {
        val inBed = asleep + awake
        if (inBed <= 0) return null
        // Half away from zero, as Swift `rounded()` (Math.rint would round half to even).
        return Math.round(asleep.toDouble() * 1000 / inBed.toDouble()).toDouble() / 10
    }

    /** The night's stage timeline, hand-written with a fixed key order. Swift twin: `GarminWatchIngest.stagesJSON`. */
    fun stagesJSON(levels: List<GarminWellnessSamples.TimedInt>, start: Long, end: Long): String? {
        val names = mapOf(1 to "wake", 2 to "light", 3 to "deep", 4 to "rem")
        val rows = levels.filter { it.ts in start..end }
        val segments = ArrayList<Triple<Long, Long, String>>()
        for (i in 1 until rows.size) {
            val name = names[rows[i].value] ?: continue
            if (rows[i].ts <= rows[i - 1].ts) continue
            val last = segments.lastOrNull()
            if (last != null && last.third == name && last.second == rows[i - 1].ts) {
                segments[segments.size - 1] = Triple(last.first, rows[i].ts, name)
            } else {
                segments.add(Triple(rows[i - 1].ts, rows[i].ts, name))
            }
        }
        if (segments.isEmpty()) return null
        return segments.joinToString(",", "[", "]") { "{\"start\":${it.first},\"end\":${it.second},\"stage\":\"${it.third}\"}" }
    }

    /** Unix seconds of local midnight starting `day` ("yyyy-MM-dd"). Swift twin: `GarminWatchIngest.dayStartUnix`. */
    fun dayStartUnix(day: String, utcOffsetS: Int): Long {
        val parts = day.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return 0
        val (y0, m, d) = parts
        val y = (if (m <= 2) y0 - 1 else y0).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146_097 + doe - 719_468
        return days * 86_400 - utcOffsetS
    }
}
