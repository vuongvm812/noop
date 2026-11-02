package com.noop.data

import com.noop.analytics.RouteMath
import com.noop.ingest.ActivityFileImporter
import com.noop.ingest.RouteExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A Garmin-synced ACTIVITY file keeps its GPS route as the row's `routePolyline` (#32). Swift twin:
 * `GarminHistoryIngestRouteTests` (the route in `RouteStore`). The expected polyline literal is the stdout
 * of the standalone Swift `RouteMath.encode` oracle over the same points, so both platforms store one string.
 */
class GarminHistoryIngestRouteTest {

    private val thames = listOf(
        ActivityFileImporter.RoutePoint(51.5033, -0.1196),
        ActivityFileImporter.RoutePoint(51.5007, -0.1246),
        ActivityFileImporter.RoutePoint(51.49812, -0.12761),
    )

    private fun activity(route: List<ActivityFileImporter.RoutePoint>) = ActivityFileImporter.Activity(
        kind = ActivityFileImporter.Kind.FIT,
        startTs = 1_760_000_000L,
        endTs = 1_760_001_800L,
        sport = "running",
        distanceM = null,
        energyKcal = null,
        steps = null,
        avgHr = null,
        maxHr = null,
        ascentM = null,
        gpsPointCount = route.size,
        hrSampleCount = 0,
        route = route,
    )

    @Test fun rowCarriesTheSwiftOraclePolyline() {
        val row = GarminHistoryIngest.workoutRow(activity(thames), "garmin-test")
        assertEquals("sfjyHnjVfOf^bOxQ", row.routePolyline)
        assertEquals("garmin-test", row.deviceId)
        assertEquals("garmin-test", row.source)
        assertNull(row.strain)
    }

    @Test fun fewerThanTwoPointsIsNoRoute() {
        assertNull(GarminHistoryIngest.workoutRow(activity(emptyList()), "garmin-test").routePolyline)
        assertNull(GarminHistoryIngest.workoutRow(activity(thames.take(1)), "garmin-test").routePolyline)
    }

    /** A real FIT file through the same parse the watch sync uses keeps a drawable route. */
    @Test fun fitActivityFileKeepsItsRoute() {
        val bytes = RouteExport.buildFit(
            thames.map { RouteExport.Point(it.lat, it.lon) }, 1_760_000_000L, 1_760_001_800L, "run",
            avgHr = 150, maxHr = 172,
        )
        val parsed = ActivityFileImporter.parse(bytes, "garmin-activity.fit").activity
        assertNotNull(parsed)
        val polyline = GarminHistoryIngest.workoutRow(parsed!!, "garmin-test").routePolyline
        assertNotNull(polyline)
        val decoded = RouteMath.decode(polyline!!)
        assertEquals(thames.size, decoded.size)
        thames.zip(decoded).forEach { (want, got) ->
            assertEquals(want.lat, got.lat, 2e-5)
            assertEquals(want.lon, got.lon, 2e-5)
        }
    }
}
