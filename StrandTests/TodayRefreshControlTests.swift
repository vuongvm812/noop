import XCTest
@testable import Strand

/// #93: the Today Refresh button's resolver. Twin of the Kotlin `TodayRefreshControlTest`.
///
/// `testOracle_wholeInputSpace` pins the resolver over the WHOLE input space of every function to the
/// same literal the Kotlin test holds (this file's generator, run standalone with `swiftc`, produced it),
/// so a change on either side fails on that side.
final class TodayRefreshControlTests: XCTestCase {

    private func oracle() -> String {
        func b(_ x: Bool) -> String { x ? "1" : "0" }
        var out: [String] = []
        for a in [false, true] { for g in [false, true] { for h in [false, true] { for o in [false, true] {
            out.append("S\(b(a))\(b(g))\(b(h))\(b(o))=\(TodayRefreshControl.source(activeIsWhoop: a, garminLive: g, huaweiLive: h, ouraActive: o).rawValue)")
        }}}}
        for s in TodayRefreshSource.allCases { for l in [false, true] { for c in [false, true] { for y in [false, true] { for r in [false, true] {
            let st = TodayRefreshControl.resolve(source: s, link: TodayRefreshLink(linkUp: l, canSync: c, syncing: y), rescanOffered: r)
            out.append("R\(s.rawValue)\(b(l))\(b(c))\(b(y))\(b(r))=\(st.rawValue)")
        }}}}}
        func f(_ k: TodayRefreshLink) -> String { "\(b(k.linkUp))\(b(k.canSync))\(b(k.syncing))" }
        for c in [false, true] { for bo in [false, true] { for bf in [false, true] { for hr in [false, true] {
            out.append("W\(b(c))\(b(bo))\(b(bf))\(b(hr))=\(f(TodayRefreshControl.whoop(connected: c, bonded: bo, backfilling: bf, historyReady: hr)))")
        }}}}
        for x in [false, true] { for y in [false, true] {
            out.append("G\(b(x))\(b(y))=\(f(TodayRefreshControl.garmin(linkReady: x, syncing: y)))")
            out.append("H\(b(x))\(b(y))=\(f(TodayRefreshControl.huawei(ready: x, syncing: y)))")
            out.append("O\(b(x))\(b(y))=\(f(TodayRefreshControl.oura(connecting: x, authenticated: y)))")
        }}
        return out.joined(separator: " ")
    }

    func testOracle_wholeInputSpace() {
        let expected = [
            "S0000=none S0001=oura S0010=huawei S0011=huawei S0100=garmin S0101=garmin S0110=garmin S0111=garmin",
            "S1000=whoop S1001=whoop S1010=whoop S1011=whoop S1100=whoop S1101=whoop S1110=whoop S1111=whoop",
            "Rwhoop0000=unavailable Rwhoop0001=rescan Rwhoop0010=unavailable Rwhoop0011=rescan Rwhoop0100=unavailable Rwhoop0101=rescan Rwhoop0110=unavailable Rwhoop0111=rescan",
            "Rwhoop1000=unavailable Rwhoop1001=unavailable Rwhoop1010=syncing Rwhoop1011=syncing Rwhoop1100=ready Rwhoop1101=ready Rwhoop1110=syncing Rwhoop1111=syncing",
            "Rgarmin0000=unavailable Rgarmin0001=rescan Rgarmin0010=unavailable Rgarmin0011=rescan Rgarmin0100=unavailable Rgarmin0101=rescan Rgarmin0110=unavailable Rgarmin0111=rescan",
            "Rgarmin1000=unavailable Rgarmin1001=unavailable Rgarmin1010=syncing Rgarmin1011=syncing Rgarmin1100=ready Rgarmin1101=ready Rgarmin1110=syncing Rgarmin1111=syncing",
            "Rhuawei0000=unavailable Rhuawei0001=rescan Rhuawei0010=unavailable Rhuawei0011=rescan Rhuawei0100=unavailable Rhuawei0101=rescan Rhuawei0110=unavailable Rhuawei0111=rescan",
            "Rhuawei1000=unavailable Rhuawei1001=unavailable Rhuawei1010=syncing Rhuawei1011=syncing Rhuawei1100=ready Rhuawei1101=ready Rhuawei1110=syncing Rhuawei1111=syncing",
            "Roura0000=unavailable Roura0001=rescan Roura0010=unavailable Roura0011=rescan Roura0100=unavailable Roura0101=rescan Roura0110=unavailable Roura0111=rescan",
            "Roura1000=unavailable Roura1001=unavailable Roura1010=syncing Roura1011=syncing Roura1100=ready Roura1101=ready Roura1110=syncing Roura1111=syncing",
            "Rnone0000=hidden Rnone0001=rescan Rnone0010=hidden Rnone0011=rescan Rnone0100=hidden Rnone0101=rescan Rnone0110=hidden Rnone0111=rescan",
            "Rnone1000=hidden Rnone1001=rescan Rnone1010=hidden Rnone1011=rescan Rnone1100=hidden Rnone1101=rescan Rnone1110=hidden Rnone1111=rescan",
            "W0000=000 W0001=000 W0010=001 W0011=001 W0100=000 W0101=000 W0110=001 W0111=001",
            "W1000=100 W1001=100 W1010=101 W1011=101 W1100=100 W1101=110 W1110=101 W1111=101",
            "G00=000 H00=000 O00=000 G01=001 H01=001 O01=110 G10=110 H10=110",
            "O10=101 G11=101 H11=101 O11=110",
        ].joined(separator: " ")
        XCTAssertEqual(oracle(), expected)
    }

    func testWhoopNotHistoryReady_isGreyedNotTappable() {
        let link = TodayRefreshControl.whoop(connected: true, bonded: true, backfilling: false, historyReady: false)
        XCTAssertEqual(TodayRefreshControl.resolve(source: .whoop, link: link, rescanOffered: false), .unavailable)
    }

    /// Apple has no Rescan disc beside the wordmark: an away strap shows a greyed Refresh, never `.rescan`.
    func testAppleNeverResolvesRescan() {
        for source in TodayRefreshSource.allCases {
            for up in [false, true] {
                let link = TodayRefreshLink(linkUp: up, canSync: false, syncing: false)
                XCTAssertNotEqual(TodayRefreshControl.resolve(source: source, link: link, rescanOffered: false), .rescan)
            }
        }
    }

    func testGarminSyncingSpins() {
        let link = TodayRefreshControl.garmin(linkReady: true, syncing: true)
        XCTAssertEqual(TodayRefreshControl.resolve(source: .garmin, link: link, rescanOffered: false), .syncing)
    }

    func testOuraReconnectingSpins() {
        let link = TodayRefreshControl.oura(connecting: true, authenticated: false)
        XCTAssertEqual(TodayRefreshControl.resolve(source: .oura, link: link, rescanOffered: false), .syncing)
    }
}
