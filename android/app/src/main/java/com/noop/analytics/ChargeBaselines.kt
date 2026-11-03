package com.noop.analytics

import com.noop.data.DailyMetric
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/*
 * ChargeBaselines.kt — the personal baselines Charge scores a night against (#2525, #53).
 *
 * Charge scores a night against personal baselines for HRV, resting HR, respiration and skin temperature.
 * #53 replaced the Winsorized EWMA those baselines used to be folded with by a plain trailing mean and SD:
 *
 *   - PER DAY. Each scored day D reads the nights BEFORE it: the calendar window (D − windowDays, D − 1].
 *     Tonight never scores against itself, and a day's baseline does not move when later nights arrive,
 *     so stored Charge history stays stable on every re-score.
 *   - EQUAL WEIGHT. Every night in the window counts once. The EWMA's fast early mode applied per scan
 *     window rather than per wearer, so the OLDEST nights of every window carried the most weight and the
 *     baseline jumped as nights aged out.
 *   - SAMPLE SD. The sample SD of the window is floored per metric, instead of an EWMA spread that started
 *     at the floor and moved with a 21-night half-life.
 *   - HRV IN THE LOG DOMAIN. The HRV baseline is the mean and SD of ln RMSSD (Plews 2012/2013, Buchheit
 *     2014). The state carries `logDomain`, its `baseline` is the geometric mean back in ms, and its σ floor
 *     is 0.05 ln. The same window applies to resting HR, respiration and skin temperature in their own units.
 *   - ERA CUT EVERYWHERE. [epochs] is the ONE place a baseline's start is decided: the manual recalibration
 *     epochs, plus the device-era cuts (#459: a Garmin switch restarts HRV and resting HR, any brand switch
 *     restarts respiration). The engine and the dashboard both call it, so the "What shaped it" rows, the
 *     calibration countdown and the readiness word describe the baseline the ring scored with.
 *
 * Which nights count is the #2525 rule, unchanged except for the window: the wearer's own nights are the
 * baseline, and imported vendor nights (then same-brand wearable-export nights, #31) only seed it while the
 * own nights in the window are fewer than `handoffNights`.
 *
 * Pure and Context-free. Byte-identical twin of the Swift `ChargeBaselines`.
 */
object ChargeBaselines {

    /** Calendar days of nightly history a Charge baseline reads, ending the day before the scored day. */
    const val windowDays: Int = 60

    /** σ floor for the HRV baseline, in ln units: a wearer whose ln RMSSD barely moves still gets a
     *  non-degenerate band (about ±5%), so one ordinary night cannot read as several SDs. */
    const val hrvSigmaFloorLn: Double = 0.05

    /** `scoreInputProvenance` key under which the engine records which source measured each own night, so
     *  the dashboard can rebuild the same device eras the engine cut on. */
    const val baselineSourceKey: String = "charge_baseline_source"

    /** Provenance keys read for a night's source, most specific first. Rows written before #53 carry only
     *  the score keys, which name the same per-day owner. */
    val sourceKeyPrecedence: List<String> = listOf(baselineSourceKey, "recovery", "strain")

    // ─────────────────────────────────────────────────────────────────────────
    // Metrics
    // ─────────────────────────────────────────────────────────────────────────

    /** The four nightly metrics a Charge baseline is kept for. [raw] matches the Swift rawValue. */
    enum class Metric(val raw: String) {
        HRV("hrv"),
        RESTING_HR("rhr"),
        RESP("resp"),
        SKIN_TEMP("skin");

        /** Plausibility bounds (a night outside them is not a reading). */
        val cfg: MetricCfg
            get() = when (this) {
                HRV -> Baselines.hrvCfg
                RESTING_HR -> Baselines.restingHRCfg
                RESP -> Baselines.respCfg
                SKIN_TEMP -> Baselines.metricCfg.getValue("skin_temp")
            }

        /** True for HRV: its mean and SD are taken over ln RMSSD. */
        val logDomain: Boolean get() = this == HRV

        /** The σ floor in the domain the SD is taken in: 0.05 ln for HRV, the config's floor otherwise. */
        val sigmaFloor: Double get() = if (this == HRV) hrvSigmaFloorLn else cfg.floorSpread
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Day arithmetic
    // ─────────────────────────────────────────────────────────────────────────

    /** The last day a scored day's baseline reads: the day before it. null for an unparseable key.
     *  Swift twin: `ChargeBaselines.anchorDay(forScoredDay:)`. */
    fun anchorDay(forScoredDay: String): String? =
        Baselines.isoEpochDay(forScoredDay)?.let { Baselines.isoDayFromEpochDay(it - 1) }

