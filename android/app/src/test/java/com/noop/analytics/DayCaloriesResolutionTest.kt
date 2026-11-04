package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DayCalories], the one "Calories" figure every surface shows (#51). [EXPECTED] is the Swift oracle's
 * output over the same inputs, verbatim (Swift twin: `DayCaloriesResolutionTests`).
 */
class DayCaloriesResolutionTest {
    private fun oracleOutput(): String {
        fun f(v: Double?) = v?.toString() ?: "nil"
        val vals = listOf(null, -5.0, 0.0, 0.5, 512.25, 1650.0)
        val out = ArrayList<String>()
        for (a in vals) for (b in vals) for (s in vals) {
            val day = DayCalories.byDay(listOf(DayCalories.ImportedDay("d", a, b)), s?.let { mapOf("d" to it) } ?: emptyMap())
                .firstOrNull()?.second
            out.add("${f(a)} ${f(b)} ${f(s)} -> ${f(day)} total=${f(DayCalories.importedTotal(a, b))}")
        }
        val rows = listOf(
            DayCalories.ImportedDay("2026-11-01", 500.0, 1700.0),
            DayCalories.ImportedDay("2026-11-01", 450.5, 1800.0),
            DayCalories.ImportedDay("2026-11-02", 600.0, null),
            DayCalories.ImportedDay("2026-11-04", 0.0, 1600.0),
        )
        val stored = mapOf("2026-11-01" to 2400.0, "2026-11-02" to 2300.5, "2026-11-03" to 0.0, "2026-11-05" to 2100.0)
        for ((d, k) in DayCalories.byDay(rows, stored)) out.add("day $d $k")
        return out.joinToString("\n")
    }

    @Test
    fun oracleOutputIsPinned() {
        assertEquals(EXPECTED, oracleOutput())
    }

    /** A phone day with only its active half never stands in for a total. */
    @Test
    fun activeOnlyPhoneDayFallsBackToTheStoredTotal() {
        val phone = listOf(DayCalories.ImportedDay("2026-11-01", 520.0, null))
        assertEquals(2_410.0, DayCalories.byDay(phone, mapOf("2026-11-01" to 2_410.0)).single().second, 0.0)
        assertTrue(DayCalories.byDay(phone, emptyMap()).isEmpty())
    }

    private companion object {
        val EXPECTED = """
            nil nil nil -> nil total=nil
            nil nil -5.0 -> nil total=nil
            nil nil 0.0 -> nil total=nil
            nil nil 0.5 -> 0.5 total=nil
            nil nil 512.25 -> 512.25 total=nil
            nil nil 1650.0 -> 1650.0 total=nil
            nil -5.0 nil -> nil total=nil
            nil -5.0 -5.0 -> nil total=nil
            nil -5.0 0.0 -> nil total=nil
            nil -5.0 0.5 -> 0.5 total=nil
            nil -5.0 512.25 -> 512.25 total=nil
            nil -5.0 1650.0 -> 1650.0 total=nil
            nil 0.0 nil -> nil total=nil
            nil 0.0 -5.0 -> nil total=nil
            nil 0.0 0.0 -> nil total=nil
            nil 0.0 0.5 -> 0.5 total=nil
            nil 0.0 512.25 -> 512.25 total=nil
            nil 0.0 1650.0 -> 1650.0 total=nil
            nil 0.5 nil -> nil total=nil
            nil 0.5 -5.0 -> nil total=nil
            nil 0.5 0.0 -> nil total=nil
            nil 0.5 0.5 -> 0.5 total=nil
            nil 0.5 512.25 -> 512.25 total=nil
            nil 0.5 1650.0 -> 1650.0 total=nil
            nil 512.25 nil -> nil total=nil
            nil 512.25 -5.0 -> nil total=nil
            nil 512.25 0.0 -> nil total=nil
            nil 512.25 0.5 -> 0.5 total=nil
            nil 512.25 512.25 -> 512.25 total=nil
            nil 512.25 1650.0 -> 1650.0 total=nil
            nil 1650.0 nil -> nil total=nil
            nil 1650.0 -5.0 -> nil total=nil
            nil 1650.0 0.0 -> nil total=nil
            nil 1650.0 0.5 -> 0.5 total=nil
            nil 1650.0 512.25 -> 512.25 total=nil
            nil 1650.0 1650.0 -> 1650.0 total=nil
            -5.0 nil nil -> nil total=nil
            -5.0 nil -5.0 -> nil total=nil
            -5.0 nil 0.0 -> nil total=nil
            -5.0 nil 0.5 -> 0.5 total=nil
            -5.0 nil 512.25 -> 512.25 total=nil
            -5.0 nil 1650.0 -> 1650.0 total=nil
            -5.0 -5.0 nil -> nil total=nil
            -5.0 -5.0 -5.0 -> nil total=nil
            -5.0 -5.0 0.0 -> nil total=nil
            -5.0 -5.0 0.5 -> 0.5 total=nil
            -5.0 -5.0 512.25 -> 512.25 total=nil
            -5.0 -5.0 1650.0 -> 1650.0 total=nil
            -5.0 0.0 nil -> nil total=nil
            -5.0 0.0 -5.0 -> nil total=nil
            -5.0 0.0 0.0 -> nil total=nil
            -5.0 0.0 0.5 -> 0.5 total=nil
            -5.0 0.0 512.25 -> 512.25 total=nil
            -5.0 0.0 1650.0 -> 1650.0 total=nil
            -5.0 0.5 nil -> nil total=nil
            -5.0 0.5 -5.0 -> nil total=nil
            -5.0 0.5 0.0 -> nil total=nil
            -5.0 0.5 0.5 -> 0.5 total=nil
            -5.0 0.5 512.25 -> 512.25 total=nil
            -5.0 0.5 1650.0 -> 1650.0 total=nil
            -5.0 512.25 nil -> nil total=nil
            -5.0 512.25 -5.0 -> nil total=nil
            -5.0 512.25 0.0 -> nil total=nil
            -5.0 512.25 0.5 -> 0.5 total=nil
            -5.0 512.25 512.25 -> 512.25 total=nil
            -5.0 512.25 1650.0 -> 1650.0 total=nil
            -5.0 1650.0 nil -> nil total=nil
            -5.0 1650.0 -5.0 -> nil total=nil
            -5.0 1650.0 0.0 -> nil total=nil
            -5.0 1650.0 0.5 -> 0.5 total=nil
            -5.0 1650.0 512.25 -> 512.25 total=nil
            -5.0 1650.0 1650.0 -> 1650.0 total=nil
            0.0 nil nil -> nil total=nil
            0.0 nil -5.0 -> nil total=nil
            0.0 nil 0.0 -> nil total=nil
            0.0 nil 0.5 -> 0.5 total=nil
            0.0 nil 512.25 -> 512.25 total=nil
            0.0 nil 1650.0 -> 1650.0 total=nil
            0.0 -5.0 nil -> nil total=nil
            0.0 -5.0 -5.0 -> nil total=nil
            0.0 -5.0 0.0 -> nil total=nil
            0.0 -5.0 0.5 -> 0.5 total=nil
            0.0 -5.0 512.25 -> 512.25 total=nil
            0.0 -5.0 1650.0 -> 1650.0 total=nil
            0.0 0.0 nil -> nil total=nil
            0.0 0.0 -5.0 -> nil total=nil
            0.0 0.0 0.0 -> nil total=nil
            0.0 0.0 0.5 -> 0.5 total=nil
            0.0 0.0 512.25 -> 512.25 total=nil
            0.0 0.0 1650.0 -> 1650.0 total=nil
            0.0 0.5 nil -> nil total=nil
            0.0 0.5 -5.0 -> nil total=nil
            0.0 0.5 0.0 -> nil total=nil
            0.0 0.5 0.5 -> 0.5 total=nil
            0.0 0.5 512.25 -> 512.25 total=nil
            0.0 0.5 1650.0 -> 1650.0 total=nil
            0.0 512.25 nil -> nil total=nil
            0.0 512.25 -5.0 -> nil total=nil
            0.0 512.25 0.0 -> nil total=nil
            0.0 512.25 0.5 -> 0.5 total=nil
            0.0 512.25 512.25 -> 512.25 total=nil
            0.0 512.25 1650.0 -> 1650.0 total=nil
            0.0 1650.0 nil -> nil total=nil
            0.0 1650.0 -5.0 -> nil total=nil
            0.0 1650.0 0.0 -> nil total=nil
            0.0 1650.0 0.5 -> 0.5 total=nil
            0.0 1650.0 512.25 -> 512.25 total=nil
            0.0 1650.0 1650.0 -> 1650.0 total=nil
            0.5 nil nil -> nil total=nil
            0.5 nil -5.0 -> nil total=nil
            0.5 nil 0.0 -> nil total=nil
            0.5 nil 0.5 -> 0.5 total=nil
            0.5 nil 512.25 -> 512.25 total=nil
            0.5 nil 1650.0 -> 1650.0 total=nil
            0.5 -5.0 nil -> nil total=nil
            0.5 -5.0 -5.0 -> nil total=nil
            0.5 -5.0 0.0 -> nil total=nil
            0.5 -5.0 0.5 -> 0.5 total=nil
            0.5 -5.0 512.25 -> 512.25 total=nil
            0.5 -5.0 1650.0 -> 1650.0 total=nil
            0.5 0.0 nil -> nil total=nil
            0.5 0.0 -5.0 -> nil total=nil
            0.5 0.0 0.0 -> nil total=nil
            0.5 0.0 0.5 -> 0.5 total=nil
            0.5 0.0 512.25 -> 512.25 total=nil
            0.5 0.0 1650.0 -> 1650.0 total=nil
            0.5 0.5 nil -> 1.0 total=1.0
            0.5 0.5 -5.0 -> 1.0 total=1.0
            0.5 0.5 0.0 -> 1.0 total=1.0
            0.5 0.5 0.5 -> 1.0 total=1.0
            0.5 0.5 512.25 -> 1.0 total=1.0
            0.5 0.5 1650.0 -> 1.0 total=1.0
            0.5 512.25 nil -> 512.75 total=512.75
            0.5 512.25 -5.0 -> 512.75 total=512.75
            0.5 512.25 0.0 -> 512.75 total=512.75
            0.5 512.25 0.5 -> 512.75 total=512.75
            0.5 512.25 512.25 -> 512.75 total=512.75
            0.5 512.25 1650.0 -> 512.75 total=512.75
            0.5 1650.0 nil -> 1650.5 total=1650.5
            0.5 1650.0 -5.0 -> 1650.5 total=1650.5
            0.5 1650.0 0.0 -> 1650.5 total=1650.5
            0.5 1650.0 0.5 -> 1650.5 total=1650.5
            0.5 1650.0 512.25 -> 1650.5 total=1650.5
            0.5 1650.0 1650.0 -> 1650.5 total=1650.5
            512.25 nil nil -> nil total=nil
            512.25 nil -5.0 -> nil total=nil
            512.25 nil 0.0 -> nil total=nil
            512.25 nil 0.5 -> 0.5 total=nil
            512.25 nil 512.25 -> 512.25 total=nil
            512.25 nil 1650.0 -> 1650.0 total=nil
            512.25 -5.0 nil -> nil total=nil
            512.25 -5.0 -5.0 -> nil total=nil
            512.25 -5.0 0.0 -> nil total=nil
            512.25 -5.0 0.5 -> 0.5 total=nil
            512.25 -5.0 512.25 -> 512.25 total=nil
            512.25 -5.0 1650.0 -> 1650.0 total=nil
            512.25 0.0 nil -> nil total=nil
            512.25 0.0 -5.0 -> nil total=nil
            512.25 0.0 0.0 -> nil total=nil
            512.25 0.0 0.5 -> 0.5 total=nil
            512.25 0.0 512.25 -> 512.25 total=nil
            512.25 0.0 1650.0 -> 1650.0 total=nil
            512.25 0.5 nil -> 512.75 total=512.75
            512.25 0.5 -5.0 -> 512.75 total=512.75
            512.25 0.5 0.0 -> 512.75 total=512.75
            512.25 0.5 0.5 -> 512.75 total=512.75
            512.25 0.5 512.25 -> 512.75 total=512.75
            512.25 0.5 1650.0 -> 512.75 total=512.75
            512.25 512.25 nil -> 1024.5 total=1024.5
            512.25 512.25 -5.0 -> 1024.5 total=1024.5
            512.25 512.25 0.0 -> 1024.5 total=1024.5
            512.25 512.25 0.5 -> 1024.5 total=1024.5
            512.25 512.25 512.25 -> 1024.5 total=1024.5
            512.25 512.25 1650.0 -> 1024.5 total=1024.5
            512.25 1650.0 nil -> 2162.25 total=2162.25
            512.25 1650.0 -5.0 -> 2162.25 total=2162.25
            512.25 1650.0 0.0 -> 2162.25 total=2162.25
            512.25 1650.0 0.5 -> 2162.25 total=2162.25
            512.25 1650.0 512.25 -> 2162.25 total=2162.25
            512.25 1650.0 1650.0 -> 2162.25 total=2162.25
            1650.0 nil nil -> nil total=nil
            1650.0 nil -5.0 -> nil total=nil
            1650.0 nil 0.0 -> nil total=nil
            1650.0 nil 0.5 -> 0.5 total=nil
            1650.0 nil 512.25 -> 512.25 total=nil
            1650.0 nil 1650.0 -> 1650.0 total=nil
            1650.0 -5.0 nil -> nil total=nil
            1650.0 -5.0 -5.0 -> nil total=nil
            1650.0 -5.0 0.0 -> nil total=nil
            1650.0 -5.0 0.5 -> 0.5 total=nil
            1650.0 -5.0 512.25 -> 512.25 total=nil
            1650.0 -5.0 1650.0 -> 1650.0 total=nil
            1650.0 0.0 nil -> nil total=nil
            1650.0 0.0 -5.0 -> nil total=nil
            1650.0 0.0 0.0 -> nil total=nil
            1650.0 0.0 0.5 -> 0.5 total=nil
            1650.0 0.0 512.25 -> 512.25 total=nil
            1650.0 0.0 1650.0 -> 1650.0 total=nil
            1650.0 0.5 nil -> 1650.5 total=1650.5
            1650.0 0.5 -5.0 -> 1650.5 total=1650.5
            1650.0 0.5 0.0 -> 1650.5 total=1650.5
            1650.0 0.5 0.5 -> 1650.5 total=1650.5
            1650.0 0.5 512.25 -> 1650.5 total=1650.5
            1650.0 0.5 1650.0 -> 1650.5 total=1650.5
            1650.0 512.25 nil -> 2162.25 total=2162.25
            1650.0 512.25 -5.0 -> 2162.25 total=2162.25
            1650.0 512.25 0.0 -> 2162.25 total=2162.25
            1650.0 512.25 0.5 -> 2162.25 total=2162.25
            1650.0 512.25 512.25 -> 2162.25 total=2162.25
            1650.0 512.25 1650.0 -> 2162.25 total=2162.25
            1650.0 1650.0 nil -> 3300.0 total=3300.0
            1650.0 1650.0 -5.0 -> 3300.0 total=3300.0
            1650.0 1650.0 0.0 -> 3300.0 total=3300.0
            1650.0 1650.0 0.5 -> 3300.0 total=3300.0
            1650.0 1650.0 512.25 -> 3300.0 total=3300.0
            1650.0 1650.0 1650.0 -> 3300.0 total=3300.0
            day 2026-11-01 2250.5
            day 2026-11-02 2300.5
            day 2026-11-05 2100.0
        """.trimIndent()
    }
}
