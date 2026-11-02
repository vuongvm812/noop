import XCTest
import GarminProtocol
import WhoopStore
@testable import Strand

/// The Garmin watch UI's pure pieces: the facts the wizard, Devices and Test Centre print, the panel's
/// status / summary / cost lines, the Devices capability profile, and the Live section's channel set.
/// Kotlin twin: `GarminWatchUiTest`.
final class GarminWatchUiTests: XCTestCase {

    func testFirmwareIsTheWatchValueOverAHundred() {
        XCTAssertEqual(GarminWatchFacts.firmware(2905), "29.05")
        XCTAssertEqual(GarminWatchFacts.firmware(1300), "13.00")
        XCTAssertEqual(GarminWatchFacts.firmware(7), "0.07")
    }

    func testProtocolLineBeforeAndAfterTheTransportIsChosen() {
        XCTAssertEqual(GarminWatchFacts.protocolLine(transport: nil, watch: nil), "-")
        XCTAssertEqual(GarminWatchFacts.protocolLine(transport: .multiLinkV2, watch: nil), "V2 multi-link")
        XCTAssertEqual(GarminWatchFacts.protocolLine(transport: .v0, watch: nil), "V0")
    }

    func testLiveCostSaysNotMeasuredUntilThereIsAMeasurement() {
        XCTAssertNil(GarminRadioPolicy.measuredLiveCostPercentPerHour,
                     "update this test together with the measured constant (docs/GARMIN_BATTERY.md)")
        XCTAssertEqual(GarminWatchFacts.liveCostText(), "not measured yet")
        // The decimal separator follows the locale ("1.2" or "1,2").
        let measured = GarminWatchFacts.liveCostText(1.25)
        XCTAssertTrue(measured.hasPrefix("about 1") && measured.hasSuffix("2% per hour"), measured)
    }

    func testStatusLinePrefersSyncingThenThePhase() {
        XCTAssertEqual(GarminWatchFacts.statusLine(phase: .ready, syncing: true), "Syncing history…")
        XCTAssertEqual(GarminWatchFacts.statusLine(phase: .ready, syncing: false), "Connected")
        XCTAssertEqual(GarminWatchFacts.statusLine(phase: .unsupported, syncing: false), "Protocol not supported")
        XCTAssertEqual(GarminWatchFacts.statusLine(phase: .disconnected, syncing: false), "Not connected")
        XCTAssertEqual(GarminWatchFacts.statusLine(phase: .busy, syncing: false), "Watch busy with another app")
        for phase: GarminSource.LinkPhase in [.connecting, .discovering, .subscribing, .handshaking] {
            XCTAssertEqual(GarminWatchFacts.statusLine(phase: phase, syncing: false), "Connecting…")
        }
    }

    /// "Sync now" stays available while another app holds the watch: there it asks the watch again.
    func testSyncNowIsOfferedWhenReadyOrBusy() {
        XCTAssertTrue(GarminWatchFacts.canSyncNow(phase: .ready, syncing: false))
        XCTAssertTrue(GarminWatchFacts.canSyncNow(phase: .busy, syncing: false))
        XCTAssertFalse(GarminWatchFacts.canSyncNow(phase: .ready, syncing: true))
        XCTAssertFalse(GarminWatchFacts.canSyncNow(phase: .handshaking, syncing: false))
        XCTAssertFalse(GarminWatchFacts.canSyncNow(phase: .disconnected, syncing: false))
    }

    func testSummaryLineCountsTheLastSync() {
        XCTAssertNil(GarminWatchFacts.summaryLine(nil))
        var s = GarminSyncSummary()
        s.wanted = 65; s.alreadySynced = 0; s.downloaded = 42; s.skippedOld = 23
        XCTAssertEqual(GarminWatchFacts.summaryLine(s), "42 new files, 0 already in NOOP, 65 on the watch")
    }

    func testGarminDeviceProfileIsReadOnlyWithNoExperimentalLabel() {
        let device = PairedDevice(id: "garmin-1", brand: "Garmin", model: "Forerunner 165", nickname: nil,
                                  peripheralId: "1", sourceKind: .garmin, capabilities: [.hr],
                                  status: .active, addedAt: 0, lastSeenAt: 0)
        let p = DeviceCapabilityProfile.make(for: device)
        XCTAssertEqual(p.displayModel, "Forerunner 165")
        XCTAssertTrue(p.captures.contains("Body Battery"))
        XCTAssertTrue(p.footnote.contains("Read-only"))
        for text in [p.displayModel, p.powers, p.footnote] {
            XCTAssertFalse(text.lowercased().contains("experimental"), text)
        }
        // A Broadcast Heart Rate Garmin (liveBLE) keeps the generic strap profile.
        let broadcast = PairedDevice(id: "garmin-2", brand: "Garmin", model: "Forerunner 165", nickname: nil,
                                     peripheralId: "2", sourceKind: .liveBLE, capabilities: [.hr, .hrv],
                                     status: .paired, addedAt: 0, lastSeenAt: 0)
        XCTAssertEqual(DeviceCapabilityProfile.make(for: broadcast).displayModel, "Heart-rate strap")
    }

    func testLiveSectionAsksOnlyForOnScreenChannels() {
        XCTAssertTrue(GarminLiveSection.cardChannels.isSubset(of: GarminRadioPolicy.onScreenOnlyChannels))
        XCTAssertFalse(GarminLiveSection.cardChannels.contains(.spo2), "SpO2 opens only on an explicit request")
        // With the Live screen visible, the cards add exactly their channels to HR + R-R.
        var inputs = GarminRadioInputs(appInForeground: true)
        inputs.liveScreenVisible = true
        inputs.onScreenChannels = GarminLiveSection.cardChannels
        XCTAssertEqual(GarminRadioPolicy.desiredChannels(inputs),
                       GarminRadioPolicy.defaultChannels.union(GarminLiveSection.cardChannels))
        inputs.appInForeground = false
        XCTAssertEqual(GarminRadioPolicy.desiredChannels(inputs), [])
    }
}