    /** True when [day] starts before [epoch] (seconds), so a baseline starting at that epoch drops it. The
     *  day starts at UTC midnight, the rule `Baselines.foldHistory` has always used. An unparseable key is
     *  dropped whenever an epoch is set. Swift twin: `ChargeBaselines.beforeEpoch`. */
    fun beforeEpoch(day: String, epoch: Double): Boolean {
        if (epoch <= 0) return false
        val d = Baselines.isoEpochDay(day) ?: return true
        return d.toDouble() * 86_400 < epoch
    }

    // ─────────────────────────────────────────────────────────────────────────
    // History (which nights count)
    // ─────────────────────────────────────────────────────────────────────────

    /** One metric's nightly history for a Charge baseline, oldest first, plus the counts the diagnostic
     *  line reports. */
    data class History(
        /** `yyyy-MM-dd` keys, ascending, parallel to [values]. */
        val dayKeys: List<String>,
        /** The nightly values; null is a night that exists but carried no value. */
        val values: List<Double?>,
        /** True while imported vendor nights still take part. */
        val seededByImport: Boolean,
        /** Valid own nights on or after the epoch inside the window: the count the handoff is decided on. */
        val ownValidNights: Int,
        /** Imported nights this history carries (0 once the own nights have taken over). */
        val importedNights: Int,
    )

    /** Valid nights (inside [cfg]'s bounds, on or after [baselineEpoch]) in a day-keyed history.
     *  Swift twin: `ChargeBaselines.validCount`. */
    fun validCount(values: List<Double?>, dayKeys: List<String>, cfg: MetricCfg, baselineEpoch: Double): Int {
        var n = 0
        for (i in 0 until minOf(values.size, dayKeys.size)) {
            val v = values[i] ?: continue
            if (!(cfg.minVal <= v && v <= cfg.maxVal) || beforeEpoch(dayKeys[i], baselineEpoch)) continue
            n += 1
        }
        return n
    }

    /**
     * Build one metric's history from the imported and own nights (#2525).
     *
     * [imported] are imported vendor nights as `(day, value)`; a repeated day keeps its last value. [own] are
     * NOOP-computed nights, same shape. [seed] are same-brand wearable-export nights (#31), in precedence
     * order: a seed night counts only on a day neither [imported] nor [own] carries a value for; it then
     * seeds like an imported night. [anchorDay] is the LAST day the window holds (the day before the scored
     * day). [cfg] counts valid own nights; own nights before [baselineEpoch] do not count toward the handoff.
     * [handoffNights] is the valid own-night count at which the imported nights stop taking part.
     *
     * Keeps days on or before the anchor and fewer than [windowDays] days before it. While seeding an
     * imported value wins a day both sources cover; an own night fills a day the import leaves open.
     * Swift twin: `ChargeBaselines.historyWithSeed`.
     */
    fun historyWithSeed(
        imported: List<Pair<String, Double?>>,
        own: List<Pair<String, Double?>>,
        anchorDay: String,
        cfg: MetricCfg,
        baselineEpoch: Double,
        windowDays: Int = ChargeBaselines.windowDays,
        handoffNights: Int = Baselines.minNightsTrust,
        seed: List<Pair<String, Double?>>,
    ): History {
        val empty = History(emptyList(), emptyList(), seededByImport = false, ownValidNights = 0, importedNights = 0)
        if (windowDays <= 0) return empty
        val anchor = Baselines.isoEpochDay(anchorDay) ?: return empty
        val inWindow = { day: String ->
            val d = Baselines.isoEpochDay(day)
            d != null && d <= anchor && anchor - d < windowDays
        }

        val ownByDay = HashMap<String, Double?>()
        for ((day, value) in own) if (inWindow(day)) ownByDay[day] = value
        val ownKeys = ownByDay.keys.sorted()
        val ownValues = ownKeys.map { ownByDay.getValue(it) }
        val ownValid = validCount(ownValues, ownKeys, cfg, baselineEpoch)

        val importedByDay = HashMap<String, Double?>()
        for ((day, value) in imported) if (inWindow(day)) importedByDay[day] = value
        for ((day, value) in seed) {
            if (value == null || !inWindow(day)) continue
            if (importedByDay[day] != null || ownByDay[day] != null) continue
            importedByDay[day] = value
        }

        if (ownValid >= handoffNights || importedByDay.isEmpty()) {
            return History(ownKeys, ownValues, seededByImport = false, ownValidNights = ownValid, importedNights = 0)
        }

        val merged = HashMap<String, Double?>(importedByDay)
        for ((day, value) in ownByDay) {
            if (merged[day] != null) continue // an imported value wins
            merged[day] = value
        }
        val keys = merged.keys.sorted()
        return History(
            keys, keys.map { merged.getValue(it) }, seededByImport = true,
            ownValidNights = ownValid, importedNights = importedByDay.size,
        )
    }

