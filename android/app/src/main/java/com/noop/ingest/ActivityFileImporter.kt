package com.noop.ingest

import com.noop.ui.failure
import com.noop.ui.summaryTextLocalized
import com.noop.ui.uiString

import com.noop.R

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.noop.analytics.RouteMath
import com.noop.data.DailyMetric
import com.noop.data.HrSample
import com.noop.data.ImportSummary
import com.noop.data.StreamBatch
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt

/**
 * On-device import of a single exported activity FILE — GPX, TCX or FIT — from ANY brand (Garmin,
 * Coros, Suunto, Wahoo, Polar, Strava, WHOOP, Apple…), fully offline.
 *
 * Kotlin mirror of the macOS/iOS source of truth
 *   Packages/StrandImport/Sources/StrandImport/ActivityFileImporter.swift (+ Gpx/Tcx/FitParser.swift)
 * so the three formats decode the same on every platform and a file imports identically.
 *
 *   • GPX  (XML)    — universal GPS track: <trkpt lat lon><ele/><time/> + Garmin TrackPointExtension hr.
 *   • TCX  (XML)    — Garmin Training Center: Trackpoint Time/Position/HeartRateBpm + per-Lap summaries.
 *   • FIT  (binary) — Garmin/ANT: header + definition/data records (little-endian); we decode the
 *                     common file_id / record / lap / session messages.
 *
 * Parsing is PURE ([parse]) so it is JVM unit-testable. The result is mapped 1:1 onto the existing
 * [WorkoutRow] store path (source "activity-file"), with the GPS route stored as a [RouteMath] polyline
 * (Android's WorkoutRow.routePolyline) where present. Never touches live data, scoring, or the WHOOP
 * import paths.
 *
 * SECURITY: every byte is UNTRUSTED. The byte budget caps the read; the XML parsers disable external
 * entities/DTDs (XXE / billion-laughs) and cap depth + point count; the FIT decoder bounds every field
 * read and caps record/field counts. A malformed file yields an empty result, never a crash.
 *
 * Patterns conceptually adapted from CoreGPX (MIT) / ticofab android-gpx-parser (Apache) /
 * FitnessKit TcxDataProtocol+FitDataProtocol (MIT) / muktihari/fit (BSD); NOOP's own clean code.
 */
object ActivityFileImporter {

    /** Room deviceId / workout source for everything this importer writes — identical to the Swift lane. */
    const val SOURCE_ID = "activity-file"

    private const val SOURCE_LABEL = "Workout file"

    /** Read ceiling (DoS guard). A real GPX/TCX is a few MB, a FIT tens–hundreds of KB. 128 MB is generous. */
    const val MAX_BYTES = 128L shl 20

    /** Max retained track points/records — bounds memory hard against a crafted file. */
    const val MAX_POINTS = 1_000_000

    enum class Kind { GPX, TCX, FIT }

    // MARK: - Normalized model (mirrors Swift ActivityFile)

    data class RoutePoint(val lat: Double, val lon: Double)

    /**
     * #137: one persisted HR sample — unix-second timestamp + bpm. Mirrors the Swift `ActivityHRSample`
     * (a LOCAL type, not the Room [com.noop.data.HrSample] entity, so the pure-JVM importer needs no
     * Room dependency; the app layer maps this 1:1 onto `HrSample` when writing to the HR store).
     */
    data class HrPoint(val ts: Long, val bpm: Int)

    data class Activity(
        val kind: Kind,
        val startTs: Long,          // unix seconds, UTC
        val endTs: Long,            // unix seconds, UTC (>= startTs)
        val sport: String?,
        val distanceM: Double?,
        val energyKcal: Double?,
        val steps: Int?,
        val avgHr: Int?,
        val maxHr: Int?,
        val ascentM: Double?,
        val gpsPointCount: Int,
        val hrSampleCount: Int,
        val route: List<RoutePoint>,
        // #137: the REAL per-sample HR time series the file carried — historically folded into
        // avg/max/hrSampleCount and then discarded (an imported file never fed the HR-based Effort). We
        // now keep it so the app layer can persist it under the `activity-file` source; on a strap-less
        // day that lets the ride's measured HR light the day Effort ring (the day-owner resolver picks
        // `activity-file` as the sole source with HR that day — see IntelligenceEngine.resolveDayOwner).
        // Measured data, not a fabricated strain, so it doesn't reintroduce the fabricated-strain the
        // WorkoutRow `strain = null` guard avoids. Only samples carrying BOTH a timestamp and a valid HR
        // appear here (a sample with no time can't key into the (deviceId, ts) HR store), so in general
        // hrSamples.size <= hrSampleCount. Defaulted so the no-data build paths need no change.
        val hrSamples: List<HrPoint> = emptyList(),
        /** The watch's own aerobic Training Effect (FIT session, 0.0-5.0), a reference value; null when absent. */
        val aerobicTrainingEffect: Double? = null,
        /** The watch's own anaerobic Training Effect (FIT session, 0.0-5.0), a reference value. */
        val anaerobicTrainingEffect: Double? = null,
        /** The watch's own EPOC-based training load for the activity (FIT session), a reference value. */
        val trainingLoad: Double? = null,
        /** Watch session average power in watts (FIT session field 20); null when absent or invalid. */
        val avgPowerW: Int? = null,
        /** Watch session average cadence (FIT session field 18): steps/min for on-foot sports, rpm otherwise. */
        val avgCadence: Double? = null,
        /** Per-record power / cadence / altitude the file carried (FIT only), for the workout detail graphs. */
        val trackMetrics: List<TrackMetric> = emptyList(),
    ) {
        val durationS: Double? get() = (endTs - startTs).takeIf { it > 0 }?.toDouble()

        /** Honest one-line note for the workout row — only what's actually present. */
        fun importNote(): String {
            val parts = ArrayList<String>(4)
            parts.add("Imported ${kind.name}")
            if (distanceM != null && distanceM > 0) {
                val km = distanceM / 1000.0
                parts.add(if (km >= 10) "${km.roundToInt()} km" else String.format(Locale.US, "%.2f km", km))
            }
            if (gpsPointCount > 0) parts.add("$gpsPointCount GPS points")
            if (hrSampleCount > 0) parts.add("$hrSampleCount HR samples")
            if (steps != null && steps > 0) parts.add("$steps steps")
            return parts.joinToString(" · ")
        }
    }

