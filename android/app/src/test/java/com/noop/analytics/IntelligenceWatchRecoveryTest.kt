package com.noop.analytics

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin twin of the macOS IntelligenceWatchRecoveryTests: the source-only Charge fold (#823). An
 * import-only user (Health Connect / Oura-Fitbit-Garmin / Apple Health) has DAILY aggregates (HRV + resting
 * HR, recovery null) but no raw stream, so the raw-HR scoring loop never scores their days.
 * [IntelligenceEngine.watchRecoveries] scores each day through the cross-lane [WatchRecovery.score] against
 * its trailing 60-day Charge baselines (#53) and writes a recovery + confidence per day, staying null/CALIBRATING until there is
 * enough baseline (never a fabricated number). Pure (no Room) , the SAME logic analyzeRecent ships per day.
 */
class IntelligenceWatchRecoveryTest {

    private fun importRow(day: String, hrv: Double?, rhr: Int?) = DailyMetric(
        deviceId = "oura-import", day = day, avgHrv = hrv, restingHr = rhr, recovery = null,
    )

    @Test
    fun tenImportDaysGiveLatestDayANonCalibratingRecovery() {
        val rows = (1..10).map { importRow("2026-06-%02d".format(it), 50.0, 50) }
        val scored = IntelligenceEngine.watchRecoveries(rows)

        assertEquals(10, scored.size)
        val latest = scored.last()
        assertEquals("2026-06-10", latest.day)
        assertNotNull("an import-only day with enough history must score a Charge (#823)", latest.recovery)
        assertFalse(latest.confidence == ScoreConfidence.CALIBRATING)
    }

    @Test
    fun earlyDaysStayCalibrating() {
        val rows = (1..10).map { importRow("2026-06-%02d".format(it), 50.0, 50) }
        val scored = IntelligenceEngine.watchRecoveries(rows)
        assertNull(scored[0].recovery)
        assertEquals(ScoreConfidence.CALIBRATING, scored[0].confidence)
        assertNull(scored[1].recovery)
    }

    @Test
    fun strapScoredDayIsSkipped() {
        val rows = (1..10).map { importRow("2026-06-%02d".format(it), 50.0, 50) }
        val scored = IntelligenceEngine.watchRecoveries(rows, strapRecoveryDays = setOf("2026-06-10"))
        assertEquals(9, scored.size)
        assertTrue(scored.none { it.day == "2026-06-10" })
    }

    @Test
    fun sparseImportStaysCalibratingNeverFabricated() {
        val rows = (1..3).map { importRow("2026-06-%02d".format(it), 50.0, 50) }
        val scored = IntelligenceEngine.watchRecoveries(rows)
        assertTrue(scored.all { it.recovery == null })
        assertTrue(scored.all { it.confidence == ScoreConfidence.CALIBRATING })
    }

    @Test
    fun unorderedInputIsSortedBeforeFolding() {
        val ordered = (1..10).map { importRow("2026-06-%02d".format(it), 50.0, 50) }
        val scored = IntelligenceEngine.watchRecoveries(ordered.shuffled())
        assertEquals(ordered.map { it.day }, scored.map { it.day })
        assertNotNull(scored.last().recovery)
    }

    /** #53: the engine hands each day only the rows inside its 60-day window, an optimisation that must not
     *  change a single result: every day equals [WatchRecovery.score] over the whole history. The history
     *  spans well over the window and varies, so the slicing is exercised. */
    @Test
    fun windowSlicingMatchesScoringOverTheWholeHistory() {
        val start = Baselines.isoEpochDay("2026-01-01")!!
        val rows = (0 until 150).filter { it % 9 != 4 }.map {
            importRow(Baselines.isoDayFromEpochDay(start + it), 40.0 + (it * 7) % 13, 50 + (it * 5) % 7)
        }
        val scored = IntelligenceEngine.watchRecoveries(rows)
        assertEquals(rows.size, scored.size)
        for (w in scored) {
            val whole = WatchRecovery.score(w.day, rows)
            assertEquals(w.day, whole.recovery, w.recovery)
            assertEquals(w.day, whole.confidence, w.confidence)
        }
        assertTrue(scored.count { it.recovery != null } > 100)
    }
}
