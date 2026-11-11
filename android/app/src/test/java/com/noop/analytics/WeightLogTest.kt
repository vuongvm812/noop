package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #57: the hand-logged weight diary resolver behind the Today Weight tile, the weight chart and the profile
 * weight. The expected table is the stdout of the Swift `WeightLog` compiled standalone over the same cases
 * (see `WeightLogTests`); change both together.
 */
class WeightLogTest {
    private fun e(day: String, kg: Double) = WeightLog.Entry(day, kg)

    private fun show(r: WeightLog.Resolved?): String =
        r?.let { "${it.source.raw}:${it.entry.day}:${it.entry.kg}" } ?: "nil"

    @Test
    fun matchesTheSwiftOracle() {
        val out = mutableListOf<String>()
        val inputs = listOf("72.5", " 72,5 ", "165", "abc", "", "29.99", "250", "250.01", "551.2", "66.1", "nan",
            "inf", "-70", "80.005", "1e2")
        for (t in inputs) {
            val m = WeightLog.parseKg(t, imperial = false)?.toString() ?: "nil"
            val i = WeightLog.parseKg(t, imperial = true)?.toString() ?: "nil"
            out += "parse|$t|$m|$i"
        }
        val logged = listOf(e("2026-10-01", 80.0), e("2026-10-05", 79.4), e("2026-10-05", 70.0),
            e("2026-10-09", 10.0), e("2026-10-12", 78.8))
        val health = listOf(e("2026-10-03", 80.6), e("2026-10-05", 79.9), e("2026-10-10", 79.1),
            e("2026-10-11", Double.NaN))
        for (d in listOf("2026-09-30", "2026-10-01", "2026-10-04", "2026-10-05", "2026-10-09", "2026-10-10",
            "2026-10-11", "2026-10-12", "2026-11-01")) {
            out += "resolve|$d|" + show(WeightLog.resolve(logged, health, d))
        }
        out += "resolve-health-only|" + show(WeightLog.resolve(emptyList(), health, "2026-10-31"))
        out += "series|" + WeightLog.dailySeries(logged, health).joinToString(",") { "${it.day}=${it.kg}" }
        out += "profile|" + (WeightLog.profileWeight(logged)?.toString() ?: "nil")
        out += "profile-empty|" + (WeightLog.profileWeight(emptyList())?.toString() ?: "nil")
        out += "newest-tie|" + (WeightLog.newest(listOf(e("2026-10-05", 70.0), e("2026-10-05", 71.0)))?.kg?.toString() ?: "nil")
        assertEquals(
            listOf(
            "parse|72.5|72.5|32.89",
            "parse| 72,5 |72.5|32.89",
            "parse|165|165.0|74.84",
            "parse|abc|nil|nil",
            "parse||nil|nil",
            "parse|29.99|nil|nil",
            "parse|250|250.0|113.4",
            "parse|250.01|nil|113.4",
            "parse|551.2|nil|nil",
            "parse|66.1|66.1|nil",
            "parse|nan|nil|nil",
            "parse|inf|nil|nil",
            "parse|-70|nil|nil",
            "parse|80.005|80.01|36.29",
            "parse|1e2|100.0|45.36",
            "resolve|2026-09-30|nil",
            "resolve|2026-10-01|logged:2026-10-01:80.0",
            "resolve|2026-10-04|health:2026-10-03:80.6",
            "resolve|2026-10-05|logged:2026-10-05:79.4",
            "resolve|2026-10-09|logged:2026-10-05:79.4",
            "resolve|2026-10-10|health:2026-10-10:79.1",
            "resolve|2026-10-11|health:2026-10-10:79.1",
            "resolve|2026-10-12|logged:2026-10-12:78.8",
            "resolve|2026-11-01|logged:2026-10-12:78.8",
            "resolve-health-only|health:2026-10-10:79.1",
            "series|2026-10-01=80.0,2026-10-03=80.6,2026-10-05=79.4,2026-10-10=79.1,2026-10-12=78.8",
            "profile|78.8",
            "profile-empty|nil",
            "newest-tie|70.0",
            ),
            out,
        )
    }

    /** The same ids the Swift twin writes, so a `.noopbak` restored on the other platform reads the diary. */
    @Test
    fun storageIdsArePinned() {
        assertEquals("weight-log", WeightLog.SOURCE_ID)
        assertEquals("weight_kg", WeightLog.KEY)
    }

    private fun b(day: String, v: Double) = WeightLog.BodyReading(day, v)

    private fun showBody(r: WeightLog.BodyReading?): String = r?.let { "${it.day}:${it.value}" } ?: "nil"

    /** Expected lines are the stdout of the Swift `WeightLog.newestBodyReading` compiled standalone. */
    @Test
    fun bodyReadingMatchesTheSwiftOracle() {
        val cases = listOf(
            Triple("empty", emptyList<WeightLog.BodyReading?>(), null as Double?),
            Triple("allNil", listOf(null, null, null), null),
            Triple("newestWins", listOf(b("2026-10-01", 18.2), b("2026-10-05", 17.9), b("2026-10-03", 19.0)), 100.0),
            Triple("tieFirst", listOf(b("2026-10-05", 18.0), b("2026-10-05", 17.0)), 100.0),
            Triple("skipNil", listOf(null, b("2026-09-30", 61.4)), null),
            Triple("invalidDropped",
                listOf(b("2026-10-09", 0.0), b("2026-10-08", Double.NaN), b("2026-10-07", -3.0), b("2026-10-01", 61.0)), null),
            Triple("overMax", listOf(b("2026-10-09", 140.0), b("2026-10-02", 22.5)), 100.0),
            Triple("atMax", listOf(b("2026-10-09", 100.0)), 100.0),
        )
        val actual = cases.map { (n, c, m) -> "body|$n|" + showBody(WeightLog.newestBodyReading(c, m)) }
        val expected = listOf(
            "body|empty|nil",
            "body|allNil|nil",
            "body|newestWins|2026-10-05:17.9",
            "body|tieFirst|2026-10-05:18.0",
            "body|skipNil|2026-09-30:61.4",
            "body|invalidDropped|2026-10-01:61.0",
            "body|overMax|2026-10-02:22.5",
            "body|atMax|2026-10-09:100.0",
        )
        assertEquals(expected, actual)
    }
}
