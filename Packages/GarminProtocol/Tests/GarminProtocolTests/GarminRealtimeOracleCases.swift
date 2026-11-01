#if !GARMIN_ORACLE_STANDALONE
@testable import GarminProtocol
#endif

// The realtime parity oracle: every channel payload decoder over its edge cases, then the live intake
// (plausibility window, the one-write-per-minute throttle, channel close and link loss). The same cases are
// re-implemented in android/.../com/noop/garmin/GarminRealtimeOracleCases.kt, and GarminRealtimeTest.kt holds
// this function's standalone stdout as its expected literal:
//
//   cd Packages/GarminProtocol && swiftc -O -D GARMIN_ORACLE_STANDALONE Sources/GarminProtocol/*.swift \
//       Tests/GarminProtocolTests/GarminOracleCases.swift Tests/GarminProtocolTests/GarminSessionOracleCases.swift \
//       Tests/GarminProtocolTests/GarminRealtimeOracleCases.swift Oracle/main.swift -o /tmp/garmin-oracle \
//       && /tmp/garmin-oracle realtime

func oracleDescribe(_ effect: GarminLiveIntakeEffect) -> String {
    let writes = effect.writes.map { "\($0.kind)@\($0.ts)=\($0.value)" }.joined(separator: ",")
    return "hr=\(oracleOpt(effect.heartRate)) rr=\(effect.rr.map(String.init).joined(separator: ",")) "
        + "writes=[\(writes)] changed=\(effect.snapshotChanged) clear=\(effect.clearHeartRate)"
}

func oracleDescribe(_ s: GarminLiveSnapshot) -> String {
    "resting=\(oracleOpt(s.restingHeartRate)) bb=\(oracleOpt(s.bodyBattery)) resp=\(oracleOpt(s.respirationBpm)) "
        + "spo2=\(oracleOpt(s.spo2Pct)) steps=\(oracleOpt(s.steps))/\(oracleOpt(s.stepGoal)) "
        + "floors=\(oracleOpt(s.floorsAscended))/\(oracleOpt(s.floorsDescended))/\(oracleOpt(s.floorsTarget))"
}

