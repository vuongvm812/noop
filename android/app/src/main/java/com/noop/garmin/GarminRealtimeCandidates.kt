package com.noop.garmin

/*
 * CANDIDATE layouts for realtime channels 8 / 10 / 13 (UNVALIDATED). Twin of
 * Packages/GarminProtocol/Sources/GarminProtocol/GarminRealtimeCandidates.swift; readouts are byte-identical.
 *
 * These are NOT documented anywhere upstream. They are hypotheses read off NOOP's own captures from a
 * Forerunner 165 (firmware 29.05): off-wrist on 2026-11-01, and one worn session on 2026-11-02 that the user
 * reported as matching the watch screen for stress and calories (intensity did not change in it; see
 * docs/GARMIN_REALTIME_CHANNELS.md). Until >= 2 worn sessions match the screen where the value moved
 * (issue "Garmin 6/10"), every value here is instrumentation: shown only in the Test Centre capture readout,
 * never stored as a metric, never fed to an analytics gate. Each decoder accepts only the exact payload length
 * seen so far; anything else returns null and stays raw in the capture log.
 */

/**
 * Channel 8, 8 bytes: `[a u32][b u32]`. Hypothesis: a = today's TOTAL kcal (resting + active), b = today's
 * ACTIVE kcal. Worn day 1: a 11 → 82, b 0 → 2, each +1 in b with a +3 step in a; reported as matching the
 * screen. A second session is still needed.
 */
data class GarminCaloriesCandidate(val totalKcal: Long, val activeKcal: Long) {
    companion object {
        fun decode(payload: ByteArray): GarminCaloriesCandidate? {
            if (payload.size != 8) return null
            val r = GarminByteReader(payload)
            val a = r.u32() ?: return null
            val b = r.u32() ?: return null
            return GarminCaloriesCandidate(a, b)
        }
    }
}

/**
 * Channel 10, 14 bytes: seven u16. Observed `172, 26, 73, 357, 0, 150, 0` (Sunday) and `0, 0, 0, 0, 0, 150, 0`
 * (just after midnight Monday). Hypothesis: [0] = this week's intensity minutes, [1] = moderate, [2] = vigorous,
 * [5] = the weekly goal; [0] == [1] + 2 × [2] is the structural check. NOT exercised with a changing value yet.
 * [3], [4] and [6] are unknown and carried raw, never interpreted.
 */
data class GarminIntensityCandidate(
    val weeklyMinutes: Int,
    val moderateMinutes: Int,
    val vigorousMinutes: Int,
    val weeklyGoal: Int,
    /** Words 3, 4 and 6, meaning unknown. */
    val unknown: List<Int>,
) {
    /** Whether the payload satisfies the structural check behind the hypothesis. */
    val totalMatchesParts: Boolean get() = weeklyMinutes == moderateMinutes + 2 * vigorousMinutes

    companion object {
        fun decode(payload: ByteArray): GarminIntensityCandidate? {
            if (payload.size != 14) return null
            val r = GarminByteReader(payload)
            val w = ArrayList<Int>()
            while (true) w.add(r.u16() ?: break)
            if (w.size != 7) return null
            return GarminIntensityCandidate(w[0], w[1], w[2], w[5], listOf(w[3], w[4], w[6]))
        }
    }
}

/**
 * Channel 13, 4 bytes: `[level i16][unknown u16]`. Worn, pushed every ~2-5 s; level 42-96 seen on day 1 (reported
 * as matching the screen). Negative levels are "no reading" sentinels (−4 off-wrist, −3 at start, −5 / −2 briefly);
 * the second word is 0 with a sentinel and 161-247 otherwise, meaning unknown.
 */
data class GarminStressCandidate(val level: Int, val unknown: Int) {
    /** A reading on Garmin's own 0-100 scale (NOT NOOP's stress scale), or null for a sentinel. */
    val score: Int? get() = if (level in 0..100) level else null

    companion object {
        fun decode(payload: ByteArray): GarminStressCandidate? {
            if (payload.size != 4) return null
            val r = GarminByteReader(payload)
            val level = r.u16() ?: return null
            val rest = r.u16() ?: return null
            return GarminStressCandidate(level.toShort().toInt(), rest)
        }
    }
}

object GarminRealtimeCandidates {

    /**
     * The Test Centre readout for one payload, or null when the channel has no candidate or the payload does
     * not fit it. Always prefixed "candidate" so it cannot be read as a shipped value.
     */
    fun describe(service: Int, payload: ByteArray): String? = when (GarminService.fromRaw(service)) {
        GarminService.REALTIME_CALORIES -> GarminCaloriesCandidate.decode(payload)?.let {
            "candidate calories total=${it.totalKcal} active=${it.activeKcal} kcal"
        }
        GarminService.REALTIME_INTENSITY -> GarminIntensityCandidate.decode(payload)?.let {
            "candidate intensity week=${it.weeklyMinutes} moderate=${it.moderateMinutes} " +
                "vigorous=${it.vigorousMinutes} goal=${it.weeklyGoal} " +
                "unknown=${it.unknown.joinToString(",")} " +
                "check=${if (it.totalMatchesParts) "ok" else "MISMATCH"}"
        }
        GarminService.REALTIME_STRESS -> GarminStressCandidate.decode(payload)?.let {
            val reading = it.score?.let { s -> "level=$s" }
                ?: if (it.level < 0) "sentinel=${it.level}" else "out-of-range=${it.level}"
            "candidate garmin-stress(0-100) $reading unknown=${it.unknown}"
        }
        else -> null
    }
}