    /**
     * [wellness] carries Garmin wellness data from a FIT file that holds no workout (monitoring,
     * sleep, HRV...); null otherwise. Decoded by [GarminWellnessParser], not yet persisted here.
     */
    data class Result(
        val activity: Activity?,
        val kind: Kind,
        val skipped: Int,
        val wellness: GarminWellnessSamples? = null,
    ) {
        val hasActivity: Boolean get() = activity != null
    }

    /**
     * One parsed track sample; any field may be null. [cadenceCycles] is the FIT cycles/min as written
     * (strides/min for running); the sport is unknown while records are read, so [trackMetrics] converts.
     * Swift twin: `TrackSample`.
     */
    internal data class Sample(
        val timeS: Long?,
        val point: RoutePoint?,
        val elevationM: Double?,
        val hr: Int?,
        val cadenceCycles: Double? = null,
        val powerW: Int? = null,
    )

    /** One timestamped power / cadence / altitude reading; each value null when the watch gave none. */
    data class TrackMetric(val ts: Long, val cadence: Double?, val powerW: Int?, val altitudeM: Double?)

    /**
     * Fold the parsed samples into the persisted power / cadence / altitude series. [cadenceFactor] is 2 for
     * on-foot sports (FIT strides to steps) and 1 otherwise. A sample needs a valid timestamp (same ceiling as
     * [hrSamples]); a value that is absent, or 0 for power and cadence, is left null. Swift twin:
     * `ActivityFileImporter.trackMetrics`.
     */
    internal fun trackMetrics(samples: List<Sample>, cadenceFactor: Double): List<TrackMetric> =
        samples.mapNotNull { s ->
            val ts = s.timeS ?: return@mapNotNull null
            if (ts <= 0 || ts >= 4_102_444_800L) return@mapNotNull null
            val cadence = s.cadenceCycles?.takeIf { it > 0 }?.let { it * cadenceFactor }
            val power = s.powerW?.takeIf { it > 0 }
            val altitude = s.elevationM?.takeIf { it.isFinite() }
            if (cadence == null && power == null && altitude == null) null
            else TrackMetric(ts, cadence, power, altitude)
        }

    /**
     * #137: fold the parsed track samples into the persisted HR series. SHARED by every format path
     * (GPX/TCX via [build], FIT via [FitDecoder.build]) so the three decode to a byte-identical HR
     * stream and stay in parity with the Swift twin. A sample is kept only when it carried BOTH a
     * timestamp and a valid HR: without a time it can't key into the (deviceId, ts) HR store, and
     * without HR there's nothing to store. The epoch is range-checked with the same 2100-01-01 sanity
     * ceiling the FIT decoder uses, so a crafted file with an absurd timestamp is dropped, not stored.
     */
    internal fun hrSamples(samples: List<Sample>): List<HrPoint> =
        samples.mapNotNull { s ->
            val ts = s.timeS ?: return@mapNotNull null
            val hr = s.hr ?: return@mapNotNull null
            if (ts <= 0 || ts >= 4_102_444_800L) return@mapNotNull null
            HrPoint(ts, hr)
        }

    // MARK: - Format detection

    enum class Format { GPX, TCX, FIT, UNKNOWN }

    fun detectFormat(filename: String?, data: ByteArray): Format {
        val ext = filename?.substringAfterLast('.', "")?.lowercase()
        when (ext) {
            "gpx" -> return Format.GPX
            "tcx" -> return Format.TCX
            "fit" -> return Format.FIT
        }
        return detectFormat(data)
    }

    fun detectFormat(data: ByteArray): Format {
        // FIT: ".FIT" signature at offset 8.
        if (data.size >= 12 &&
            data[8] == '.'.code.toByte() && data[9] == 'F'.code.toByte() &&
            data[10] == 'I'.code.toByte() && data[11] == 'T'.code.toByte()
        ) return Format.FIT
        // XML: sniff the root element from the first 512 (BOM-stripped) bytes.
        val stripped = Bom.stripUtf8(data)
        val head = stripped.copyOfRange(0, minOf(512, stripped.size))
        val s = String(head, Charsets.UTF_8).lowercase()
        if (s.contains("<gpx")) return Format.GPX
        if (s.contains("<trainingcenterdatabase") || s.contains("<tcx")) return Format.TCX
        return Format.UNKNOWN
    }

    // MARK: - Public entry point (UI calls this)

