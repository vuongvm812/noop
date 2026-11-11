package com.noop.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.ingest.ActivityFileImporter
import com.noop.ingest.GarminWatchIngest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The workout detail's power / cadence / altitude read against a real Room database: mean per bucket over the
 * workout window, empty when the watch stored no rows. Swift twin: `WorkoutMetricBucketsTests`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WorkoutMetricBucketsRoomTest {

    private val db: WhoopDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        WhoopDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val repo = WhoopRepository(db)
    private val start = 1_760_000_010L   // a multiple of 15 s, the bucket width of a 1800 s window

    @After
    fun close() = db.close()

    private fun row(offset: Long, kind: DeviceMetricKind, value: Double) =
        DeviceMetricRow(start + offset, kind, value, "fit-record")

    @Test
    fun bucketWidthFollowsTheHrRule() {
        assertEquals(15L, WhoopRepository.workoutBucketSeconds(600))
        assertEquals(15L, WhoopRepository.workoutBucketSeconds(1800))
        assertEquals(50L, WhoopRepository.workoutBucketSeconds(6000))
        assertEquals(300L, WhoopRepository.workoutBucketSeconds(90_000))
    }

    @Test
    fun bucketsAreMeansInsideTheWindowOldestFirst() = runBlocking {
        repo.insert(StreamBatch(deviceMetric = listOf(
            row(0, DeviceMetricKind.POWER_W, 100.0), row(5, DeviceMetricKind.POWER_W, 200.0),
            row(15, DeviceMetricKind.POWER_W, 300.0),
            row(1801, DeviceMetricKind.POWER_W, 999.0),
            row(0, DeviceMetricKind.CADENCE, 80.0),
        )), deviceId = "garmin-1")
        val buckets = repo.workoutMetricBuckets(listOf("garmin-1"), DeviceMetricKind.POWER_W, start, start + 1800)
        assertEquals(listOf(MetricBucket(start, 150.0), MetricBucket(start + 15, 300.0)), buckets)
    }

    @Test
    fun earlierDeviceIdWinsABucketStart() = runBlocking {
        repo.insert(StreamBatch(deviceMetric = listOf(row(0, DeviceMetricKind.ALTITUDE_M, 100.0))), deviceId = "a")
        repo.insert(StreamBatch(deviceMetric = listOf(
            row(0, DeviceMetricKind.ALTITUDE_M, 5.0), row(15, DeviceMetricKind.ALTITUDE_M, 300.0),
        )), deviceId = "b")
        val buckets = repo.workoutMetricBuckets(listOf("a", "b"), DeviceMetricKind.ALTITUDE_M, start, start + 1800)
        assertEquals(listOf(MetricBucket(start, 100.0), MetricBucket(start + 15, 300.0)), buckets)
    }

    @Test
    fun noRowsIsEmpty() = runBlocking {
        repo.insert(StreamBatch(deviceMetric = listOf(row(0, DeviceMetricKind.POWER_W, 100.0))), deviceId = "garmin-1")
        assertEquals(emptyList<MetricBucket>(), repo.workoutMetricBuckets(listOf("garmin-1"), DeviceMetricKind.CADENCE, start, start + 1800))
        assertEquals(emptyList<MetricBucket>(), repo.workoutMetricBuckets(listOf("nobody"), DeviceMetricKind.POWER_W, start, start + 1800))
        assertEquals(emptyList<MetricBucket>(), repo.workoutMetricBuckets(listOf("garmin-1"), DeviceMetricKind.POWER_W, start, start))
    }

    /** What the Garmin ingest writes for a decoded activity is exactly what the detail read returns. */
    @Test
    fun ingestedTrackMetricsReadBackAndAnAbsentSeriesStaysEmpty() = runBlocking {
        val activity = ActivityFileImporter.Activity(
            kind = ActivityFileImporter.Kind.FIT, startTs = start, endTs = start + 60, sport = "Cycling",
            distanceM = null, energyKcal = null, steps = null, avgHr = null, maxHr = null, ascentM = null,
            gpsPointCount = 0, hrSampleCount = 0, route = emptyList(),
            trackMetrics = listOf(
                ActivityFileImporter.TrackMetric(start, 90.0, 210, null),
                ActivityFileImporter.TrackMetric(start + 15, 92.0, 230, null),
            ),
        )
        repo.insert(StreamBatch(deviceMetric = GarminWatchIngest.activityTrackMetrics(activity)), deviceId = "garmin-1")
        val ids = listOf("garmin-1")
        assertEquals(listOf(MetricBucket(start, 210.0), MetricBucket(start + 15, 230.0)),
            repo.workoutMetricBuckets(ids, DeviceMetricKind.POWER_W, start, start + 60))
        assertEquals(listOf(MetricBucket(start, 90.0), MetricBucket(start + 15, 92.0)),
            repo.workoutMetricBuckets(ids, DeviceMetricKind.CADENCE, start, start + 60))
        assertEquals(emptyList<MetricBucket>(), repo.workoutMetricBuckets(ids, DeviceMetricKind.ALTITUDE_M, start, start + 60))
    }
}
