package com.noop.analytics

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * IllnessInputs — the ONE place the illness heads-up turns nightly history into the engine's z-scored
 * inputs and journal context. Line-for-line twin of Swift `IllnessInputs` (StrandAnalytics), pinned by
 * oracle in `IllnessInputsTest`.
 *
 * Android used to z-score only the newest night against a plain 30-day mean/SD with a 1e-6 SD floor, so a
 * stable 0.05 °C skin-temp series plus a 0.3 °C night read z ≈ 6 here and z ≈ 1 on Apple. The shape kept
 * is the Apple one (Mishra 2020, Alavi 2022, Quer 2021): a personal EWMA baseline with floored spread
 * ([Baselines.foldHistory]) over up to 28 nights ending 3 nights before the newest, and
 * z = min(z of the 2-night mean, z of the newest night) illness-ward. Skin temperature only compares
 * nights of the newest night's kind (absolute imported °C vs live deviation), each with its own config.
 *
 * A travel / body-clock jump is a recent night's sleep midpoint ≥ [phaseJumpMinutes] from the baseline's
 * circular-mean midpoint on the UTC clock. Journal confounders match the catalog's canonical question keys,
 * never substrings.
 */
object IllnessInputs {

    const val recentNights: Int = 2
    const val gapNights: Int = 1
    const val baselineNights: Int = 28
    const val hardWorkoutZ: Double = 2.0
    /** Swift twin: `IllnessInputs.strainCfg` — the 0–100 Effort scale, a wide floor for training swings. */
    val strainCfg: MetricCfg
        get() = MetricCfg(minVal = 0.0, maxVal = 100.0, floorSpread = 5.0, halfLifeB = 14.0, halfLifeS = 21.0)
    const val phaseJumpMinutes: Double = 180.0
    const val phaseBaselineMinNights: Int = 7
    const val phaseBaselineMinConcentration: Double = 0.5

    data class Night(
        val day: String,
        val restingHR: Double? = null,
        val hrv: Double? = null,
        val respiration: Double? = null,
        val skinTemp: Double? = null,
        val strain: Double? = null,
        /** The main sleep block's midpoint, minutes into the UTC day [0, 1440) ([sleepMidpoints]). */
        val sleepMidpointUtcMin: Double? = null,
    )

    /** One sleep block keyed by its local wake day, bounds in unix seconds. */
    data class SleepBlock(val day: String, val start: Long, val end: Long)

    data class Signal(val reading: IllnessSignalEngine.SignalReading, val trusted: Boolean)

    data class Built(
        val restingHR: Signal?,
        val hrv: Signal?,
        val respiration: Signal?,
        val skinTemp: Signal?,
        val baselineTrusted: Boolean,
        val hardWorkout: Boolean,
        val travelPhaseJump: Boolean,
    ) {
        val inputs: IllnessSignalEngine.Inputs
            get() = IllnessSignalEngine.Inputs(
                restingHR = restingHR?.reading, skinTemp = skinTemp?.reading,
                hrv = hrv?.reading, respiration = respiration?.reading,
            )

        val distanceFeatures: IllnessDistance.FeatureVector
            get() {
                val z = listOf(restingHR, hrv, skinTemp, respiration).map { s ->
                    s?.reading?.takeIf { it.present }?.zIllnessward
                }
                return IllnessDistance.FeatureVector(restingHR = z[0], rmssd = z[1], skinTemp = z[2], respiration = z[3])
            }
    }

    fun build(nights: List<Night>): Built {
        val latest = nights.lastOrNull()
            ?: return Built(null, null, null, null, baselineTrusted = false, hardWorkout = false, travelPhaseJump = false)
        val recent = nights.takeLast(recentNights)
        val base = baseline(nights)

        val rhr = signal(recent.map { it.restingHR }, latest.restingHR, base.map { it.restingHR },
            Baselines.restingHRCfg, illnessUp = true)
        val hrv = signal(recent.map { it.hrv }, latest.hrv, base.map { it.hrv },
            Baselines.hrvCfg, illnessUp = false)
        val resp = signal(recent.map { it.respiration }, latest.respiration, base.map { it.respiration },
            Baselines.respCfg, illnessUp = true)

        val skin = latest.skinTemp?.let { latestSkin ->
            val absolute = VitalBands.isAbsoluteSkinTemp(latestSkin)
            val cfg = if (absolute) Baselines.metricCfg.getValue("skin_temp") else VitalBands.skinTempDeviationCfg
            signal(
                VitalBands.skinTempHistory(latestSkin, recent.map { it.skinTemp }),
                latestSkin,
                VitalBands.skinTempHistory(latestSkin, base.map { it.skinTemp }),
                cfg, illnessUp = true,
            )
        }

        return Built(
            restingHR = rhr, hrv = hrv, respiration = resp, skinTemp = skin,
            baselineTrusted = (rhr?.trusted ?: false) || (hrv?.trusted ?: false),
            hardWorkout = hardWorkout(nights, base),
            travelPhaseJump = phaseJump(nights, base),
        )
    }

