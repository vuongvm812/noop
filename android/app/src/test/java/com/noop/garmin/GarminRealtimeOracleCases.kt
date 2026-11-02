package com.noop.garmin

// Kotlin re-run of Packages/GarminProtocol/Tests/GarminProtocolTests/GarminRealtimeOracleCases.swift: every
// channel payload decoder over its edge cases, then the live intake. GarminRealtimeTest compares this
// against the Swift stdout.

internal fun oracleDescribe(effect: GarminLiveIntakeEffect): String {
    val writes = effect.writes.joinToString(",") { "${it.kind}@${it.ts}=${it.value}" }
    return "hr=${oracleOpt(effect.heartRate)} rr=${effect.rr.joinToString(",")} " +
        "writes=[$writes] changed=${effect.snapshotChanged} clear=${effect.clearHeartRate}"
}

internal fun oracleDescribe(s: GarminLiveSnapshot): String =
    "resting=${oracleOpt(s.restingHeartRate)} bb=${oracleOpt(s.bodyBattery)} resp=${oracleOpt(s.respirationBpm)} " +
        "spo2=${oracleOpt(s.spo2Pct)} steps=${oracleOpt(s.steps)}/${oracleOpt(s.stepGoal)} " +
        "floors=${oracleOpt(s.floorsAscended)}/${oracleOpt(s.floorsDescended)}/${oracleOpt(s.floorsTarget)}"

