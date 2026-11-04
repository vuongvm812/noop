#if !os(watchOS)
import SwiftUI

// MARK: - Chart scrub (one interaction for every chart, #58)
//
// Every chart reads its values the same way: a vertical rule follows the pointer or finger, snaps to the
// nearest reading, and a callout names that reading's value and date. The look (rule, dot, callout) lives
// in ChartHover.swift. This file holds the two pieces every chart shares on top of it:
//
// - `ChartScrubMath.resolve`, which decides what the cursor is ON: a reading, or an empty slot where a
//   reading would be. It is pure and has a byte-identical Kotlin twin (`chartScrubResolve` in
//   android/.../ui/ChartScrub.kt), pinned by the same oracle table on both sides.
// - `.chartScrub(_:)`, the gesture. macOS: hover, plus click-drag. iOS: hover on a pointer (iPad), plus
//   touch-and-hold then drag. The hold is what keeps a vertical swipe scrolling the page instead of
//   being captured by the chart.

/// What a scrub cursor resolved to.
public struct ChartScrubHit: Equatable, Sendable {
    /// Index of the nearest reading in the series the caller passed.
    public var index: Int
    /// Set when the cursor sits on an empty slot rather than on a reading: the instant (epoch seconds)
    /// of that slot, so the callout can say "No data" with the right date. Nil on a reading.
    public var gapAt: Int64?

    public init(index: Int, gapAt: Int64? = nil) {
        self.index = index
        self.gapAt = gapAt
    }
}

public enum ChartScrubMath {

    /// The series' regular spacing: the lower median of the positive steps between consecutive readings.
    ///
    /// The median, not the minimum, so one closely spaced pair does not make every ordinary step look like
    /// a hole. Zero when there is no positive step (fewer than two readings, or all at one instant), which
    /// turns gap detection off.
    public static func cadence(_ times: [Int64]) -> Int64 {
        var steps: [Int64] = []
        steps.reserveCapacity(max(0, times.count - 1))
        var i = 1
        while i < times.count {
            let d = times[i] - times[i - 1]
            if d > 0 { steps.append(d) }
            i += 1
        }
        guard !steps.isEmpty else { return 0 }
        steps.sort()
        return steps[(steps.count - 1) / 2]
    }

    /// Resolve a cursor at `cursor` (epoch seconds) against readings at ascending `times`.
    ///
    /// The nearest reading wins; an exact tie picks the earlier one. The cursor is a gap when it is
    /// further than three quarters of the cadence from that reading: that is past the half-step
    /// boundary of a regular slot, so it can only happen over a slot that holds nothing, with a quarter
    /// step of slack for jittery sampling. The gap's instant is the cursor snapped to the cadence grid
    /// that starts at the first reading. Integer arithmetic only, so both platforms agree exactly.
    public static func resolve(times: [Int64], cursor: Int64) -> ChartScrubHit? {
        resolve(times: times, cursor: cursor, cadence: cadence(times))
    }

    /// `resolve` with the cadence computed once by the caller, for charts that resolve on every pointer
    /// event over a long series. `cadence` must be `cadence(times)`.
    public static func resolve(times: [Int64], cursor: Int64, cadence: Int64) -> ChartScrubHit? {
        guard let first = times.first, let last = times.last else { return nil }
        let t = min(max(cursor, first), last)
        var lo = 0, hi = times.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if times[mid] < t { lo = mid + 1 } else { hi = mid }
        }
        var index = lo
        if lo == times.count {
            index = times.count - 1
        } else if lo > 0, t - times[lo - 1] <= times[lo] - t {
            index = lo - 1
        }
        let distance = abs(times[index] - t)
        guard cadence > 0, 4 * distance > 3 * cadence else { return ChartScrubHit(index: index) }
        let offset = t - first
        let slot = (2 * offset + cadence) / (2 * cadence)
        return ChartScrubHit(index: index, gapAt: first + slot * cadence)
    }

    /// `resolve` over dates, for the Swift Charts callers whose x values are `Date`s.
    public static func resolve(dates: [Date], cursor: Date) -> ChartScrubHit? {
        resolve(times: dates.map { epochSeconds($0) }, cursor: epochSeconds(cursor))
    }

    /// Whole epoch seconds, floored, so a fractional instant lands on the same second on both platforms.
    public static func epochSeconds(_ date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970.rounded(.down))
    }

    /// The callout's value line for a gap.
    public static var noDataText: String { String(localized: "No data", bundle: .module) }
}

