import Foundation

/// The device a Today Refresh tap syncs (#93).
enum TodayRefreshSource: String, CaseIterable {
    case whoop, garmin, huawei, oura, none
}

/// What the control beside the wordmark shows (#93).
enum TodayRefreshState: String {
    /// Nothing: the active device has no sync to offer (a generic HR strap, an Apple Watch).
    case hidden
    /// Android's Rescan disc. Apple never resolves it: it passes `rescanOffered: false`.
    case rescan
    /// Refresh, tappable.
    case ready
    /// Refresh, spinning and not tappable: a sync (or, for a ring, a reconnect) is running.
    case syncing
    /// Refresh, greyed: a device that can sync is active but cannot take a request right now.
    case unavailable
}

/// One source's link, reduced to the three facts the resolver reads.
struct TodayRefreshLink: Equatable {
    let linkUp: Bool
    let canSync: Bool
    let syncing: Bool
}

/// The Today Refresh button (#93): which device a tap syncs, and what the control shows. Pure and
/// Foundation-only, so it compiles standalone as the oracle for the Kotlin twin
/// (`android/…/ui/TodayRefreshControl.kt`, pinned by `TodayRefreshControlTest.oracle_matchesSwiftTwin`).
enum TodayRefreshControl {

    /// WHOOP first, matching the registry's WHOOP-first default for an unresolvable active row; Garmin
    /// and Huawei by their live source existing, since the coordinator builds exactly one and only for
    /// the active device; Oura by the registry verdict. Apple has no Huawei source and passes false.
    static func source(activeIsWhoop: Bool, garminLive: Bool, huaweiLive: Bool,
                       ouraActive: Bool) -> TodayRefreshSource {
        if activeIsWhoop { return .whoop }
        if garminLive { return .garmin }
        if huaweiLive { return .huawei }
        if ouraActive { return .oura }
        return .none
    }

    /// `rescanOffered` is whether the slot would hold Android's Rescan disc. Apple has no Rescan disc
    /// beside the wordmark and passes false, so a device that is away shows a greyed Refresh.
    static func resolve(source: TodayRefreshSource, link: TodayRefreshLink,
                        rescanOffered: Bool) -> TodayRefreshState {
        if source != .none, link.linkUp {
            if link.syncing { return .syncing }
            return link.canSync ? .ready : .unavailable
        }
        if rescanOffered { return .rescan }
        return source == .none ? .hidden : .unavailable
    }

    /// WHOOP: the pull-to-sync gate (`LiquidTodayView.handlePull`, `HealthView.canSync`), so the button
    /// is offered exactly when `syncNow()` would run. Kotlin reads `todayPullToSyncEnabled`.
    static func whoop(connected: Bool, bonded: Bool, backfilling: Bool,
                      historyReady: Bool) -> TodayRefreshLink {
        TodayRefreshLink(linkUp: connected,
                         canSync: connected && bonded && !backfilling && historyReady,
                         syncing: backfilling)
    }

    /// Garmin: up once `.ready`, or `.busy` (another app holds GFDI; "Sync now" asks for it again).
    static func garmin(linkReady: Bool, syncing: Bool) -> TodayRefreshLink {
        TodayRefreshLink(linkUp: linkReady, canSync: linkReady && !syncing, syncing: syncing)
    }

    /// Huawei: up once ready. Android-only today; kept so the resolver's input space matches Kotlin.
    static func huawei(ready: Bool, syncing: Bool) -> TodayRefreshLink {
        TodayRefreshLink(linkUp: ready, canSync: ready && !syncing, syncing: syncing)
    }

    /// Oura has no history sync to request; Refresh reconnects the ring, which spins until it is
    /// authenticated again.
    static func oura(connecting: Bool, authenticated: Bool) -> TodayRefreshLink {
        TodayRefreshLink(linkUp: connecting || authenticated,
                         canSync: authenticated,
                         syncing: connecting && !authenticated)
    }
}