func garminRealtimeOracleLines() -> [String] {
    var out: [String] = []
    let cases: [(GarminService, [UInt8])] = [
        (.realtimeHeartRate, [3, 62, 55, 0xFF, 0xFF]),
        (.realtimeHeartRate, [0, 0, 0, 0xFF, 0xFF]),
        (.realtimeHeartRate, [2, 255, 255]),
        (.realtimeHeartRate, [3, 61]),
        (.realtimeHeartRate, [3]),
        (.realtimeHeartRate, []),
        (.realtimeHrv, [0xE8, 0x03, 4, 3, 2, 1]),
        (.realtimeHrv, [0x00, 0x00, 0, 0, 0, 0]),
        (.realtimeHrv, [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]),
        (.realtimeHrv, [0x52, 0x03]),
        (.realtimeHrv, [0x52, 0x03, 9, 9]),
        (.realtimeHrv, [0x52]),
        (.realtimeSteps, oracleLE32(4_321) + oracleLE32(7_500)),
        (.realtimeSteps, [0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0, 0xAA]),
        (.realtimeSteps, oracleLE32(1) + [0, 0, 0]),
        (.realtimeFloors, oracleLE16(3) + oracleLE16(1) + oracleLE16(10)),
        (.realtimeFloors, [0xFF, 0xFF, 0, 0, 0, 0]),
        (.realtimeFloors, oracleLE16(3) + oracleLE16(1)),
        (.realtimeSpo2, [97] + oracleLE32(1_162_481_386)),
        (.realtimeSpo2, [0xFF] + oracleLE32(1_162_481_386)),
        (.realtimeSpo2, [0x00, 1, 2, 3, 4]),
        (.realtimeSpo2, [101, 1, 2, 3, 4]),
        (.realtimeSpo2, [0x80, 1, 2, 3, 4]),
        (.realtimeSpo2, [100, 1, 2]),
        (.realtimeSpo2, []),
        (.realtimeBodyBattery, [0]),
        (.realtimeBodyBattery, [63]),
        (.realtimeBodyBattery, [100]),
        (.realtimeBodyBattery, [101]),
        (.realtimeBodyBattery, [0x7F]),
        (.realtimeBodyBattery, [0xFF, 7]),
        (.realtimeBodyBattery, []),
        (.realtimeRespiration, [14]),
        (.realtimeRespiration, [0xFE]),
        (.realtimeRespiration, [0xFF]),
        (.realtimeRespiration, [0x00]),
        (.realtimeRespiration, [0x7F]),
        (.realtimeRespiration, [0x80]),
        (.realtimeRespiration, []),
        (.realtimeStress, [1, 2, 3]),
        (.realtimeAccelerometer, Array(repeating: 0x11, count: 16)),
        (.gfdi, [1]),
    ]
    for (service, payload) in cases {
        let decoded = GarminRealtime.decode(service: service, payload: payload)
        out.append("decode \(service.rawValue) \(garminHex(payload)) -> \(decoded.map(GarminRealtime.describe) ?? "nil")")
    }
    out.append("decodable \(GarminRealtime.decodableServices.map(\.rawValue).sorted().map(String.init).joined(separator: ","))")

    // The intake: a minute of readings across two wall-clock minutes, then a close and a link loss.
    var intake = GarminLiveIntake()
    let base = 1_793_546_940  // a whole minute (divisible by 60)
    let steps: [(Int, GarminRealtimeReading?, GarminService?)] = [
        (base, .heartRate(bpm: 62, resting: 55, type: 3, trailing: [0xFF, 0xFF]), nil),
        (base, .heartRate(bpm: nil, resting: nil, type: 0, trailing: []), nil),
        (base + 1, .heartRate(bpm: 29, resting: 25, type: 3, trailing: []), nil),
        (base + 1, .heartRate(bpm: 221, resting: 221, type: 3, trailing: []), nil),
        (base + 1, .heartRate(bpm: 30, resting: 220, type: 3, trailing: []), nil),
        (base + 2, .rrInterval(ms: 1_000, unknown: 7), nil),
        (base + 2, .rrInterval(ms: nil, unknown: 7), nil),
        (base + 2, .rrInterval(ms: 65_535, unknown: nil), nil),
        (base + 3, .bodyBattery(63), nil),
        (base + 4, .bodyBattery(64), nil),
        (base + 59, .bodyBattery(64), nil),
        (base + 60, .bodyBattery(64), nil),
        (base + 61, .bodyBattery(nil), nil),
        (base + 62, .bodyBattery(65), nil),
        (base + 5, .respiration(breathsPerMinute: 14), nil),
        (base + 6, .respiration(breathsPerMinute: nil), nil),
        (base + 119, .respiration(breathsPerMinute: 15), nil),
        (base + 120, .respiration(breathsPerMinute: 15), nil),
        (base + 180, .respiration(breathsPerMinute: 127), nil),
        (base + 181, .respiration(breathsPerMinute: 3), nil),
        (base + 182, .respiration(breathsPerMinute: 4), nil),
        (base + 183, .respiration(breathsPerMinute: 60), nil),
        (base + 184, .respiration(breathsPerMinute: 61), nil),
        (base + 7, .spo2(percent: nil, garminTime: nil), nil),
        (base + 8, .spo2(percent: 97, garminTime: 1_162_481_386), nil),
        (base + 9, .spo2(percent: 96, garminTime: 1_162_481_387), nil),
        (base + 10, .steps(total: 4_321, goal: 7_500), nil),
        (base + 11, .steps(total: 4_321, goal: 7_500), nil),
        (base + 12, .floors(ascended: 3, descended: 1, ascendTarget: 10), nil),
        (base + 130, nil, .realtimeBodyBattery),
        (base + 131, .bodyBattery(66), nil),
        (base + 131, nil, .realtimeSteps),
        (base + 131, nil, .realtimeFloors),
        (base + 131, nil, .realtimeRespiration),
        (base + 131, nil, .realtimeSpo2),
        (base + 131, nil, .realtimeHeartRate),
        (base + 131, nil, .realtimeHrv),
        (base + 132, .heartRate(bpm: 70, resting: 54, type: 3, trailing: []), nil),
        (-61, .respiration(breathsPerMinute: 12), nil),
        (-60, .respiration(breathsPerMinute: 12), nil),
        (-1, .respiration(breathsPerMinute: 12), nil),
    ]
    for (ts, reading, closed) in steps {
        let effect: GarminLiveIntakeEffect
        let label: String
        if let reading {
            effect = intake.ingest(reading, unixSeconds: ts)
            label = GarminRealtime.describe(reading)
        } else {
            effect = intake.channelClosed(closed!)
            label = "close \(closed!.rawValue)"
        }
        out.append("intake @\(ts) \(label) -> \(oracleDescribe(effect)) | \(oracleDescribe(intake.snapshot))")
    }
    let down = intake.linkDown()
    out.append("intake link-down -> \(oracleDescribe(down)) | \(oracleDescribe(intake.snapshot))")
    let again = intake.linkDown()
    out.append("intake link-down -> \(oracleDescribe(again)) | \(oracleDescribe(intake.snapshot))")
    out.append("kinds \(GarminLiveIntake.kindBodyBattery),\(GarminLiveIntake.kindRespiration),\(GarminLiveIntake.kindSpo2) "
               + "source=\(GarminLiveIntake.metricSource) interval=\(GarminLiveIntake.metricWriteIntervalSeconds)")
    return out
}