    internal fun baseline(nights: List<Night>): List<Night> {
        val end = nights.size - recentNights - gapNights
        if (end <= 0) return emptyList()
        return nights.subList(maxOf(0, end - baselineNights), end)
    }

    internal fun signal(
        recent: List<Double?>,
        latest: Double?,
        base: List<Double?>,
        cfg: MetricCfg,
        illnessUp: Boolean,
    ): Signal? {
        val vals = recent.filterNotNull()
        if (latest == null || vals.isEmpty()) return null
        var sum = 0.0
        for (v in vals) sum += v
        val recentMean = sum / vals.size.toDouble()
        val state = Baselines.foldHistory(base, cfg)
        if (!state.usable) {
            return Signal(IllnessSignalEngine.SignalReading(zIllnessward = 0.0, present = false), trusted = false)
        }
        val dev = Baselines.deviation(recentMean, state).z
        val latestDev = Baselines.deviation(latest, state).z
        val z = if (illnessUp) minOf(dev, latestDev) else minOf(-dev, -latestDev)
        return Signal(IllnessSignalEngine.SignalReading(zIllnessward = z), trusted = state.trusted)
    }

    internal fun hardWorkout(nights: List<Night>, base: List<Night>): Boolean {
        val state = Baselines.foldHistory(base.map { it.strain }, strainCfg)
        if (!state.usable) return false
        val end = nights.size - 1
        val start = maxOf(0, nights.size - recentNights - 1)
        if (start >= end) return false
        for (night in nights.subList(start, end)) {
            val s = night.strain ?: continue
            if (Baselines.deviation(s, state).z >= hardWorkoutZ) return true
        }
        return false
    }

    /** Swift twin: `IllnessInputs.phaseJump`. */
    internal fun phaseJump(nights: List<Night>, base: List<Night>): Boolean {
        val ref = base.mapNotNull { it.sleepMidpointUtcMin }
        if (ref.size < phaseBaselineMinNights) return false
        var s = 0.0
        var c = 0.0
        for (m in ref) {
            val a = m * 2.0 * Math.PI / 1440.0
            s += sin(a)
            c += cos(a)
        }
        if (sqrt(s * s + c * c) / ref.size.toDouble() < phaseBaselineMinConcentration) return false
        var mean = atan2(s, c) * 1440.0 / (2.0 * Math.PI)
        if (mean < 0) mean += 1440.0
        for (night in nights.takeLast(recentNights)) {
            val m = night.sleepMidpointUtcMin ?: continue
            var d = abs(m - mean) % 1440.0
            if (d > 720.0) d = 1440.0 - d
            if (d >= phaseJumpMinutes) return true
        }
        return false
    }

    /** Swift twin: `IllnessInputs.sleepMidpoints`. Longest block per wake day; a tie keeps the first. */
    fun sleepMidpoints(blocks: List<SleepBlock>): Map<String, Double> {
        val best = HashMap<String, SleepBlock>()
        for (b in blocks) {
            if (b.end <= b.start) continue
            val cur = best[b.day]
            if (cur != null && cur.end - cur.start >= b.end - b.start) continue
            best[b.day] = b
        }
        val out = HashMap<String, Double>()
        for ((day, b) in best) {
            val mid = b.start + (b.end - b.start) / 2
            out[day] = (((mid % 86_400L) + 86_400L) % 86_400L).toDouble() / 60.0
        }
        return out
    }

    val alcoholQuestions: Set<String> get() = setOf("did you drink any alcohol?", "have any alcoholic drinks?")
    val stressQuestions: Set<String> get() = setOf("did you feel stressed?")
    val saunaQuestions: Set<String> get() = setOf("did you use a sauna?")
    val unwellQuestions: Set<String> get() = setOf("did you feel sick or ill?")

    fun context(yesQuestions: List<String>, built: Built): IllnessSignalEngine.Context {
        val keys = yesQuestions.map { normalizeQuestion(it) }.toSet()
        return IllnessSignalEngine.Context(
            alcohol = keys.any { it in alcoholQuestions },
            stress = keys.any { it in stressQuestions },
            sauna = keys.any { it in saunaQuestions },
            hardOrLateWorkout = built.hardWorkout,
            travelPhaseJump = built.travelPhaseJump,
            alreadyUnwell = keys.any { it in unwellQuestions },
            baselineTrusted = built.baselineTrusted,
        )
    }

    fun recentDays(nights: List<Night>): Set<String> = nights.takeLast(recentNights).map { it.day }.toSet()

    /** Same normalisation as the journal catalog (`normJournalKey` / `JournalCatalogStore.norm`). */
    fun normalizeQuestion(s: String): String = buildString {
        var prevSpace = true
        for (c in s) {
            if (c.isWhitespace()) {
                if (!prevSpace) append(' ')
                prevSpace = true
            } else {
                append(c)
                prevSpace = false
            }
        }
    }.trim().lowercase()
}
