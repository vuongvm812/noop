package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #42: the one resolver behind the Today stress card and the Stress screen. The expected table is the
 * stdout of the Swift `StressToday` compiled standalone over the same cases (see `StressTodayTests`);
 * change both together.
 */
class StressTodayTest {
    private fun p(ts: Long, level: Double) = StressToday.Point(ts, level)

    private fun show(r: StressToday.Reading?): String {
        r ?: return "nil"
        val l = r.latest?.let { "${it.ts}:${it.level}" } ?: "nil"
        val a = r.average?.toString() ?: "nil"
        return "${r.source.raw}|$l|$a|${r.points.size}|${r.scaleMax}"
    }

    @Test
    fun resolverMatchesTheSwiftOracle() {
        val g = listOf(p(300, 40.0), p(100, 25.0), p(200, 101.0), p(250, -1.0), p(400, 33.0))
        val n = listOf(p(1000, 1.2), p(1800, 2.4), p(2600, 0.7))
        data class C(
            val id: String, val g: List<StressToday.Point>, val w: Double?, val i: Double?,
            val n: List<StressToday.Point>, val m: Double?,
        )
        val cases = listOf(
            C("garmin-ab12", g, 31.0, 50.0, n, 1.4),
            C("garmin-ab12", emptyList(), 31.0, 50.0, n, 1.4),
            C("garmin-ab12", emptyList(), null, 47.0, emptyList(), null),
            C("garmin-ab12", emptyList(), null, null, n, 1.4),
            C("garmin-ab12", emptyList(), 150.0, 47.0, emptyList(), null),
            C("my-whoop", g, 31.0, 50.0, n, 1.4),
            C("my-whoop", g, null, null, emptyList(), null),
            C("my-whoop", emptyList(), null, null, emptyList(), 1.1),
            C("my-whoop", emptyList(), null, null, listOf(p(5, 3.5)), null),
            C("whoop-uuid", emptyList(), null, null, emptyList(), null),
        )
        val out = cases.map { show(StressToday.resolve(it.id, it.g, it.w, it.i, it.n, it.m)) }
        assertEquals(
            listOf(
                "garmin|400:33.0|32.666666666666664|3|100.0",
                "garmin|nil|31.0|0|100.0",
                "garmin|nil|47.0|0|100.0",
                "noop|2600:0.7|1.4|3|3.0",
                "garmin|nil|47.0|0|100.0",
                "noop|2600:0.7|1.4|3|3.0",
                "garmin|400:33.0|32.666666666666664|3|100.0",
                "noop|nil|1.1|0|3.0",
                "nil",
                "nil",
            ),
            out,
        )
    }

    /** A Garmin-only day filled from the imported export (#31) still resolves, with no sparkline. */
    @Test
    fun importedGarminDayResolvesWithoutSamples() {
        val r = StressToday.resolve("garmin-import", emptyList(), null, 42.0, emptyList(), null)
        assertEquals(StressToday.Source.GARMIN, r?.source)
        assertEquals(42.0, r?.average)
        assertNull(r?.latest)
        assertTrue(r?.points?.isEmpty() == true)
    }

    /** #57: the Key Metrics tile prints the latest level, else today's average. Swift twin:
     *  `StressTodayTests.testTileLevelPrefersLatestThenAverage`. */
    @Test
    fun tileLevelMatchesTheSwiftOracle() {
        val withLatest = StressToday.resolve("my-whoop", emptyList(), null, null, listOf(p(5, 2.5)), 1.1)
        val averageOnly = StressToday.resolve("garmin-ab12", emptyList(), 31.0, null, emptyList(), null)
        assertEquals(2.5, StressToday.tileLevel(withLatest)!!, 0.0)
        assertEquals(31.0, StressToday.tileLevel(averageOnly)!!, 0.0)
        assertNull(StressToday.tileLevel(null))
    }
}
