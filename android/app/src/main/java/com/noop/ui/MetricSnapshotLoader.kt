package com.noop.ui

import com.noop.analytics.HydrationStore
import com.noop.analytics.LabMarkerCategory
import com.noop.analytics.MarkerCatalog
import com.noop.analytics.MetricSnapshot
import com.noop.analytics.MetricSnapshotBuilder
import com.noop.analytics.MetricSnapshotCatalog
import com.noop.analytics.MetricSnapshotCategory
import com.noop.analytics.MetricSnapshotFormat
import com.noop.analytics.MetricSnapshotNote
import com.noop.analytics.WeeklyDigestInputs
import com.noop.data.CycleTrackingStore
import com.noop.data.DeviceMetricKind
import com.noop.data.DeviceMetricSampleEntity
import com.noop.data.DeviceRegistry
import com.noop.data.JournalEntry
import com.noop.data.LabMarkerRow
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Best-effort read: [default] when [block] throws, but a cancelled coroutine stays cancelled (unlike
 * `runCatching`, which would swallow the [CancellationException]).
 */
internal suspend inline fun <T> safeRead(default: T, block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        default
    }

/**
 * The store half of the metric snapshot: reads every stored metric for a day range and hands the per-day
 * values to the pure [MetricSnapshotBuilder]. Not a Composable, so the Daily summary page, the weekly digest
 * and the AI Coach context read through the same calls.
 *
 * Entry points (ticket T4 builds the Coach context from these):
 *  - [dayValues]: catalog item id → day → value, for [MetricSnapshotBuilder.build] or [WeeklyDigestInputs.series]
 *  - [notes]: journal answers and lab readings as free-text notes
 *  - [snapshot]: both, built into a [MetricSnapshot]
 *
 * Swift twin: `MetricSnapshotLoader`.
 */
object MetricSnapshotLoader {

    /** One workout as the day aggregation reads it, with its device samples already reduced. Swift counterpart: SnapshotWorkout. */
    data class WorkoutInput(
        val day: String,
        val durationMin: Double,
        val energyKcal: Double? = null,
        val distanceKm: Double? = null,
        val avgHr: Double? = null,
        val maxHr: Double? = null,
        val effort: Double? = null,
        /** Zone percentages Z1..Z5 (0–100), or null when the workout has none. */
        val zonePercents: List<Double>? = null,
        val avgPowerW: Double? = null,
        val avgCadence: Double? = null,
        val altitudeGainM: Double? = null,
        val maxAltitudeM: Double? = null,
        val aerobicTe: Double? = null,
        val anaerobicTe: Double? = null,
    )

    /** The Garmin export import's source id (`WearableExportImporter.Brand.GARMIN.sourceId`). */
    private const val GARMIN_IMPORT = "garmin-import"

    /** A cycle start further back than this is not "the current cycle" (no cycle day is reported). */
    const val MAX_CYCLE_DAYS = 90

