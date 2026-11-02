import Foundation

// MARK: - Sharing the watch with Garmin Connect (#35)
//
// Every app on the phone talks to the watch over the same multi-link (ML) characteristic pair, each under
// its own client id. Observed on a Forerunner 165 (fw 29.05) with Garmin Connect on the same iPhone:
//   - The watch grants the GFDI service to ONE client at a time. A second REGISTER for GFDI is answered
//     with status 3 while the other app holds it.
//   - NOOP's CLOSE_ALL (sent under NOOP's client id) was answered and the REGISTER after it was still
//     refused: it did not release Garmin Connect's GFDI, consistent with CLOSE_ALL being scoped to the
//     sender's client id. NOOP keeps sending it on connect to clear its own stale handles.
//   - The refusals continued across NOOP relaunches after Garmin Connect's Bluetooth was reported off,
//     and stopped at the first connect after the phone's Bluetooth was toggled: a registration appears
//     to outlive its app while the link stays up.
//
// So NOOP never fights for GFDI. A refusal parks the session in `busy` (link kept, no handshake) and
// re-asks on a backoff, on foreground and on "Sync now". When the watch's answers to the other app show
// it released GFDI, NOOP asks again after a short grace. When the other app is refused GFDI while NOOP
// holds it, NOOP finishes any running sync and then closes its own GFDI handle so the other app can sync.
// The only ML frames involved are REGISTER, CLOSE_HANDLE of NOOP's own handle and CLOSE_ALL of NOOP's own
// client id, all reversible. NOOP never sends a frame under another client's id.

public enum GarminCoexistence {

    /// The REGISTER status the watch answered while Garmin Connect held GFDI.
    public static let inUseStatus: UInt8 = 3

    /// First re-ask after a refusal; doubles per refusal up to `retryCapMs`.
    public static let retryBaseMs: Int64 = 30_000
    public static let retryCapMs: Int64 = 600_000

    /// After the other app's GFDI handle (or all its handles) closed, or after the watch closed NOOP's own
    /// GFDI handle unasked: long enough for an app that is only restarting its session to register again.
    public static let releaseGraceMs: Int64 = 5_000

    /// After NOOP yields GFDI to another app: time for that app to run its sync before NOOP asks again.
    public static let yieldCooldownMs: Int64 = 120_000

    /// A foreground or "Sync now" re-ask is skipped when NOOP asked this recently.
    public static let manualRetryMinGapMs: Int64 = 5_000

    /// The delay before re-ask number `refusals` (1-based): 30 s, 60 s, 120 s, 240 s, 480 s, then 600 s.
    public static func retryDelayMs(refusals: Int) -> Int64 {
        let shift = min(max(0, refusals - 1), 10)
        return min(retryCapMs, retryBaseMs << Int64(shift))
    }

    /// Foreign data packets are logged at these counts per handle (first sight, then sparsely), so a
    /// watch re-sending to an app that stopped answering shows up without flooding the log.
    public static func logsForeignPacket(count: Int) -> Bool {
        count == 1 || count == 10 || count == 100 || count == 1_000 || count == 10_000
    }
}
