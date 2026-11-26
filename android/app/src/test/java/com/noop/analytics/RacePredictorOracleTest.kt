package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Swift/Kotlin parity for the race predictor, pinned by ORACLE: every expected literal below is the
 * verbatim stdout of the Swift twin `RacePredictor` compiled standalone with `swiftc -O` over the same
 * fixtures. Swift `RacePredictorTests` pins the same lines from the other side.
 */
class RacePredictorOracleTest {
    private fun f(v: Double?): String = v?.let { String.format(Locale.US, "%.9f", it) } ?: "nil"

    private fun p(x: RacePredictor.Prediction?): String =
        x?.let { "${it.targetIndex}:${it.seconds}@${it.sourceStartTs}/${f(it.sourceDistanceM)}/${f(it.sourceDurationS)}" }
            ?: "nil"

    private fun line(items: List<RacePredictor.Prediction?>): String = items.joinToString(" ") { p(it) }

    private fun run(start: Long, sport: String, d: Double?, dur: Double?, end: Long? = null): RacePredictor.Run {
        val e = end ?: if (dur != null && dur.isFinite()) start + dur.toLong() else start
        return RacePredictor.Run(startTs = start, endTs = e, sport = sport, distanceM = d, durationS = dur, avgHr = null)
    }

    private val now = 1_800_000_000L
    private val day = 86_400L

    @Test
    fun riegelMatchesSwift() {
        val line = listOf(
            RacePredictor.riegel(1500.0, 5000.0, 10000.0),
            RacePredictor.riegel(3000.0, 10000.0, 42195.0),
            RacePredictor.riegel(1200.0, 4000.0, 5000.0),
            RacePredictor.riegel(7200.0, 21097.5, 5000.0),
            RacePredictor.riegel(1500.0, 5000.0, 5000.0),
        ).joinToString(" ") { f(it) }
        assertEquals("RIEGEL 3127.397282523 13800.597999770 1520.217962846 1565.149568183 1500.000000000", "RIEGEL $line")
    }

    @Test
    fun qualifyingFiltersMatchSwift() {
        val qual = listOf(
            run(0, "Running", 5000.0, 1500.0),
            run(0, "Walking", 5000.0, 1500.0),
            run(0, "Running", 2900.0, 1200.0),
            run(0, "Running", 3000.0, 660.0),
            run(0, "Running", 9600.0, 1200.0),
            run(0, " TREADMILL RUN\n", 5000.0, 1800.0),
            run(1000, "Running", 5000.0, null, end = 2500),
            run(0, "Running", null, 1500.0),
            run(1000, "running", 5000.0, 0.0, end = 2600),
            run(0, "Hiking", 8000.0, 3600.0),
            run(0, "Running", 5000.0, 3600.0),
            run(0, "Running", 4500.0, 3000.0),
            run(0, "Running", 7000.0, 1000.0),
            run(0, "Running", Double.NaN, 1500.0),
            run(0, "Trail running", 5000.0, 1500.0),
            run(0, "Running", 5000.0, Double.POSITIVE_INFINITY, end = 1500),
            run(0, "Running", 5000.0, -10.0, end = 1500),
        )
        assertEquals("QUAL 10000110100110011", "QUAL " + qual.joinToString("") { if (RacePredictor.qualifies(it)) "1" else "0" })
        assertEquals(
            "DUR 1500.000000000 1500.000000000 1200.000000000 660.000000000 1200.000000000 1800.000000000 " +
                "1500.000000000 1500.000000000 1600.000000000 3600.000000000 3600.000000000 3000.000000000 " +
                "1000.000000000 1500.000000000 1500.000000000 1500.000000000 1500.000000000",
            "DUR " + qual.joinToString(" ") { f(RacePredictor.effectiveDurationS(it)) },
        )
        val sports = listOf("Running", "running", "  Running\t", "Treadmill run", "TREADMILL RUN", "Treadmill walk",
            "Run", "Trail running", "")
        assertEquals("SPORT 111110000", "SPORT " + sports.joinToString("") { if (RacePredictor.isRunningSport(it)) "1" else "0" })
    }

