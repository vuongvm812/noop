import Foundation

// WhatMovesYouHighlights.swift — the Today card's pick from the What Moves You ranking.
//
// Pure. Takes the SAME `EffectRanker.rankFamily` output the Insights hub shows, so Today and the
// hub cannot disagree: Today only filters and orders rows the hub already holds. Kotlin twin:
// `WhatMovesYouHighlights.kt`.

/// Kotlin twin: `WhatMovesYouHighlights`.
public enum WhatMovesYouHighlights {

    /// How many rows the Today card shows.
    ///
    /// Kotlin twin: `WhatMovesYouHighlights.limit`.
    public static func limit() -> Int { 3 }

    /// Days of outcome history below which the card shows its "needs about two weeks" state.
    ///
    /// Kotlin twin: `WhatMovesYouHighlights.minHistoryDays`.
    public static func minHistoryDays() -> Int { 14 }

    /// The significant rows for `outcomes` (labels as keyed in `ranked`), strongest first:
    /// |Cohen's d| descending, then the outcome's position in `outcomes`, then behaviour name.
    /// At most `limit` rows.
    ///
    /// Kotlin twin: `WhatMovesYouHighlights.top`.
    public static func top(_ ranked: [String: [RankedEffect]], outcomes: [String],
                           limit: Int = WhatMovesYouHighlights.limit()) -> [RankedEffect] {
        var rows: [(order: Int, row: RankedEffect)] = []
        for (i, o) in outcomes.enumerated() {
            for r in ranked[o] ?? [] where r.effect.significant { rows.append((i, r)) }
        }
        rows.sort { a, b in
            let da = abs(a.row.effect.cohensD), db = abs(b.row.effect.cohensD)
            if da != db { return da > db }
            if a.order != b.order { return a.order < b.order }
            return a.row.behavior < b.row.behavior
        }
        return Array(rows.prefix(max(0, limit)).map(\.row))
    }
}
