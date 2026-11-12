import XCTest
import GarminProtocol
@testable import Strand

/// `GarminRadioPolicy`: the Garmin battery policy. Radio-idle by default, live channels only while
/// watched, history sync rate-limited. Pure value logic, no CoreBluetooth seam. The Kotlin twin
/// (`GarminRadioPolicyTest`) pins the same decisions against this implementation's oracle stdout.
final class GarminRadioPolicyTests: XCTestCase {
    private let all = Set(GarminLiveChannel.allCases)
    private let hrRr: Set<GarminLiveChannel> = [.heartRate, .rrIntervals]

    func testNoDemandMeansRadioIdle() {
        XCTAssertEqual(GarminRadioPolicy.desiredChannels(GarminRadioInputs(appInForeground: true)), [])
        XCTAssertEqual(GarminRadioPolicy.desiredChannels(GarminRadioInputs(appInForeground: false)), [])
    }

    func testLiveScreenOpensOnlyHrAndRrByDefault() {
        let inputs = GarminRadioInputs(appInForeground: true, liveScreenVisible: true)
        XCTAssertEqual(GarminRadioPolicy.desiredChannels(inputs), hrRr)
    }

    func testOptionalChannelsNeedTheirUiOnScreen() {
        let inputs = GarminRadioInputs(appInForeground: true, liveScreenVisible: true, onScreenChannels: [.bodyBattery])
        XCTAssertEqual(GarminRadioPolicy.desiredChannels(inputs), hrRr.union([.bodyBattery]))
    }

    func testSpO2AndAccelerometerNeverOpenAutomatically() {
        let auto = GarminRadioInputs(appInForeground: true, liveScreenVisible: true, workoutActive: true,
                                     continuousLiveEnabled: true, onScreenChannels: all)
        let desired = GarminRadioPolicy.desiredChannels(auto)
        XCTAssertFalse(desired.contains(.spo2))
        XCTAssertFalse(desired.contains(.accelerometer))
        var asked = auto
        asked.explicitChannels = [.spo2]
        XCTAssertTrue(GarminRadioPolicy.desiredChannels(asked).contains(.spo2))
    }

    func testBackgroundClosesEverythingExceptAWorkout() {
        var bg = GarminRadioInputs(appInForeground: false, liveScreenVisible: true, continuousLiveEnabled: true,
                                   onScreenChannels: all, explicitChannels: all)
        XCTAssertEqual(GarminRadioPolicy.desiredChannels(bg), [])
        bg.workoutActive = true
        XCTAssertEqual(GarminRadioPolicy.desiredChannels(bg), hrRr)
    }

    func testContinuousLiveStopsWhileAsleep() {
        var on = GarminRadioInputs(appInForeground: true, continuousLiveEnabled: true)
        XCTAssertEqual(GarminRadioPolicy.desiredChannels(on), hrRr)
        on.asleep = true
        XCTAssertEqual(GarminRadioPolicy.desiredChannels(on), [])
    }

    func testChannelsCloseThirtySecondsAfterTheViewerLeaves() {
        var linger = GarminLiveChannelLinger()
        let away = GarminRadioInputs(appInForeground: true)
        _ = linger.update(GarminRadioInputs(appInForeground: true, liveScreenVisible: true), now: 0)
        let left = linger.update(away, now: 100)
        XCTAssertEqual(left, GarminLiveHold(channels: hrRr, recheckAt: 130))
        XCTAssertEqual(linger.update(away, now: 129.9).channels, hrRr)
        XCTAssertEqual(linger.update(away, now: 130), GarminLiveHold(channels: [], recheckAt: nil))
    }

    func testBackgroundingSkipsTheLinger() {
        var linger = GarminLiveChannelLinger()
        _ = linger.update(GarminRadioInputs(appInForeground: true, liveScreenVisible: true), now: 0)
        XCTAssertEqual(linger.update(GarminRadioInputs(appInForeground: false), now: 1).channels, [])
    }

    func testExplicitSpO2ClosesImmediately() {
        var linger = GarminLiveChannelLinger()
        var asking = GarminRadioInputs(appInForeground: true, liveScreenVisible: true, explicitChannels: [.spo2])
        _ = linger.update(asking, now: 0)
        asking.explicitChannels = []
        XCTAssertEqual(linger.update(asking, now: 5).channels, hrRr)
    }

    func testSyncFloorAndEmptyBackoff() {
        XCTAssertFalse(GarminRadioPolicy.shouldSync(trigger: .periodic, now: 10_000, lastSyncAt: 10_000 - 899))
        XCTAssertTrue(GarminRadioPolicy.shouldSync(trigger: .periodic, now: 10_000, lastSyncAt: 10_000 - 900))
        // 3 empty syncs double the floor; the cap is 1 h.
        XCTAssertFalse(GarminRadioPolicy.shouldSync(trigger: .periodic, now: 10_000, lastSyncAt: 10_000 - 1799, emptyStreak: 3))
        XCTAssertTrue(GarminRadioPolicy.shouldSync(trigger: .periodic, now: 10_000, lastSyncAt: 10_000 - 1800, emptyStreak: 3))
        XCTAssertFalse(GarminRadioPolicy.shouldSync(trigger: .periodic, now: 10_000, lastSyncAt: 10_000 - 3599, emptyStreak: 50))
        XCTAssertTrue(GarminRadioPolicy.shouldSync(trigger: .periodic, now: 10_000, lastSyncAt: 10_000 - 3600, emptyStreak: 50))
        // Connect and foreground never back off; the user is never delayed.
        XCTAssertTrue(GarminRadioPolicy.shouldSync(trigger: .connect, now: 10_000, lastSyncAt: 10_000 - 90, emptyStreak: 50))
        XCTAssertTrue(GarminRadioPolicy.shouldSync(trigger: .manual, now: 1, lastSyncAt: 1, emptyStreak: 50))
    }