    @Test
    fun forRunAppliesTheExtrapolationCapLikeSwift() {
        val runs = listOf(
            run(0, "Running", 5000.0, 1500.0), run(0, "Running", 10500.0, 3300.0), run(0, "Running", 10600.0, 3300.0),
            run(0, "Running", 21097.5, 6300.0), run(0, "Running", 42195.0, 13500.0),
            run(0, "Walking", 10000.0, 4000.0), run(0, "Running", 3000.0, 900.0),
        )
        assertEquals(
            listOf(
                "FOR 0:1500@0/5000.000000000/1500.000000000 1:3127@0/5000.000000000/1500.000000000 nil nil",
                "FOR 0:1503@0/10500.000000000/3300.000000000 1:3134@0/10500.000000000/3300.000000000 " +
                    "2:6914@0/10500.000000000/3300.000000000 nil",
                "FOR 0:1488@0/10600.000000000/3300.000000000 1:3102@0/10600.000000000/3300.000000000 " +
                    "2:6845@0/10600.000000000/3300.000000000 3:14271@0/10600.000000000/3300.000000000",
                "FOR 0:1370@0/21097.500000000/6300.000000000 1:2855@0/21097.500000000/6300.000000000 " +
                    "2:6300@0/21097.500000000/6300.000000000 3:13135@0/21097.500000000/6300.000000000",
                "FOR 0:1408@0/42195.000000000/13500.000000000 1:2935@0/42195.000000000/13500.000000000 " +
                    "2:6475@0/42195.000000000/13500.000000000 3:13500@0/42195.000000000/13500.000000000",
                "FOR nil nil nil nil",
                "FOR 0:1547@0/3000.000000000/900.000000000 1:3225@0/3000.000000000/900.000000000 nil nil",
            ),
            runs.map { "FOR " + line(RacePredictor.forRun(it)) },
        )
    }

    @Test
    fun windowFallbackAndFastestWinsMatchSwift() {
        val mixed = listOf(
            run(now - 10 * day, "Running", 5000.0, 1560.0),
            run(now - 20 * day, "Running", 10000.0, 3000.0),
            run(now - 30 * day, "Running", 15000.0, 4800.0),
            run(now - 40 * day, "Walking", 12000.0, 2000.0),
            run(now - 200 * day, "Running", 21097.5, 5400.0),
            run(now - 5 * day, "Running", 2000.0, 500.0),
        )
        val res = RacePredictor.predictions(mixed, now)
        assertEquals(
            "MIX recent 0:1439@1798272000/10000.000000000/3000.000000000 " +
                "1:3000@1798272000/10000.000000000/3000.000000000 " +
                "2:6619@1798272000/10000.000000000/3000.000000000 " +
                "3:14367@1797408000/15000.000000000/4800.000000000",
            "MIX " + (if (res.fromAllHistory) "all" else "recent") + " " + line(res.items),
        )
        val old = listOf(
            run(now - 200 * day, "Running", 21097.5, 5400.0),
            run(now - 400 * day, "Running", 5000.0, 1200.0),
            run(now - 91 * day, "Running", 8000.0, 2600.0),
        )
        val resOld = RacePredictor.predictions(old, now)
        assertEquals(
            "OLD all 0:1174@1782720000/21097.500000000/5400.000000000 " +
                "1:2447@1782720000/21097.500000000/5400.000000000 " +
                "2:5400@1782720000/21097.500000000/5400.000000000 " +
                "3:11259@1782720000/21097.500000000/5400.000000000",
            "OLD " + (if (resOld.fromAllHistory) "all" else "recent") + " " + line(resOld.items),
        )
        val edge = RacePredictor.predictions(listOf(run(now - 90 * day, "Running", 5000.0, 1500.0)), now)
        assertEquals(
            "EDGE recent 0:1500@1792224000/5000.000000000/1500.000000000 " +
                "1:3127@1792224000/5000.000000000/1500.000000000 nil nil",
            "EDGE " + (if (edge.fromAllHistory) "all" else "recent") + " " + line(edge.items),
        )
        val none = RacePredictor.predictions(listOf(run(now, "Cycling", 30000.0, 3600.0)), now)
        assertEquals("NONE recent nil nil nil nil", "NONE " + (if (none.fromAllHistory) "all" else "recent") + " " + line(none.items))
        assertEquals(
            "PACE 287.800000000 300.000000000 313.733854722 340.490579453",
            "PACE " + res.items.joinToString(" ") { it?.let { p -> f(RacePredictor.secPerKm(p)) } ?: "nil" },
        )
    }

