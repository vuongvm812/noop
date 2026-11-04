# Huawei Band 10

> **Not verified on a Huawei Band 10.** Everything on this page is implemented and covered by JVM unit
> tests against a **simulated band** only. No part of it has been run against a real Huawei Band 10: the
> pairing handshake, the history sync and every decoded value remain unvalidated candidates until checked
> on real hardware. Treat this support as untested.

This fork pairs a Huawei Band 10 over the band's own Bluetooth protocol and reads its recorded history on
the phone: per-minute heart rate, steps, distance and energy, SpO2, resting heart rate, sleep (TruSleep
stages, or the older light / deep records), stress, HRV, skin temperature and workouts. It is shown as a
supported watch next to Garmin, with no Experimental badge. Nothing leaves the device, no Huawei account
login is used, and NOOP never pretends to be Huawei Health beyond the package names the band's own
services address.

**Android only.** The implementation is Kotlin (`android/app/src/main/java/com/noop/huawei/`,
`com/noop/ble/Huawei*`, `com/noop/ingest/HuaweiWatchIngest.kt`, `com/noop/data/HuaweiHistoryIngest.kt`).
The Swift twin for macOS and iOS is deferred in this fork, so the cross-platform parity rule in
[AGENTS.md](../AGENTS.md) is explicitly not met for this feature.

Related documents: [GARMIN.md](GARMIN.md) (the source this one is modelled on),
[ATTRIBUTION.md](../ATTRIBUTION.md) (where the protocol facts come from), [SCOPE.md](SCOPE.md).

## Pairing

1. **Add a device → Huawei Band 10.**
2. **Get your band ready.** In Huawei Health, turn off the app's Bluetooth access (or its Bluetooth
   permission) so it stops connecting to the band. Do not unpair the band in Huawei Health; just keep the
   app away from it while NOOP pairs.
3. **Account id (optional).** If the band is bound to a Huawei account, either factory reset it in Huawei
   Health first, or enter the 17-digit Huawei account id in the field on this step. The wizard accepts
   only exactly 17 digits; the id is stored with the band's key, at rest, and is never sent anywhere but
   to the band (see below).
4. **Pick your band.** The scan lists bands that advertise service FE86 or the band's name.
5. **Replace the band's pairing?** Pairing replaces the band's current pairing: the phone or app it is
   paired with now stops syncing with it. Tap **Pair** to continue.
6. **Confirm on your band.** Tap the check mark on the band within **30 s** (the wizard counts down). This
   step is the only place the first-pairing permission is created.
7. **Band paired.** The band becomes the active device and the first history sync starts on its own.

On failure the wizard says the band did not confirm the pairing and offers **Try again**. Reconnects
never pair: with no stored key, or when the band says it needs a fresh pairing, the source stops at
**Needs pairing** without sending anything, and **Pair again** on the band panel reopens this flow.

NOOP never asks Android to bond with the band; the band's own handshake is the security layer. Because
the Huawei source shares nothing with the WHOOP client, the WHOOP bond watchdog, the #982 never-bonded
detector and the bond-refusal give-up never see a Huawei connection.

## What NOOP shows

- **Devices / Data Sources:** a Huawei band panel under the active band's card with the link state
  (Not connected, Connecting, Authenticating, Connected, Needs pairing, Connection failed, Syncing),
  model, firmware, battery, the last sync time, sync progress and the last sync error, **Sync now** and
  **Pair again**.
- **Live:** a note that live heart rate is not available on this band and NOOP syncs its history instead.
- **Today, Sleep, Health, Stress, Trends, Workouts:** the band's history through the same tables a Garmin
  watch fills (see the metric matrix).
- **Test Centre → Huawei:** auth mode and version, cipher, slice size, MTU, capability bits, supported
  services, connect count and the last failure.

## Sync

A sync is one pass over every history stream the band says it supports. It runs:

- when the link reaches READY (after authentication);
- when the app comes to the foreground;
- on a periodic timer with a **15-minute floor**, stretched after empty syncs (`HuaweiRadioPolicy`: the
  floor doubles from the third empty sync in a row, capped at 4×, so one hour);
- on **Sync now** in the band panel (always runs).

Connect and foreground triggers share a 90 s floor. Only one sync runs at a time. The first sync of a band
reaches back 14 days; later syncs start from per-stream watermarks that move only after the batch has been
committed to the database, so a failed or partial sync is re-read next time. A failure in one stream is
recorded and does not stop the others. Every write to the database is keyed (UPSERT), so storing the same
batch twice leaves the same rows.

## Protocol summary

