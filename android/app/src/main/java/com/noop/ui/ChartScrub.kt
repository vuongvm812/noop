package com.noop.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.sp
import kotlin.math.abs

// MARK: - Chart scrub (one interaction for every chart, #58)
//
// Every chart reads its values the same way: a vertical rule follows the finger, snaps to the nearest
// reading, and a callout names that reading's value and date. This file holds the pieces they share:
//
// - `chartScrubResolve`, which decides what the cursor is ON: a reading, or an empty slot where one would
//   be. Byte-identical twin of Swift `ChartScrubMath.resolve` (Packages/StrandDesign/.../ChartScrub.swift),
//   pinned by the same oracle table on both sides (ChartScrubResolveTest).
// - `Modifier.chartScrub`, the gesture: a tap selects, a horizontal drag scrubs, and a touch-and-hold
//   scrubs in any direction. A vertical swipe that starts before the hold completes is left to the
//   enclosing scroll container, so charts never trap the page scroll.
// - `ChartScrubHaptic`, a light tick each time the cursor snaps to a new reading.
// - `drawScrubCursor` / `drawScrubCallout`, the shared rule, dot and callout.

/** What a scrub cursor resolved to: the nearest reading's [index], plus [gapAt] (epoch seconds of the
 *  empty slot) when the cursor sits over a missing reading instead. */
data class ChartScrubHit(val index: Int, val gapAt: Long? = null)

/**
 * The series' regular spacing: the lower median of the positive steps between consecutive readings. The
 * median, not the minimum, so one close pair does not make every ordinary step look like a hole. Zero
 * when there is no positive step, which turns gap detection off. Twin of `ChartScrubMath.cadence`.
 */
internal fun chartScrubCadence(times: List<Long>): Long {
    val steps = ArrayList<Long>(maxOf(0, times.size - 1))
    for (i in 1 until times.size) {
        val d = times[i] - times[i - 1]
        if (d > 0) steps.add(d)
    }
    if (steps.isEmpty()) return 0
    steps.sort()
    return steps[(steps.size - 1) / 2]
}

/**
 * Resolve a cursor at [cursor] (epoch seconds) against readings at ascending [times]. The nearest reading
 * wins and an exact tie picks the earlier one. The cursor is a gap when it is further than three quarters
 * of the cadence from that reading, which is only possible over a slot holding nothing; the gap's instant
 * is the cursor snapped to the cadence grid starting at the first reading. Integer arithmetic only, so
 * both platforms agree exactly. Twin of `ChartScrubMath.resolve`.
 */
internal fun chartScrubResolve(
    times: List<Long>,
    cursor: Long,
    cadence: Long = chartScrubCadence(times),
): ChartScrubHit? {
    if (times.isEmpty()) return null
    val first = times.first()
    val t = cursor.coerceIn(first, times.last())
    var lo = 0
    var hi = times.size
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (times[mid] < t) lo = mid + 1 else hi = mid
    }
    var index = lo
    if (lo == times.size) {
        index = times.size - 1
    } else if (lo > 0 && t - times[lo - 1] <= times[lo] - t) {
        index = lo - 1
    }
    val distance = abs(times[index] - t)
    if (cadence <= 0 || 4 * distance <= 3 * cadence) return ChartScrubHit(index)
    val slot = (2 * (t - first) + cadence) / (2 * cadence)
    return ChartScrubHit(index, first + slot * cadence)
}

/** Nearest of [fractions] (0..1 positions across [width]) to [x]; ties pick the earlier one. */
internal fun chartScrubNearestIndex(fractions: List<Float>, width: Float, x: Float): Int {
    if (fractions.size <= 1 || width <= 0f) return 0
    val clampedX = x.coerceIn(0f, width)
    var best = 0
    var bestDist = Float.MAX_VALUE
    fractions.forEachIndexed { i, f ->
        val d = abs(f * width - clampedX)
        if (d < bestDist) { bestDist = d; best = i }
    }
    return best
}

/** Evenly spaced 0..1 positions for [count] points (a lone point sits at 0). */
internal fun chartScrubIndexFractions(count: Int): List<Float> =
    if (count <= 1) List(count) { 0f } else List(count) { it.toFloat() / (count - 1) }

/** The callout's value line for a gap. */
internal const val CHART_SCRUB_NO_DATA = "No data"

/** How long a touch must hold still before the scrub engages in every direction. Matches Apple's 0.25 s. */
private const val CHART_SCRUB_HOLD_MS = 250L

/**
 * Drive a chart's scrub cursor from touch. [onScrub] receives the cursor's x in this node's local space;
 * [onRelease] fires when the finger lifts after a scrub. A plain tap pins the cursor with one [onScrub] at
 * the tap; a tap elsewhere moves it, and a second tap on the same spot dismisses it with [onRelease].
 * [keys] restart the gesture handler when the chart's data changes.
 *
 * [selectOnTap] false leaves a plain tap untouched (not even [onScrub]), so a chart inside a clickable tile
 * does not steal the tile's click; only a drag or a hold scrubs. [holdOnly] true engages ONLY after the
 * hold: any movement past the touch slop before it, and any second finger, abandons the gesture to
 * whatever else handles it (a pan/zoom detector earlier in the modifier chain), and a tap does nothing.
 * Once a scrub engages, every change of the pointer is consumed, including its lift, so a detector earlier
 * in the chain (a pan, a parent's click) cancels instead of acting on the same touch. A parent that has
 * already consumed the change (a pinch or pan detector on the Initial pass) ends the scrub instead.
 */
