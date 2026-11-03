package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The LineChart scrub callout's value and label (#463, #58). The overlay used to draw the RAW plotted value
 * unconditionally, so on Trends' Effort chart a tapped day printed the stored 0-100 figure (a bare
 * "13") beside a 0-21 converted axis. Callers can inject the same formatter the axis uses and
 * name a selected daily point's date; with neither, the raw default is unchanged.
 */
class ChartSelectionLabelTest {

    @Test fun withoutAFormatterTheRawDefaultIsUnchanged() {
        // Near-integer values collapse to the bare integer, the exact "13" the reporter saw.
        assertEquals("13" to null, lineChartSelectionParts(13.0, null))
        assertEquals("13" to null, lineChartSelectionParts(12.98, null))
        // Clearly fractional values keep one decimal.
        assertEquals("9.4" to null, lineChartSelectionParts(9.4, null))
    }

    @Test fun aSuppliedFormatterOwnsTheLabel() {
        val toWhoopScale: (Double) -> String = { UnitFormatter.effortDisplay(it, EffortScale.WHOOP) }
        // The stored 13 renders as 2.7 on the 0-21 display scale, matching the axis column.
        assertEquals("2.7" to null, lineChartSelectionParts(13.0, toWhoopScale))
    }

    @Test fun theFormatterReceivesThePlottedValueVerbatim() {
        var seen: Double? = null
        lineChartSelectionParts(41.5, { v -> seen = v; "x" })
        assertEquals(41.5, seen!!, 0.0)
    }

    // Prototype (hr-chart-time-axis): a caller-supplied sample timestamp becomes the local clock-time
    // label, so the scrub answers "when" — "87 bpm" over "14:32", not a bare value.
    @Test fun aSuppliedTimestampLabelsTheLocalClockTime() {
        val zone = java.time.ZoneId.of("Europe/Kyiv")   // UTC+3 in July
        val ts = java.time.ZonedDateTime.of(2026, 7, 10, 14, 32, 0, 0, zone).toEpochSecond()
        assertEquals("87 bpm" to "14:32", lineChartSelectionParts(87.2, { "${it.toInt()} bpm" }, ts, zone))
        // Without a formatter the raw default still carries the time label.
        assertEquals("87" to "14:32", lineChartSelectionParts(87.0, null, ts, zone))
    }

    @Test fun withoutATimestampTheLabelIsUnchanged() {
        assertEquals("87" to null, lineChartSelectionParts(87.0, null, null))
    }

    @Test fun aSuppliedPointLabelNamesTheFormattedValue() {
        assertEquals(
            "87 bpm" to "16 Jul",
            lineChartSelectionParts(
                value = 87.2,
                formatValue = { "${it.toInt()} bpm" },
                pointLabel = "16 Jul",
            ),
        )
    }

    @Test fun aBlankPointLabelLeavesTheValueUnchanged() {
        assertEquals("87" to null, lineChartSelectionParts(87.0, null, pointLabel = ""))
    }
}
