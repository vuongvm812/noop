package com.noop.garmin

// Kotlin re-run of Packages/GarminProtocol/Tests/GarminProtocolTests/GarminSessionOracleCases.swift: the same
// scripted watch traces, printed the same way. GarminSessionTest compares this against the Swift stdout.

internal sealed class OracleSessionStep {
    object Start : OracleSessionStep()
    class Rx(val bytes: ByteArray) : OracleSessionStep()
    object Tick : OracleSessionStep()
    class Foreground(val fg: Boolean) : OracleSessionStep()
}

/** The Forerunner 165's 5024 as observed on 2026-11-01 (protocol 151, product 4432, firmware 29.05). */
internal fun oracleFr165DeviceInformation(): ByteArray =
    oracleFrame(5024, oracleLE16(151) + oracleLE16(4432) + oracleLE32(3_632_104_734L) + oracleLE16(2905) +
        oracleLE16(512) + oracleStr("Forerunner 165") + oracleStr("Forerunner") + oracleStr("165"))

internal fun oracleCobs(message: ByteArray): ByteArray = GarminCobs.encode(message)

internal fun oracleControl(type: Int, rest: ByteArray): ByteArray = bytes(0, type, 2, 0, 0, 0, 0, 0, 0, 0) + rest

internal fun oracleSessionHost(): GarminHostIdentity = GarminSessionConfig.noopHost("oracle", 1)

internal fun oracleDescribe(o: GarminSessionOutput): String = when (o) {
    is GarminSessionOutput.Write -> "w ${garminHex(o.bytes)}"
    is GarminSessionOutput.Log -> "log ${if (o.line.rare) "R" else "D"} ${o.line.text}"
    is GarminSessionOutput.Phase -> "phase ${o.phase.label}"
    is GarminSessionOutput.Battery -> "battery ${o.percent}"
    is GarminSessionOutput.Watch -> "watch ${o.info.bluetoothName} sw=${o.info.softwareVersion}"
    is GarminSessionOutput.PairingEventsSent -> "paired"
    is GarminSessionOutput.SyncAnnounced -> "sync"
}

internal fun oracleRunSession(
    name: String,
    session: GarminSession,
    steps: List<Pair<Long, OracleSessionStep>>,
    next: Long? = null,
    following: Long? = null,
): List<String> {
    val out = mutableListOf("trace $name")
    for ((ms, step) in steps) {
        val clock = GarminClock(ms, 1_793_546_986L + ms / 1000, 25_200, next, following)
        val outputs = when (step) {
            is OracleSessionStep.Start -> session.start(clock)
            is OracleSessionStep.Rx -> session.receive(step.bytes, clock)
            is OracleSessionStep.Tick -> session.tick(clock)
            is OracleSessionStep.Foreground -> session.setForeground(step.fg, clock)
        }
        out += outputs.map { "@$ms " + oracleDescribe(it) }
    }
    out += "end phase=${session.phase.label} in=${session.messagesIn} dropped=${session.framesDropped} " +
        "deadline=${oracleOpt(session.nextDeadlineMs)}"
    return out
}

