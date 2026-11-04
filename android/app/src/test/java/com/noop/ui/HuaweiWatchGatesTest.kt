package com.noop.ui

import com.noop.R
import com.noop.analytics.Baselines
import com.noop.analytics.ChargeBaselines
import com.noop.analytics.FusionInput
import com.noop.analytics.FusionResolver
import com.noop.analytics.FusionSource
import com.noop.analytics.GarminScoringInputs
import com.noop.analytics.GarminWorkoutEffort
import com.noop.analytics.IntelligenceEngine
import com.noop.analytics.MetricArbitrationPolicy
import com.noop.analytics.StressToday
import com.noop.ble.ExperimentalBrand
import com.noop.ble.HuaweiPlaceholderSource
import com.noop.ble.SourceCoordinator
import com.noop.data.MetricSeriesRow
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import com.noop.data.Vo2MaxEstimator
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A paired Huawei Band 10 ("huawei-<addr>") takes the watch branch at every gate a Garmin watch takes, with
 * its own brand on every label, while Garmin keeps its exact behaviour. The scoring pass itself is covered
 * end to end by HuaweiWatchEngineTest. All values are synthetic.
 */
class HuaweiWatchGatesTest {

    private val band = "huawei-AA:BB:CC:00:11:22"
    private val garmin = "garmin-AA:BB:CC:00:11:22"

    // MARK: - The one resolver

    @Test
    fun resolverNamesTheBrand() {
        assertEquals(FusionSource.HUAWEI_WATCH, FusionSource.watchBrand(band))
        assertEquals(FusionSource.GARMIN_WATCH, FusionSource.watchBrand(garmin))
        assertTrue(FusionSource.isWatchId(band))
        assertFalse(FusionSource.isGarminWatchId(band))
        assertEquals(FusionSource.HUAWEI_WATCH, FusionSource.forStoredSourceId(band))
        for (id in listOf("huawei-import", "$band-noop", "huawei", "my-whoop", "oura-1", "huami-1")) {
            assertNull(id, FusionSource.watchBrand(id))
        }
        assertEquals("huawei", FusionSource.HUAWEI_WATCH.watchIdPrefix)
        assertEquals("garmin", FusionSource.GARMIN_WATCH.watchIdPrefix)
        assertNull(FusionSource.WHOOP_IMPORT.watchIdPrefix)
    }

    // MARK: - Arbitration + fusion

    @Test
    fun arbitrationTreatsTheBandLikeGarmin() {
        for (kind in MetricArbitrationPolicy.MetricKind.entries) {
            assertEquals(kind.name, MetricArbitrationPolicy.tier(kind, FusionSource.GARMIN_WATCH),
                MetricArbitrationPolicy.tier(kind, FusionSource.HUAWEI_WATCH))
            assertEquals(kind.name, MetricArbitrationPolicy.reason(kind, FusionSource.GARMIN_WATCH),
                MetricArbitrationPolicy.reason(kind, FusionSource.HUAWEI_WATCH))
        }
        assertEquals(MetricArbitrationPolicy.sourcePriority(FusionSource.GARMIN_WATCH),
            MetricArbitrationPolicy.sourcePriority(FusionSource.HUAWEI_WATCH))
        val point = FusionResolver.resolve("rhr",
            listOf(FusionInput(FusionSource.NOOP_COMPUTED, 58.0), FusionInput(FusionSource.HUAWEI_WATCH, 61.0)))!!
        assertEquals(FusionSource.HUAWEI_WATCH, point.winningSource)
        assertEquals("watch stages",
            MetricArbitrationPolicy.reason(MetricArbitrationPolicy.MetricKind.SLEEP, FusionSource.HUAWEI_WATCH))
    }

    // MARK: - Vitals + baseline era

