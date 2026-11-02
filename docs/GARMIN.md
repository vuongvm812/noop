# Garmin watches (experimental)

NOOP can pair a Garmin watch over the watch's own Bluetooth protocol (GFDI) and read its data on the
device: the watch's history files (sleep, heart rate, steps, stress, Body Battery, SpO2, respiration,
HRV) and live channels on demand. It is **experimental**: one model has been tested on hardware, and
several pieces are still being validated (listed below). Nothing leaves the device, no Garmin account is
used, and NOOP never pretends to be Garmin Connect.

The Broadcast Heart Rate fallback (the watch's standard heart-rate broadcast, `GarminBroadcast`) stays
available for any watch NOOP cannot pair this way.

Related documents: [GARMIN_BATTERY.md](GARMIN_BATTERY.md) (battery policy and measurement protocol),
[GARMIN_REALTIME_CHANNELS.md](GARMIN_REALTIME_CHANNELS.md) (the undecoded channels 8 / 10 / 13),
[ATTRIBUTION.md](../ATTRIBUTION.md) (where the protocol facts come from).

## Supported models and protocol variants

| Model | Firmware | Product | Transport | GFDI | History flow | Status |
|---|---|---|---|---|---|---|
| Forerunner 165 | 29.05 | 4432 | V2 multi-link | 151 | FileAccess (activities appear only there), with the legacy directory flush | Tested on macOS: pairing, handshake, history sync, live channels |

Any other Garmin watch is **untested**. NOOP picks the transport the watch offers, in this order:

1. **V2 multi-link** (service `6a4e2800`, first present `281n` / `282n` characteristic pair). Tested.
2. **V1** and **V0** (older GFDI characteristic pairs, each only inside its own service). Unit-tested
   only; no V1 / V0 watch has been seen.
3. Anything else: the wizard says **"This watch uses a protocol NOOP doesn't support yet. You can still
   use Broadcast Heart Rate."**, the link is dropped, no reconnect is scheduled, and one tap moves to the
   Broadcast Heart Rate steps.

History uses the legacy directory flow by default and FileAccess for the models listed in
`GarminSyncModelPolicy` (Forerunner 165 only today).

## Pairing

1. **Add a device → Garmin watch.**
2. On the watch: **Settings → Phone** (or **Connectivity**) **→ Pair Phone**. Any QR code the watch
   shows can be ignored; NOOP does not use it.
3. **Scan**, then tap the watch. A watch the OS already holds connected (a bonded watch often stops
   advertising) is listed as "Connected to this device".
4. The first time, the OS asks to pair: check that the PIN matches the watch and accept. The GFDI
   channel needs this encrypted, bonded link.
5. The wizard shows **Watch connected** with the model, firmware and protocol variant
   (for example "V2 multi-link · GFDI 151"), and the first history sync starts on its own.

Adding the watch makes it the active device (the coordinator runs one live source at a time). If the
watch has not answered after 45 s the wizard says so, shows the last error if there is one, and offers
"Keep waiting" or Broadcast Heart Rate; NOOP keeps reconnecting in the background either way.

Handshake on the Forerunner 165: 230–270 ms after the subscribe; ready about 1.5 s after a reconnect
and 2.6 s after Bluetooth comes back on.

## What NOOP shows

- **Devices / Data Sources:** a Garmin watch panel with the link state, battery (or "not reported by
  this watch"; see below), last sync and its summary, **Sync now**, the **Continuous live** toggle with
  its measured battery cost, and the Broadcast Heart Rate fallback note.
- **Live:** heart rate and R-R in the usual live console, plus Body Battery, respiration, steps, floors
  and an on-request SpO2 reading ("Measure SpO₂", 60 s).
- **Today, Sleep, Trends:** the watch's own resting heart rate, HRV, sleep stages, SpO2, respiration and
  steps, with Charge, Effort and Rest computed by NOOP from them. Effort from a Garmin day is labelled
  experimental until it has been compared with Garmin's own numbers.
- **Test Centre → Garmin** (shown when a Garmin watch is paired): the current connect (link, watch,
  firmware, protocol, MTU, handshake time, sessions, last failure), the radio counters, the realtime
  capture toggle for channels 8 / 10 / 13, and the sync-file list (every FIT file the watch listed at the
  last sync, marked ingested or not, against the files NOOP has stored). Per-message detail goes to the
  strap log while the Connection or Sources test mode is on.

## Coexistence with Garmin Connect

Tested only with Garmin Connect **not** connected (its phone was away). The multi-link registration
returned status 0 on every connect; the "already in use" answer has never been seen. Until simultaneous
use is measured:

- If NOOP reports a refused registration, close Garmin Connect or turn off Bluetooth on that phone, then
  reconnect.
- The watch keeps only today's monitoring files, which suggests Garmin Connect removes them after
  uploading. **If Garmin Connect syncs first, NOOP may never see those files.**

## Read-only guarantee

NOOP never changes anything on the watch. This is enforced by construction, not by convention:

- Every outbound byte is built by `GarminOutbound` (Swift) / its Kotlin twin, and the transport accepts
  nothing else. Delete, archive, set-file-flag, erase, FileAccess ModifyFlags / Delete, firmware and
  system events that reset or change the watch have **no case** in that type, so they cannot be sent.
- History sync only lists, downloads and acknowledges. What NOOP has already read is recorded in its own
  `garminSyncedFile` table, never by flagging files on the watch.
- The handshake answers with NOOP's own identity (never Garmin Connect's), advertises a minimal
  capability set (no notifications, weather, music or calendar), and refuses the watch's HTTP / weather /
  AGPS / OAuth proxy requests.
- Tests on both platforms decode every byte written in every fake-watch trace and check it against the
  allowlist.

## Battery results

The policy is in [GARMIN_BATTERY.md](GARMIN_BATTERY.md): the radio is idle by default, live channels open
only while someone is watching and close 30 s after the viewer leaves (at once on backgrounding), SpO2
opens only on an explicit request, and history sync runs in short batched bursts with a 15 min floor that
backs off after empty syncs.

Measured so far (Forerunner 165, macOS):

- Idle connection: about 200 bytes each way per session, all of it the handshake, and **no traffic** over
  a 312 s idle hold.
- Live channels: open within 0.04–0.3 s of the live screen opening, close 30.2–30.6 s after it closes and
  within 0.4–0.6 s of backgrounding; every close confirmed by the watch.

**Not measured yet:** the 24 h watch drain, connection mode (a) vs (b), the per-hour cost of continuous
live, the SpO2 cost, and the phone's energy use. Until they are, the Continuous live toggle says "not
measured yet" instead of showing a number.

## Known limits and what is still unverified

- **Battery level:** the Forerunner 165 has not sent a battery status message (5023) in any run;
  Gadgetbridge enables it through a request that is not on NOOP's allowlist. The panel says "not
  reported by this watch".
- **Sleep, overnight HRV and Rest / Charge on real data:** waiting for worn nights; only fixtures and
  synthetic profiles exercise them so far.
- **Beat intervals from files (FIT 290) and skin temperature (397 / 398):** decoded as candidates only;
  not used. Live R-R (channel 12) is used.
- **Channels 8 / 10 / 13** (calories, intensity minutes, stress): Test Centre capture only, see
  [GARMIN_REALTIME_CHANNELS.md](GARMIN_REALTIME_CHANNELS.md).
- **Comparison with Garmin Connect** over several days, and **Garmin Connect still receiving every file**
  after NOOP synced: not done yet.
- **iOS and Android on hardware:** built and unit-tested; only macOS has run against a real watch.
- **MLR (reliable) mode, V1 / V0, the CONTINUE-from-offset resume:** fake-watch tests only. The
  Forerunner 165 restarts a file instead of continuing it, so a resumed transfer re-downloads from 0.