internal fun garminSessionOracleLines(): List<String> {
    val out = mutableListOf<String>()
    val handle = 0x65
    fun ml(message: ByteArray): ByteArray = bytes(handle) + oracleCobs(message)
    fun rx(b: ByteArray) = OracleSessionStep.Rx(b)

    val a = GarminSession(GarminSessionConfig(oracleSessionHost(), firstPairing = true, sendsHostLifecycleEvents = true),
        GarminTransportKind.MULTI_LINK_V2, 150)
    val deviceInfo = ml(oracleFr165DeviceInformation())
    val corrupt = oracleFr165DeviceInformation().also { it[6] = (it[6].toInt() xor 0xFF).toByte() }.let { ml(it) }
    val config = ml(oracleFrame(5050, bytes(8, 0xFF, 0x01, 0, 0, 0, 0, 0, 0x80)))
    out += oracleRunSession("ml-full", a, listOf(
        0L to OracleSessionStep.Start,
        5L to rx(oracleControl(3, oracleLE16(1) + bytes(0x60, 0))),
        10L to rx(oracleControl(6, oracleLE16(0) + bytes(0))),
        20L to rx(oracleControl(1, oracleLE16(1) + bytes(0, handle, 0))),
        30L to rx(deviceInfo.copyOfRange(0, 20)),
        31L to rx(bytes(handle) + deviceInfo.copyOfRange(20, deviceInfo.size)),
        40L to rx(deviceInfo),
        50L to rx(config),
        60L to rx(ml(oracleFrame(5000, oracleLE16(5050) + bytes(0)))),
        70L to rx(ml(oracleFrame(5052, oracleLE32(90_094_860L)))),
        80L to rx(ml(oracleFrame(5101, bytes(4) + oracleLE32(0x0102_0304L)))),
        90L to rx(ml(oracleFrame(5043, oracleLE16(795) + oracleLE32(0) + oracleLE32(4) + oracleLE32(4) + bytes(0x6A, 0x02, 0x08, 0x01)))),
        100L to rx(ml(oracleFrame(5023, bytes(0x10, 0xC8, 87, 0, 0, 0, 0)))),
        110L to rx(ml(oracleFrame(5037, bytes(0, 4) + oracleLE32(0x0F)))),
        120L to rx(ml(oracleFrame(5014, bytes(1, 2)))),
        121L to rx(ml(oracleFrame(5014, bytes(1, 2)))),
        130L to rx(ml(oracleFrame(5000, oracleLE16(5030) + bytes(2)))),
        140L to rx(corrupt),
        150L to rx(bytes(0x22, 1, 2, 3)),
        160L to rx(oracleControl(1, oracleLE16(6) + bytes(0, 0x22, 0))),
        170L to OracleSessionStep.Foreground(false),
        171L to OracleSessionStep.Foreground(false),
        180L to rx(oracleControl(3, oracleLE16(1) + bytes(handle, 0))),
        190L to rx(oracleControl(1, oracleLE16(1) + bytes(0, 0x66, 0))),
        195L to rx(bytes(0x66) + oracleCobs(oracleFr165DeviceInformation())),
        196L to rx(bytes(0x66) + oracleCobs(oracleFrame(5050, bytes(1, 0x09)))),
        200L to OracleSessionStep.Foreground(true),
        210L to OracleSessionStep.Tick,
    ))

    val b = GarminSession(GarminSessionConfig(oracleSessionHost(), firstPairing = false, sendsHostLifecycleEvents = false),
        GarminTransportKind.MULTI_LINK_V2, 23)
    out += oracleRunSession("ml-refused", b, listOf(
        0L to OracleSessionStep.Start,
        10L to rx(oracleControl(6, oracleLE16(0) + bytes(0))),
        20L to rx(oracleControl(1, oracleLE16(1) + bytes(3, 0, 0))),
        30L to rx(ml(oracleFr165DeviceInformation())),
    ))

    val c = GarminSession(GarminSessionConfig(oracleSessionHost(), firstPairing = false, sendsHostLifecycleEvents = true,
        handshakeTimeoutMs = 1_000), GarminTransportKind.MULTI_LINK_V2, 23)
    out += oracleRunSession("ml-timeout", c, listOf(
        0L to OracleSessionStep.Start, 999L to OracleSessionStep.Tick, 1_000L to OracleSessionStep.Tick, 1_001L to OracleSessionStep.Tick,
    ))

    val d = GarminSession(GarminSessionConfig(oracleSessionHost(), firstPairing = false, sendsHostLifecycleEvents = true),
        GarminTransportKind.V1, 23)
    val direct = oracleCobs(oracleFr165DeviceInformation())
    out += oracleRunSession("v1-direct", d, listOf(
        0L to OracleSessionStep.Start,
        1L to OracleSessionStep.Foreground(false),
        10L to rx(direct.copyOfRange(0, 9)),
        11L to rx(direct.copyOfRange(9, direct.size)),
        20L to rx(oracleCobs(oracleFrame(5050, bytes(2, 0x09, 0x00)))),
        30L to rx(oracleCobs(oracleFrame(5052, oracleLE32(7)))),
    ), next = 1_801_386_000L, following = 1_793_523_600L + 31_536_000L)

    val e = GarminSession(GarminSessionConfig(oracleSessionHost(), firstPairing = false, sendsHostLifecycleEvents = false),
        GarminTransportKind.MULTI_LINK_V2, 64)
    val reliableHandle = 0x81
    val mlrInfo = GarminMlrHeader.of(reliableHandle, 0, 0).bytes + oracleCobs(oracleFr165DeviceInformation())
    out += oracleRunSession("mlr", e, listOf(
        0L to OracleSessionStep.Start,
        10L to rx(oracleControl(6, oracleLE16(0) + bytes(0))),
        20L to rx(oracleControl(1, oracleLE16(1) + bytes(0, reliableHandle, 1))),
        30L to rx(mlrInfo),
        50L to OracleSessionStep.Tick,
        1_040L to OracleSessionStep.Tick,
    ))

    val s = GarminTransportSelection
    val fr165 = listOf(
        GarminGattService("6A4E2800-667B-11E3-949A-0800200C9A66", listOf(
            "6A4E2810-667B-11E3-949A-0800200C9A66", "6A4E2811-667B-11E3-949A-0800200C9A66",
            "6A4E2820-667B-11E3-949A-0800200C9A66", "6A4E2821-667B-11E3-949A-0800200C9A66", "2803")),
        GarminGattService("6A4E8022-667B-11E3-949A-0800200C9A66", listOf(
            "6A4E4C80-667B-11E3-949A-0800200C9A66", "6A4ECD28-667B-11E3-949A-0800200C9A66")),
        GarminGattService("180D", listOf("2A37")),
    )
    val selections = listOf(
        "fr165" to fr165,
        "ml-second-pair" to listOf(GarminGattService(s.MULTI_LINK_SERVICE, listOf(
            s.MULTI_LINK_NOTIFY[0], s.MULTI_LINK_NOTIFY[2], s.MULTI_LINK_WRITE[1], s.MULTI_LINK_WRITE[2]))),
        "watch-apps-only" to listOf(fr165[1], fr165[2]),
        "v1" to listOf(GarminGattService(s.V1_SERVICE, listOf(s.V1_WRITE, s.V1_NOTIFY))),
        "v0" to listOf(GarminGattService(s.V0_SERVICE.uppercase(), listOf(s.V0_NOTIFY, s.V0_WRITE))),
        "v1-half" to listOf(GarminGattService(s.V1_SERVICE, listOf(s.V1_WRITE))),
        "empty" to emptyList(),
    )
    for ((name, services) in selections) {
        val pick = s.select(services)
        out += "select $name ${pick?.let { "${it.kind.label} ${it.notify.take(8)} ${it.write.take(8)}" } ?: "unsupported"} " +
            "found=${s.describe(services)}"
    }
    for ((name, data) in listOf("garmin" to bytes(0x87, 0x00, 1), "other" to bytes(0x4C, 0x00), "short" to bytes(0x87), "none" to null)) {
        out += "advert $name ${GarminAdvertisement.isGarmin(data)}"
    }
    return out
}
