package com.noop.analytics

import kotlin.math.abs

/*
 * WhatMovesYouHighlights.kt — the Today card's pick from the What Moves You ranking.
 *
 * Kotlin twin of StrandAnalytics/WhatMovesYouHighlights.swift. Pure. Takes the SAME
 * EffectRanker.rankFamily output the Insights hub shows, so Today and the hub cannot disagree:
 * Today only filters and orders rows the hub already holds.
 */
/** Kotlin twin of Swift `WhatMovesYouHighlights`. */
object WhatMovesYouHighlights {

    /** How many rows the Today card shows. Swift twin: `WhatMovesYouHighlights.limit`. */
    fun limit(): Int = 3

    /** Days of outcome history below which the card shows its "needs about two weeks" state.
     *  Swift twin: `WhatMovesYouHighlights.minHistoryDays`. */
    fun minHistoryDays(): Int = 14

    /**
     * The significant rows for [outcomes] (labels as keyed in [ranked]), strongest first: |Cohen's d|
     * descending, then the outcome's position in [outcomes], then behaviour name. At most [limit] rows.
     * Swift twin: `WhatMovesYouHighlights.top`.
     */
    fun top(ranked: Map<String, List<RankedEffect>>, outcomes: List<String>, limit: Int = WhatMovesYouHighlights.limit()): List<RankedEffect> {
        val rows = ArrayList<Pair<Int, RankedEffect>>()
        for ((i, o) in outcomes.withIndex()) {
            for (r in ranked[o].orEmpty()) if (r.effect.significant) rows.add(i to r)
        }
        val sorted = rows.sortedWith(
            compareByDescending<Pair<Int, RankedEffect>> { abs(it.second.effect.cohensD) }
                .thenBy { it.first }
                .thenBy { it.second.behavior },
        )
        return sorted.take(maxOf(0, limit)).map { it.second }
    }
}