    /** [historyWithSeed] with no wearable-export seed. Swift twin: `ChargeBaselines.history`. */
    fun history(
        imported: List<Pair<String, Double?>>,
        own: List<Pair<String, Double?>>,
        anchorDay: String,
        cfg: MetricCfg,
        baselineEpoch: Double,
        windowDays: Int = ChargeBaselines.windowDays,
        handoffNights: Int = Baselines.minNightsTrust,
    ): History = historyWithSeed(imported, own, anchorDay, cfg, baselineEpoch, windowDays, handoffNights, seed = emptyList())

    // ─────────────────────────────────────────────────────────────────────────
    // State (the trailing mean and SD)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The trailing baseline over a history: mean and sample SD (n − 1) of the valid nights on or after
     * [baselineEpoch], in ln for HRV. σ is floored at `metric.sigmaFloor` (a single night has σ = floor) and
     * stored as `spread = σ / 1.253`, the unit every z reader divides back out. `nightsSinceUpdate` is the
     * calendar days from the newest valid night to [anchorDay]. With no valid night the state is the
     * calibrating midpoint, which no reader scores against. Swift twin: `ChargeBaselines.state(values:dayKeys:metric:anchorDay:baselineEpoch:)`.
     */
    fun state(
        values: List<Double?>,
        dayKeys: List<String>,
        metric: Metric,
        anchorDay: String,
        baselineEpoch: Double,
    ): BaselineState {
        val cfg = metric.cfg
        val xs = ArrayList<Double>()
        var newest: Int? = null
        for (i in 0 until minOf(values.size, dayKeys.size)) {
            val v = values[i] ?: continue
            if (!(cfg.minVal <= v && v <= cfg.maxVal) || beforeEpoch(dayKeys[i], baselineEpoch)) continue
            xs.add(if (metric.logDomain) ln(v) else v)
            val d = Baselines.isoEpochDay(dayKeys[i])
            if (d != null) newest = max(newest ?: d, d)
        }
        if (xs.isEmpty()) {
            return BaselineState(
                baseline = (cfg.minVal + cfg.maxVal) / 2.0, spread = metric.sigmaFloor / 1.253,
                nValid = 0, nightsSinceUpdate = 0, status = BaselineStatus.CALIBRATING,
                logDomain = metric.logDomain,
            )
        }
        val n = xs.size
        var sum = 0.0
        for (x in xs) sum += x
        val mean = sum / n.toDouble()
        var sd = 0.0
        if (n >= 2) {
            var ss = 0.0
            for (x in xs) {
                val d = x - mean
                ss += d * d
            }
            sd = sqrt(ss / (n - 1).toDouble())
        }
        val sigma = max(sd, metric.sigmaFloor)
        val a = Baselines.isoEpochDay(anchorDay)
        val nw = newest
        val since = if (a != null && nw != null) max(0, a - nw) else 0
        return BaselineState(
            baseline = if (metric.logDomain) exp(mean) else mean, spread = sigma / 1.253,
            nValid = n, nightsSinceUpdate = since,
            status = Baselines.computeStatus(nValid = n, nightsSinceUpdate = since),
            logDomain = metric.logDomain,
        )
    }

    /** [state] over a built history. Swift twin: `ChargeBaselines.state(_:metric:anchorDay:baselineEpoch:)`. */
    fun state(history: History, metric: Metric, anchorDay: String, baselineEpoch: Double): BaselineState =
        state(history.values, history.dayKeys, metric, anchorDay, baselineEpoch)

    // ─────────────────────────────────────────────────────────────────────────
    // Eras (the one place a baseline's start is decided)
    // ─────────────────────────────────────────────────────────────────────────

    /** One night's measuring source, for the device-era cut. */
    data class SourceDay(val day: String, val sourceId: String)

