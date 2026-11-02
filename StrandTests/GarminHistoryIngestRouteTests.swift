import XCTest
import Foundation
import GarminProtocol
import StrandImport
import WhoopStore
@testable import Strand

/// A Garmin-synced ACTIVITY file keeps its GPS route, so the workout detail can draw the map (#32).
/// Android twin: `GarminHistoryIngestRouteTest` (the row's `routePolyline`). The expected polyline is the
/// stdout of the standalone Swift `RouteMath.encode` oracle; the Kotlin test pins the same literal.
final class GarminHistoryIngestRouteTests: XCTestCase {

    private let thames = [RoutePoint(lat: 51.5033, lon: -0.1196), RoutePoint(lat: 51.5007, lon: -0.1246),
                          RoutePoint(lat: 51.49812, lon: -0.12761)]

    private func activity(route: [RoutePoint]) -> ActivityFile {
        ActivityFile(kind: .fit, start: Date(timeIntervalSince1970: 1_760_000_000),
                     end: Date(timeIntervalSince1970: 1_760_001_800), sport: "running",
                     gpsPointCount: route.count, route: route)
    }

    func testRouteEncodesThePolylineAndDistance() throws {
        let route = try XCTUnwrap(GarminHistoryIngest.workoutRoute(from: activity(route: thames)))
        XCTAssertEqual(route.polyline, "sfjyHnjVfOf^bOxQ")
        XCTAssertEqual(route.distanceM, 805.5154317536665, accuracy: 1e-6)
        XCTAssertNil(route.points, "a FIT route has no horizontal accuracy, so none is invented")
    }

    func testFewerThanTwoPointsIsNoRoute() {
        XCTAssertNil(GarminHistoryIngest.workoutRoute(from: activity(route: [])))
        XCTAssertNil(GarminHistoryIngest.workoutRoute(from: activity(route: [thames[0]])))
    }

    /// End to end: a watch ACTIVITY file goes through `ingest`, and the route is readable under the stored
    /// row's natural key, which is the lookup `WorkoutDetailView` makes.
    func testIngestedActivityStoresItsRouteUnderTheRowKey() async throws {
        let suite = "GarminHistoryIngestRouteTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        let startTs = 1_760_000_000, endTs = 1_760_001_800
        let bytes = [UInt8](RouteExporter.buildFit(route: thames, startTs: startTs, endTs: endTs, sport: "run",
                                                   avgHr: 150, maxHr: 172))
        let key = GarminSyncFileKey(fileIndex: 3, dataType: 128, subType: GarminFitFileType.activity.rawValue,
                                    fileNumber: 7, garminTimestamp: 1_000_000_000, size: UInt32(bytes.count))
        let file = GarminDownloadedFile(key: key, bytes: bytes, resumedFrom: nil, typeName: nil)
        let store = try await WhoopStore.inMemory()

        let stored = await GarminHistoryIngest.ingest(file, deviceId: "garmin-test", store: store,
                                                      routeDefaults: defaults, log: { _ in })
        XCTAssertTrue(stored)

        let rows = try await store.workouts(deviceId: "garmin-test", from: startTs - 60, to: endTs + 60, limit: 10)
        let row = try XCTUnwrap(rows.first)
        let route = try XCTUnwrap(RouteStore.load(startTs: row.startTs, sport: row.sport, from: defaults))
        let decoded = RouteMath.decode(route.polyline)
        XCTAssertEqual(decoded.count, thames.count)
        for (got, want) in zip(decoded, thames) {
            XCTAssertEqual(got.lat, want.lat, accuracy: 2e-5)
            XCTAssertEqual(got.lon, want.lon, accuracy: 2e-5)
        }
    }

    func testIngestedActivityWithoutGpsStoresNoRoute() async throws {
        let suite = "GarminHistoryIngestRouteTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        let bytes = [UInt8](RouteExporter.buildFit(route: [thames[0]], startTs: 1_760_000_000,
                                                   endTs: 1_760_001_800, sport: "run"))
        let key = GarminSyncFileKey(fileIndex: 4, dataType: 128, subType: GarminFitFileType.activity.rawValue,
                                    fileNumber: 8, garminTimestamp: 1_000_000_100, size: UInt32(bytes.count))
        let store = try await WhoopStore.inMemory()
        _ = await GarminHistoryIngest.ingest(GarminDownloadedFile(key: key, bytes: bytes, resumedFrom: nil, typeName: nil),
                                             deviceId: "garmin-test", store: store, routeDefaults: defaults, log: { _ in })
        XCTAssertTrue(RouteStore.loadMap(from: defaults).isEmpty)
    }
}
