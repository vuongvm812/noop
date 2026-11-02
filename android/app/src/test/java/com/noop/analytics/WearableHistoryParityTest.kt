package com.noop.analytics

import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import com.noop.data.WhoopRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Android half of the shared wearable-history oracle (`Tools/parity_cases/wearable_history.json`, #31).
 * The expected values were produced by the Swift implementation (`WearableHistoryTests`,
 * `SleepGapFillTests`, `WearableHistoryFoldTests` read the same file), so the id resolver, the baseline
 * seed rule, the daily fold and the sleep gap-fill cannot drift between platforms.
 */
class WearableHistoryParityTest {
    private fun fixture(): JSONObject {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val file = listOf(
            File(cwd, "../Tools/parity_cases/wearable_history.json"),
            File(cwd, "Tools/parity_cases/wearable_history.json"),
            File(cwd, "../../Tools/parity_cases/wearable_history.json"),
        ).firstOrNull(File::isFile)
        assertNotNull("wearable_history.json not found from $cwd; parity must fail closed", file)
        return JSONObject(file!!.readText())
    }

    private fun strings(a: JSONArray): List<String> = List(a.length()) { a.getString(it) }
    private fun objects(a: JSONArray): List<JSONObject> = List(a.length()) { a.getJSONObject(it) }
    private fun JSONObject.doubleOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else getDouble(k)
    private fun JSONObject.intOrNull(k: String): Int? = if (isNull(k) || !has(k)) null else getInt(k)

    private fun nights(a: JSONArray): List<Pair<String, Double?>> = List(a.length()) {
        val n = a.getJSONArray(it)
        n.getString(0) to (if (n.isNull(1)) null else n.getDouble(1))
    }

    @Test
    fun idResolverMatchesSharedOracle() {
        for (c in objects(fixture().getJSONArray("idCases"))) {
            val active = c.getString("activeDeviceId")
            assertEquals(active, strings(c.getJSONArray("readOrder")), WearableHistory.readOrder(active))
            assertEquals(active, strings(c.getJSONArray("baselineSeedIds")), WearableHistory.baselineSeedIds(active))
        }
    }

    @Test
    fun seedRuleMatchesSharedOracle() {
        for (c in objects(fixture().getJSONArray("seedCases"))) {
            val h = ChargeBaselines.historyWithSeed(
                nights(c.getJSONArray("imported")), nights(c.getJSONArray("own")), c.getString("anchorDay"),
                Baselines.hrvCfg, 0.0, seed = nights(c.getJSONArray("seed")),
            )
            val e = c.getJSONObject("expected")
            val name = c.getString("name")
            assertEquals(name, strings(e.getJSONArray("dayKeys")), h.dayKeys)
            assertEquals(name, strings(e.getJSONArray("values")), h.values.map { it?.toString() ?: "nil" })
            assertEquals(name, e.getBoolean("seededByImport"), h.seededByImport)
            assertEquals(name, e.getInt("importedNights"), h.importedNights)
            assertEquals(name, e.getInt("ownValidNights"), h.ownValidNights)
        }
    }

    private fun rows(a: JSONArray): List<DailyMetric> = objects(a).map {
        DailyMetric(
            deviceId = "x", day = it.getString("day"),
            totalSleepMin = it.doubleOrNull("totalSleepMin"), deepMin = it.doubleOrNull("deepMin"),
            avgHrv = it.doubleOrNull("avgHrv"), restingHr = it.intOrNull("restingHr"), steps = it.intOrNull("steps"),
        )
    }

    private fun encode(rows: List<DailyMetric>): List<String> = rows.map {
        fun f(v: Double?) = v?.toString() ?: "nil"
        fun i(v: Int?) = v?.toString() ?: "nil"
        "${it.day} sleep=${f(it.totalSleepMin)} deep=${f(it.deepMin)} hrv=${f(it.avgHrv)} " +
            "rhr=${i(it.restingHr)} steps=${i(it.steps)}"
    }

    @Test
    fun dailyFoldMatchesSharedOracle() {
        for (c in objects(fixture().getJSONArray("foldCases"))) {
            val out = WhoopRepository.foldWearableHistory(rows(c.getJSONArray("merged")), rows(c.getJSONArray("history")))
            assertEquals(c.getString("name"), strings(c.getJSONArray("expected")), encode(out))
        }
    }

    private fun sessions(a: JSONArray): List<SleepSession> = objects(a).map {
        SleepSession(deviceId = it.getString("deviceId"), startTs = it.getLong("startTs"), endTs = it.getLong("endTs"))
    }

    @Test
    fun sleepGapFillMatchesSharedOracle() {
        for (c in objects(fixture().getJSONArray("sleepGapCases"))) {
            val out = WhoopRepository.fillSleepGaps(
                sessions(c.getJSONArray("base")), sessions(c.getJSONArray("history")),
            ) { (it.endTs / 86_400L).toString() }
            assertEquals(
                c.getString("name"), strings(c.getJSONArray("expected")),
                out.map { "${it.deviceId}@${it.startTs}-${it.endTs}" },
            )
        }
    }

    @Test
    fun historyIdsStayOutOfTheImportedUnion() {
        val imported = WhoopRepository.importedSourceIdsFor("garmin-abc")
        assertEquals(listOf("garmin-abc", "my-whoop"), imported)
        assertFalse(imported.any { it in WearableHistory.sourceIds })
        val stress = WhoopRepository.sourceCandidates("stress", "my-whoop", "garmin-abc").map { it.source }
        assertFalse(stress.contains("garmin-import"))
        val steps = WhoopRepository.sourceCandidates("steps", "my-whoop", "garmin-abc").map { it.source }
        assertEquals(listOf("garmin-import", "oura-import", "oura-api", "fitbit-import"), steps.takeLast(4))
    }
}