    /** Where each metric's baseline starts (epoch seconds; 0 = no cut). */
    data class Epochs(val hrv: Double, val restingHR: Double, val resp: Double, val skin: Double) {
        /** The epoch for one metric. Swift twin: `Epochs.epoch`. */
        fun epoch(metric: Metric): Double = when (metric) {
            Metric.HRV -> hrv
            Metric.RESTING_HR -> restingHR
            Metric.RESP -> resp
            Metric.SKIN_TEMP -> skin
        }

        companion object {
            val none = Epochs(0.0, 0.0, 0.0, 0.0)
        }
    }

    /**
     * The epochs a day scored on [scoredDay] uses. Only nights up to and including the scored day decide its
     * era, so a past day keeps the era it was scored in and the first night on a new device starts a new
     * baseline with nothing before it. HRV and resting HR cut where a Garmin watch starts or stops
     * ([GarminScoringInputs.baselineEraEpoch]); respiration cuts at any brand switch
     * ([Baselines.deviceEraEpoch]); skin temperature only at the manual recalibration. Each is the later of
     * its era cut and its recalibration epoch. Swift twin: `ChargeBaselines.epochs`.
     */
    fun epochs(
        sourceDays: List<SourceDay>,
        scoredDay: String,
        hrvRecalibration: Double,
        recoveryRecalibration: Double,
    ): Epochs {
        val upTo = sourceDays.filter { it.day <= scoredDay }.map { it.day to it.sourceId }
        val garmin = GarminScoringInputs.baselineEraEpoch(upTo)
        val anyBrand = Baselines.deviceEraEpoch(upTo)
        return Epochs(
            hrv = max(hrvRecalibration, garmin), restingHR = max(recoveryRecalibration, garmin),
            resp = max(recoveryRecalibration, anyBrand), skin = recoveryRecalibration,
        )
    }

    /**
     * One source per night, by the history's own precedence: an own night's recorded source ([own], null =
     * unknown, which falls back to [ownFallbackId]), else [importedSourceId] on an imported day, else the
     * first seed source listed for that day. Sorted by day. Swift twin: `ChargeBaselines.sourceDays`.
     */
    fun sourceDays(
        own: List<Pair<String, String?>>,
        ownFallbackId: String,
        importedDays: List<String>,
        importedSourceId: String,
        seed: List<SourceDay>,
    ): List<SourceDay> {
        val byDay = HashMap<String, String>()
        for (s in seed) if (byDay[s.day] == null) byDay[s.day] = s.sourceId
        for (d in importedDays) byDay[d] = importedSourceId
        for ((day, sourceId) in own) byDay[day] = sourceId ?: ownFallbackId
        return byDay.keys.sorted().map { SourceDay(it, byDay.getValue(it)) }
    }

    /** One `scoreInputProvenance` row as [recordedSources] reads it. */
    data class ProvenanceRow(val day: String, val key: String, val sourceId: String)