fun Modifier.chartScrub(
    vararg keys: Any?,
    enabled: Boolean = true,
    selectOnTap: Boolean = true,
    holdOnly: Boolean = false,
    onRelease: () -> Unit = {},
    onScrub: (Float) -> Unit,
): Modifier = if (!enabled) this else this.pointerInput(*keys, selectOnTap, holdOnly) {
    // x of the cursor a tap pinned, or null; a later tap within the touch slop of it dismisses it.
    var pinnedX: Float? = null
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        // Phase 1: before the hold completes, decide between tap, horizontal scrub and page scroll.
        var abandoned = false
        var tapped = false
        withTimeoutOrNull(CHART_SCRUB_HOLD_MS) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) { tapped = change != null && !change.isConsumed; break }
                if (change.isConsumed) { abandoned = true; break }
                if (holdOnly && event.changes.count { it.pressed } > 1) { abandoned = true; break }
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (holdOnly) {
                    if (abs(dx) > slop || abs(dy) > slop) { abandoned = true; break }
                    continue
                }
                if (abs(dx) > slop && abs(dx) > abs(dy)) break
                if (abs(dy) > slop) { abandoned = true; break }
            }
        }
        if (abandoned) return@awaitEachGesture
        if (tapped) {
            if (selectOnTap && !holdOnly) {
                val pinned = pinnedX
                if (pinned != null && abs(down.position.x - pinned) <= slop) {
                    pinnedX = null
                    onRelease()
                } else {
                    pinnedX = down.position.x
                    onScrub(down.position.x)
                }
            }
            return@awaitEachGesture
        }
        // Phase 2: the hold completed or the drag went horizontal — own the pointer until it lifts.
        pinnedX = null
        onScrub(down.position.x)
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // A parent detector (pinch, zoomed pan) claimed this touch on an earlier pass: yield to it.
                if (change.isConsumed) break
                change.consume()
                if (!change.pressed) break
                onScrub(change.position.x)
            }
        } finally {
            onRelease()
        }
    }
}

/**
 * A light tick each time [key] (whatever identifies the snapped reading) changes to a non-null value.
 * The same KEYBOARD_TAP route the liquid primitives use, the lightest tick this Compose BOM reaches.
 */
@Composable
fun ChartScrubHaptic(key: Any?) {
    val view = LocalView.current
    LaunchedEffect(key) {
        if (key != null) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}

/** Pre-laid paints for the shared callout, remembered so a drag never allocates per frame. */
class ChartScrubPaints(
    val value: android.graphics.Paint,
    val label: android.graphics.Paint,
)

@Composable
fun rememberChartScrubPaints(): ChartScrubPaints {
    val density = LocalDensity.current
    val primary = Palette.textPrimary
    val secondary = Palette.textSecondary
    return remember(density, primary, secondary) {
        ChartScrubPaints(
            value = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = primary.toArgb()
                textSize = with(density) { 13.sp.toPx() }
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            },
            label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = secondary.toArgb()
                textSize = with(density) { 11.sp.toPx() }
            },
        )
    }
}

/**
 * The shared cursor: a dashed full-height rule at [x], an accented dot at ([x], [y]) when [y] is given
 * (a reading; null for a gap or a bar whose own highlight marks it), and the callout naming [value] and
 * [label]. Design tokens only.
 */
fun DrawScope.drawScrubCursor(
    x: Float,
    y: Float?,
    accent: Color,
    value: String,
    label: String?,
    paints: ChartScrubPaints,
    overflowAbove: Boolean = false,
) {
    drawLine(
        color = Palette.hairlineStrong,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = px(1f),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(px(3f), px(3f))),
    )
    if (y != null) {
        drawCircle(color = Palette.surfaceBase, radius = px(6f), center = Offset(x, y))
        drawCircle(color = accent, radius = px(4.5f), center = Offset(x, y))
    }
    drawScrubCallout(
        anchorX = x, anchorY = y ?: (size.height / 2f), value = value, label = label, paints = paints,
        overflowAbove = overflowAbove,
    )
}

/** The callout card, above the anchor where it fits (else below), clamped inside the chart. */
fun DrawScope.drawScrubCallout(
    anchorX: Float,
    anchorY: Float,
    value: String,
    label: String?,
    paints: ChartScrubPaints,
    // True draws the card above the anchor even past the top edge: for a strip too short to hold the card
    // (a tile sparkline), where clamping would cover the line being read.
    overflowAbove: Boolean = false,
) {
    val padH = px(9f)
    val padV = px(6f)
    val gap = px(12f)
    val lineGap = px(2f)
    val valueH = paints.value.textSize
    val labelH = if (label.isNullOrEmpty()) 0f else paints.label.textSize + lineGap
    val w = maxOf(paints.value.measureText(value), label?.let { paints.label.measureText(it) } ?: 0f) + padH * 2
    val h = valueH + labelH + padV * 2
    var top = anchorY - gap - h
    if (!overflowAbove) {
        if (top < 0f) top = anchorY + gap
        top = top.coerceIn(0f, (size.height - h).coerceAtLeast(0f))
    }
    val left = (anchorX - w / 2f).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    val radius = CornerRadius(px(8f), px(8f))
    drawRoundRect(color = Palette.surfaceRaised, topLeft = Offset(left, top), size = Size(w, h), cornerRadius = radius)
    drawRoundRect(
        color = Palette.hairlineStrong, topLeft = Offset(left, top), size = Size(w, h),
        cornerRadius = radius, style = Stroke(width = 1f),
    )
    drawContext.canvas.nativeCanvas.apply {
        drawText(value, left + padH, top + padV + valueH * 0.85f, paints.value)
        if (!label.isNullOrEmpty()) {
            drawText(label, left + padH, top + padV + valueH + lineGap + paints.label.textSize * 0.85f, paints.label)
        }
    }
}

private fun DrawScope.px(dp: Float): Float = dp * density
