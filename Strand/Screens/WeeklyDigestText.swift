import Foundation
import StrandAnalytics

/// Rebuilds the Weekly Digest's user-visible sentences from structured data (`WeeklyFocal`,
/// `BalanceRead`) with localized templates. The engine's English strings stay untouched (they are
/// pinned by golden tests); in English this renderer produces the same sentences, except that metric names use
/// the card's display names (e.g. "SpO₂", "Skin temperature", "VO₂max" where the engine says "Overnight SpO2",
/// "Skin temp deviation", "VO2 max").
/// Kotlin twin: `WeeklyDigestText`.
enum WeeklyDigestText {

    /// The digest's focal sentences in the user's language, same order as `digest.focalPoints`.
    /// Falls back to the engine's English lines for a digest built without structured details.
    static func focalLines(_ digest: WeeklyDigest) -> [String] {
        digest.focalDetails.isEmpty ? digest.focalPoints : digest.focalDetails.map(focal)
    }

    /// The notification body: the top focal sentence, nil for an empty week (no notification then).
    static func notificationBody(_ digest: WeeklyDigest) -> String? {
        digest.isEmpty ? nil : focalLines(digest).first
    }

    static func balance(_ read: BalanceRead) -> String {
        switch read {
        case .overreaching:
            return String(localized: "Your Effort outpaced your Charge this week: you leaned into the red. Watch for a recovery dip.")
        case .balanced:
            return String(localized: "Effort and Charge tracked together this week: a sustainable load.")
        case .underloaded:
            return String(localized: "You carried more Charge than you spent this week: there's room to push if you want it.")
        case .insufficient:
            return String(localized: "Not enough Effort and Charge days this week to read your balance.")
        }
    }

    static func focal(_ f: WeeklyFocal) -> String {
        switch f {
        case let .mover(metric, delta, magnitude, rollup, thisValue, lastValue, goodness):
            return mover(metric: metric, delta: delta, magnitude: magnitude, rollup: rollup,
                         thisValue: thisValue, lastValue: lastValue, goodness: goodness)
        case let .balance(read):
            return balance(read)
        case let .earlyWeek(days):
            return String(localized: "Only \(days) days into this week so far, too early to call a week-over-week trend yet.")
        case let .sparseLastWeek(days):
            return String(localized: "Last week only had \(days) days of data, so week-over-week changes are rough, not a trend.")
        case let .steadyRest(sd):
            return String(localized: "A steady week: Rest held even (±\(sd) pts) and nothing moved much.")
        case .steady:
            return String(localized: "A steady week: no metric moved meaningfully from last week.")
        }
    }

    private static func mover(metric: WeeklyMetric, delta: Int, magnitude: WeeklyFocal.Magnitude,
                              rollup: WeeklyRollup, thisValue: String, lastValue: String,
                              goodness: Int) -> String {
        let dir: String
        if delta > 0 { dir = String(localized: "up") }
        else if delta < 0 { dir = String(localized: "down") }
        else { dir = String(localized: "flat") }
        let mag: String
        switch magnitude {
        case let .percent(p): mag = String(localized: "\(p)%")
        case let .points(v): mag = String(localized: "\(v) pts")
        case let .unit(v, unit): mag = String(localized: "\(v) \(unit)")
        }
        let word: String
        switch rollup {
        case .mean: word = String(localized: "avg")
        case .sum: word = String(localized: "total")
        case .latest: word = String(localized: "latest")
        }
        let frame: String
        switch goodness {
        case 1: frame = String(localized: ", a good sign")
        case -1: frame = String(localized: ", worth a look")
        default: frame = ""
        }
        let name = metric.localizedName
        return String(localized: "\(name) is \(dir) \(mag) week over week (\(word) \(thisValue) vs \(lastValue))\(frame).")
    }
}
