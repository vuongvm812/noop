# Garmin battery policy and measurements

Garmin support is experimental and is being built in layers under the Garmin parent issue. This page
records the battery policy every Garmin layer follows, how that policy is measured, and the results.
Keep it current when a measurement lands or a constant changes.

Code: `Strand/BLE/GarminRadioPolicy.swift` and its Kotlin twin
`android/app/src/main/java/com/noop/ble/GarminRadioPolicy.kt`. Tests: `StrandTests/GarminRadioPolicyTests.swift`
and `android/app/src/test/java/com/noop/ble/GarminRadioPolicyTest.kt`. The Kotlin test pins the whole
decision space against stdout from the Swift implementation compiled standalone, so the platforms
cannot drift apart unnoticed.

## Principle

A Garmin watch records around the clock at its own optimized cadence. NOOP never has to stream to
capture data, so:

- **Radio-idle is the default state.**
- **Live channels are a viewer.** They are open only while someone is watching.
- **History sync is the recorder.** It runs in short batched bursts.

## Policy

### Live channels

Ask `GarminRadioPolicy.desiredChannels`, then apply the auto-close with `GarminLiveChannelLinger`.
Open what the result adds and close what it drops.

| Rule | Value |
|---|---|
| Live demand (app in the foreground) | live screen visible, a NOOP workout active, or "continuous live" on and the user not asleep |
| Live demand (app in the background) | an active NOOP workout only |
| Default channel set | HR (6) + RR (12) |
| Added only while their UI is on screen, foreground only | steps 7, stress 8, floors 9, calories 10, intensity 13, Body Battery 20, respiration 21 |
| Never automatic | accelerometer 16, SpO2 19: explicit foreground request only, no linger, never during a background workout |
| Auto-close | 30 s after the viewer leaves (`liveLingerSeconds`), counted from the input change that ended demand |
| Backgrounding | closes everything at once, except HR + RR for an active workout |
| Asleep | "continuous live" stops; sleep comes from the history files. Someone who opens the live screen at night is awake, so that still opens HR + RR. |

### History sync

`GarminRadioPolicy.shouldSync(trigger:now:lastSyncAt:emptyStreak:)`. Same backoff curve as
`BackfillPolicy`.

| Trigger | Gate |
|---|---|
| `manual` ("Sync now") | always runs |
| `connect`, `foreground` | 90 s event floor, never backs off |
| `watch` (5037 / 5009 / ItemAdded) | 90 s × backoff |
| `periodic` | 900 s (15 min) × backoff. In mode (b) this also gates the reconnect. |

Backoff is ×1 below 3 consecutive empty syncs, then ×2 after 3 and ×4 (the 1 h periodic cap) from 4
on. The first sync that downloads a file resets the streak to 0. One session drains every pending
file. NOOP requests MTU 515 (`requestedMTU`) and compression (`requestsCompression`).

### Connection

| Rule | Value |
|---|---|
| Discovery | a known watch connects by stored identifier; scanning only for first pairing with the app open; never in the background |
| Android connection priority | LOW_POWER idle, BALANCED live, HIGH only during file transfer |
| Capability bitmap | minimal (sync, file transfer, realtime), so the watch does not push notification, weather or music traffic |
| Proxy requests | answered at once with "unsupported" |
| HOST_DID_ENTER_BACKGROUND / FOREGROUND | sent for now (`sendsHostLifecycleEvents`); kept only if measurement shows a benefit |
| Link mode | (a) persistent idle connection, as Garmin Connect does, or (b) connect-on-sync, disconnecting after the drain unless a live channel is open. `defaultConnectionMode` is **provisionally (a)** until measured. |

### Local telemetry

`GarminRadioTelemetry` keeps per-session counters for the Test Centre log and never sends them
anywhere:

```
garmin radio: connected=130s bytes_in=4096 bytes_out=127 syncs=2 handles=6:1x/90s,12:2x/60s
```

`handles` lists each ML service id as `id:opens x/open seconds`. Sync sessions per day come from the
`syncs` count over the day's log lines.

## Measurement protocol

Use one watch with the same watch settings throughout (watch face, backlight, all-day SpO2 setting,
notifications, firmware). Charge it to 100 %, unplug it, and record the battery % shown on the watch at
the start and after 24 h. Run each scenario three times and report the mean and the range.

| # | Scenario | What it answers |
|---|---|---|
| 1 | Baseline: Garmin Connect only (and separately no phone) | the reference drain |
| 2a | NOOP idle, history sync only, mode (a) persistent link | idle cost of holding the link |
| 2b | NOOP idle, history sync only, mode (b) connect-on-sync | idle cost of reconnecting every 15 min |
| 3 | NOOP live HR + RR for 1 h, inside a 24 h run | per-hour live cost, shown next to the "continuous live" toggle |
| 4 | SpO2 channel open for 15 min | whether channel 19 powers the pulse-ox sensor |
| 5 | Scenario 2 with and without HOST_DID_ENTER_BACKGROUND / FOREGROUND | whether those events earn their place |

Phone energy, same 24 h windows:

- **Android:** `adb shell dumpsys batterystats --reset` at the start, then
  `adb shell dumpsys batterystats --charged com.noop` (use the installed package id) at the end. Compare
  NOOP with and without a Garmin source.
- **iOS:** the Xcode Energy Log (Instruments, Energy) for short runs; MetricKit `MXCPUMetric`,
  `MXNetworkTransferMetric` and `MXDisplayMetric` daily payloads for 24 h runs.

Before Bluetooth use, take the shared-watch lock (`shlock -f ~/.herdr/garmin-watch.lock -p $$`) and
remove it afterwards.

## Targets

- NOOP idle (2a or 2b) adds **no more watch drain than the Garmin Connect baseline**.
- The phone drain per day is reported for both platforms.
- The cheaper link mode ships as the default. If (a) and (b) differ by less than the run-to-run range,
  the user chooses.

## Results

**Not measured yet.** The policy and its tests land first, so that the connection, live and
history-sync layers implement against it. NOOP cannot hold a Garmin session until the connection
layer lands, and the Forerunner 165 smoke test showed the multi-link channel needs an OS-level bonded
link (the watch shows a PIN) before any of these runs can start. Until scenario 3 is measured,
`measuredLiveCostPercentPerHour` stays `nil` and the "continuous live" toggle must say the cost is not
yet measured instead of showing a guess.

| # | Watch, firmware | Run 1 | Run 2 | Run 3 | Mean | Notes |
|---|---|---|---|---|---|---|
| 1 | | | | | | |
| 2a | | | | | | |
| 2b | | | | | | |
| 3 | | | | | | per hour |
| 4 | | | | | | per 15 min |
| 5 | | | | | | |

| Phone | NOOP without Garmin | NOOP with Garmin, mode (a) | NOOP with Garmin, mode (b) |
|---|---|---|---|
| Android | | | |
| iOS | | | |

When results land, update `measuredLiveCostPercentPerHour`, `defaultConnectionMode` and
`sendsHostLifecycleEvents` on both platforms in the same change, and update this table.
