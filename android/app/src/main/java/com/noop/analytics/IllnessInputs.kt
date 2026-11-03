package com.noop.analytics

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
 * Journal confounders match the catalog's canonical question keys, never substrings.
 */
object IllnessInputs {

    const val recentNights: Int = 2
    const val gapNights: Int = 1
    const val baselineNights: Int = 28
    const val hardWorkoutZ: Double = 2.0

    data class Night(
        val day: String,
        val restingHR: Double? = null,
        val hrv: Double? = null,
        val respiration: Double? = null,
        val skinTemp: Double? = null,
        val strain: Double? = null,
    )

    data class Signal(val reading: IllnessSignalEngine.SignalReading, val trusted: Boolean)

    data class Built(
        val restingHR: Signal?,
        val hrv: Signal?,
        val respiration: Signal?,
        val skinTemp: Signal?,
        val baselineTrusted: Boolean,
        val hardWorkout: Boolean,
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
            ?: return Built(null, null, null, null, baselineTrusted = false, hardWorkout = false)
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
        val state = Baselines.foldHistory(base.map { it.strain }, Baselines.strainCfg)
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

    val alcoholQuestions: Set<String> get() = setOf("did you drink any alcohol?", "have any alcoholic drinks?")
    val stressQuestions: Set<String> get() = setOf("did you feel stressed?")
    val saunaQuestions: Set<String> get() = setOf("did you use a sauna?")
    val unwellQuestions: Set<String> get() = setOf("did you feel sick or ill?")

    fun context(
        yesQuestions: List<String>,
        built: Built,
        travelPhaseJump: Boolean = false,
    ): IllnessSignalEngine.Context {
        val keys = yesQuestions.map { normalizeQuestion(it) }.toSet()
        return IllnessSignalEngine.Context(
            alcohol = keys.any { it in alcoholQuestions },
            stress = keys.any { it in stressQuestions },
            sauna = keys.any { it in saunaQuestions },
            hardOrLateWorkout = built.hardWorkout,
            travelPhaseJump = travelPhaseJump,
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