    /**
     * Read the SAF [uri], detect the format, parse it, upsert one workout under [deviceId] (default
     * "activity-file"), and return an [ImportSummary]. The route is stored as a polyline where present.
     */
    suspend fun importExport(
        context: Context,
        uri: Uri,
        repo: WhoopRepository,
        deviceId: String = SOURCE_ID,
    ): ImportSummary {
        val filename = displayName(context, uri)
        val bytes: ByteArray = try {
            context.contentResolver.openInputStream(uri)?.use { it.readCapped(MAX_BYTES) }
                ?: return ImportSummary.failure(SOURCE_LABEL, "Could not open the selected file.") { uiString(R.string.l10n_activity_file_importer_could_not_open_the_selected_file_d412e6e8) }
        } catch (e: Exception) {
            return ImportSummary.failure(SOURCE_LABEL, "Could not read the file: ${e.message ?: "unknown error"}") { uiString(R.string.l10n_activity_file_importer_could_not_read_the_file_1_65a3ad34, (e.message ?: uiString(R.string.l10n_activity_file_importer_unknown_error_af6321ca))) }
        }

        val result = parse(bytes, filename)
        val activity = result.activity
        val durationS = activity?.durationS
        if (activity == null || durationS == null || durationS <= 0) {
            return ImportSummary.failure(
                SOURCE_LABEL,
                "No usable activity found - point at a .gpx, .tcx or .fit workout file.",
            ) { uiString(R.string.l10n_activity_file_importer_no_usable_activity_found_point_at_daa3cb33) }
        }

        val row = WorkoutRow(
            deviceId = deviceId,
            startTs = activity.startTs,
            endTs = activity.endTs,
            sport = workoutSport(activity.sport),
            source = SOURCE_ID,
            durationS = activity.durationS,
            energyKcal = activity.energyKcal,
            avgHr = activity.avgHr,
            maxHr = activity.maxHr,
            strain = null,                                  // never a fabricated cardiovascular strain
            distanceM = activity.distanceM,
            zonesJSON = null,
            notes = activity.importNote(),
            routePolyline = activity.route.takeIf { it.size >= 2 }
                ?.let { RouteMath.encode(it.map { p -> RouteMath.LatLng(p.lat, p.lon) }) },
            steps = activity.steps,                         // #1058: per-session steps, summed into the day below
        )

        repo.upsertDevice(deviceId, name = "Workout files")
        repo.upsertWorkouts(listOf(row))

        // #137 (A): persist the ride's real per-sample HR under the activity-file source. The insert is
        // keyed on (deviceId, ts) (OnConflict.REPLACE), so re-importing the same file is idempotent — an
        // identical ts overwrites, never duplicates. Skipped when the file carried no timestamped HR (a
        // pure GPS track), leaving day Effort honestly dark. Registering activity-file as a `.fileImport`
        // owner candidate (B1) happens in the UI caller (DataSourcesScreen), mirroring the Swift lane.
        if (activity.hrSamples.isNotEmpty()) {
            repo.insertHr(activity.hrSamples.map { HrSample(deviceId = deviceId, ts = it.ts, bpm = it.bpm) })
        }
        // The watch's own Training Effect / training load (a FIT session's reference values), so the workout
        // detail can show them.
        val reference = GarminWatchIngest.activityReference(activity)
        if (reference.isNotEmpty()) repo.insert(StreamBatch(deviceMetric = reference), deviceId)
        // #1058: recompute the day's activity-file step total as the SUM over ALL that day's sessions
        // (each now carries its own steps), so a second file for the same day ADDS to the first instead
        // of clobbering it. Idempotent on re-import: the file's workout row (keyed on startTs+sport) is
        // replaced, not duplicated, so the re-summed total is unchanged. Only recompute when THIS file
        // contributed steps (a foot sport); a cycling import leaves the day's step total untouched.
        var dayStepsWritten = false
        if (activity.steps != null && activity.steps > 0) {
            val zone = java.time.ZoneId.systemDefault()
            val localDate = Instant.ofEpochSecond(activity.startTs).atZone(zone).toLocalDate()
            val dayStart = localDate.atStartOfDay(zone).toEpochSecond()
            val dayEnd = localDate.plusDays(1).atStartOfDay(zone).toEpochSecond()
            val daySteps = repo.sumWorkoutSteps(deviceId, dayStart, dayEnd)
            if (daySteps > 0) {
                repo.upsertDailyMetrics(
                    listOf(DailyMetric(deviceId = deviceId, day = localDayString(activity.startTs), steps = daySteps)),
                )
                dayStepsWritten = true
            }
        }

        val counts = linkedMapOf("workouts" to 1)
        if (dayStepsWritten) counts["dailyMetric"] = 1
        return ImportSummary(
            source = SOURCE_LABEL,
            counts = counts,
            firstDay = dayString(activity.startTs),
            lastDay = dayString(activity.endTs),
            message = summaryText(activity),
            localized = { summaryTextLocalized(activity) },
        )
    }

    /** Parse raw bytes, auto-detecting the format. Never throws — a bad file yields activity == null. */
    fun parse(data: ByteArray, filename: String? = null): Result {
        if (data.size > MAX_BYTES) return Result(null, Kind.GPX, 0)
        return when (detectFormat(filename, data)) {
            Format.GPX -> parseXml(data, Kind.GPX)
            Format.TCX -> parseXml(data, Kind.TCX)
            Format.FIT -> FitDecoder(data).decode()
            Format.UNKNOWN -> {
                val g = parseXml(data, Kind.GPX); if (g.hasActivity) return g
                val t = parseXml(data, Kind.TCX); if (t.hasActivity) return t
                FitDecoder(data).decode()
            }
        }
    }