    func testGrownFileFloorPerTrigger() {
        XCTAssertEqual(GarminRadioPolicy.grownFileFloorSeconds(.manual), 0)
        XCTAssertEqual(GarminRadioPolicy.grownFileFloorSeconds(.foreground), 900)
        XCTAssertEqual(GarminRadioPolicy.grownFileFloorSeconds(.connect), 3_600)
        XCTAssertEqual(GarminRadioPolicy.grownFileFloorSeconds(.watch), 3_600)
        XCTAssertEqual(GarminRadioPolicy.grownFileFloorSeconds(.periodic), 3_600)
    }

    func testConnectionRules() {
        XCTAssertEqual(GarminRadioPolicy.connectionPriority(.fileTransfer), .high)
        XCTAssertEqual(GarminRadioPolicy.connectionPriority(.idle), .lowPower)
        XCTAssertEqual(GarminRadioPolicy.discoveryAction(appInForeground: false, hasStoredIdentifier: false), .none)
        XCTAssertEqual(GarminRadioPolicy.discoveryAction(appInForeground: false, hasStoredIdentifier: true), .connectByIdentifier)
        XCTAssertTrue(GarminRadioPolicy.shouldDisconnectAfterSync(mode: .connectOnSync, holdingLiveChannels: false))
        XCTAssertFalse(GarminRadioPolicy.shouldDisconnectAfterSync(mode: .connectOnSync, holdingLiveChannels: true))
        XCTAssertFalse(GarminRadioPolicy.shouldDisconnectAfterSync(mode: .persistentIdle, holdingLiveChannels: false))
        XCTAssertEqual(GarminRadioPolicy.requestedMTU, 515)
        XCTAssertNil(GarminRadioPolicy.measuredLiveCostPercentPerHour)
    }

    func testTelemetrySummary() {
        var t = GarminRadioTelemetry()
        XCTAssertEqual(t.summary(now: 0), "garmin radio: connected=0s bytes_in=0 bytes_out=0 syncs=0 handles=none")
        t.linkUp(at: 100)
        t.channelOpened(.heartRate, at: 110)
        t.channelOpened(.rrIntervals, at: 110)
        t.recordBytes(inbound: 4096, outbound: 120)
        t.channelClosed(.rrIntervals, at: 140.9)
        XCTAssertEqual(t.summary(now: 150.5),
                       "garmin radio: connected=50s bytes_in=4096 bytes_out=120 syncs=0 handles=6:1x/40s,12:1x/30s")
        t.syncSessionCompleted()
        t.linkDown(at: 200)  // closes the still-open HR handle too
        XCTAssertEqual(t.summary(now: 9999),
                       "garmin radio: connected=100s bytes_in=4096 bytes_out=120 syncs=1 handles=6:1x/90s,12:1x/30s")
    }

    /// Each policy channel is the protocol's ML service of the same meaning. Guards the 8 / 10 / 13 mix-up
    /// (calories 8, intensity 10, stress 13; Garmin 6/10) and any future renumbering on one side only.
    func testChannelNumbersMatchTheProtocolServices() {
        let pairs: [(GarminLiveChannel, GarminService)] = [
            (.heartRate, .realtimeHeartRate), (.steps, .realtimeSteps), (.calories, .realtimeCalories),
            (.floors, .realtimeFloors), (.intensity, .realtimeIntensity), (.rrIntervals, .realtimeHrv),
            (.stress, .realtimeStress), (.accelerometer, .realtimeAccelerometer), (.spo2, .realtimeSpo2),
            (.bodyBattery, .realtimeBodyBattery), (.respiration, .realtimeRespiration),
        ]
        XCTAssertEqual(pairs.count, GarminLiveChannel.allCases.count)
        for (channel, service) in pairs {
            XCTAssertEqual(UInt16(channel.rawValue), service.rawValue, "\(channel)")
            XCTAssertTrue(service.isRealtime, "\(service)")
        }
    }

    /// The Test Centre capture opens HR + 8 / 10 / 13 only through the policy: in the foreground with the
    /// capture view as the viewer, and nothing in the background.
    func testCaptureChannelsAreAllowedByThePolicyOnlyInTheForeground() {
        let capture = Set(GarminRealtimeCapture.captureChannels.map { Int($0.rawValue) })
        let on = GarminRadioInputs(appInForeground: true, liveScreenVisible: true,
                                   onScreenChannels: [.calories, .intensity, .stress])
        let allowed = Set(GarminRadioPolicy.desiredChannels(on).map(\.rawValue))
        XCTAssertTrue(capture.isSubset(of: allowed))
        var off = on
        off.appInForeground = false
        XCTAssertTrue(GarminRadioPolicy.desiredChannels(off).isEmpty)
    }
}
