package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [chartScrubResolve] / [chartScrubCadence] (#58) to the Swift twin `ChartScrubMath`. The expected
 * table is the verbatim stdout of the standalone Swift oracle (`swiftc -O twin.swift main.swift`) over
 * these cases; the same literal is asserted by Swift `ChartScrubMathTests`.
 */
class ChartScrubResolveTest {

    private data class Case(val name: String, val times: List<Long>, val cursor: Long)

    @Test
    fun resolveMatchesSwiftOracle() {
        val D = 86400L
        val cases = listOf(
            Case("empty", emptyList(), 5L),
            Case("single", listOf(100L), 0L),
            Case("single-after", listOf(100L), 999L),
            Case("daily-on", listOf(0L, D, 2*D, 3*D), D),
            Case("daily-tie", listOf(0L, D), D/2),
            Case("daily-gap-mid", listOf(0L, D, 3*D, 4*D), 2*D),
            Case("daily-gap-edge", listOf(0L, D, 3*D, 4*D), D + 3*D/4),
            Case("daily-gap-justin", listOf(0L, D, 3*D, 4*D), D + 3*D/4 + 1),
            Case("daily-gap-long", listOf(0L, D, 6*D, 7*D), 3*D + 7),
            Case("before-first", listOf(10L, 20L, 30L), -50L),
            Case("after-last", listOf(10L, 20L, 30L), 500L),
            Case("jitter", listOf(0L, 300L, 660L, 900L, 1200L), 480L),
            Case("dup-instant", listOf(5L, 5L, 5L), 6L),
            Case("dst-23h", listOf(0L, 82800L, 169200L, 255600L), 120000L),
            Case("hr-1hz-hole", listOf(0L, 1L, 2L, 3L, 10L, 11L, 12L), 6L),
            Case("negative", listOf(-300L, -200L, -100L, 0L), -151L),
        )
        val lines = cases.map { c ->
            val cad = chartScrubCadence(c.times)
            val h = chartScrubResolve(c.times, c.cursor)
            if (h == null) "${c.name} cad=$cad nil" else "${c.name} cad=$cad idx=${h.index} gap=${h.gapAt ?: "nil"}"
        }
        assertEquals(EXPECTED, lines.joinToString("\n"))
    }

    @Test
    fun nearestIndexClampsAndPrefersEarlierOnTie() {
        val f = chartScrubIndexFractions(3)
        assertEquals(0, chartScrubNearestIndex(f, 100f, -40f))
        assertEquals(2, chartScrubNearestIndex(f, 100f, 400f))
        assertEquals(0, chartScrubNearestIndex(f, 100f, 25f))
        assertEquals(1, chartScrubNearestIndex(f, 100f, 26f))
        assertEquals(listOf(0f), chartScrubIndexFractions(1))
    }

    private companion object {
        val EXPECTED = """
empty cad=0 nil
single cad=0 idx=0 gap=nil
single-after cad=0 idx=0 gap=nil
daily-on cad=86400 idx=1 gap=nil
daily-tie cad=86400 idx=0 gap=nil
daily-gap-mid cad=86400 idx=1 gap=172800
daily-gap-edge cad=86400 idx=1 gap=nil
daily-gap-justin cad=86400 idx=1 gap=172800
daily-gap-long cad=86400 idx=1 gap=259200
before-first cad=10 idx=0 gap=nil
after-last cad=10 idx=2 gap=nil
jitter cad=300 idx=1 gap=nil
dup-instant cad=0 idx=0 gap=nil
dst-23h cad=86400 idx=1 gap=nil
hr-1hz-hole cad=1 idx=3 gap=6
negative cad=100 idx=1 gap=nil
""".trimIndent()
    }
}
