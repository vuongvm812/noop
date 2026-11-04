package com.noop.analytics

import com.noop.data.DailyMetric
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which nights the Charge baselines read and how they are built (#2525, #53).
 *
 * The defect these pin: the imported vendor history was folded in full while the wearer's own nights entered
 * only from the scan window, so the import kept about a third of the weight however long NOOP had been worn.
 * Twin of the Swift `ChargeBaselinesTests` (same cases); the shared oracle lives in [ChargeBaselinesOracleTest].
 */
class ChargeBaselinesTest {

    private val hrvCfg = Baselines.hrvCfg

    private fun dayKey(epochDay: Int): String = LocalDate.ofEpochDay(epochDay.toLong()).toString()

    /** 2026-09-30 as a day count from 1970-01-01. */
    private val anchorEpochDay = 20_726
    private val anchor = dayKey(anchorEpochDay)

    /** [count] consecutive nights ending [endBack] days before the anchor, all at [value]. */
    private fun run(count: Int, endBack: Int, value: Double?): List<Pair<String, Double?>> =
        (0 until count).map { i -> dayKey(anchorEpochDay - endBack - (count - 1 - i)) to value }

    private fun history(
        imported: List<Pair<String, Double?>>,
        own: List<Pair<String, Double?>>,
        cfg: MetricCfg = hrvCfg,
        epoch: Double = 0.0,
    ) = ChargeBaselines.history(imported, own, anchor, cfg, epoch)

    @Test fun theAnchorIsTheDayItClaims() {
        assertEquals("2026-09-30", anchor)
        assertEquals(anchor, Baselines.isoDayFromEpochDay(anchorEpochDay))
        assertEquals(anchor, ChargeBaselines.anchorDay("2026-10-01"))
        assertEquals("2024-02-29", ChargeBaselines.anchorDay("2024-03-01"))
        assertNull(ChargeBaselines.anchorDay("nope"))
    }

    @Test fun isoDayRoundTripsAcrossCenturies() {
        var d = -700_000
        while (d <= 800_000) {
            assertEquals(d, Baselines.isoEpochDay(Baselines.isoDayFromEpochDay(d)))
            d += 997
        }
        // And agrees with java.time on the ordinary range.
        for (e in listOf(0, 1, 59, 60, 11_016, 20_726, 47_481)) {
            assertEquals(LocalDate.ofEpochDay(e.toLong()).toString(), Baselines.isoDayFromEpochDay(e))
        }
    }

    // ---- the window ---------------------------------------------------------------------------------

    /** The window holds the anchor and the 59 days before it; the 60th day back and any later day are out. */
    @Test fun theWindowKeepsSixtyCalendarDaysEndingOnTheAnchor() {
        val own = listOf(
            dayKey(anchorEpochDay - 60) to 50.0,
            dayKey(anchorEpochDay - 59) to 51.0,
            anchor to 52.0,
            dayKey(anchorEpochDay + 1) to 53.0,
        )
        val h = history(emptyList(), own)
        assertEquals(listOf("2026-08-02", "2026-09-30"), h.dayKeys)
        assertEquals(listOf(51.0, 52.0), h.values)
    }

    /** An import that ended before the window has nothing left to seed with, however few own nights exist. */
    @Test fun anImportOlderThanTheWindowDoesNotSeed() {
        val h = history(run(300, 61, 40.0), run(3, 0, 60.0))
        assertFalse(h.seededByImport)
        assertEquals(listOf(60.0, 60.0, 60.0), h.values)
        assertEquals(0, h.importedNights)
    }

    /** #53 seed gap: an export that ended 40 days ago used to fall outside the 21-day window and seed nothing;
     *  inside the 60-day window it seeds a new wearer. */
    @Test fun anExportFromSixWeeksAgoSeedsANewWearer() {
        val h = history(run(30, 40, 45.0), run(2, 0, 60.0))
        assertTrue(h.seededByImport)
        assertEquals(20, h.importedNights)
        assertTrue(ChargeBaselines.state(h, ChargeBaselines.Metric.HRV, anchor, 0.0).usable)
    }

    // ---- the handoff --------------------------------------------------------------------------------

    @Test fun importSeedsUntilTheOwnNightsAloneAreTrusted() {
        val imported = run(7, 14, 40.0)
        val seeding = history(imported, run(13, 0, 60.0))
        assertTrue(seeding.seededByImport)
        assertEquals(13, seeding.ownValidNights)
        assertEquals(7, seeding.importedNights)
        assertEquals(20, seeding.dayKeys.size)

        val handedOff = history(imported, run(14, 0, 60.0))
        assertFalse(handedOff.seededByImport)
        assertEquals(14, handedOff.ownValidNights)
        assertEquals(0, handedOff.importedNights)
        assertEquals(List(14) { 60.0 }, handedOff.values)
    }

    @Test fun ownNightsWithoutAValueDoNotCountTowardTheHandoff() {
        val h = history(run(6, 14, 40.0), run(13, 1, 60.0) + run(1, 0, null))
        assertTrue(h.seededByImport)
        assertEquals(13, h.ownValidNights)
    }

    @Test fun ownNightsBeforeTheEpochDoNotCountTowardTheHandoff() {
        val epoch = (anchorEpochDay - 9).toDouble() * 86_400 // the last ten own nights survive
        val h = history(run(3, 18, 40.0), run(30, 0, 60.0), epoch = epoch)
        assertEquals(10, h.ownValidNights)
        assertTrue(h.seededByImport)
    }

    @Test fun unparseableKeysAreDroppedAndAnUnparseableAnchorYieldsNothing() {
        val own = listOf("garbage" to 50.0, anchor to 51.0)
        assertEquals(listOf(anchor), history(emptyList(), own).dayKeys)
        val none = ChargeBaselines.history(emptyList(), own, "not-a-day", hrvCfg, 0.0)
        assertEquals(emptyList<String>(), none.dayKeys)
        assertFalse(none.seededByImport)
    }

    // ---- seeding precedence -------------------------------------------------------------------------

    @Test fun whileSeedingAnImportedValueWinsASharedDay() {
        val h = history(listOf(anchor to 62.0), listOf(anchor to 48.0))
        assertTrue(h.seededByImport)
        assertEquals(listOf(62.0), h.values)
    }

    @Test fun whileSeedingAnOwnNightFillsADayTheImportLacks() {
        val yesterday = dayKey(anchorEpochDay - 1)
        val h = history(listOf(yesterday to 62.0), listOf(anchor to 48.0))
        assertEquals(listOf(yesterday, anchor), h.dayKeys)
        assertEquals(listOf(62.0, 48.0), h.values)
    }

    @Test fun whileSeedingABlankImportedRowIsFilledByTheOwnNight() {
        val h = history(listOf(anchor to null), listOf(anchor to 48.0))
        assertEquals(listOf(48.0), h.values)
    }

    @Test fun whileSeedingABlankImportedRowWithNoOwnNightStaysMissing() {
        val yesterday = dayKey(anchorEpochDay - 1)
        val h = history(listOf(yesterday to null, anchor to 62.0), emptyList())
        assertTrue(h.seededByImport)
        assertEquals(listOf(yesterday, anchor), h.dayKeys)
        assertEquals(listOf(null, 62.0), h.values)
    }

    @Test fun whileSeedingABlankOwnNightDisturbsNothing() {
        val d1 = dayKey(anchorEpochDay - 2)
        val d2 = dayKey(anchorEpochDay - 1)
        val h = history(listOf(d1 to 62.0, d2 to null), listOf(d1 to null, d2 to null, anchor to null))
        assertEquals(listOf(d1, d2, anchor), h.dayKeys)
        assertEquals(listOf(62.0, null, null), h.values)
    }

    @Test fun whileSeedingAWeekOfBlankImportedRowsStillSeedsFromTheStrap() {
        val own = (0 until 7).map { i -> dayKey(anchorEpochDay - 6 + i) to (50.0 + i) }
        val h = history(run(7, 0, null), own)
        assertEquals(7, h.values.count { it != null })
        val st = ChargeBaselines.state(h, ChargeBaselines.Metric.HRV, anchor, 0.0)
        assertTrue(st.nValid >= Baselines.minNightsSeed)
    }

    /** Once the own nights have taken over, the import has no weight at all. */
    @Test fun onceHandedOffTheImportCarriesNoWeight() {
        val own = run(60, 0, 52.0)
        val rhr = Baselines.restingHRCfg
        assertEquals(history(run(200, 10, 58.0), own, cfg = rhr), history(run(200, 10, 68.0), own, cfg = rhr))
    }

    // ---- state (#53) --------------------------------------------------------------------------------

    private fun state(
        values: List<Double?>,
        metric: ChargeBaselines.Metric = ChargeBaselines.Metric.HRV,
        epoch: Double = 0.0,
    ): BaselineState {
        val keys = values.indices.map { dayKey(anchorEpochDay - values.size + 1 + it) }
        return ChargeBaselines.state(values, keys, metric, anchor, epoch)
    }

    /** HRV is the mean and sample SD of ln RMSSD; the baseline reads back as the geometric mean in ms. */
    @Test fun hrvIsAGeometricMeanWithALogSD() {
        val s = state(listOf(40.0, 50.0, 80.0, null, 300.0)) // null missing, 300 out of bounds
        assertEquals(3, s.nValid)
        assertTrue(s.logDomain)
        val l = listOf(ln(40.0), ln(50.0), ln(80.0))
        val m = l.sum() / 3
        val sd = sqrt(l.sumOf { (it - m) * (it - m) } / 2)
        assertEquals(exp(m), s.baseline, 1e-9)
        assertEquals(sd, 1.253 * s.spread, 1e-12)
        assertEquals(BaselineStatus.CALIBRATING, s.status)
        assertEquals("newest valid night is two days before the anchor", 2, s.nightsSinceUpdate)
    }

    /** Every night weighs the same: reversing the window's order changes nothing. */
    @Test fun everyNightWeighsTheSame() {
        val vals: List<Double?> = (0 until 21).map { 40.0 + ((it * 11) % 23) }
        val a = state(vals)
        val b = state(vals.reversed())
        assertEquals(a.baseline, b.baseline, 1e-9)
        assertEquals(a.spread, b.spread, 1e-12)
    }

    /** The SD is the window's own: a wide wearer reads wide and a flat one is floored at 0.05 ln. */
    @Test fun spreadIsTheSampleSDFlooredPerMetric() {
        val flat = state(List(20) { 60.0 })
        assertEquals(ChargeBaselines.hrvSigmaFloorLn, 1.253 * flat.spread, 1e-12)
        assertEquals(BaselineStatus.TRUSTED, flat.status)
        val rhr = state(List(20) { 55.0 }, metric = ChargeBaselines.Metric.RESTING_HR)
        assertFalse(rhr.logDomain)
        assertEquals(Baselines.restingHRCfg.floorSpread, 1.253 * rhr.spread, 1e-12)
        val wide = state((0 until 30).map { if (it % 2 == 0) 30.0 else 120.0 })
        assertEquals(ln(4.0) / 2 * sqrt(30.0 / 29.0), 1.253 * wide.spread, 1e-9)
    }

    @Test fun epochDropsEarlierNightsAndEmptyIsCalibrating() {
        val epoch = (anchorEpochDay - 2).toDouble() * 86_400
        assertEquals(3, state(List(10) { 50.0 }, epoch = epoch).nValid)
        val empty = state(listOf(null, null))
        assertEquals(0, empty.nValid)
        assertFalse(empty.usable)
    }

    @Test fun aLongGapReadsStale() {
        val s = state(List<Double?>(10) { 50.0 } + List<Double?>(15) { null })
        assertEquals(15, s.nightsSinceUpdate)
        assertEquals(BaselineStatus.STALE, s.status)
    }

    /** A log-domain state z-scores in ln: the geometric mean scores 0, and a night e^σ above it scores +1. */
    @Test fun logDomainZIsInLn() {
        val s = state(listOf(40.0, 50.0, 80.0, 60.0, 45.0))
        assertEquals(0.0, Baselines.z(s.baseline, s), 1e-9)
        val sigma = Baselines.sigma(s)
        assertEquals(1.0, Baselines.z(s.baseline * exp(sigma), s), 1e-9)
        assertEquals(-1.0, Baselines.deviation(s.baseline * exp(-sigma), s).z, 1e-9)
        // A linear state is unchanged: (value − baseline) / σ.
        val lin = state(List(10) { 55.0 }, metric = ChargeBaselines.Metric.RESTING_HR)
        assertEquals(1.0, Baselines.z(55.0 + Baselines.sigma(lin), lin), 1e-9)
    }

    // ---- eras and per-day resolution (#53) ----------------------------------------------------------

    private fun src(back: Int, id: String) = ChargeBaselines.SourceDay(dayKey(anchorEpochDay - back), id)

    /** A WHOOP -> Garmin switch: a WHOOP-era day keeps its full baseline; the first Garmin day has nothing
     *  before it in its era; HRV/RHR cut, respiration cuts too, skin only on recalibration. */
    @Test fun eraCutIsDecidedPerScoredDay() {
        val sources = (0 until 30).map { b -> src(b, if (b < 5) "garmin-AAA" else "my-whoop") }
        assertEquals(
            ChargeBaselines.Epochs.none,
            ChargeBaselines.epochs(sources, dayKey(anchorEpochDay - 6), 0.0, 0.0),
        )
        val e = ChargeBaselines.epochs(sources, dayKey(anchorEpochDay - 4), 0.0, 0.0)
        val cut = (anchorEpochDay - 4).toDouble() * 86_400
        assertEquals(ChargeBaselines.Epochs(cut, cut, cut, 0.0), e)
        val oura = (0 until 30).map { b -> src(b, if (b < 5) "oura-import" else "my-whoop") }
        assertEquals(ChargeBaselines.Epochs(7.0, 9.0, cut, 9.0), ChargeBaselines.epochs(oura, anchor, 7.0, 9.0))
    }

    @Test fun sourceDaysPrecedenceOwnThenImportThenSeed() {
        val d = { b: Int -> dayKey(anchorEpochDay - b) }
        val out = ChargeBaselines.sourceDays(
            own = listOf(d(0) to "garmin-AAA", d(1) to null), ownFallbackId = "my-whoop",
            importedDays = listOf(d(1), d(2)), importedSourceId = "imp",
            seed = listOf(src(2, "garmin-import"), src(3, "garmin-import"), src(3, "garmin-other")),
        )
        assertEquals(listOf("garmin-import", "imp", "my-whoop", "garmin-AAA"), out.map { it.sourceId })
    }

    @Test fun recordedSourcesPreferTheBaselineKey() {
        val rows = listOf(
            ChargeBaselines.ProvenanceRow("a", "strain", "s"),
            ChargeBaselines.ProvenanceRow("a", "recovery", "r"),
            ChargeBaselines.ProvenanceRow("b", "strain", "s"),
            ChargeBaselines.ProvenanceRow("b", ChargeBaselines.baselineSourceKey, "x"),
            ChargeBaselines.ProvenanceRow("b", "recovery", "r"),
            ChargeBaselines.ProvenanceRow("c", "vo2max_est", "nes"),
        )
        assertEquals(mapOf("a" to "r", "b" to "x"), ChargeBaselines.recordedSources(rows))
    }

    /** The per-day baseline never reads the scored night or any later one, so adding nights after a day
     *  leaves that day's baseline (and so its stored Charge) exactly as it was. */
    @Test fun aDaysBaselineIgnoresTonightAndLaterNights() {
        val day = dayKey(anchorEpochDay - 10)
        fun resolve(backs: IntRange, tonight: Double): ChargeBaselines.Resolved {
            val own = backs.map { b -> dayKey(anchorEpochDay - b) to (if (b == 10) tonight else 50.0 + b % 5) }
            return ChargeBaselines.resolveDay(
                day, ChargeBaselines.Nights(), ChargeBaselines.Nights(hrv = own), ChargeBaselines.Nights(),
                emptyList(), 0.0, 0.0,
            )!!
        }
        val a = resolve(10..40, 20.0)
        val b = resolve(0..40, 200.0)
        assertEquals(dayKey(anchorEpochDay - 11), a.anchorDay)
        assertEquals(a.hrv, b.hrv)
        assertEquals(30, a.hrv.nValid)
    }

    private fun row(back: Int) = DailyMetric(
        deviceId = "my-whoop", day = dayKey(anchorEpochDay - back), restingHr = 50 + back % 7,
        avgHrv = 40.0 + back % 11, respRateBpm = 14.0 + (back % 3).toDouble() / 2,
        skinTempC = 33.0 + (back % 4).toDouble() / 10,
    )

    /** The dashboard inputs resolve exactly what the engine's day-by-day call resolves. */
    @Test fun inputsResolveMatchesResolveDay() {
        val own = (0 until 25).map { row(it) }
        val imported = (20 until 70).map { row(it) }
        val sources = (0 until 70).map { src(it, if (it < 3) "garmin-AAA" else "my-whoop") }
        val inputs = ChargeBaselines.Inputs(imported, own, emptyList(), sources, 0.0, 0.0)
        for (back in listOf(0, 2, 5, 12)) {
            val day = dayKey(anchorEpochDay - back)
            val direct = ChargeBaselines.resolveDay(
                day, ChargeBaselines.Nights.fromRows(imported).copy(skin = emptyList()),
                ChargeBaselines.Nights.fromRows(own), ChargeBaselines.Nights(), sources, 0.0, 0.0,
            )
            assertEquals(direct, inputs.resolve(day))
        }
        // Garmin era starts at day -2: day -2 has no night before it in its era, day -1 has one.
        assertEquals(1, inputs.resolve(dayKey(anchorEpochDay - 1))?.hrv?.nValid)
        assertEquals(0, inputs.resolve(dayKey(anchorEpochDay - 2))?.hrv?.nValid)
        // The last WHOOP day keeps its whole WHOOP baseline.
        assertTrue(inputs.resolve(dayKey(anchorEpochDay - 3))!!.hrv.nValid >= 14)
    }

    // ---- diagnostic line ----------------------------------------------------------------------------

    @Test fun logLineNamesEachMetricsComposition() {
        val seeded = ChargeBaselines.History(emptyList(), emptyList(), true, 9, 40)
        val own = ChargeBaselines.History(emptyList(), emptyList(), false, 45, 0)
        assertEquals(
            "charge baseline day=2026-09-30 window=60d hrv=own/45 rhr=seed/9+40 resp=own/45 skin=own/45",
            ChargeBaselines.logLine("2026-09-30", own, seeded, own, own),
        )
    }
}
