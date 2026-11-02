package com.noop.garmin

// Kotlin re-run of Packages/GarminProtocol/Tests/GarminProtocolTests/GarminCaptureOracleCases.swift: the same
// capture traces through the live-channel table, capture lines and candidate decodes, printed the same way.
// GarminCaptureTest compares this against the Swift stdout. Every payload is synthesized, not a personal log.

internal sealed class OracleCaptureStep {
    object Start : OracleCaptureStep()
    class Rx(val bytes: ByteArray) : OracleCaptureStep()
    class Want(val services: List<GarminService>) : OracleCaptureStep()
    class Capture(val on: Boolean) : OracleCaptureStep()
}

internal fun oracleRunCapture(name: String, session: GarminSession, steps: List<Pair<Long, OracleCaptureStep>>): List<String> {
    val out = mutableListOf("trace $name")
    for ((ms, step) in steps) {
        val clock = GarminClock(ms, 1_793_546_986L + ms / 1000, 25_200)
        val outputs = when (step) {
            is OracleCaptureStep.Start -> session.start(clock)
            is OracleCaptureStep.Rx -> session.receive(step.bytes, clock)
            is OracleCaptureStep.Want -> session.setLiveChannels(step.services.toSet(), clock)
            is OracleCaptureStep.Capture -> session.setUndecodedCapture(step.on, clock)
        }
        out += outputs.map { "@$ms " + oracleDescribe(it) }
    }
    val open = session.openLiveChannels.map { it.raw }.sorted().map { it.toString() }
    out += "end phase=${session.phase.label} open=${if (open.isEmpty()) "-" else open.joinToString(",")}"
    return out
}

/** A V2 session driven to `ready` on GFDI handle 0x65 (no first pairing, no lifecycle events). */
internal fun oracleCaptureReadySteps(): List<Pair<Long, OracleCaptureStep>> {
    val handle = 0x65
    fun ml(message: ByteArray): ByteArray = bytes(handle) + oracleCobs(message)
    return listOf(
        0L to OracleCaptureStep.Start,
        1L to OracleCaptureStep.Rx(oracleControl(6, oracleLE16(0) + bytes(0))),
        2L to OracleCaptureStep.Rx(oracleControl(1, oracleLE16(1) + bytes(0, handle, 0))),
        3L to OracleCaptureStep.Rx(ml(oracleFr165DeviceInformation())),
        4L to OracleCaptureStep.Rx(ml(oracleFrame(5050, bytes(8, 0xFF, 0x01, 0, 0, 0, 0, 0, 0x80)))),
    )
}