// MARK: - Gesture

public extension View {
    /// Drive a chart's scrub cursor from the pointer and from touch.
    ///
    /// `x` is the cursor's x in this view's local space, nil when no cursor is shown. The caller resolves it
    /// to a reading and draws the rule and callout; this only owns the input. Pass `enabled: false` while
    /// the chart has nothing to read (for example a hidden or empty series).
    ///
    /// - macOS: hover moves the cursor; a click-drag moves it too and keeps it while the button is held.
    /// - iOS: touch and hold (0.25 s, within 8 pt) shows the cursor, dragging moves it, lifting clears it. A swipe that
    ///   moves before the hold completes is left to the enclosing scroll view.
    ///
    /// `tapThrough: true` is for a chart inside a tappable parent (a navigation link or a button): macOS reads by
    /// hover only, so a click still reaches the parent, and on iOS a plain tap fails the hold and reaches it too.
    func chartScrub(_ x: Binding<CGFloat?>, enabled: Bool = true, tapThrough: Bool = false) -> some View {
        modifier(ChartScrubModifier(
            location: Binding(get: { x.wrappedValue.map { CGPoint(x: $0, y: 0) } },
                              set: { x.wrappedValue = $0?.x }),
            enabled: enabled, tapThrough: tapThrough))
    }

    /// `chartScrub` for a two-dimensional chart (a scatter or a grid): the same gestures, reporting the
    /// cursor's full location so the caller can snap to the nearest point in both axes.
    func chartScrub(location: Binding<CGPoint?>, enabled: Bool = true, tapThrough: Bool = false) -> some View {
        modifier(ChartScrubModifier(location: location, enabled: enabled, tapThrough: tapThrough))
    }

    /// A light selection tick each time the scrub cursor snaps to a different reading. `key` is whatever
    /// identifies the snapped reading (an index or a date); nil when no cursor is shown. iOS only, as
    /// with every `StrandHaptic`.
    func chartScrubHaptic<K: Equatable>(_ key: K?) -> some View {
        strandHaptic(.selection, trigger: key)
    }
}

private struct ChartScrubModifier: ViewModifier {
    @Binding var location: CGPoint?
    var enabled: Bool
    var tapThrough: Bool

    /// Every cursor write is non-animating. Entering or leaving a chart inside an animated context makes
    /// Swift Charts replay the line's draw-on animation, flickering the curve (#104).
    private func set(_ value: CGPoint?) {
        var tx = Transaction()
        tx.disablesAnimations = true
        withTransaction(tx) { location = value }
    }

    func body(content: Content) -> some View {
        content
            .contentShape(Rectangle())
            .onContinuousHover(coordinateSpace: .local) { phase in
                guard enabled else { return }
                switch phase {
                case .active(let point): set(point)
                case .ended: set(nil)
                }
            }
            .gesture(touchGesture, including: gestureMask)
    }

    #if os(macOS)
    private var gestureMask: GestureMask { enabled && !tapThrough ? .all : .none }

    private var touchGesture: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .onChanged { set($0.location) }
    }
    #else
    private var gestureMask: GestureMask { enabled ? .all : .none }

    private var touchGesture: some Gesture {
        LongPressGesture(minimumDuration: 0.25, maximumDistance: 8)
            .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .local))
            .onChanged { value in
                if case .second(true, let drag?) = value { set(drag.location) }
            }
            .onEnded { _ in set(nil) }
    }
    #endif
}
#endif