    /** Each own night's recorded source from `scoreInputProvenance` rows: per day, the row whose key comes
     *  first in [sourceKeyPrecedence] (other keys are ignored). Swift twin: `ChargeBaselines.recordedSources`. */
    fun recordedSources(rows: List<ProvenanceRow>): Map<String, String> {
        val best = HashMap<String, Pair<Int, String>>()
        for (r in rows) {
            val rank = sourceKeyPrecedence.indexOf(r.key)
            if (rank < 0) continue
            val held = best[r.day]
            if (held != null && held.first <= rank) continue
            best[r.day] = rank to r.sourceId
        }
        return best.mapValues { it.value.second }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resolve (one day's four baselines)
    // ─────────────────────────────────────────────────────────────────────────

    /** One source's nights for the four metrics. */
    data class Nights(
        val hrv: List<Pair<String, Double?>> = emptyList(),
        val restingHR: List<Pair<String, Double?>> = emptyList(),
        val resp: List<Pair<String, Double?>> = emptyList(),
        val skin: List<Pair<String, Double?>> = emptyList(),
    ) {
        /** One metric's day-keyed nights. Swift twin: `Nights.values`. */
        fun values(metric: Metric): List<Pair<String, Double?>> = when (metric) {
            Metric.HRV -> hrv
            Metric.RESTING_HR -> restingHR
            Metric.RESP -> resp
            Metric.SKIN_TEMP -> skin
        }

        companion object {
            /** The four metrics read off daily rows (skin from the raw nightly `skinTempC`). */
            fun fromRows(rows: List<DailyMetric>): Nights = Nights(
                hrv = rows.map { it.day to it.avgHrv },
                restingHR = rows.map { it.day to it.restingHr?.toDouble() },
                resp = rows.map { it.day to it.respRateBpm },
                skin = rows.map { it.day to it.skinTempC },
            )
        }
    }

    /** The four baselines a scored day reads, with the histories and epochs they were built from. */
    data class Resolved(
        val scoredDay: String,
        val anchorDay: String,
        val epochs: Epochs,
        val hrvHistory: History,
        val restingHRHistory: History,
        val respHistory: History,
        val skinHistory: History,
        val hrv: BaselineState,
        val restingHR: BaselineState,
        val resp: BaselineState,
        val skin: BaselineState,
    )

    /**
     * Resolve the four baselines for [scoredDay]: window ending the day before, epochs from [epochs]. Skin
     * temperature has no imported counterpart (imports carry a deviation, not the raw mean), so its history
     * is the own nights alone. null for an unparseable day. Swift twin: `ChargeBaselines.resolveDay`.
     */
    fun resolveDay(
        scoredDay: String,
        imported: Nights,
        own: Nights,
        seed: Nights,
        sourceDays: List<SourceDay>,
        hrvRecalibration: Double,
        recoveryRecalibration: Double,
    ): Resolved? {
        val anchor = anchorDay(scoredDay) ?: return null
        val e = epochs(sourceDays, scoredDay, hrvRecalibration, recoveryRecalibration)
        /** One metric's history and state. Swift twin: `ChargeBaselines.build`. */
        fun build(m: Metric): Pair<History, BaselineState> {
            val h = if (m == Metric.SKIN_TEMP) {
                history(emptyList(), own.skin, anchor, m.cfg, e.skin)
            } else {
                historyWithSeed(
                    imported.values(m), own.values(m), anchor, m.cfg, e.epoch(m), seed = seed.values(m),
                )
            }
            return h to state(h, m, anchor, e.epoch(m))
        }
        val hrv = build(Metric.HRV)
        val rhr = build(Metric.RESTING_HR)
        val resp = build(Metric.RESP)
        val skin = build(Metric.SKIN_TEMP)
        return Resolved(
            scoredDay = scoredDay, anchorDay = anchor, epochs = e,
            hrvHistory = hrv.first, restingHRHistory = rhr.first, respHistory = resp.first, skinHistory = skin.first,
            hrv = hrv.second, restingHR = rhr.second, resp = resp.second, skin = skin.second,
        )
    }

    /** The stored rows the dashboard resolves baselines from: the same buckets the engine reads. */
    data class Inputs(
        val imported: List<DailyMetric>,
        val own: List<DailyMetric>,
        val seed: List<DailyMetric>,
        val sourceDays: List<SourceDay>,
        val hrvRecalibration: Double,
        val recoveryRecalibration: Double,
    ) {
        /** The baselines [scoredDay] was scored against. Swift twin: `ChargeBaselines.Inputs.resolve`. */
        fun resolve(scoredDay: String): Resolved? = resolveDay(
            scoredDay = scoredDay,
            imported = Nights.fromRows(imported).copy(skin = emptyList()),
            own = Nights.fromRows(own),
            seed = Nights.fromRows(seed).copy(skin = emptyList()),
            sourceDays = sourceDays,
            hrvRecalibration = hrvRecalibration,
            recoveryRecalibration = recoveryRecalibration,
        )

        /** The era epochs for [scoredDay], for readers that need only the cut (the readiness word).
         *  Swift twin: `Inputs.epochs(scoredDay:)`. */
        fun epochs(scoredDay: String): Epochs =
            ChargeBaselines.epochs(sourceDays, scoredDay, hrvRecalibration, recoveryRecalibration)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Diagnostics
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The Recovery test-mode line naming what each Charge baseline was folded from for one scored day. Per
     * metric, `own/N` means the own nights alone, N valid; `seed/N+M` means N valid own nights still seeded
     * by M imported nights. Swift twin: `ChargeBaselines.logLine`, byte-identical.
     */
    fun logLine(
        scoredDay: String,
        hrv: History,
        restingHR: History,
        resp: History,
        skin: History,
        windowDays: Int = ChargeBaselines.windowDays,
    ): String {
        val part = { h: History ->
            if (h.seededByImport) "seed/${h.ownValidNights}+${h.importedNights}" else "own/${h.ownValidNights}"
        }
        return "charge baseline day=$scoredDay window=${windowDays}d hrv=${part(hrv)} " +
            "rhr=${part(restingHR)} resp=${part(resp)} skin=${part(skin)}"
    }
}
