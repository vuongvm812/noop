package com.noop.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The synthetic Garmin export under Tools/parity-fixtures/garmin-export-json, parsed by the JSON-field
 * importer, must equal the shared golden. The Swift twin (GarminExportJsonTests) reads the SAME files and the
 * SAME golden, which the Swift oracle produced, so the two platforms cannot drift. Synthetic numbers only.
 */
class GarminExportJsonTest {

    private val root: File = listOf("../../Tools/parity-fixtures/garmin-export-json", "Tools/parity-fixtures/garmin-export-json")
        .map { File(it) }.first { it.isDirectory }

    private fun fixture(): Pair<Map<String, ByteArray>, List<String>> {
        val files = LinkedHashMap<String, ByteArray>()
        root.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach {
            files[it.relativeTo(root).path.replace(File.separatorChar, '/').lowercase()] = it.readBytes()
        }
        return files to File(root, "skipped.txt").readLines().filter { it.isNotBlank() }
    }

    /** The result as canonical text, one record per line: the shared-golden format (Swift twin: GarminExportJsonTests.dump). */
    private fun dump(r: GarminExportJson.Result): String {
        val out = ArrayList<String>()
        for (p in r.points) out.add("P|${p.day}|${p.key}|${GarminExportJson.num(p.value)}")
        val o = { v: Double? -> v?.let { GarminExportJson.num(it) } ?: "-" }
        val i = { v: Int? -> v?.toString() ?: "-" }
        for (a in r.activities) out.add(
            "A|${a.activityId}|${a.startTs}|${a.sport}|${GarminExportJson.num(a.durationS)}|${o(a.distanceM)}|${o(a.energyKcal)}|${i(a.avgHr)}|${i(a.maxHr)}|${i(a.steps)}|" +
                "${a.zonePercents?.joinToString(",") { GarminExportJson.num(it) } ?: "-"}|${a.powerZonePercents?.joinToString(",") { GarminExportJson.num(it) } ?: "-"}|" +
                "${o(a.avgPowerW)}|${o(a.maxPowerW)}|${o(a.normPowerW)}|${o(a.cadenceSpm)}|${o(a.elevationGainM)}|${o(a.elevationLossM)}|" +
                "${o(a.minElevationM)}|${o(a.maxElevationM)}|${o(a.aerobicTe)}|${o(a.anaerobicTe)}|${i(a.rpe)}|${i(a.feel)}|${i(a.bodyBatteryChange)}|" +
                "${o(a.vo2max)}|${i(a.strokes)}|${o(a.swolf)}|${o(a.strideM)}|${o(a.groundContactMs)}|${o(a.verticalOscillationCm)}|${o(a.sweatLossMl)}",
        )
        out.add("S|${i(r.settings.maxHr)}|${i(r.settings.restingHr)}|${i(r.settings.ftpW)}|${r.settings.zoneFloors.joinToString(",")}")
        for ((c, n) in r.imported) out.add("I|$c|$n")
        for ((c, n) in r.skipped) out.add("K|$c|$n")
        return out.joinToString("\n")
    }


    @Test
    fun syntheticExportMatchesSharedGolden() {
        val (files, skipped) = fixture()
        val dump = dump(GarminExportJson.parse(files, skipped))
        assertEquals(File(root, "golden.txt").readText().trim('\n'), dump)
    }

    @Test
    fun hostileNumbersAreDroppedNotStored() {
        val bad = """[{"calendarDate":"2026-01-01","bmrKilocalories":1e308,"maxHeartRate":9999,"respiration":{"avgWakingRespirationValue":-3}}]"""
        val r = GarminExportJson.parse(mapOf("di_connect/di-connect-aggregator/udsfile_x.json" to bad.toByteArray()))
        assertFalse(r.points.any { it.key == "max_hr" || it.key == "resp_rate" })
    }