    @Test
    fun tieBreakIsOrderIndependentLikeSwift() {
        val ties = listOf(
            run(now - 3 * day, "Running", 5000.0, 1500.0), run(now - 2 * day, "Running", 5000.0, 1500.0),
            run(now - 4 * day, "Running", 4000.0, 1500.0),
        )
        val a = RacePredictor.predictions(ties, now)
        val b = RacePredictor.predictions(ties.reversed(), now)
        assertEquals(
            "TIE 0:1500@1799827200/5000.000000000/1500.000000000 " +
                "1:3127@1799827200/5000.000000000/1500.000000000 nil nil | " +
                "0:1500@1799827200/5000.000000000/1500.000000000 " +
                "1:3127@1799827200/5000.000000000/1500.000000000 nil nil",
            "TIE " + line(a.items) + " | " + line(b.items),
        )
    }

    @Test
    fun addingAndRemovingAFasterRunMovesThePrediction() {
        val base = listOf(run(now - 10 * day, "Running", 5000.0, 1560.0))
        val before = RacePredictor.predictions(base, now).items[0]?.seconds
        val faster = base + run(now - 1 * day, "Running", 5000.0, 1440.0)
        assertEquals(1440, RacePredictor.predictions(faster, now).items[0]?.seconds)
        assertEquals(before, RacePredictor.predictions(base, now).items[0]?.seconds)
        assertEquals(1560, before)
    }

    @Test
    fun minRunMatchesSwift() {
        assertEquals(
            "MINRUN 0.000000000 1250.000000000 2500.000000000 5274.375000000 10548.750000000 0.000000000",
            "MINRUN " + listOf(-1, 0, 1, 2, 3, 4).joinToString(" ") { f(RacePredictor.minRunM(it)) },
        )
    }

    @Test
    fun formatTimeMatchesSwift() {
        assertEquals(
            "FMT 0:00 0:59 10:00 24:59 59:59 1:00:00 1:01:01 2:46:40 10:00:00 0:00",
            "FMT " + listOf(0, 59, 600, 1499, 3599, 3600, 3661, 10_000, 36_000, -5).joinToString(" ") { RacePredictor.formatTime(it) },
        )
    }

    // v2: Daniels VDOT, heart-rate effort, VO₂max, blend, history (same oracle, same fixtures as Swift).

    private fun est(e: RacePredictor.Estimate?): String = e?.let {
        "${it.targetIndex}:${it.seconds}[${f(it.components.riegelS)}|${f(it.components.hrS)}|${f(it.components.vo2S)}]" +
            (if (it.riegel == null) "E" else "R")
    } ?: "nil"

    private fun i(v: Int?): String = v?.toString() ?: "nil"

    private fun hrRun(start: Long, sport: String, d: Double, dur: Double, hr: Double?): RacePredictor.Run =
        RacePredictor.Run(start, start + dur.toLong(), sport, d, dur, hr)

    @Test
    fun danielsModelMatchesSwift() {
        assertEquals(
            "VO2COST -4.600000000 14.665800000 25.078700000 36.011600000 47.464500000 51.762444560 " +
            "59.437400000 71.930300000 84.943200000",
            "VO2COST " + listOf(0.0, 100.0, 150.0, 200.0, 250.0, 268.2, 300.0, 350.0, 400.0).joinToString(" ") { f(RacePredictor.vo2Cost(it)) },
        )
        assertEquals(
            "FRAC 1.288395100 1.091463441 0.991914029 0.952983125 0.930025336 0.906647316 0.888007314 " +
            "0.859982257 0.840882768 0.818992205 0.808822883",
            "FRAC " + listOf(0.0, 5.0, 12.0, 20.0, 30.0, 45.0, 60.0, 90.0, 120.0, 180.0, 240.0).joinToString(" ") { f(RacePredictor.fractionAt(it)) },
        )
        assertEquals(
            listOf(
                "TFV 30 1840.877537363 3828.853225879 8477.218074930 17388.861762954",
                "TFV 40 1446.218941311 3000.771086871 6653.909186662 13777.421299376",
                "TFV 50 1196.020824328 2479.603649337 5491.197069311 11439.741755151",
                "TFV 60 1022.785122427 2121.854975856 4688.594740426 9802.480501888",
                "TFV 70 895.523685568 1860.950249480 4102.678137969 8592.728224653",
                "TFV 85 757.305815165 1579.188061992 3471.442195617 7275.054904279",
            ),
            listOf(30.0, 40.0, 50.0, 60.0, 70.0, 85.0).map { vdot ->
                "TFV ${vdot.toInt()} " + RacePredictor.targetDistancesM().joinToString(" ") { f(RacePredictor.timeForVdot(vdot, it)) }
            },
        )
        val edge = listOf(
            0.0 to 5000.0, -1.0 to 5000.0, Double.NaN to 5000.0, Double.POSITIVE_INFINITY to 5000.0, 50.0 to 0.0,
            50.0 to -5.0, 50.0 to Double.NaN, 1.0 to 5000.0, 2.0 to 5000.0, 200.0 to 5000.0, 400.0 to 5000.0,
        )
        assertEquals(
            "TFVEDGE nil nil nil nil nil nil nil nil nil 367.704937948 nil",
            "TFVEDGE " + edge.joinToString(" ") { f(RacePredictor.timeForVdot(it.first, it.second)) },
        )
    }