internal fun garminRealtimeOracleLines(): List<String> {
    val out = mutableListOf<String>()
    val hr = GarminService.REALTIME_HEART_RATE
    val hrv = GarminService.REALTIME_HRV
    val steps = GarminService.REALTIME_STEPS
    val floors = GarminService.REALTIME_FLOORS
    val spo2 = GarminService.REALTIME_SPO2
    val bb = GarminService.REALTIME_BODY_BATTERY
    val resp = GarminService.REALTIME_RESPIRATION
    val cases: List<Pair<GarminService, ByteArray>> = listOf(
        hr to bytes(3, 62, 55, 0xFF, 0xFF),
        hr to bytes(0, 0, 0, 0xFF, 0xFF),
        hr to bytes(2, 255, 255),
        hr to bytes(3, 61),
        hr to bytes(3),
        hr to bytes(),
        hrv to bytes(0xE8, 0x03, 4, 3, 2, 1),
        hrv to bytes(0x00, 0x00, 0, 0, 0, 0),
        hrv to bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF),
        hrv to bytes(0x52, 0x03),
        hrv to bytes(0x52, 0x03, 9, 9),
        hrv to bytes(0x52),
        steps to oracleLE32(4_321) + oracleLE32(7_500),
        steps to bytes(0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0, 0xAA),
        steps to oracleLE32(1) + bytes(0, 0, 0),
        floors to oracleLE16(3) + oracleLE16(1) + oracleLE16(10),
        floors to bytes(0xFF, 0xFF, 0, 0, 0, 0),
        floors to oracleLE16(3) + oracleLE16(1),
        spo2 to bytes(97) + oracleLE32(1_162_481_386L),
        spo2 to bytes(0xFF) + oracleLE32(1_162_481_386L),
        spo2 to bytes(0x00, 1, 2, 3, 4),
        spo2 to bytes(101, 1, 2, 3, 4),
        spo2 to bytes(0x80, 1, 2, 3, 4),
        spo2 to bytes(100, 1, 2),
        spo2 to bytes(),
        bb to bytes(0),
        bb to bytes(63),
        bb to bytes(100),
        bb to bytes(101),
        bb to bytes(0x7F),
        bb to bytes(0xFF, 7),
        bb to bytes(),
        resp to bytes(14),
        resp to bytes(0xFE),
        resp to bytes(0xFF),
        resp to bytes(0x00),
        resp to bytes(0x7F),
        resp to bytes(0x80),
        resp to bytes(),
        GarminService.REALTIME_STRESS to bytes(1, 2, 3),
        GarminService.REALTIME_ACCELEROMETER to ByteArray(16) { 0x11 },
        GarminService.GFDI to bytes(1),
    )
    for ((service, payload) in cases) {
        val decoded = GarminRealtime.decode(service, payload)
        out += "decode ${service.raw} ${garminHex(payload)} -> ${decoded?.let { GarminRealtime.describe(it) } ?: "nil"}"
    }
    out += "decodable ${GarminRealtime.DECODABLE_SERVICES.map { it.raw }.sorted().joinToString(",")}"

    val intake = GarminLiveIntake()
    val base = 1_793_546_940L
    fun h(bpm: Int?, resting: Int?, type: Int, trailing: ByteArray) = GarminRealtimeReading.HeartRate(bpm, resting, type, trailing)
    val ff = bytes(0xFF, 0xFF)
    val none = bytes()
    val steps2: List<Triple<Long, GarminRealtimeReading?, GarminService?>> = listOf(
        Triple(base, h(62, 55, 3, ff), null),
        Triple(base, h(null, null, 0, none), null),
        Triple(base + 1, h(29, 25, 3, none), null),
        Triple(base + 1, h(221, 221, 3, none), null),
        Triple(base + 1, h(30, 220, 3, none), null),
        Triple(base + 2, GarminRealtimeReading.RrInterval(1_000, 7), null),
        Triple(base + 2, GarminRealtimeReading.RrInterval(null, 7), null),
        Triple(base + 2, GarminRealtimeReading.RrInterval(65_535, null), null),
        Triple(base + 3, GarminRealtimeReading.BodyBattery(63), null),
        Triple(base + 4, GarminRealtimeReading.BodyBattery(64), null),
        Triple(base + 59, GarminRealtimeReading.BodyBattery(64), null),
        Triple(base + 60, GarminRealtimeReading.BodyBattery(64), null),
        Triple(base + 61, GarminRealtimeReading.BodyBattery(null), null),
        Triple(base + 62, GarminRealtimeReading.BodyBattery(65), null),
        Triple(base + 5, GarminRealtimeReading.Respiration(14), null),
        Triple(base + 6, GarminRealtimeReading.Respiration(null), null),
        Triple(base + 119, GarminRealtimeReading.Respiration(15), null),
        Triple(base + 120, GarminRealtimeReading.Respiration(15), null),
        Triple(base + 180, GarminRealtimeReading.Respiration(127), null),
        Triple(base + 181, GarminRealtimeReading.Respiration(3), null),
        Triple(base + 182, GarminRealtimeReading.Respiration(4), null),
        Triple(base + 183, GarminRealtimeReading.Respiration(60), null),
        Triple(base + 184, GarminRealtimeReading.Respiration(61), null),
        Triple(base + 7, GarminRealtimeReading.Spo2(null, null), null),
        Triple(base + 8, GarminRealtimeReading.Spo2(97, 1_162_481_386L), null),
        Triple(base + 9, GarminRealtimeReading.Spo2(96, 1_162_481_387L), null),
        Triple(base + 10, GarminRealtimeReading.Steps(4_321, 7_500), null),
        Triple(base + 11, GarminRealtimeReading.Steps(4_321, 7_500), null),
        Triple(base + 12, GarminRealtimeReading.Floors(3, 1, 10), null),
        Triple(base + 130, null, bb),
        Triple(base + 131, GarminRealtimeReading.BodyBattery(66), null),
        Triple(base + 131, null, steps),
        Triple(base + 131, null, floors),
        Triple(base + 131, null, resp),
        Triple(base + 131, null, spo2),
        Triple(base + 131, null, hr),
        Triple(base + 131, null, hrv),
        Triple(base + 132, h(70, 54, 3, none), null),
        Triple(-61L, GarminRealtimeReading.Respiration(12), null),
        Triple(-60L, GarminRealtimeReading.Respiration(12), null),
        Triple(-1L, GarminRealtimeReading.Respiration(12), null),
    )
    for ((ts, reading, closed) in steps2) {
        val effect: GarminLiveIntakeEffect
        val label: String
        if (reading != null) {
            effect = intake.ingest(reading, ts)
            label = GarminRealtime.describe(reading)
        } else {
            effect = intake.channelClosed(closed!!)
            label = "close ${closed.raw}"
        }
        out += "intake @$ts $label -> ${oracleDescribe(effect)} | ${oracleDescribe(intake.snapshot)}"
    }
    val down = intake.linkDown()
    out += "intake link-down -> ${oracleDescribe(down)} | ${oracleDescribe(intake.snapshot)}"
    val again = intake.linkDown()
    out += "intake link-down -> ${oracleDescribe(again)} | ${oracleDescribe(intake.snapshot)}"
    out += "kinds ${GarminLiveIntake.KIND_BODY_BATTERY},${GarminLiveIntake.KIND_RESPIRATION},${GarminLiveIntake.KIND_SPO2} " +
        "source=${GarminLiveIntake.METRIC_SOURCE} interval=${GarminLiveIntake.METRIC_WRITE_INTERVAL_SECONDS}"
    return out
}