    // MARK: - XML (GPX + TCX) via a swappable pull-parser

    /**
     * Pull-parser factory. Production uses the framework `Xml.newPullParser()`; JVM tests swap in a
     * kXML2 parser (android.util.Xml is a throwing stub off-device). Mirrors AppleHealthImporter.
     */
    @Volatile
    internal var newPullParser: () -> XmlPullParser = { Xml.newPullParser() }

    /**
     * Stream-parse a GPX or TCX document with a single START/TEXT/END event loop and a text buffer
     * (the AppleHealthImporter pattern). Element text is accumulated on TEXT events and committed to the
     * right field on END_TAG — robust against mixed content and never advances the parser out of band.
     */
    private fun parseXml(data: ByteArray, kind: Kind): Result {
        val samples = ArrayList<Sample>()
        var sport: String? = null
        var skipped = 0
        // TCX summary accumulators.
        var lapDistanceSum = 0.0
        var maxCumulativeDistance = 0.0
        var calorieSum = 0
        var lapMaxHr: Int? = null

        try {
            val parser = newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            // SECURITY: never resolve external DTDs/entities (XXE / billion-laughs). Not every
            // implementation exposes these, so set them best-effort.
            runCatching { parser.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            parser.setInput(ByteArrayInputStream(Bom.stripUtf8(data)), null)

            var depth = 0
            var inTrkpt = false
            var curLat: Double? = null
            var curLon: Double? = null
            var curEle: Double? = null
            var curTime: Long? = null
            var curHr: Int? = null
            var hrContext = 0 // 0 none, 1 trackpoint HR, 2 lap-max HR (TCX)
            val nameStack = ArrayList<String>()
            val text = StringBuilder()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (depth > 256) return buildXml(kind, samples, sport, lapDistanceSum, maxCumulativeDistance, calorieSum, lapMaxHr, skipped)
                        text.setLength(0)
                        val name = local(parser.name)
                        nameStack.add(name)
                        when (name) {
                            "trkpt", "rtept", "wpt" -> {           // GPX point
                                inTrkpt = true
                                curLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                                curLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                                curEle = null; curTime = null; curHr = null
                            }
                            "trackpoint" -> {                       // TCX point
                                inTrkpt = true
                                curLat = null; curLon = null; curEle = null; curTime = null; curHr = null
                            }
                            "activity" ->                            // TCX Activity Sport="..."
                                if (sport == null) parser.getAttributeValue(null, "Sport")?.takeIf { it.isNotEmpty() }?.let { sport = it }
                            "heartratebpm" -> hrContext = 1
                            "maximumheartratebpm" -> hrContext = 2
                        }
                    }
                    XmlPullParser.TEXT -> {
                        // Cap accumulated text so a giant text node can't balloon memory.
                        if (text.length < 4096) parser.text?.let { text.append(it) }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = local(parser.name)
                        val trimmed = text.toString().trim()
                        when (name) {
                            // GPX
                            "ele" -> if (inTrkpt) trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { curEle = it }
                            "time" -> if (inTrkpt) curTime = parseTimeS(trimmed)
                            "hr" -> if (inTrkpt) curHr = validHr(trimmed.toDoubleOrNull())
                            "heartrate" -> if (inTrkpt && curHr == null) curHr = validHr(trimmed.toDoubleOrNull())
                            "type" -> if (sport == null && trimmed.isNotEmpty() && nameStack.contains("trk")) sport = trimmed
                            // TCX
                            "latitudedegrees" -> if (inTrkpt) curLat = trimmed.toDoubleOrNull()
                            "longitudedegrees" -> if (inTrkpt) curLon = trimmed.toDoubleOrNull()
                            "altitudemeters" -> if (inTrkpt) trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { curEle = it }
                            "value" -> when (hrContext) {
                                1 -> if (inTrkpt) curHr = validHr(trimmed.toDoubleOrNull())
                                2 -> validHr(trimmed.toDoubleOrNull())?.let { lapMaxHr = maxOf(lapMaxHr ?: 0, it) }
                            }
                            "heartratebpm", "maximumheartratebpm" -> hrContext = 0
                            "distancemeters" -> {
                                val v = trimmed.toDoubleOrNull()
                                if (v != null && v.isFinite() && v >= 0) {
                                    if (inTrkpt) maxCumulativeDistance = maxOf(maxCumulativeDistance, v)
                                    // Directly under a Lap (parent is the element below this one on the stack).
                                    else if (nameStack.size >= 2 && nameStack[nameStack.size - 2] == "lap") lapDistanceSum += v
                                }
                            }
                            "calories" -> trimmed.toIntOrNull()?.takeIf { it in 0 until 100_000 }?.let { calorieSum += it }
                            "trkpt", "rtept", "wpt", "trackpoint" -> {
                                val finished = finishPoint(curLat, curLon, curEle, curTime, curHr)
                                if (finished == null) { skipped++ } else {
                                    if (samples.size >= MAX_POINTS) return buildXml(kind, samples, sport, lapDistanceSum, maxCumulativeDistance, calorieSum, lapMaxHr, skipped)
                                    samples.add(finished)
                                }
                                inTrkpt = false
                            }
                        }
                        if (nameStack.isNotEmpty()) nameStack.removeAt(nameStack.size - 1)
                        depth--
                        text.setLength(0)
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // Tolerant: keep whatever we decoded before a hard XML error.
        }

        return buildXml(kind, samples, sport, lapDistanceSum, maxCumulativeDistance, calorieSum, lapMaxHr, skipped)
    }

    private fun finishPoint(lat: Double?, lon: Double?, ele: Double?, time: Long?, hr: Int?): Sample? {
        val point = validCoordinate(lat, lon)
        if (point == null && time == null && hr == null) return null
        return Sample(timeS = time, point = point, elevationM = ele, hr = hr)
    }

    private fun buildXml(
        kind: Kind, samples: List<Sample>, sport: String?,
        lapDistanceSum: Double, maxCumulativeDistance: Double, calorieSum: Int, lapMaxHr: Int?,
        skipped: Int,
    ): Result {
        val summaryDistance = when {
            kind == Kind.TCX && lapDistanceSum > 0 -> lapDistanceSum
            kind == Kind.TCX && maxCumulativeDistance > 0 -> maxCumulativeDistance
            else -> null
        }
        val summaryEnergy = if (kind == Kind.TCX && calorieSum > 0) calorieSum.toDouble() else null
        return build(kind, samples, sport, summaryDistance, summaryEnergy, null, lapMaxHr.takeIf { kind == Kind.TCX }, null, skipped)
    }

    // MARK: - Shared post-processing (mirrors Swift ActivityFileImporter.build)

    internal fun build(
        kind: Kind,
        samples: List<Sample>,
        sportHint: String?,
        summaryDistanceM: Double?,
        summaryEnergyKcal: Double?,
        summaryAvgHr: Int?,
        summaryMaxHr: Int?,
        summaryAscentM: Double?,
        skipped: Int,
    ): Result {
        if (samples.isEmpty()) return Result(null, kind, skipped)

        val route = samples.mapNotNull { it.point }
        val times = samples.mapNotNull { it.timeS }
        if (times.isEmpty()) {
            // A pure coordinate track (no timestamps): keep it but with no interval.
            if (route.isEmpty()) return Result(null, kind, skipped)
            val dist = summaryDistanceM ?: if (route.size >= 2) routeDistanceM(route) else null
            val a = Activity(kind, 0L, 0L, sportHint, dist, summaryEnergyKcal, null, summaryAvgHr, summaryMaxHr,
                summaryAscentM, route.size, 0, cappedRoute(route))
            return Result(a, kind, skipped)
        }

        val start = times.min()
        val end = maxOf(times.max(), start)
        val hrs = samples.mapNotNull { it.hr }

        val distance = summaryDistanceM ?: if (route.size >= 2) routeDistanceM(route) else null
        val ascent = summaryAscentM ?: ascentM(samples)
        val avg = summaryAvgHr ?: if (hrs.isEmpty()) null else (hrs.sum().toDouble() / hrs.size).roundToInt()
        val mx = summaryMaxHr ?: hrs.maxOrNull()

        val a = Activity(
            kind = kind,
            startTs = start,
            endTs = end,
            sport = sportHint,
            distanceM = distance,
            energyKcal = summaryEnergyKcal,
            steps = null,
            avgHr = avg,
            maxHr = mx,
            ascentM = ascent,
            gpsPointCount = route.size,
            hrSampleCount = hrs.size,
            route = cappedRoute(route),
            // #137: carry the real per-sample HR through (only timestamped+HR samples survive). The
            // no-timestamp path above leaves this empty — those samples have no epoch to key the store.
            hrSamples = hrSamples(samples),
        )
        return Result(a, kind, skipped)
    }

    // MARK: - Geo / summary math (parity with Swift + RouteMath)

    internal fun routeDistanceM(points: List<RoutePoint>): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until points.size) {
            sum += RouteMath.haversineMeters(
                RouteMath.LatLng(points[i - 1].lat, points[i - 1].lon),
                RouteMath.LatLng(points[i].lat, points[i].lon),
            )
        }
        return sum
    }

    internal fun ascentM(samples: List<Sample>): Double? {
        val elevs = samples.mapNotNull { it.elevationM }
        if (elevs.size < 2) return null
        var gain = 0.0
        for (i in 1 until elevs.size) {
            val d = elevs[i] - elevs[i - 1]
            if (d > 1.0) gain += d
        }
        return gain
    }

    internal fun cappedRoute(route: List<RoutePoint>): List<RoutePoint> =
        if (route.size <= MAX_POINTS) route else route.take(MAX_POINTS)

    // MARK: - Coordinate / value validation (shared, parity with Swift)

    internal fun validCoordinate(lat: Double?, lon: Double?): RoutePoint? {
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return null
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        if (lat == 0.0 && lon == 0.0) return null // "null island" pre-lock fix
        return RoutePoint(lat, lon)
    }

    internal fun validHr(v: Double?): Int? {
        if (v == null || !v.isFinite() || v < 1 || v > 300) return null
        return v.roundToInt()
    }

    /** Parse a GPX/TCX ISO-8601 timestamp into UTC epoch seconds (shared WhoopTime parser). */
    internal fun parseTimeS(raw: String?): Long? {
        val t = raw?.trim() ?: return null
        if (t.isEmpty()) return null
        return WhoopTime.parseEpochSeconds(t, 0)
    }

    private fun local(name: String?): String {
        val n = name ?: return ""
        val colon = n.lastIndexOf(':')
        return (if (colon >= 0) n.substring(colon + 1) else n).lowercase()
    }

    // MARK: - App-layer mapping helpers (parity with Swift workoutSport / summaryText)

    /** Sport label the imported workout is filed under; unknown/absent → neutral "Activity". */
    fun workoutSport(raw: String?): String {
        val r = raw?.trim()
        if (r.isNullOrEmpty()) return "Activity"
        return when (r.lowercase()) {
            "run", "running", "treadmill_running", "trail_running" -> "Running"
            "ride", "bike", "biking", "cycling", "road_biking", "mountain_biking", "virtual_cycling", "indoor_cycling" -> "Cycling"
            "swim", "swimming", "lap_swimming", "open_water_swimming" -> "Swimming"
            "walk", "walking" -> "Walking"
            "hike", "hiking" -> "Hiking"
            "strength_training", "strength", "weight_training" -> "Strength Training"
            "cardio" -> "Cardio"
            "rowing", "row" -> "Rowing"
            "generic", "other", "fitness_equipment", "training" -> "Activity"
            else -> if (r.contains(" ")) r else r.replaceFirstChar { it.uppercase() }
        }
    }

    /** One-line status string for the import UI — only what the file actually carried. */
    fun summaryText(a: Activity): String {
        val head = StringBuilder("Imported")
        if (a.distanceM != null && a.distanceM > 0) {
            val km = a.distanceM / 1000.0
            head.append(if (km >= 10) " a ${km.roundToInt()} km" else String.format(Locale.US, " a %.2f km", km))
        } else {
            head.append(" an")
        }
        head.append(" ${workoutSport(a.sport)} activity")
        val parts = ArrayList<String>(4)
        parts.add(head.toString())
        if (a.gpsPointCount > 0) parts.add("${a.gpsPointCount} GPS points")
        if (a.hrSampleCount > 0) parts.add("${a.hrSampleCount} HR samples")
        if (a.avgHr != null) parts.add("avg ${a.avgHr} bpm")
        if (a.steps != null && a.steps > 0) parts.add("${a.steps} steps")
        return parts.joinToString(" · ")
    }


    private fun dayString(ts: Long): String =
        Instant.ofEpochSecond(ts).atOffset(ZoneOffset.UTC).toLocalDate().toString()

    private fun localDayString(ts: Long): String =
        Instant.ofEpochSecond(ts).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()
}

