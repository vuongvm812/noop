package com.noop.data

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.garmin.GarminFitFileType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one-shot ACTIVITY re-download: activities synced before power / cadence / altitude were stored are
 * forgotten once, on both listing flows, for this watch only. Swift twin: `GarminActivityRefetchTests`
 * (StrandTests); [SUMMARY] matches that test's dump. All data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GarminActivityRefetchTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db: WhoopDatabase = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java)
        .allowMainThreadQueries().build()
    private val store = GarminSyncStore(db)
    private val prefs = context.getSharedPreferences("GarminActivityRefetchTest", Context.MODE_PRIVATE)
    private val watch = "garmin-TEST-0165"

    @After
    fun close() {
        db.close()
        prefs.edit().clear().apply()
    }

    private fun row(dataType: Int, subType: Int, n: Int) = GarminSyncedFileRow(n, dataType, subType, n, 1_100_000_000L + n, 10)

    @Test
    fun activityFilesAreForgottenOncePerWatch() = runBlocking {
        val activity = GarminFitFileType.ACTIVITY.raw
        val lines = ArrayList<String>()
        for (r in listOf(row(255, activity, 1), row(128, activity, 2), row(255, 32, 3), row(128, 44, 4), row(255, 49, 5))) {
            store.record(r, watch, 1)
        }
        store.record(row(255, activity, 6), "garmin-OTHER", 1)
        val before = store.syncedFiles(watch).size
        GarminHistoryIngest.refetchActivityFilesOnce(store, watch, prefs) {}
        val kept = store.syncedFiles(watch)
        lines += "forgotten ${before - kept.size}"
        lines += "kept " + kept.map { "${it.dataType}/${it.subType}" }.sorted().joinToString(" ")
        lines += "other device kept ${store.syncedFiles("garmin-OTHER").size}"
        // Once per watch: an activity synced after the re-download stays synced.
        store.record(row(255, activity, 1), watch, 2)
        val afterRepair = store.syncedFiles(watch).size
        GarminHistoryIngest.refetchActivityFilesOnce(store, watch, prefs) {}
        lines += "second run forgotten ${afterRepair - store.syncedFiles(watch).size}"
        assertEquals(SUMMARY, lines.joinToString("\n"))
    }

    private companion object {
        val SUMMARY = """
            forgotten 2
            kept 128/44 255/32 255/49
            other device kept 1
            second run forgotten 0
        """.trimIndent()
    }
}
