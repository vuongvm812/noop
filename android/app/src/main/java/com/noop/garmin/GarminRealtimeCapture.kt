package com.noop.garmin

/**
 * Capture mode for the undecoded realtime channels 8 (calories), 10 (intensity), 13 (stress). Twin of
 * Packages/GarminProtocol/Sources/GarminProtocol/GarminRealtimeCapture.swift; the lines are byte-identical.
 *
 * No public source documents these payloads: Gadgetbridge lists the service numbers but closes the handles on
 * registration, so their contents were never logged. This is the instrumentation that captures them: one
 * strap-log line per payload, raw hex plus every little-endian reading a layout could plausibly use, beside
 * the watch's own live HR for context. Nothing here interprets a value; the candidate layouts live in
 * [GarminRealtimeCandidates] and stay Test Centre-only until compared against the watch screen. The handles are
 * opened and closed by the live-channel table, which admits these three only while the capture flag is on.
 */
object GarminRealtimeCapture {

    /** The channels nobody has decoded, in the order they are opened. */
    val undecodedChannels: List<GarminService> =
        listOf(GarminService.REALTIME_CALORIES, GarminService.REALTIME_INTENSITY, GarminService.REALTIME_STRESS)

    /** What the capture mode opens: the undecoded channels plus HR, whose value is the context column. */
    val captureChannels: List<GarminService> = listOf(GarminService.REALTIME_HEART_RATE) + undecodedChannels

    fun name(service: Int): String = when (GarminService.fromRaw(service)) {
        GarminService.REALTIME_HEART_RATE -> "hr"
        GarminService.REALTIME_CALORIES -> "calories"
        GarminService.REALTIME_INTENSITY -> "intensity"
        GarminService.REALTIME_STRESS -> "stress"
        else -> "service$service"
    }

    /**
     * Context only: the watch's live HR from a decoded channel-6 reading. 0 (decoded as null) and 0xFF
     * (observed off-wrist) mean "no reading" and are reported as null.
     */
    fun contextHeartRate(reading: GarminRealtimeReading): Int? {
        val bpm = (reading as? GarminRealtimeReading.HeartRate)?.bpm ?: return null
        return if (bpm < 0xFF) bpm else null
    }

    /**
     * One capture line. [unixMs] is the host receive time; [hr] the latest channel-6 HR, if any. `u16` reads
     * every even offset, `u32` every 4-aligned offset, `i8` is byte 0 signed. A trailing odd byte is in `raw`
     * only. The candidate reading ([GarminRealtimeCandidates]) follows a ` | ` when the payload fits one.
     */
    fun line(service: Int, payload: ByteArray, unixMs: Long, hr: Int?): String {
        var r = GarminByteReader(payload)
        val u16 = ArrayList<String>()
        while (true) u16.add((r.u16() ?: break).toString())
        r = GarminByteReader(payload)
        val u32 = ArrayList<String>()
        while (true) u32.add((r.u32() ?: break).toString())
        val i8 = if (payload.isEmpty()) "-" else payload[0].toInt().toString()
        return "garmin-rt ch=$service ${name(service)} t=$unixMs len=${payload.size} " +
            "hr=${hr?.toString() ?: "-"} raw=${garminHex(payload)} " +
            "u16=${if (u16.isEmpty()) "-" else u16.joinToString(",")} " +
            "u32=${if (u32.isEmpty()) "-" else u32.joinToString(",")} i8=$i8" +
            (GarminRealtimeCandidates.describe(service, payload)?.let { " | $it" } ?: "")
    }

    /**
     * The per-model availability line, once every undecoded channel has answered: `opened` or the raw refusal
     * status per channel.
     */
    fun availabilityLine(model: String, firmware: Int, answers: Map<Int, String>): String {
        val minor = firmware % 100
        val fw = "${firmware / 100}.${if (minor < 10) "0" else ""}$minor"
        val cols = undecodedChannels.map { "ch${it.raw}=${answers[it.raw] ?: "unanswered"}" }
        return "garmin-rt availability model=$model fw=$fw " + cols.joinToString(" ")
    }
}