    @Test
    fun vitalsResolveToTheBandsOwnValues() {
        val r = GarminScoringInputs.resolve(
            computedRestingHr = 58, computedHrv = 31.75, computedResp = 14.2,
            watch = GarminScoringInputs.WatchDay(49, 52.5), hrvSamples = emptyList(), watchRespRateBpm = 15.0,
            nights = emptyList(), watchSource = FusionSource.HUAWEI_WATCH,
        )
        assertEquals(49, r.restingHr)
        assertEquals(FusionSource.HUAWEI_WATCH, r.restingHrSource)
        assertEquals(52.5, r.avgHrv!!, 0.0)
        assertEquals(FusionSource.HUAWEI_WATCH, r.avgHrvSource)
        assertEquals(GarminScoringInputs.HrvBasis.WATCH_LAST_NIGHT, r.hrvBasis)
        assertEquals(FusionSource.HUAWEI_WATCH, r.respRateSource)
        assertTrue(IntelligenceEngine.garminVitalsLine("2026-10-01", r).contains("rhr=49/huawei-watch"))
    }

    @Test
    fun aBandSwitchOpensANewBaselineEra() {
        assertEquals("huawei", Baselines.brandBucket(band))
        val whoopThenBand = listOf("2026-10-01" to "my-whoop", "2026-10-02" to band)
        val whoopThenGarmin = listOf("2026-10-01" to "my-whoop", "2026-10-02" to garmin)
        assertEquals(GarminScoringInputs.baselineEraEpoch(whoopThenGarmin),
            GarminScoringInputs.baselineEraEpoch(whoopThenBand), 0.0)
        assertTrue(GarminScoringInputs.baselineEraEpoch(whoopThenBand) > 0.0)
        val epochs = ChargeBaselines.epochs(
            whoopThenBand.map { ChargeBaselines.SourceDay(it.first, it.second) }, "2026-10-02", 0.0, 0.0)
        assertTrue(epochs.hrv > 0.0 && epochs.restingHR > 0.0)
    }

    // MARK: - Stress

    @Test
    fun stressComesFromTheBandFirstOnItsOwnScale() {
        val samples = listOf(StressToday.Point(100, 40.0), StressToday.Point(200, 30.0))
        val noop = listOf(StressToday.Point(150, 1.2))
        val reading = StressToday.resolve(band, samples, null, null, noop, 1.2)!!
        assertEquals(StressToday.Source.HUAWEI, reading.source)
        assertEquals(100.0, reading.scaleMax, 0.0)
        assertTrue(StressToday.prefersWatch(band))
        assertFalse(StressToday.prefersWatch("my-whoop"))
        assertEquals("huawei_stress", StressTodayLoader.watchStressKey(band))
        assertEquals("garmin_stress", StressTodayLoader.watchStressKey(garmin))
        assertEquals("garmin_stress", StressTodayLoader.watchStressKey("my-whoop"))
        assertEquals("huawei-import", StressTodayLoader.watchStressImportId(band))
        assertEquals("garmin-import", StressTodayLoader.watchStressImportId(garmin))
        assertEquals("garmin-import", StressTodayLoader.watchStressImportId("my-whoop"))
        assertEquals(R.string.stress_summary_source_huawei, sourceLabelRes(StressToday.Source.HUAWEI))
        assertEquals("35", formatStressSummaryValue(35.2, StressToday.Source.HUAWEI))
        // A Garmin export behind a WHOOP still reads as Garmin.
        assertEquals(StressToday.Source.GARMIN, StressToday.resolve("my-whoop", emptyList(), null, 40.0, emptyList(), null)!!.source)
    }

    // MARK: - Workouts + VO2max

    @Test
    fun bandWorkoutsAreWatchWorkouts() {
        assertTrue(GarminWorkoutEffort.isWatchSource(band))
        assertFalse(GarminWorkoutEffort.isWatchSource("huawei-import"))
        assertEquals(FusionSource.HUAWEI_WATCH, GarminWorkoutEffort.watchBrand(band.uppercase()))
        assertEquals(WorkoutSource.HUAWEI, WorkoutEditing.classify(band))
        assertEquals(WorkoutSource.GARMIN, WorkoutEditing.classify(garmin))
        assertEquals(listOf(band), WhoopRepository.workoutHrDeviceIds(band, band, activeStrapId = band))
        val row = WorkoutRow(deviceId = band, startTs = 0, endTs = 60, sport = "Running", source = band)
        assertEquals("huawei", WorkoutEditing.sourceLabel(row))
        assertFalse(WorkoutMerge.isMergeable(row))
    }

