import Foundation

/// EXPERIMENTAL Garmin support — the in-app "enable Broadcast Heart Rate" hint.
///
/// The FALLBACK path. A watch NOOP pairs over its own GFDI protocol runs `GarminSource` (`.garmin`). For a
/// model whose protocol NOOP does not speak, the watch can still broadcast the STANDARD Bluetooth Heart
/// Rate profile (0x180D / 0x2A37) once the user turns on "Broadcast Heart Rate", and that reaches the
/// EXISTING generic-HR path (`StandardHRSource`).
///
/// This type carries the user-facing hint to enable Broadcast HR, which makes the watch discoverable
/// on 0x180D. Advertised-name recognition lives in `ExperimentalBrand`.
/// Such a device is registered with `sourceKind: .liveBLE`, so the SourceCoordinator runs it through
/// `StandardHRSource` and the WHOOP/standard paths are untouched.
public enum GarminBroadcast {

    /// Step-by-step guidance to put a Garmin watch into Broadcast Heart Rate mode so NOOP (and any other
    /// standard-HR app) can read it. Human, US-neutral, no em-dashes. The exact menu path varies a little
    /// by model, so we keep it general and accurate.
    public static let broadcastHint: [String] = [
        "On your Garmin watch, press and hold the menu button (or open the controls menu).",
        "Find Heart Rate or Sensors, then turn on Broadcast Heart Rate.",
        "While it's broadcasting, your watch shows up here as a regular heart-rate strap.",
        "Keep the watch awake and not connected to another app, then scan.",
    ]
}