    @Test
    fun zipWalkKeepsHealthFilesByFullPathAndReportsTheRest() {
        val bytes = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                for ((name, body) in listOf(
                    "DI_CONNECT/DI-Connect-Wellness/1_fitnessAgeData.json" to "[]",   // no wellness hint in the basename
                    "DI_CONNECT/DI-Connect-User/user_profile.json" to "{\"a\":1}",
                    "IT_CONSENT_HISTORY/consentHistory.json" to "{\"a\":1}",
                    "DI_CONNECT/DI-Connect-Fitness/u_PrimaryTrainingBackup_Part1.zip" to "PK",
                )) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
            }
        }.toByteArray()
        val skipped = ArrayList<String>()
        WearableExportImporter.skipSink = skipped
        val (kept, _) = try { WearableExportImporter.collectZipEntries(bytes.inputStream()) } finally { WearableExportImporter.skipSink = null }
        assertEquals(setOf("di_connect/di-connect-wellness/1_fitnessagedata.json"), kept.keys)
        assertEquals(
            listOf("di_connect/di-connect-user/user_profile.json", "it_consent_history/consenthistory.json",
                "di_connect/di-connect-fitness/u_primarytrainingbackup_part1.zip"),
            skipped,
        )
        assertEquals(GarminExportJson.Skip.PRIVATE_FILE, GarminExportJson.skipReason(skipped.last()))
        assertEquals(GarminExportJson.Skip.ACCOUNT, GarminExportJson.skipReason(skipped.first()))
    }

    @Test
    fun enrichmentIsIdempotentAndZonesJsonMatchesTheFlatShape() {
        assertEquals("""{"z1":3.16,"z2":31.58,"z3":47.37,"z4":15.79,"z5":2.11}""",
            GarminExportJson.zonesJson(listOf(3.16, 31.58, 47.37, 15.79, 2.11)))
        assertEquals(null, GarminExportJson.zonesJson(null))
        val once = WearableExportImporter.enrichedNotes("Imported RUNNING", "RPE 5 · feel 75/100")
        assertEquals("Imported RUNNING · RPE 5 · feel 75/100", once)
        assertEquals(once, WearableExportImporter.enrichedNotes(once, "RPE 5 · feel 75/100"))
        assertTrue(GarminExportJson.slug("Most Steps in a Day") == "most_steps_in_a_day")
    }

    @Test
    fun repeatedEntriesBadDatesAndHostileFieldsAreHandled() {
        val hyd = """[{"calendarDate":"2026-01-01","hydrationSource":"MANUAL","valueInML":500.0,"uuid":{"uuid":"a"}}]"""
        val sleep = """[{"calendarDate":"2026-01-01","sleepStartTimestampGMT":"2026-01-01T01:00:00.0","remSleepSeconds":3600}]"""
        val act = """[{"summarizedActivitiesExport":[{"activityId":7,"activityType":"running","startTimeGmt":1.7e12,"duration":600000,"elevationGain":1e30,"hrTimeInZone_1":1}]}]"""
        val bad = """[{"calendarDate":20260101,"bmrKilocalories":5},{"calendarDate":"2026-13-45","bmrKilocalories":5}]"""
        val health = """[{"calendarDate":"2026-01-01","metrics":[{"type":"SKIN_TEMP_C","value":-0.4}]}]"""
        val hz = """[{"sport":"DEFAULT","maxHeartRateUsed":190,"zone1Floor":1e30,"zone2Floor":1,"zone3Floor":1,"zone4Floor":1,"zone5Floor":1}]"""
        val files = mapOf(
            "a/hydrationlogfile_1.json" to hyd, "b/hydrationlogfile_2.json" to hyd,
            "a/1_sleepdata.json" to sleep, "b/2_sleepdata.json" to sleep,
            "a/x_summarizedactivities.json" to act, "b/y_summarizedactivities.json" to act,
            "a/udsfile_bad.json" to bad, "a/h_healthstatusdata.json" to health, "a/1_heartratezones.json" to hz,
        ).mapValues { it.value.toByteArray() }
        val r = GarminExportJson.parse(files)
        assertEquals(500.0, r.points.first { it.key == "hydration_ml" }.value, 0.0)
        assertEquals(1, r.activities.size)
        assertEquals(null, r.activities[0].elevationGainM)
        assertEquals(1, r.imported.first { it.first == GarminExportJson.Category.SLEEP }.second)
        assertFalse(r.points.any { it.key == "nap_min" || it.day == "2026-13-45" || it.day == "20260101" })
        assertEquals(-0.4, r.points.first { it.key == "skin_temp_dev_c" }.value, 0.0)
        assertTrue(r.settings.zoneFloors.isEmpty())
    }
}
