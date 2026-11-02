package com.noop.ble

/**
 * EXPERIMENTAL Garmin support — the in-app "enable Broadcast Heart Rate" hint.
 *
 * Faithful Kotlin twin of Strand/BLE/GarminBroadcast.swift.
 *
 * The FALLBACK path. A watch NOOP pairs over its own GFDI protocol runs [GarminSource] (sourceKind
 * "garmin"). For a model whose protocol NOOP does not speak, the watch can still broadcast the STANDARD
 * Bluetooth Heart Rate profile (0x180D / 0x2A37) once the user turns on "Broadcast Heart Rate", and that
 * reaches the EXISTING generic-HR path ([StandardHrSource]).
 *
 * Such a device is registered with sourceKind "liveBLE", so the SourceCoordinator runs it through
 * [StandardHrSource] and the WHOOP/standard paths are untouched.
 *
 * The Add Device wizard reaches it from the Garmin flow: "Use Broadcast Heart Rate instead" on the first
 * step, and the "not supported yet" / "still connecting" faces when the GFDI handshake cannot complete
 * (docs/GARMIN.md).
 */
object GarminBroadcast {

    /**
     * Step-by-step guidance to put a Garmin watch into Broadcast Heart Rate mode so NOOP (and any other
     * standard-HR app) can read it. Human, US-neutral, no em-dashes. The exact menu path varies a little
     * by model, so we keep it general and accurate.
     */
    val broadcastHint: List<String> = listOf(
        "On your Garmin watch, press and hold the menu button (or open the controls menu).",
        "Find Heart Rate or Sensors, then turn on Broadcast Heart Rate.",
        "While it's broadcasting, your watch shows up here as a regular heart-rate strap.",
        "Keep the watch awake and not connected to another app, then scan.",
    )
}