    @Test
    fun heartRateEffortMatchesSwift() {
        val cases = listOf<Triple<Double?, Double?, Double?>>(
            Triple(150.0, 50.0, 190.0), Triple(134.0, 50.0, 190.0), Triple(133.0, 50.0, 190.0), Triple(190.0, 50.0, 190.0),
            Triple(191.0, 50.0, 190.0), Triple(null, 50.0, 190.0), Triple(150.0, null, 190.0), Triple(150.0, 50.0, null),
            Triple(150.0, 0.0, 190.0), Triple(150.0, 190.0, 190.0), Triple(150.0, 200.0, 190.0), Triple(Double.NaN, 50.0, 190.0),
            Triple(170.0, 55.0, 185.0),
        )
        assertEquals(
            "HRR 0.714285714 0.600000000 nil 1.000000000 nil nil nil nil nil nil nil nil 0.884615385",
            "HRR " + cases.joinToString(" ") { f(RacePredictor.hrrFraction(it.first, it.second, it.third)) },
        )
        val runs = listOf(
            hrRun(now - 1 * day, "Running", 10000.0, 3600.0, 150.0), hrRun(now - 2 * day, "Running", 5000.0, 1500.0, 175.0),
            hrRun(now - 3 * day, "Running", 8000.0, 2880.0, 120.0), hrRun(now - 4 * day, "Running", 12000.0, 4200.0, 160.0),
            hrRun(now - 5 * day, "Running", 6000.0, 2100.0, null), hrRun(now - 6 * day, "Walking", 6000.0, 3600.0, 150.0),
            hrRun(now - 7 * day, "Running", 2000.0, 600.0, 170.0), hrRun(now - 8 * day, "Running", 7000.0, 2400.0, 200.0),
        )
        assertEquals(
            "VDOTHR 38.731311111 39.912992000 nil 36.846161039 nil nil nil nil",
            "VDOTHR " + runs.joinToString(" ") { f(RacePredictor.vdotFromHr(it, 50.0, 190.0)) },
        )
        val inputs = RacePredictor.Inputs(190.0, 50.0, 52.0)
        assertEquals(
            "HRVDOT 38.496821383 39.322151556 nil nil",
            "HRVDOT ${f(RacePredictor.hrVdot(runs, inputs))} ${f(RacePredictor.hrVdot(runs.take(2), inputs))} " +
                "${f(RacePredictor.hrVdot(listOf(runs[2]), inputs))} " +
                f(RacePredictor.hrVdot(runs, RacePredictor.Inputs(null, 50.0, null))),
        )
    }

    @Test
    fun blendWeightsMatchSwift() {
        val blends = ArrayList<String>()
        for (r in listOf(1500.0, null)) for (h in listOf(1400.0, null)) for (v in listOf(1300.0, null)) {
            blends.add(f(RacePredictor.blend(RacePredictor.Components(r, h, v))))
        }
        assertEquals("BLEND 1430.000000000 1462.500000000 1442.857142857 1500.000000000 1360.000000000 " +
            "1400.000000000 1300.000000000 nil", "BLEND " + blends.joinToString(" "))
        assertEquals(
            "WEIGHTS 0.500000000 0.300000000 0.200000000",
            "WEIGHTS ${f(RacePredictor.riegelWeight())} ${f(RacePredictor.hrWeight())} ${f(RacePredictor.vo2Weight())}",
        )
    }