internal fun garminCaptureOracleLines(): List<String> {
    val out = mutableListOf<String>()
    fun config() = GarminSessionConfig(oracleSessionHost(), firstPairing = false, sendsHostLifecycleEvents = false)
    fun registered(service: Int, status: Int, handle: Int) = oracleControl(1, oracleLE16(service) + bytes(status, handle, 0))
    fun closed(service: Int, handle: Int) = oracleControl(3, oracleLE16(service) + bytes(handle, 0))
    fun rx(b: ByteArray) = OracleCaptureStep.Rx(b)
    fun want(vararg s: GarminService) = OracleCaptureStep.Want(s.toList())
    fun capture(on: Boolean) = OracleCaptureStep.Capture(on)
    fun le16s(vararg v: Int): ByteArray = v.fold(ByteArray(0)) { acc, x -> acc + oracleLE16(x) }
    val hr = GarminService.REALTIME_HEART_RATE
    val cal = GarminService.REALTIME_CALORIES
    val int = GarminService.REALTIME_INTENSITY
    val str = GarminService.REALTIME_STRESS

    // A: asked for without capture (only HR opens), then capture on: data routed raw, one refused, capture off.
    val a = GarminSession(config(), GarminTransportKind.MULTI_LINK_V2, 150)
    val intensity = le16s(95, 15, 40, 7, 0, 150, 0)
    val calories = oracleLE32(2150) + oracleLE32(410)
    out += oracleRunCapture("capture", a, oracleCaptureReadySteps() + listOf(
        10L to want(hr, cal, int, str, GarminService.REALTIME_ACCELEROMETER),
        11L to capture(true),
        20L to rx(registered(6, 0, 0x66)),
        21L to rx(registered(8, 0, 0x67)),
        22L to rx(registered(10, 0, 0x68)),
        23L to rx(registered(13, 3, 0)),
        30L to rx(bytes(0x66, 0x00, 0x48, 0x3B, 0xFF, 0xFF)),
        31L to rx(bytes(0x67) + calories),
        32L to rx(bytes(0x68) + intensity),
        33L to rx(bytes(0x69, 0x01, 0x02)),
        34L to capture(true),
        40L to capture(false),
        41L to rx(closed(8, 0x67)),
        42L to rx(bytes(0x68, 0x01)),
        43L to rx(closed(10, 0x68)),
        44L to want(hr),
        45L to capture(true),
    ))

    // B: capture switched off before the registration answers; the late handle is given back.
    val b = GarminSession(config(), GarminTransportKind.MULTI_LINK_V2, 150)
    out += oracleRunCapture("late-answer", b, oracleCaptureReadySteps() + listOf(
        10L to capture(true),
        11L to want(str),
        12L to capture(false),
        13L to rx(registered(13, 0, 0x70)),
        14L to rx(closed(13, 0x70)),
    ))

    // C: the watch closes one channel itself, then closes everything.
    val c = GarminSession(config(), GarminTransportKind.MULTI_LINK_V2, 150)
    out += oracleRunCapture("watch-closes", c, oracleCaptureReadySteps() + listOf(
        10L to capture(true),
        11L to want(str, cal),
        12L to rx(registered(8, 0, 0x67)),
        13L to rx(registered(13, 0, 0x66)),
        14L to rx(closed(13, 0x66)),
        15L to rx(oracleControl(6, oracleLE16(0) + bytes(0))),
        16L to rx(bytes(0x67, 0x01)),
    ))

    // D: capture and demand set before the handshake are applied once it completes.
    val d = GarminSession(config(), GarminTransportKind.MULTI_LINK_V2, 150)
    val early = mutableListOf(0L to OracleCaptureStep.Start, 1L to capture(true), 1L to want(int))
    for (step in oracleCaptureReadySteps().drop(1)) early += (step.first + 1) to step.second
    out += oracleRunCapture("before-ready", d, early)

    // E: V1 has no multi-link handles.
    val e = GarminSession(config(), GarminTransportKind.V1, 150)
    out += oracleRunCapture("v1", e, listOf(0L to OracleCaptureStep.Start, 1L to capture(true), 2L to want(str)))

    val payloads: List<Pair<Int, ByteArray>> = listOf(
        6 to bytes(0x00, 0x48, 0x3B, 0xFF, 0xFF),
        6 to bytes(0x00, 0xFF, 0x3B, 0xFF, 0xFF),
        6 to bytes(0x00, 0x00),
        6 to bytes(0x00),
        8 to oracleLE32(2150) + oracleLE32(410),
        8 to oracleLE32(0xFFFF_FFFFL) + oracleLE32(0),
        8 to oracleLE32(2150),
        10 to le16s(95, 15, 40, 7, 0, 150, 0),
        10 to le16s(96, 15, 40, 7, 0, 150, 0),
        10 to le16s(0xFFFF, 0xFFFF, 0xFFFF, 1, 2, 3, 4),
        10 to le16s(95, 15) + bytes(0x28),
        13 to le16s(42, 0),
        13 to le16s(0xFFFE, 0),
        13 to le16s(101, 7),
        13 to le16s(0, 0xFFFF),
        13 to bytes(0x2A),
        13 to ByteArray(0),
        21 to bytes(0x10),
    )
    for ((i, p) in payloads.withIndex()) {
        val (service, payload) = p
        val reading = GarminService.fromRaw(service)?.let { GarminRealtime.decode(it, payload) }
        out += "hr $service ${oracleOpt(reading?.let { GarminRealtimeCapture.contextHeartRate(it) })}"
        out += GarminRealtimeCapture.line(service, payload, 1_793_549_639_000L + i, if (i % 2 == 0) 72 else null)
    }
    out += GarminRealtimeCapture.availabilityLine("Forerunner 165", 2905, mapOf(8 to "opened", 13 to "refused(3)"))
    out += GarminRealtimeCapture.availabilityLine("Venu", 1001, mapOf(8 to "opened", 10 to "opened", 13 to "opened"))
    for (s in GarminService.entries) out += "realtime ${s.raw} ${s.isRealtime}"
    return out
}