// MARK: - FIT binary decoder (mirrors Swift FitParser / FitDecoder)

/**
 * Decodes the FIT binary format: a 12/14-byte header, then definition + data records (little- or
 * big-endian). Decodes the common file_id(0) / record(20) / lap(19) / session(18) messages. The record
 * walk lives in [FitRecordStream], shared with [GarminWellnessParser], which reads the health messages
 * of the same files. Here developer fields are skipped, compressed-timestamp headers carry no time
 * into the track, and every other message type is skipped gracefully.
 */
internal class FitDecoder(private val raw: ByteArray) {

    private val stream: FitRecordStream? = FitRecordStream.open(Bom.stripUtf8(raw))

    private val samples = ArrayList<ActivityFileImporter.Sample>()
    private var gpsPointCount = 0
    private var hrSampleCount = 0
    private var skipped = 0

    private var sessionSport: String? = null
    private var sessionStartS: Long? = null
    private var sessionElapsedS: Double? = null
    private var sessionDistance: Double? = null
    private var sessionCalories: Double? = null
    private var sessionSteps: Int? = null
    private var sessionAscent: Double? = null
    private var sessionAvgHr: Int? = null
    private var sessionMaxHr: Int? = null
    private var sessionAerobicTE: Double? = null
    private var sessionAnaerobicTE: Double? = null
    private var sessionTrainingLoad: Double? = null
    private var sessionAvgPower: Int? = null
    private var sessionAvgCadence: Double? = null