    @Test
    fun blendedPredictionsMatchSwift() {
        val mixed = listOf(
            hrRun(now - 10 * day, "Running", 5000.0, 1560.0, 168.0), hrRun(now - 20 * day, "Running", 8000.0, 2900.0, 140.0),
            hrRun(now - 30 * day, "Running", 6000.0, 2100.0, 150.0), hrRun(now - 200 * day, "Running", 21097.5, 5400.0, 165.0),
        )
        val cases = listOf(
            "NONE" to RacePredictor.Inputs(null, null, null), "HR" to RacePredictor.Inputs(190.0, 50.0, null),
            "VO2" to RacePredictor.Inputs(null, null, 50.0), "ALL" to RacePredictor.Inputs(190.0, 50.0, 50.0),
            "BADVO2" to RacePredictor.Inputs(190.0, 50.0, -3.0),
        )
        assertEquals(
            listOf(
                "PRED NONE recent 0:1560[1560.000000000|nil|nil]R 1:3252[3252.000000000|nil|nil]R " +
                "2:7963[7963.000000000|nil|nil]R nil",
                "PRED HR recent 0:1508[1560.000000000|1421.412694314|nil]R " +
                "1:3138[3252.000000000|2948.912182022|nil]R 2:7429[7963.000000000|6538.680895379|nil]R " +
                "3:13547[nil|13547.257362012|nil]E",
                "PRED VO2 recent 0:1456[1560.000000000|nil|1196.020824328]R " +
                "1:3031[3252.000000000|nil|2479.603649337]R 2:7257[7963.000000000|nil|5491.197069311]R " +
                "3:11440[nil|nil|11439.741755151]E",
                "PRED ALL recent 0:1446[1560.000000000|1421.412694314|1196.020824328]R " +
                "1:3007[3252.000000000|2948.912182022|2479.603649337]R " +
                "2:7041[7963.000000000|6538.680895379|5491.197069311]R " +
                "3:12704[nil|13547.257362012|11439.741755151]E",
                "PRED BADVO2 recent 0:1508[1560.000000000|1421.412694314|nil]R " +
                "1:3138[3252.000000000|2948.912182022|nil]R 2:7429[7963.000000000|6538.680895379|nil]R " +
                "3:13547[nil|13547.257362012|nil]E",
            ),
            cases.map { (name, inputs) ->
                val r = RacePredictor.blendedPredictions(mixed, now, inputs)
                "PRED $name ${if (r.fromAllHistory) "all" else "recent"} " + r.estimates.joinToString(" ") { est(it) }
            },
        )
        val riegelOnly = RacePredictor.predictions(mixed, now)
        assertEquals(riegelOnly.items.map { it?.seconds }, riegelOnly.estimates.map { it?.seconds })
        val old = RacePredictor.blendedPredictions(
            listOf(hrRun(now - 200 * day, "Running", 10000.0, 3000.0, 160.0)), now, RacePredictor.Inputs(190.0, 50.0, 48.0),
        )
        assertEquals(
            "PREDOLD all 0:1382[1439.000000000|nil|1238.491615985]R " +
            "1:2876[3000.000000000|nil|2567.737140031]R 2:6353[6619.000000000|nil|5688.482961099]R " +
            "3:11839[nil|nil|11838.993075231]E",
            "PREDOLD ${if (old.fromAllHistory) "all" else "recent"} " + old.estimates.joinToString(" ") { est(it) },
        )
        val noRuns = RacePredictor.blendedPredictions(
            listOf(hrRun(now, "Cycling", 30000.0, 3600.0, 150.0)), now, RacePredictor.Inputs(190.0, 50.0, 55.0),
        )
        assertEquals("PREDNORUN nil nil nil nil", "PREDNORUN " + noRuns.estimates.joinToString(" ") { est(it) })
        val paces = listOf(1500 to 0, 3127 to 1, 6914 to 2, 14271 to 3, 100 to 4, 100 to -1)
        assertEquals(
            "PACE 300.000000000 312.700000000 327.716554094 338.215428368 0.000000000 0.000000000",
            "PACE " + paces.joinToString(" ") { f(RacePredictor.paceSecPerKm(it.first, it.second)) },
        )
    }

