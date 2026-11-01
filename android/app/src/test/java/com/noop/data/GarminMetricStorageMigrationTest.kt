package com.noop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Guards the additive v42 -> v43 Room migration (`deviceMetricSample` and `garminSyncedFile`), the
 * Android twin of the Swift WhoopStore `v49-garmin-metric-samples` GRDB migration, plus the repository
 * plumbing that writes watch metrics.
 *
 * The SQL is pinned here as text; `WhoopDatabaseUpgradeTest` proves it opens the previous schema into the
 * one Room expects, and `SchemaOracleTest` compares Room's export to the shared schema_oracle.json. The
 * idempotent re-ingest against a real database is `GarminMetricStorageRoomTest`.
 *
 * Swift twin: `GarminMetricStorageTests`.
 */
class GarminMetricStorageMigrationTest {

    @Test
    fun migration_isAdditive_onlyCreateTable() {
        val sql = WhoopDatabase.GARMIN_METRIC_STORAGE_MIGRATION_SQL
        assertEquals("two CREATE TABLE statements", 2, sql.size)
        for (s in sql) {
            val up = s.trimStart().uppercase()
            assertTrue("only CREATE TABLE allowed, got: $s", up.startsWith("CREATE TABLE"))
            for (banned in listOf("DROP ", "DELETE ", "UPDATE ", "INSERT ", "ALTER ")) {
                assertFalse("additive migration must not contain '$banned': $s", up.contains(banned))
            }
        }
    }

    @Test
    fun migration_createsExactTables() {
        // Column order == entity field order == the GRDB t.column order. The metric key is
        // (deviceId, kind, ts); the ledger key is the whole directory entry, ingestedAt outside it.
        assertEquals(
            listOf(
                "CREATE TABLE IF NOT EXISTS `deviceMetricSample` (`deviceId` TEXT NOT NULL, " +
                    "`ts` INTEGER NOT NULL, `kind` TEXT NOT NULL, `value` REAL NOT NULL, " +
                    "`source` TEXT NOT NULL, PRIMARY KEY(`deviceId`, `kind`, `ts`))",
                "CREATE TABLE IF NOT EXISTS `garminSyncedFile` (`deviceId` TEXT NOT NULL, " +
                    "`fileIndex` INTEGER NOT NULL, `dataType` INTEGER NOT NULL, `subType` INTEGER NOT NULL, " +
                    "`fileNumber` INTEGER NOT NULL, `ts` INTEGER NOT NULL, `size` INTEGER NOT NULL, " +
                    "`ingestedAt` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `fileIndex`, `dataType`, " +
                    "`subType`, `fileNumber`, `ts`, `size`))",
            ),
            WhoopDatabase.GARMIN_METRIC_STORAGE_MIGRATION_SQL,
        )
    }

    @Test
    fun migration_versionPair_is42to43() {
        assertEquals(42, WhoopDatabase.MIGRATION_42_43.startVersion)
        assertEquals(43, WhoopDatabase.MIGRATION_42_43.endVersion)
        assertEquals(43, WhoopDatabase.SCHEMA_VERSION)
    }

    /** The stored `kind` strings are shared with Swift: this is the list `GarminMetricStorageTests.
     *  testKindWireValuesArePinned` asserts, in the same order. */
    @Test
    fun kindWireValuesMatchSwift() {
        assertEquals(
            listOf(
                "stress", "body_battery", "respiration_bpm", "spo2_pct",
                "intensity_moderate_min", "intensity_vigorous_min", "active_kcal", "hrv_ms", "sleep_score",
            ),
            DeviceMetricKind.entries.map { it.wire },
        )
    }

    /** A batch carrying ONLY watch metrics is not empty; `insert` early-returns on empty and would bank
     *  nothing. */
    @Test
    fun aBatchOfOnlyMetricsIsNotEmpty() {
        val batch = StreamBatch(deviceMetric = listOf(DeviceMetricRow(1L, DeviceMetricKind.STRESS, 20.0, "x")))
        assertFalse(batch.isEmpty)
    }

    /** The repository maps rows onto entities under the batch's deviceId and drops non-finite values,
     *  which would otherwise fail NOT NULL and roll back the whole batch. */
    @Test
    fun repositoryUpsertsFiniteMetricsOnly() = runBlocking {
        var captured: List<DeviceMetricSampleEntity>? = null
        val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "upsertDeviceMetric" -> {
                    @Suppress("UNCHECKED_CAST")
                    captured = args[0] as List<DeviceMetricSampleEntity>
                    Unit
                }
                else -> throw UnsupportedOperationException("metric-only insert must not call ${method.name}")
            }
        } as WhoopDao

        WhoopRepository(dao).insert(
            StreamBatch(deviceMetric = listOf(
                DeviceMetricRow(1_760_000_000L, DeviceMetricKind.BODY_BATTERY, 77.0, "fit.stress_level"),
                DeviceMetricRow(1_760_000_000L, DeviceMetricKind.SPO2_PCT, Double.NaN, "fit.spo2_data"),
                DeviceMetricRow(1_760_000_000L, DeviceMetricKind.RESPIRATION_BPM, Double.POSITIVE_INFINITY, "r"),
            )),
            deviceId = "garmin-1",
        )

        assertEquals(
            listOf(DeviceMetricSampleEntity("garmin-1", 1_760_000_000L, "body_battery", 77.0, "fit.stress_level")),
            captured,
        )
    }
}
