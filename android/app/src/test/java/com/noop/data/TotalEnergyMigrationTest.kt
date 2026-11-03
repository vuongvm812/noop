package com.noop.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * v43 -> v44 (#51): `dailyMetric.activeKcalEst` repaired to whole-day TOTAL energy, and the additive
 * `garminActivityPeak` table. The statements run here against plain SQLite, over the columns they read;
 * `WhoopDatabaseUpgradeTest` opens the previous schema through the chain and `SchemaOracleTest` pins the
 * table shape. Swift twin: `TotalEnergyMigrationTests`, the same seed and expectations.
 */
class TotalEnergyMigrationTest {
    private lateinit var db: Connection

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("CREATE TABLE dailyMetric (deviceId TEXT NOT NULL, day TEXT NOT NULL, activeKcalEst REAL, PRIMARY KEY(deviceId, day))")
        exec("CREATE TABLE metricSeries (deviceId TEXT NOT NULL, day TEXT NOT NULL, `key` TEXT NOT NULL, value REAL NOT NULL, " +
            "PRIMARY KEY(deviceId, day, `key`))")
    }

    @After fun close() = db.close()

    private fun exec(sql: String) { db.createStatement().use { it.executeUpdate(sql) } }

    private fun daily(device: String, day: String, kcal: Double?) =
        exec("INSERT INTO dailyMetric VALUES ('$device', '$day', ${kcal ?: "NULL"})")

    private fun point(device: String, day: String, key: String, value: Double) =
        exec("INSERT INTO metricSeries VALUES ('$device', '$day', '$key', $value)")

    private fun kcal(device: String, day: String): Double? = db.createStatement().use { s ->
        s.executeQuery("SELECT activeKcalEst FROM dailyMetric WHERE deviceId = '$device' AND day = '$day'").use { r ->
            r.next(); r.getDouble(1).takeUnless { r.wasNull() }
        }
    }

    @Test
    fun repairsActiveCaloriesToTheImportersTotal() {
        daily("garmin-1", "2026-11-01", 512.0); point("garmin-1", "2026-11-01", "energy_kcal", 512.0)
        point("garmin-1", "2026-11-01", "total_kcal", 2_380.0)
        daily("oura-1", "2026-11-01", null); point("oura-1", "2026-11-01", "total_kcal", 2_150.0)
        daily("xiaomi-1", "2026-11-01", 340.0); point("xiaomi-1", "2026-11-01", "energy_kcal", 340.0)
        daily("my-whoop-noop", "2026-11-01", 2_410.0)
        daily("whoop-import", "2026-11-01", 2_600.0); point("whoop-import", "2026-11-01", "energy_kcal", 2_550.0)
        daily("garmin-1", "2026-11-02", 480.0); point("garmin-1", "2026-11-03", "total_kcal", 2_000.0)

        WhoopDatabase.TOTAL_ENERGY_MIGRATION_SQL.forEach(::exec)

        assertEquals(2_380.0, kcal("garmin-1", "2026-11-01"))
        assertEquals(2_150.0, kcal("oura-1", "2026-11-01"))
        assertNull(kcal("xiaomi-1", "2026-11-01"))
        assertEquals(2_410.0, kcal("my-whoop-noop", "2026-11-01"))
        assertEquals(2_600.0, kcal("whoop-import", "2026-11-01"))
        assertEquals(480.0, kcal("garmin-1", "2026-11-02"))
    }

    /** The same text as the Swift `WhoopStore.totalEnergyMigrationSQL`, so the platforms agree. */
    @Test
    fun migrationStatementsArePinned() {
        assertEquals(
            listOf(
                "CREATE TABLE IF NOT EXISTS garminActivityPeak (deviceId TEXT NOT NULL, day TEXT NOT NULL, " +
                    "activityType INTEGER NOT NULL, steps INTEGER, distanceM REAL, totalKcal REAL, activeKcal REAL, " +
                    "PRIMARY KEY(deviceId, day, activityType))",
                "UPDATE dailyMetric SET activeKcalEst = (SELECT m.value FROM metricSeries m " +
                    "WHERE m.deviceId = dailyMetric.deviceId AND m.day = dailyMetric.day AND m.key = 'total_kcal') " +
                    "WHERE EXISTS (SELECT 1 FROM metricSeries m WHERE m.deviceId = dailyMetric.deviceId " +
                    "AND m.day = dailyMetric.day AND m.key = 'total_kcal')",
                "UPDATE dailyMetric SET activeKcalEst = NULL WHERE activeKcalEst IS NOT NULL " +
                    "AND EXISTS (SELECT 1 FROM metricSeries m WHERE m.deviceId = dailyMetric.deviceId " +
                    "AND m.day = dailyMetric.day AND m.key = 'energy_kcal' AND m.value = dailyMetric.activeKcalEst)",
            ),
            WhoopDatabase.TOTAL_ENERGY_MIGRATION_SQL,
        )
    }

    @Test
    fun migrationVersionPairIs43to44() {
        assertEquals(43, WhoopDatabase.MIGRATION_43_44.startVersion)
        assertEquals(44, WhoopDatabase.MIGRATION_43_44.endVersion)
        assertEquals(44, WhoopDatabase.SCHEMA_VERSION)
    }
}