    /**
     * Every catalog item's per-day values over the inclusive range [from]..[to] ("yyyy-MM-dd"), keyed by
     * [com.noop.analytics.MetricSnapshotItem.id]. A day with no reading is absent; every read is
     * best-effort, so one failing store never blanks the others. Swift twin: `MetricSnapshotLoader.dayValues`.
     */
    suspend fun dayValues(
        repo: WhoopRepository,
        registry: DeviceRegistry?,
        activeDeviceId: String,
        from: String,
        to: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<String, Map<String, Double>> {
        val out = LinkedHashMap<String, MutableMap<String, Double>>()
        fun put(id: String, day: String, v: Double?) {
            if (v == null || !v.isFinite()) return
            out.getOrPut(id) { LinkedHashMap() }[day] = v
        }
        /** Fill-missing-only: never overwrites a day an earlier read already set. */
        fun putMissing(id: String, day: String, v: Double?) {
            if (out[id]?.containsKey(day) == true) return
            put(id, day, v)
        }
        val watch = safeRead<String?>(null) { WeeklyDigestLoader.watchId(repo, registry, activeDeviceId, to) }

        // Stored series: the first source with a value per day wins; the paired watch is read last for VO2 max.
        for (item in MetricSnapshotCatalog.allItems()) {
            val key = item.seriesKey ?: continue
            val sources = if (item.id == "vo2max" && watch != null) item.sources + watch else item.sources
            for (src in sources) {
                val rows = safeRead(emptyList()) { repo.resolvedSeries(key, src, from, to, strapDeviceId = activeDeviceId).points }
                for (p in rows) if (out[item.id]?.containsKey(p.day) != true) put(item.id, p.day, p.value)
            }
        }

        // Daily-row columns that have no series key (the active strap's row wins over the canonical import).
        for (id in listOf(WhoopRepository.WHOOP_SOURCE, activeDeviceId).distinct()) {
            for (d in safeRead(emptyList()) { repo.dailyMetrics(id, from, to) }) {
                put("disturbances", d.day, d.disturbances?.toDouble())
                put("sdnn", d.day, d.avgSdnn)
            }
        }

        // Hydration: hand-logged plus imported water.
        val water = HashMap<String, Double>()
        for (key in listOf(HydrationStore.KEY, HydrationStore.KEY_IMPORTED)) {
            safeRead(emptyList()) { repo.metricSeries(HydrationStore.SOURCE_ID, key, from, to) }
                .forEach { water.merge(it.day, it.value, Double::plus) }
        }
        // A Garmin export's logged water joins the day's total (its activity sweat loss is a separate item).
        safeRead(emptyList()) { repo.metricSeries(GARMIN_IMPORT, "hydration_ml", from, to) }
            .forEach { water.merge(it.day, it.value, Double::plus) }
        hydrationDays(water).forEach { (d, v) -> put("hydration_ml", d, v) }

        // Distance: the strap's series first, then the watch's fills only the days still missing (metres to km).
        for (p in safeRead(emptyList()) {
            repo.resolvedSeries("distance_m", WhoopRepository.WHOOP_SOURCE, from, to, strapDeviceId = activeDeviceId).points
        }) put("distance_km", p.day, p.value / 1000.0)
        if (watch != null) {
            for (r in safeRead(emptyList()) { repo.metricSeries(watch, "distance_m", from, to) }) {
                putMissing("distance_km", r.day, r.value / 1000.0)
            }
        }

        // Cycle day from the logged period starts.
        val starts = safeRead(emptyList()) { CycleTrackingStore(repo).starts() }
        if (starts.isNotEmpty()) {
            var day = LocalDate.parse(from)
            val end = LocalDate.parse(to)
            while (!day.isAfter(end)) {
                cycleDay(starts, day.toString())?.let { put("cycle_day", day.toString(), it.toDouble()) }
                day = day.plusDays(1)
            }
        }

        // Workouts.
        val fromTs = LocalDate.parse(from).atStartOfDay(zone).toEpochSecond()
        val toTs = LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toEpochSecond() - 1
        val inputs = safeRead(emptyList()) { workoutInputs(repo, activeDeviceId, watch, fromTs, toTs, zone) }
        for ((day, vals) in workoutDays(inputs)) for ((id, v) in vals) put(id, day, v)

        // The paired watch's own series.
        if (watch != null) {
            for ((id, days) in watchValues(repo, watch, from, to, fromTs, toTs, zone)) {
                for ((d, v) in days) putMissing(id, d, v)
            }
        }

        // Body Battery low / high from a Garmin export, for the days the paired watch holds none. Swift twin:
        // `MetricSnapshotLoader.dayValues`.
        for ((id, key) in listOf("body_battery_min" to "body_battery_low", "body_battery_max" to "body_battery_high")) {
            for (r in safeRead(emptyList()) { repo.metricSeries(GARMIN_IMPORT, key, from, to) }) putMissing(id, r.day, r.value)
        }
        return out
    }

    /**
     * Journal answers and lab readings dated within [from]..[to], as free-text notes. Journal rows from
     * the WHOOP import and the native journal are merged (a native answer wins its question/day); lab
     * readings come from every Lab Book category under the active strap. Swift twin: `MetricSnapshotLoader.notes`.
     */
    suspend fun notes(
        repo: WhoopRepository,
        activeDeviceId: String,
        from: String,
        to: String,
    ): List<MetricSnapshotNote> {
        val out = ArrayList<MetricSnapshotNote>()
        val journal = LinkedHashMap<Pair<String, String>, JournalEntry>()
        for (id in listOf("my-whoop", JOURNAL_DEVICE_ID)) {
            safeRead(emptyList()) { repo.journal(id, from, to) }
                .forEach { journal[it.day to it.question] = it }
        }
        val multiDay = from != to
        for (e in journal.values.sortedWith(compareBy({ it.day }, { it.question }))) {
            out += MetricSnapshotNote(
                MetricSnapshotCategory.MIND, "journal:${e.day}:${e.question}",
                if (multiDay) "${e.question} (${e.day})" else e.question,
                journalText(e.answeredYes, e.numericValue),
            )
        }
        val labs = ArrayList<LabMarkerRow>()
        for (cat in LabMarkerCategory.entries) {
            labs += safeRead(emptyList()) { repo.labMarkersByCategory(activeDeviceId, cat.raw) }
        }
        val multi = from != to
        for (r in labs.filter { it.day in from..to }.sortedWith(compareBy({ it.takenAt }, { it.markerKey }))) {
            val def = MarkerCatalog.definition(r.markerKey)
            val name = def?.displayName ?: r.markerKey
            out += MetricSnapshotNote(
                MetricSnapshotCategory.LABS, "lab:${r.markerKey}:${r.takenAt}", if (multi) "$name (${r.day})" else name,
                labText(r.value, r.valueText, r.unit, def?.decimals ?: 1),
            )
        }
        return out
    }

    /**
     * The whole snapshot for the inclusive range [from]..[to]: a one-day range is a day's snapshot, a longer one
     * rolls each metric up by its catalog roll-up. Swift twin: `MetricSnapshotLoader.snapshot`.
     */
    suspend fun snapshot(
        repo: WhoopRepository,
        registry: DeviceRegistry?,
        activeDeviceId: String,
        from: String,
        to: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MetricSnapshot = MetricSnapshotBuilder.build(
        from, to,
        dayValues(repo, registry, activeDeviceId, from, to, zone),
        notes(repo, activeDeviceId, from, to),
    )

    // MARK: - Store reads

    private suspend fun workoutInputs(
        repo: WhoopRepository,
        activeDeviceId: String,
        watchId: String?,
        fromTs: Long,
        toTs: Long,
        zone: ZoneId,
    ): List<WorkoutInput> {
        val rows = ArrayList<WorkoutRow>()
        rows += safeRead(emptyList()) { repo.workoutsUnion(activeDeviceId, fromTs, toTs) }
        rows += safeRead(emptyList()) { repo.detectedWorkoutsUnion(activeDeviceId, fromTs, toTs) }
        for (id in safeRead(emptyList()) { repo.otherWorkoutSourceIds(activeDeviceId, emptyList()) }) {
            rows += safeRead(emptyList()) { repo.workouts(id, fromTs, toTs) }
        }
        return WhoopRepository.dedupWorkoutsByKey(rows).map { w ->
            val ids = listOfNotNull(w.deviceId, watchId).distinct()
            suspend fun samples(kind: DeviceMetricKind): List<DeviceMetricSampleEntity> {
                for (id in ids) {
                    val s = safeRead(emptyList()) { repo.deviceMetricSamples(id, kind, w.startTs, w.endTs) }
                    if (s.isNotEmpty()) return s
                }
                return emptyList()
            }
            workoutInput(
                w, Instant.ofEpochSecond(w.startTs).atZone(zone).toLocalDate().toString(),
                samples(DeviceMetricKind.POWER_W).map { it.value },
                samples(DeviceMetricKind.CADENCE).map { it.value },
                samples(DeviceMetricKind.ALTITUDE_M).sortedBy { it.ts }.map { it.value },
                samples(DeviceMetricKind.AEROBIC_TRAINING_EFFECT).maxOfOrNull { it.value },
                samples(DeviceMetricKind.ANAEROBIC_TRAINING_EFFECT).maxOfOrNull { it.value },
            )
        }
    }

    private suspend fun watchValues(
        repo: WhoopRepository,
        watchId: String,
        from: String,
        to: String,
        fromTs: Long,
        toTs: Long,
        zone: ZoneId,
    ): Map<String, Map<String, Double>> {
        suspend fun daily(key: String): Map<String, Double> = safeRead(emptyMap()) {
            repo.metricSeries(watchId, key, from, to).associate { it.day to it.value }
        }

        suspend fun samples(kind: DeviceMetricKind) = safeRead(emptyList()) {
            repo.deviceMetricSamples(watchId, kind, fromTs, toTs)
        }

        fun dayOf(ts: Long): String = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate().toString()

        suspend fun summed(kind: DeviceMetricKind): Map<String, Double> {
            val m = HashMap<String, Double>()
            for (s in samples(kind)) m.merge(dayOf(s.ts), s.value, Double::plus)
            return m
        }

        val skin = HashMap<String, MutableList<Double>>()
        for (s in samples(DeviceMetricKind.SKIN_TEMP_C)) skin.getOrPut(dayOf(s.ts)) { ArrayList() }.add(s.value)
        return mapOf(
            "watch_stress" to daily(StressTodayLoader.watchStressKey(watchId)),
            "watch_sleep_score" to daily("ref_sleep_score"),
            "body_battery_min" to daily("body_battery_min"),
            "body_battery_max" to daily("body_battery_max"),
            "training_load" to summed(DeviceMetricKind.TRAINING_LOAD),
            "intensity_min" to WeeklyDigestInputs.intensityMinutes(
                summed(DeviceMetricKind.INTENSITY_MODERATE_MIN), summed(DeviceMetricKind.INTENSITY_VIGOROUS_MIN),
            ),
            "body_battery_recharge" to safeRead(emptyMap()) {
                WeeklyDigestLoader.bodyBatteryRecharge(repo, watchId, fromTs, toTs, zone)
            },
            "watch_skin_temp_c" to skin.mapValues { it.value.sum() / it.value.size },
        )
    }

    // MARK: - Pure aggregation (JVM-tested)

    /** [w] with its device samples reduced to the day aggregation's inputs. */
    internal fun workoutInput(
        w: WorkoutRow,
        day: String,
        power: List<Double>,
        cadence: List<Double>,
        altitude: List<Double>,
        aerobicTe: Double?,
        anaerobicTe: Double?,
    ): WorkoutInput = WorkoutInput(
        day = day,
        durationMin = maxOf(w.durationS ?: (w.endTs - w.startTs).toDouble(), 0.0) / 60.0,
        energyKcal = w.energyKcal,
        distanceKm = w.distanceM?.let { it / 1000.0 },
        avgHr = w.avgHr?.toDouble(),
        maxHr = w.maxHr?.toDouble(),
        effort = w.strain,
        zonePercents = parseZonePercents(w.zonesJSON),
        avgPowerW = power.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size },
        avgCadence = cadence.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size },
        altitudeGainM = altitudeGain(altitude),
        maxAltitudeM = altitude.maxOrNull(),
        aerobicTe = aerobicTe,
        anaerobicTe = anaerobicTe,
    )