    private var lapDistanceSum = 0.0
    private var lapCalorieSum = 0.0
    private var lapStepSum = 0
    private var lapAscentSum = 0.0
    private var lapMaxHr: Int? = null

    fun decode(): ActivityFileImporter.Result {
        val stream = stream ?: return fail()
        stream.walk(FitRecordStream.MAX_RECORDS) { def, offset, _ -> consume(def, offset) }
        val result = build()
        if (result.activity != null) return result
        // No workout: a Garmin wellness file (monitoring, sleep, HRV...) decodes here instead.
        val wellness = GarminWellnessParser.parse(raw)
        return if (wellness.isEmpty) result else result.copy(wellness = wellness)
    }

    private fun fail() = ActivityFileImporter.Result(null, ActivityFileImporter.Kind.FIT, skipped)

    private fun consume(def: FitRecordStream.Definition, start: Int) {
        when (def.globalNum) {
            0 -> { /* file_id — type field; we don't gate on it, parity with Swift */ }
            20 -> consumeRecord(def, start)
            19 -> consumeLap(def, start)
            18 -> consumeSession(def, start)
        }
    }

    private fun consumeRecord(def: FitRecordStream.Definition, start: Int) {
        var off = start
        var lat: Double? = null; var lon: Double? = null
        var ele: Double? = null; var hr: Int? = null; var time: Long? = null
        var enhancedEle: Double? = null
        var cadence: Long? = null; var fraction = 0.0; var power: Int? = null
        for (f in def.fields) {
            when (f.num) {
                253 -> u(off, f.size, def.bigEndian)?.let { time = fitDate(it) }
                0 -> s(off, f.size, def.bigEndian)?.let { lat = semicircles(it) }
                1 -> s(off, f.size, def.bigEndian)?.let { lon = semicircles(it) }
                2 -> u(off, f.size, def.bigEndian)?.let { if (!isInvalid(it, f.size)) ele = it.toDouble() / 5.0 - 500.0 }
                // enhanced_altitude: modern watches write only this one; it wins over the legacy field.
                78 -> u(off, f.size, def.bigEndian)?.let { if (!isInvalid(it, f.size)) enhancedEle = it.toDouble() / 5.0 - 500.0 }
                4 -> u(off, f.size, def.bigEndian)?.let { if (!isInvalid(it, f.size)) cadence = it }
                53 -> u(off, f.size, def.bigEndian)?.let { if (!isInvalid(it, f.size)) fraction = it.toDouble() / 128.0 }
                7 -> u(off, f.size, def.bigEndian)?.let { if (!isInvalid(it, f.size)) power = it.toInt() }
                3 -> u(off, f.size, def.bigEndian)?.let { hr = ActivityFileImporter.validHr(it.toDouble()) }
            }
            off += f.size
        }
        val point = ActivityFileImporter.validCoordinate(lat, lon)
        if (point == null && time == null && hr == null) { skipped++; return }
        if (samples.size >= ActivityFileImporter.MAX_POINTS) return
        if (point != null) gpsPointCount++
        if (hr != null) hrSampleCount++
        val altitude = enhancedEle ?: ele
        val cycles = cadence?.let { it.toDouble() + fraction }
        samples.add(ActivityFileImporter.Sample(time, point, altitude, hr, cycles, power))
    }

