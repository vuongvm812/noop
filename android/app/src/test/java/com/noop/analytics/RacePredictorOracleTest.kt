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
        return RacePredictor.Run(startTs = start, endTs = e, sport = sport, distanceM = d, durationS = dur)
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
}