    @Test
    fun inputsAsOfDayMatchSwift() {
        val dv = { d: Int, v: Double -> RacePredictor.DayValue(d, v) }
        val rhr = listOf(dv(100, 60.0), dv(101, 58.0), dv(103, 55.0), dv(104, 0.0), dv(106, 57.0), dv(108, 52.0))
        assertEquals(
            "RHR nil 60.000000000 59.000000000 58.000000000 57.500000000 57.000000000 55.000000000 " +
            "54.500000000 nil nil",
            "RHR " + listOf(99, 100, 101, 103, 106, 107, 108, 112, 115, 116).joinToString(" ") { f(RacePredictor.restingHrAsOf(rhr, it)) },
        )
        val vo2 = listOf(dv(100, 48.0), dv(110, 49.5), dv(110, 50.5), dv(120, -1.0), dv(150, 51.0))
        assertEquals(
            "VO2AT nil 48.000000000 48.000000000 50.500000000 50.500000000 50.500000000 50.500000000 " +
            "nil 51.000000000 51.000000000 nil",
            "VO2AT " + listOf(99, 100, 109, 110, 120, 130, 140, 141, 150, 180, 181).joinToString(" ") { f(RacePredictor.vo2maxAsOf(vo2, it)) },
        )
        assertEquals(
            "KEYS race_pred_5k_s race_pred_10k_s race_pred_half_s race_pred_marathon_s 365 7 30 3 100",
            "KEYS " + RacePredictor.historyKeys().joinToString(" ") +
                " ${RacePredictor.historyDays()} ${RacePredictor.restingHrDays()} ${RacePredictor.vo2maxMaxAgeDays()}" +
                " ${RacePredictor.hrTopCount()} ${RacePredictor.bisectionSteps()}",
        )
    }

    @Test
    fun dailyHistoryAndChangesMatchSwift() {
        val e0 = (now / day).toInt()
        val runs = listOf(
            hrRun((e0 - 5) * day + 3600, "Running", 5000.0, 1500.0, 165.0),
            hrRun((e0 - 3) * day + 3600, "Running", 10000.0, 3300.0, 150.0),
            hrRun((e0 - 1) * day + 3600, "Running", 3000.0, 1000.0, 140.0),
        )
        val days = (0..6).map { k ->
            val ed = e0 - 6 + k
            RacePredictor.HistoryDay(
                ed, if (k == 6) now else (ed + 1) * day - 1, RacePredictor.Inputs(190.0, 50.0, if (k >= 4) 51.0 else null),
            )
        }
        val points = RacePredictor.dailyHistory(runs, days)
        assertEquals(
            "HIST 20827=nil,nil,nil,nil 20828=1447,3011,6244,12958 20829=1447,3011,6244,12958 " +
            "20830=1445,3008,6886,12922 20831=1391,2894,6588,12253 20832=1381,2872,6540,12062 " +
            "20833=1381,2872,6540,12062",
            "HIST " + points.joinToString(" ") { p -> "${p.epochDay}=" + p.seconds.joinToString(",") { i(it) } },
        )
        val live = RacePredictor.blendedPredictions(runs, now, RacePredictor.Inputs(190.0, 50.0, 51.0))
        assertEquals("LIVE 1381,2872,6540,12062", "LIVE " + live.estimates.joinToString(",") { i(it?.seconds) })
        val stored = listOf(
            listOf(
                RacePredictor.DayValue(e0 - 6, 999.0), RacePredictor.DayValue(e0 - 4, (points[2].seconds[0] ?: 0).toDouble()),
                RacePredictor.DayValue(e0 - 30, 1.0),
            ),
            listOf(RacePredictor.DayValue(e0 - 3, (points[3].seconds[1] ?: 0).toDouble())),
            emptyList(),
            listOf(RacePredictor.DayValue(e0 - 5, 12345.0)),
        )
        assertEquals(
            "CHANGES -6/0=nil -5/0=1447 -5/1=3011 -5/2=6244 -5/3=12958 -4/1=3011 -4/2=6244 -4/3=12958 " +
            "-3/0=1445 -3/2=6886 -3/3=12922 -2/0=1391 -2/1=2894 -2/2=6588 -2/3=12253 -1/0=1381 " +
            "-1/1=2872 -1/2=6540 -1/3=12062 0/0=1381 0/1=2872 0/2=6540 0/3=12062",
            "CHANGES " + RacePredictor.historyChanges(points, stored)
                .joinToString(" ") { "${it.epochDay - e0}/${it.targetIndex}=${i(it.seconds)}" },
        )
    }
}