    private fun consumeLap(def: FitRecordStream.Definition, start: Int) {
        var off = start
        for (f in def.fields) {
            when (f.num) {
                9 -> u(off, f.size, def.bigEndian)?.let { if (it != 0xFFFFFFFFL) lapDistanceSum += it.toDouble() / 100.0 }
                11 -> u(off, f.size, def.bigEndian)?.let { if (it != 0xFFFFL) lapCalorieSum += it.toDouble() }
                10 -> u(off, f.size, def.bigEndian)?.let { validSteps(it, f.size)?.let { steps -> lapStepSum += steps } }
                21 -> u(off, f.size, def.bigEndian)?.let { if (it != 0xFFFFL) lapAscentSum += it.toDouble() }
                16 -> u(off, f.size, def.bigEndian)?.let { v -> ActivityFileImporter.validHr(v.toDouble())?.let { lapMaxHr = maxOf(lapMaxHr ?: 0, it) } }
            }
            off += f.size
        }
    }

    private fun consumeSession(def: FitRecordStream.Definition, start: Int) {
        var off = start
        for (f in def.fields) {
            when (f.num) {
                2 -> u(off, f.size, def.bigEndian)?.let { sessionStartS = fitDate(it) }
                5 -> u(off, f.size, def.bigEndian)?.let { sessionSport = sportName(it) }
                7 -> u(off, f.size, def.bigEndian)?.let { if (it != 0xFFFFFFFFL) sessionElapsedS = it.toDouble() / 1000.0 }
                9 -> u(off, f.size, def.bigEndian)?.let { if (it != 0xFFFFFFFFL) sessionDistance = it.toDouble() / 100.0 }
                11 -> u(off, f.size, def.bigEndian)?.let { if (it != 0xFFFFL) sessionCalories = it.toDouble() }
                10 -> u(off, f.size, def.bigEndian)?.let { sessionSteps = validSteps(it, f.size) }
                22 -> u(off, f.size, def.bigEndian)?.let { if (it != 0xFFFFL) sessionAscent = it.toDouble() }
                16 -> u(off, f.size, def.bigEndian)?.let { sessionAvgHr = ActivityFileImporter.validHr(it.toDouble()) }
                17 -> u(off, f.size, def.bigEndian)?.let { sessionMaxHr = ActivityFileImporter.validHr(it.toDouble()) }
                18 -> u(off, f.size, def.bigEndian)?.let { if (!isInvalid(it, f.size) && it > 0) sessionAvgCadence = it.toDouble() }
                20 -> u(off, f.size, def.bigEndian)?.let { if (!isInvalid(it, f.size) && it > 0) sessionAvgPower = it.toInt() }
                // total_training_effect / total_anaerobic_training_effect (u8, scale 10), training_load_peak (s32, scale 65536).
                24 -> u(off, f.size, def.bigEndian)?.let { sessionAerobicTE = trainingEffect(it, f.size) }
                137 -> u(off, f.size, def.bigEndian)?.let { sessionAnaerobicTE = trainingEffect(it, f.size) }
                168 -> s(off, f.size, def.bigEndian)?.let { sessionTrainingLoad = trainingLoad(it, f.size) }
            }
            off += f.size
        }
    }

    /** True when [v] is the all-ones "invalid" sentinel for an unsigned field of [size] bytes.
     *  Swift twin: `FitDecoder.isInvalid`. */
    private fun isInvalid(v: Long, size: Int): Boolean = size in 1..4 && v == (1L shl (size * 8)) - 1L

