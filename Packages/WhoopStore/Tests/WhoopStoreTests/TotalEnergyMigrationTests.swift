import XCTest
import GRDB
@testable import WhoopStore

/// v50 migration (#51): `dailyMetric.activeKcalEst` repaired to whole-day TOTAL energy, and the additive
/// `garminActivityPeak` table. Kotlin twin: `TotalEnergyMigrationTest`, over the same SQL.
final class TotalEnergyMigrationTests: XCTestCase {

    private func migratedFromV49(_ seed: (Database) throws -> Void) throws -> DatabaseQueue {
        let dbQueue = try DatabaseQueue()
        let migrator = WhoopStore.makeMigrator()
        try migrator.migrate(dbQueue, upTo: "v49-garmin-metric-samples")
        try dbQueue.write(seed)
        try migrator.migrate(dbQueue)
        return dbQueue
    }

    private func kcal(_ db: Database, _ device: String, _ day: String) throws -> Double? {
        try Double.fetchOne(db, sql: "SELECT activeKcalEst FROM dailyMetric WHERE deviceId = ? AND day = ?",
                            arguments: [device, day])
    }

    func testRepairsActiveCaloriesToTheImportersTotal() throws {
        let q = try migratedFromV49 { db in
            func daily(_ device: String, _ day: String, _ kcal: Double?) throws {
                try db.execute(sql: "INSERT INTO dailyMetric (deviceId, day, activeKcalEst) VALUES (?, ?, ?)",
                               arguments: [device, day, kcal])
            }
            func point(_ device: String, _ day: String, _ key: String, _ value: Double) throws {
                try db.execute(sql: "INSERT INTO metricSeries (deviceId, day, key, value) VALUES (?, ?, ?, ?)",
                               arguments: [device, day, key, value])
            }
            // A Garmin day: the watch's active calories in the column, its total in the series.
            try daily("garmin-1", "2026-11-01", 512); try point("garmin-1", "2026-11-01", "energy_kcal", 512)
            try point("garmin-1", "2026-11-01", "total_kcal", 2_380)
            // An import that stored no value but has a total: the total fills it.
            try daily("oura-import", "2026-11-01", nil); try point("oura-import", "2026-11-01", "total_kcal", 2_150)
            // An active value with no total anywhere: cleared, so NOOP's own total can fill the day.
            try daily("xiaomi-band", "2026-11-01", 340); try point("xiaomi-band", "2026-11-01", "energy_kcal", 340)
            // The Oura cloud sync stored active energy with no total beside it: cleared.
            try daily("oura-api", "2026-11-01", 455)
            // NOOP's own estimate (no series points): untouched. WHOOP's energy_kcal is a TOTAL, so a row equal
            // to it is not an active-energy source and keeps both.
            try daily("my-whoop-noop", "2026-11-01", 2_410)
            try daily("whoop-import", "2026-11-01", 2_550); try point("whoop-import", "2026-11-01", "energy_kcal", 2_550)
            // A total for another day never crosses over.
            try daily("garmin-1", "2026-11-02", 480); try point("garmin-1", "2026-11-03", "total_kcal", 2_000)
        }
        try q.read { db in
            XCTAssertEqual(try kcal(db, "garmin-1", "2026-11-01"), 2_380)
            XCTAssertEqual(try kcal(db, "oura-import", "2026-11-01"), 2_150)
            XCTAssertNil(try kcal(db, "xiaomi-band", "2026-11-01"))
            XCTAssertNil(try kcal(db, "oura-api", "2026-11-01"))
            XCTAssertEqual(try kcal(db, "my-whoop-noop", "2026-11-01"), 2_410)
            XCTAssertEqual(try kcal(db, "whoop-import", "2026-11-01"), 2_550)
            XCTAssertEqual(try kcal(db, "garmin-1", "2026-11-02"), 480)
            // The active-energy sources' energy_kcal points move to active_kcal; WHOOP's total stays.
            let keys = try Row.fetchAll(db, sql: "SELECT deviceId, key FROM metricSeries WHERE day = '2026-11-01' "
                                        + "AND key IN ('energy_kcal', 'active_kcal') ORDER BY deviceId")
                .map { "\($0["deviceId"] as String) \($0["key"] as String)" }
            XCTAssertEqual(keys, ["garmin-1 active_kcal", "whoop-import energy_kcal", "xiaomi-band active_kcal"])
            XCTAssertEqual(try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM garminActivityPeak"), 0)
        }
    }

    func testMigrationStatementsArePinned() {
        // The Kotlin twin (`TotalEnergyMigrationTest.migrationStatementsArePinned`) asserts the same text.
        XCTAssertEqual(WhoopStore.totalEnergyMigrationSQL, [
            "CREATE TABLE IF NOT EXISTS garminActivityPeak (deviceId TEXT NOT NULL, day TEXT NOT NULL, "
                + "activityType INTEGER NOT NULL, steps INTEGER, distanceM REAL, totalKcal REAL, activeKcal REAL, "
                + "PRIMARY KEY(deviceId, day, activityType))",
            "UPDATE dailyMetric SET activeKcalEst = (SELECT m.value FROM metricSeries m "
                + "WHERE m.deviceId = dailyMetric.deviceId AND m.day = dailyMetric.day AND m.key = 'total_kcal') "
                + "WHERE EXISTS (SELECT 1 FROM metricSeries m WHERE m.deviceId = dailyMetric.deviceId "
                + "AND m.day = dailyMetric.day AND m.key = 'total_kcal')",
            "UPDATE dailyMetric SET activeKcalEst = NULL WHERE activeKcalEst IS NOT NULL "
                + "AND (deviceId IN ('oura-api', 'oura-import', 'fitbit-import', 'xiaomi-band') OR deviceId LIKE 'garmin-%') "
                + "AND EXISTS (SELECT 1 FROM metricSeries m WHERE m.deviceId = dailyMetric.deviceId "
                + "AND m.day = dailyMetric.day AND m.key = 'energy_kcal' AND m.value = dailyMetric.activeKcalEst)",
            "UPDATE dailyMetric SET activeKcalEst = NULL WHERE deviceId = 'oura-api'",
            "UPDATE metricSeries SET key = 'active_kcal' WHERE key = 'energy_kcal' "
                + "AND (deviceId IN ('oura-api', 'oura-import', 'fitbit-import', 'xiaomi-band') OR deviceId LIKE 'garmin-%')",
        ])
    }
}