    /** Total climb: the sum of the positive steps of a time-ordered altitude series; null with fewer than two points. Swift twin: `MetricSnapshotLoader.altitudeGain`. */
    internal fun altitudeGain(altitude: List<Double>): Double? {
        if (altitude.size < 2) return null
        var gain = 0.0
        for (i in 1 until altitude.size) {
            val d = altitude[i] - altitude[i - 1]
            if (d > 0) gain += d
        }
        return gain
    }

    /** Per local day, the workout items of [workoutDay]. Swift twin: `MetricSnapshotLoader.workoutDays`. */
    internal fun workoutDays(inputs: List<WorkoutInput>): Map<String, Map<String, Double>> =
        inputs.groupBy { it.day }.mapValues { (_, ws) -> workoutDay(ws) }

    /**
     * One day's workout items: count, time, energy, distance, duration-weighted average HR, max HR, max
     * effort, zone minutes (duration × percent), duration-weighted power and cadence, climb, max altitude and
     * the Training Effects. A value no workout carried is absent. Swift twin: `MetricSnapshotLoader.workoutDay`.
     */
    internal fun workoutDay(ws: List<WorkoutInput>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        fun dur(w: WorkoutInput) = maxOf(w.durationMin, 0.0)
        out["workout_count"] = ws.size.toDouble()
        out["workout_duration_min"] = ws.sumOf { dur(it) }
        fun sum(id: String, sel: (WorkoutInput) -> Double?) {
            val v = ws.mapNotNull(sel)
            if (v.isNotEmpty()) out[id] = v.sum()
        }
        fun max(id: String, sel: (WorkoutInput) -> Double?) {
            ws.mapNotNull(sel).maxOrNull()?.let { out[id] = it }
        }
        fun weighted(id: String, sel: (WorkoutInput) -> Double?) {
            val pairs = ws.mapNotNull { w -> sel(w)?.let { it to dur(w) } }
            if (pairs.isEmpty()) return
            val total = pairs.sumOf { it.second }
            out[id] = if (total > 0) pairs.sumOf { it.first * it.second } / total else pairs.sumOf { it.first } / pairs.size
        }
        sum("workout_energy_kcal") { it.energyKcal }
        sum("workout_distance_km") { it.distanceKm }
        weighted("workout_avg_hr") { it.avgHr }
        max("workout_max_hr") { it.maxHr }
        max("workout_effort") { it.effort }
        for (z in 0 until 5) {
            val mins = ws.mapNotNull { w -> if (dur(w) > 0) w.zonePercents?.let { dur(w) * it[z] / 100.0 } else null }
            if (mins.isNotEmpty()) out["workout_zone${z + 1}_min"] = mins.sum()
        }
        weighted("workout_avg_power_w") { it.avgPowerW }
        weighted("workout_avg_cadence") { it.avgCadence }
        sum("workout_altitude_gain_m") { it.altitudeGainM }
        max("workout_max_altitude_m") { it.maxAltitudeM }
        max("workout_aerobic_te") { it.aerobicTe }
        max("workout_anaerobic_te") { it.anaerobicTe }
        return out
    }