Facts re-derived from Gadgetbridge (see [Provenance](#provenance)); none of them checked on a real band.

- **Transport.** GATT service **FE86**: the phone writes to **FE01** and the band notifies on **FE02**.
  The Android radio connects over LE, requests a larger MTU, finds FE86 and enables FE02 notifications.
- **Framing (Link Protocol).** `5A | len u16 BE | 00 | sid | cid | body | CRC-16`, the CRC being
  CRC-16/XMODEM over everything from the magic byte. Long messages are sliced:
  `5A | len | flag | fragIdx | [sid | cid on the first slice] | chunk | CRC`, flags 1 first, 2 middle,
  3 last. The slice size starts at 0xF4, is replaced by the band's LinkParams value, and is **capped to the
  negotiated ATT MTU**. Bodies are TLV with VarInt lengths, except raw file blocks (2C/05, 0A/05).
- **Encryption.** After authentication, an encrypted message's TLV is replaced by the envelope
  `{7C: 01, 7D: IV, 7E: ciphertext}`. AES-GCM (128-bit tag) when the band asks for it (encryptMethod 1,
  or deviceSupportType 4), otherwise AES-CBC. The IV is 16 random bytes for deviceSupportType 4, otherwise
  12 random bytes and a 4-byte counter seeded from BondParams and incremented before every packet. A
  command marked encrypted is refused until a session cipher is installed.
- **Authentication.** LinkParams (01/01) tells the mode:
  - **normal** (deviceSupportType 0): Auth 01/13 → BondParams 01/0F → Bond 01/0E on first pairing;
  - **HiChain / HiChain3** (via SecurityNegotiation 01/33): first pairing PinCode 01/2C → HiChain 01/28
    operation 1 (authenticate) → operation 2 (bind); a reconnect runs operation 2 only. HiChain3 uses
    16-byte random IVs and GCM;
  - **HiChain Lite**: optional PinCode → Auth → BondParams → optional Bond.

  NOOP presents a random identity per band (a random 16-byte hex id and a random `FF:FF:FF:xx:xx:xx`
  address), never the phone's own identifiers. The session key or HiChain token is stored per band in
  Keystore-backed encrypted preferences and is deleted with the device. Honor PAKE / STS bands are not
  supported.
- **After authentication.** ProductInfo 01/07, then the clock is set (01/05), then Battery 01/08,
  SupportedServices 01/02, SupportedCommands 01/03, ExpandCapabilities 01/37 and SettingRelated 01/31;
  then, only when the user entered an account id, the account id is sent (0x1A). Battery pushes (01/27) update the panel.
- **Services used.** Device Config 0x01, FitnessData 0x07, Workout 0x17, file download 0x0A ("old sync")
  and 0x2C ("new sync"), AccountRelated 0x1A, P2P 0x34 (data-dictionary sync only). Each stream runs only
  when the band lists the service and command, and some only with an expand-capability bit
  (143 new sleep, 235 HRV, 0x1D skin temperature).

## Metric matrix

| Metric | Band source | Where stored | Screen |
|---|---|---|---|
| Heart rate (per minute) | FitnessData step data 07/0A count, 07/0B records | `hrSample` | Today, Health, Trends |
| Steps, distance, energy | 07/0B per-minute records, summed per local day | `dailyMetric` (`steps`, `activeKcalEst` as whole-day total), `metricSeries` (`steps`, `distance_m`, `total_kcal`) | Today, Trends |
| Resting heart rate | 07/0B records (latest of the day) | `dailyMetric.restingHr`, `metricSeries` `rhr` | Today, Health, Trends |
| SpO2 | 07/0B records | `deviceMetricSample` (`spo2_pct`); overnight mean through the shared night-vitals resolver | Health, Sleep |
| Sleep stages (TruSleep) | `sequence_data` file (dictionary 700013) over 0x2C, on bands with expand bit 143 | `sleepSession` with `stagesJSON`; main night onto `dailyMetric` and `metricSeries` `sleep_*` | Sleep, Today |
| Sleep (TruSleep time frames) | `sleep_state.bin` file, on bands **without** expand bit 143 | `sleepSession` as unstaged sleep ranges, only where neither a `sequence_data` night nor a legacy record covers the night | Sleep |
| Sleep (legacy light / deep) | 07/0C count, 07/0D records | `sleepSession` (light / deep with gaps as wake), where no TruSleep night covers them | Sleep |
| Band sleep score | `sequence_data` summary | `deviceMetricSample` (`sleep_score`), `metricSeries` `ref_sleep_score` (reference) | Sleep |
| Stress | `rrisqi_data.bin` file (0x2C or 0x0A), on bands with 20/09 | `deviceMetricSample` (`stress`); day average in `metricSeries` `huawei_stress` | Stress card, Stress |
| HRV (RMSSD) | P2P data dictionary, class 500044, on bands with expand bit 235 | `deviceMetricSample` (`hrv_ms`). The night's average HRV comes from the `sequence_data` summary instead and lands on `sleepSession.avgHrv`, `dailyMetric.avgHrv` and `metricSeries` `hrv` | Health, Sleep, Trends |
| Skin temperature | P2P data dictionary, class 400012, on bands with expand bit 0x1D | `deviceMetricSample` (`skin_temp_c`) | Health |
| Workouts | 17/07 list, 17/08 totals, 17/0A samples, GPS track file over 0x2C | `workout` (sport, duration, distance, energy, steps, avg / max HR, route), workout HR into `hrSample` | Workouts |
| Training Effect, training load | 17/08 workout totals | `deviceMetricSample` (aerobic / anaerobic Training Effect, training load) | Health / Explore |
| Workout pace | 17/0C pace blocks | **Fetched and decoded, not stored**: no existing workout column or series holds per-unit pace, and no schema migration was added for it | none |
| Battery | 01/08, push 01/27 | not stored (band panel only) | Devices |

Blank for a Huawei band, and why:

- **Respiration.** None of the streams NOOP reads carries a respiration rate.
- **Body Battery.** A Garmin metric with no Huawei counterpart.
- **Intensity minutes.** Not read by this sync.
- **Watch VO2max.** Not read by this sync.
- **Live heart rate.** No realtime heart-rate command is on the allowlist; the band is a history source
  only. Workout and per-minute heart rate arrive with the next sync.

NOOP's own scores (Charge, Effort, Rest) read the same tables a Garmin watch fills. Whether they behave
well on Huawei data has not been checked on real nights.

## Safety: what NOOP never sends

Every outbound message is a `HuaweiRequest`, which can only be built for an entry of the `HuaweiCommand`
allowlist (`HuaweiCommands.kt`); `HuaweiOutboundAllowlistTest` enumerates the list. There is no case
taking a caller-supplied service or command id. Deliberately absent, and not to be added
(BLE safety contract, [CONTRIBUTING.md](CONTRIBUTING.md)):

- factory reset (01/0D);
- OTA / firmware update (service 0x09);
- file upload (service 0x28);
- watchfaces (service 0x27);
- app install (service 0x2A);
- ephemeris / AGPS uploads (services 0x1F and 0x1C);
- debug or raw packets of any kind;
- any delete: nothing on the band is deleted, and no history is cleared after a sync.

## Writes NOOP does send, and why each is reversible

- **Measurement switches** (automatic HR 07/17, automatic SpO2 07/24, resting-HR 07/23): sent once per
  band, only those the band supports, and the request type rejects anything but "on". Each is a setting
  the user can turn off on the band. A switch the band refused is tried again on the next sync.
- **Set time** (01/05): the phone's Unix time and UTC offset, after every authentication. The next set,
  or the band's own settings, replace it.
- **Account id** (AccountRelated 0x1A): only when the user typed one in the wizard, and only as exactly 17
  digits. 1A/05 (extended) when the band supports 1A/05 and 1A/06, otherwise 1A/01. The request type
  refuses an empty or cleared account, so NOOP can never unbind the band from an account. The value is
  the user's own id; NOOP never sends any other.
- **File download control** (0x2C and 0x0A init, info, block requests and "done"): band → phone only. The
  "done" message ends the transfer; it does not delete the file.
- **P2P data dictionary** (34/01): ping, data request and acknowledgement, limited by the request type to
  the dictionary sync packages. The acknowledgement answers a band message; it changes no setting.
- **Pairing** (Bond 01/0E, HiChain operation 1): installs NOOP's key on the band, replacing its current
  pairing. Only possible with the first-pairing permission the wizard creates after the user confirms;
  undone by pairing the band again with Huawei Health (or a factory reset on the band).

## Provenance

Protocol facts were **re-derived** from [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)
(AGPL-3.0) at commit `6e4220682feb6917a5644304d9d7c760f30b6eaf`. NOOP copies none of its code; the files
consulted are listed in [ATTRIBUTION.md](../ATTRIBUTION.md#huawei-band-10-fork-android-only). Vendor
constants every band expects (digest secrets, bond mixing keys, HiChain group id and labels) are committed
verbatim, and Gadgetbridge's test vectors are used as expected literals in NOOP's tests, attributed there.

## Known limits

- **Not verified on a Huawei Band 10.** JVM tests against a simulated band only. Pairing, every auth mode,
  the file transfers and every decoded value are candidates until a real band confirms them.
- **Android only;** no macOS or iOS support.
- **Honor PAKE / STS** authentication is not implemented.
- **Workout pace** is decoded and dropped (no place to store it).
- **No live heart rate.**
- **Huawei Health must stay away from the band** while NOOP is paired; pairing replaces its pairing.