    private fun u(off: Int, size: Int, bigEndian: Boolean): Long? = stream?.uint(off, size, bigEndian)

    private fun s(off: Int, size: Int, bigEndian: Boolean): Long? = stream?.int(off, size, bigEndian)

    private fun semicircles(v: Long): Double? {
        if (v == 0x7FFFFFFFL) return null
        val deg = v.toDouble() * (180.0 / 2_147_483_648.0)
        return if (deg.isFinite()) deg else null
    }

    private fun fitDate(v: Long): Long? {
        if (v == 0xFFFFFFFFL) return null
        val unix = v + 631_065_600L
        if (unix <= 631_065_600L || unix >= 4_102_444_800L) return null
        return unix
    }

    private fun sportName(v: Long): String? = when (v) {
        0L -> "Generic"; 1L -> "Running"; 2L -> "Cycling"; 5L -> "Swimming"
        11L -> "Walking"; 13L -> "Strength Training"; 14L -> "Cardio"; 15L -> "Hiking"
        17L -> "Hiking"; 4L -> "Fitness Equipment"; 10L -> "Training"
        else -> null
    }

    /** A Training Effect byte as the 0.0-5.0 score, or null for the invalid sentinel or an out-of-range value.
     *  Swift twin: `FitDecoder.trainingEffect`. */
    private fun trainingEffect(v: Long, size: Int): Double? =
        if (size == 1 && v in 0..50L) v.toDouble() / 10 else null

    /** A training-load field as the watch's load figure, or null for the invalid sentinel, a negative value or
     *  a field of the wrong width. Swift twin: `FitDecoder.trainingLoad`. */
    private fun trainingLoad(v: Long, size: Int): Double? =
        if (size == 4 && v != 0x7FFFFFFFL && v >= 0) v.toDouble() / 65_536 else null

    private fun validSteps(v: Long, size: Int): Int? {
        val invalid = if (size >= 8) -1L else (1L shl (size * 8)) - 1L
        return if (v != invalid && v in 1..1_000_000L) v.toInt() else null
    }

    private fun isFootSport(sport: String?): Boolean = when (sport?.lowercase(Locale.US)) {
        "running", "walking", "hiking" -> true
        else -> false
    }

    private fun build(): ActivityFileImporter.Result {
        if (samples.isEmpty() && sessionStartS == null) return fail()
        val route = samples.mapNotNull { it.point }
        val times = samples.mapNotNull { it.timeS }

        val distance = sessionDistance
            ?: if (lapDistanceSum > 0) lapDistanceSum else if (route.size >= 2) ActivityFileImporter.routeDistanceM(route) else null
        val calories = sessionCalories ?: if (lapCalorieSum > 0) lapCalorieSum else null
        // FIT field 10 is total_cycles — for foot sports that's STRIDES, and one stride = two steps, so
        // double it to the real step count. Reading it raw showed exactly half (a walk logged as 1150
        // strides read as 1150 steps instead of 2300, #568). Non-foot sports carry no steps.
        val steps = if (isFootSport(sessionSport)) {
            (sessionSteps ?: if (lapStepSum > 0) lapStepSum else null)?.let { it * 2 }
        } else {
            null
        }
        val ascent = sessionAscent ?: if (lapAscentSum > 0) lapAscentSum else ActivityFileImporter.ascentM(samples)

        val cadenceFactor = if (isFootSport(sessionSport)) 2.0 else 1.0

        val sampledHrs = samples.mapNotNull { it.hr }
        val avgHr = sessionAvgHr ?: if (sampledHrs.isEmpty()) null else (sampledHrs.sum().toDouble() / sampledHrs.size).roundToInt()
        val maxHr = sessionMaxHr ?: lapMaxHr ?: sampledHrs.maxOrNull()

        val start = times.minOrNull() ?: sessionStartS ?: 0L
        val computedEnd = times.maxOrNull() ?: start
        val end = if (sessionStartS != null && sessionElapsedS != null && sessionElapsedS!! > 0)
            maxOf((sessionStartS!! + sessionElapsedS!!.toLong()), computedEnd) else computedEnd

        val activity = ActivityFileImporter.Activity(
            kind = ActivityFileImporter.Kind.FIT,
            startTs = minOf(start, sessionStartS ?: start),
            endTs = maxOf(end, start),
            sport = sessionSport,
            distanceM = distance,
            energyKcal = calories,
            steps = steps,
            avgHr = avgHr,
            maxHr = maxHr,
            ascentM = ascent,
            gpsPointCount = gpsPointCount,
            hrSampleCount = hrSampleCount,
            route = ActivityFileImporter.cappedRoute(route),
            // #137: the real per-record HR series, via the SHARED extractor so FIT persists a
            // byte-identical HR stream to the GPX/TCX paths (and the Swift twin).
            hrSamples = ActivityFileImporter.hrSamples(samples),
            aerobicTrainingEffect = sessionAerobicTE,
            anaerobicTrainingEffect = sessionAnaerobicTE,
            trainingLoad = sessionTrainingLoad,
            avgPowerW = sessionAvgPower,
            avgCadence = sessionAvgCadence?.let { it * cadenceFactor },
            trackMetrics = ActivityFileImporter.trackMetrics(samples, cadenceFactor),
        )
        return ActivityFileImporter.Result(activity, ActivityFileImporter.Kind.FIT, skipped)
    }
}