    /** Hand-logged plus imported water per day, from the summed series; non-positive days dropped. Swift twin: `MetricSnapshotLoader.hydrationDays`. */
    internal fun hydrationDays(summed: Map<String, Double>): Map<String, Double> =
        summed.filterValues { it > 0 }

    /**
     * The 1-based cycle day of [day]: days since the latest logged period start on or before it, plus one;
     * null with no start or when the start is more than [MAX_CYCLE_DAYS] back. Swift twin: `MetricSnapshotLoader.cycleDay`.
     */
    internal fun cycleDay(starts: List<String>, day: String): Int? {
        val start = starts.filter { it <= day }.maxOrNull() ?: return null
        val n = ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(day)).toInt() + 1
        return if (n <= MAX_CYCLE_DAYS) n else null
    }

    /** "Yes" / "No", with a numeric reading appended ("Yes (2)"). Swift twin: `MetricSnapshotLoader.journalText`. */
    internal fun journalText(answeredYes: Boolean, numeric: Double?): String {
        val base = if (answeredYes) "Yes" else "No"
        if (numeric == null) return base
        return "$base (${MetricSnapshotFormat.number(numeric, 1).removeSuffix(".0")})"
    }

    /** "2.9 mmol/L"; the value text when the entry is qualitative. Swift twin: `MetricSnapshotLoader.labText`. */
    internal fun labText(value: Double?, valueText: String?, unit: String, decimals: Int): String {
        val v = value?.let { MetricSnapshotFormat.number(it, decimals) } ?: valueText ?: "-"
        return if (unit.isEmpty()) v else "$v $unit"
    }
}
