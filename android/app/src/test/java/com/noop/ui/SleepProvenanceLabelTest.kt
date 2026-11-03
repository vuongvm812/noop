package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #47: a night recorded by a paired Garmin watch carries the WATCH's own stages, so the Sleep tab names its
 * provenance "Garmin" and captions the split "Garmin watch stages" — not NOOP's "On-device" approximation
 * wording. Oura keeps its own caption; a WHOOP import still wins the hero badge. Mirrors macOS
 * SleepView.heroSource / nightSource / stageCaption.
 */
class SleepProvenanceLabelTest {
    @Test fun stageCaptionPerActiveDevice() {
        assertEquals(" · approx. stages (on-device)", stageProvenanceCaption(activeIsOura = false, activeIsGarmin = false))
        assertEquals(" · raw on-device stages", stageProvenanceCaption(activeIsOura = true, activeIsGarmin = false))
        assertEquals(" · Garmin watch stages", stageProvenanceCaption(activeIsOura = false, activeIsGarmin = true))
    }

    @Test fun heroSourceNamesGarminButWhoopImportStillWins() {
        val none = ImportedSleepSeries()
        assertEquals("Garmin", restHeroSource(none, "2026-11-02", activeIsGarmin = true))
        assertEquals("Oura", restHeroSource(none, "2026-11-02", activeIsOura = true))
        assertEquals("On-device", restHeroSource(none, "2026-11-02"))
        val whoop = ImportedSleepSeries(performance = mapOf("2026-11-02" to 88.0))
        assertEquals("Whoop", restHeroSource(whoop, "2026-11-02", activeIsGarmin = true))
    }

    @Test fun mainSleepBadgeNamesGarminNight() {
        // Banked under the watch's own registry id: daySourceBadge's else-branch would say "Whoop".
        val watchId = "garmin-abc123"
        assertEquals("Garmin", mainSleepSourceBadge(daySourceBadge(watchId), watchId, false, false).first)
        // Computed sibling under a live Garmin watch → "Garmin", not "On-device".
        val computed = "garmin-abc123-noop"
        assertEquals("Garmin", mainSleepSourceBadge(daySourceBadge(computed), computed, false, true).first)
        assertEquals("On-device", mainSleepSourceBadge(daySourceBadge(computed), computed, false, false).first)
        // A non-computed, non-Garmin night keeps its own badge even under a live Garmin watch.
        assertEquals("Whoop", mainSleepSourceBadge(daySourceBadge("my-whoop"), "my-whoop", false, true).first)
        // A Garmin Connect CSV import is not the watch.
        assertEquals("Whoop", mainSleepSourceBadge(daySourceBadge("garmin-import"), "garmin-import", false, false).first)
        assertEquals(Palette.restColor, mainSleepSourceBadge(daySourceBadge(watchId), watchId, false, false).second)
    }
}