    @Test
    fun vo2maxFromTheBandIsTaggedHuawei() {
        assertEquals(Vo2MaxEstimator.HUAWEI, Vo2MaxEstimator.forWatch(FusionSource.HUAWEI_WATCH))
        assertEquals(Vo2MaxEstimator.GARMIN, Vo2MaxEstimator.forWatch(FusionSource.GARMIN_WATCH))
        assertEquals(Vo2MaxEstimator.HUAWEI, Vo2MaxEstimator.fromProvenanceId("huawei"))
        val rows = listOf(MetricSeriesRow("$band-noop", "2026-10-31", "vo2max_est", 47.0))
        val tag = IntelligenceEngine.vo2MaxProvenance(rows, 0.0, "$band-noop", 47.0, Vo2MaxEstimator.HUAWEI).single()
        assertEquals("huawei", tag.sourceId)
        assertEquals(R.string.today_source_huawei, vo2MaxAttributionLabelRes(Vo2MaxEstimator.HUAWEI))
    }

    // MARK: - Provenance + sleep captions

    @Test
    fun provenanceNamesTheBand() {
        assertEquals(FusionSource.HUAWEI_WATCH, dayOwnerSource(band))
        assertEquals(FusionSource.GARMIN_WATCH, dayOwnerSource(garmin))
        assertEquals(DisplayText.Resource(R.string.today_source_huawei), provenanceBadgeLabel(FusionSource.HUAWEI_WATCH))
        assertEquals(DisplayText.Resource(R.string.today_source_huawei), provenanceDisplayLabel(band, band))
    }

    @Test
    fun sleepCaptionsNameTheBand() {
        val none = ImportedSleepSeries(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        assertEquals("Huawei", restHeroSource(none, "2026-11-02", activeWatch = FusionSource.HUAWEI_WATCH))
        assertEquals(" · Huawei watch stages", stageProvenanceCaption(false, activeWatch = FusionSource.HUAWEI_WATCH))
        assertEquals("Huawei", mainSleepSourceBadge(daySourceBadge(band), band, false).first)
        val computed = "$band-noop"
        assertEquals("Huawei",
            mainSleepSourceBadge(daySourceBadge(computed), computed, false, activeWatch = FusionSource.HUAWEI_WATCH).first)
        // Garmin's brand-driven path prints exactly what the Boolean form printed.
        assertEquals(" · Garmin watch stages", stageProvenanceCaption(false, activeWatch = FusionSource.GARMIN_WATCH))
    }

    // MARK: - Brand plumbing + routing

    @Test
    fun bandIsASupportedWatchThatIsNotYetConnectable() {
        val brand = ExperimentalBrand.recognise("HUAWEI Band 10-A1B")
        assertEquals(ExperimentalBrand.HUAWEI, brand)
        assertEquals(SourceKind.huawei, brand?.sourceKind)
        assertEquals("huawei", brand?.idPrefix)
        assertEquals(false, brand?.canStreamLiveHR)
        val row = PairedDeviceRow(id = band, brand = "Huawei", model = "Band 10", nickname = null, peripheralId = "AA",
            sourceKind = SourceKind.huawei.name, capabilities = "hr", status = "active", addedAt = 0, lastSeenAt = 0)
        assertFalse(SourceCoordinator.isWhoop(row))
        assertEquals(FusionSource.HUAWEI_WATCH, watchSourceOf(row))
        assertFalse("the Garmin panel stays Garmin-only", isGarminWatch(row))
        val lines = ArrayList<String>()
        val placeholder = HuaweiPlaceholderSource(band) { lines.add(it) }
        placeholder.connect("AA")
        placeholder.scan()
        placeholder.stop()
        assertTrue(placeholder.needsPairing.value)
        assertFalse(placeholder.isConnectable)
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.startsWith(HuaweiPlaceholderSource.NOT_CONNECTABLE) })
    }
}
