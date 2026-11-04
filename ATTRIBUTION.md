# Attribution

NOOP is an independent, unofficial, local-first app for macOS, Android and iOS. It is not affiliated
with, endorsed by, or connected to WHOOP, Inc. "WHOOP" is used nominatively only to
identify the hardware the app interoperates with.

NOOP builds on prior community reverse-engineering and interoperability work:

## WHOOP 4.0 protocol + Swift packages
- **`johnmiddleton12/my-whoop`** — the `WhoopProtocol` and `WhoopStore` Swift packages
  (vendored under `Packages/`), the WHOOP 4.0 BLE framing/command/decode work, and the
  iOS collection logic that NOOP's `WhoopBLE`/`Collect` layers are adapted from.
  See `DISCLAIMER.md` (carried over from that project).

## WHOOP 5.0 / MG protocol
- **`b-nnett/goose`** — the WHOOP 5.0 BLE reverse-engineering (service UUID family
  `fd4b0001-…`, CRC16-Modbus header, CLIENT_HELLO, and the "puffin" packet types)
  that NOOP's `DeviceFamily` Whoop-5 path and `whoop5_protocol.json` are ported from.

## Xiaomi Smart Band (Mi Band) import
- **`artyomxx/xiaomi-band-ios-export`** — documented the Mi Fitness iOS app's on-device
  SQLite layout (`DataBase/<user_id>/de/<user_id>.db`, JSON `value` columns, the `*_day`
  rollups and the `sleep` table's `items[]` hypnogram with state codes). NOOP's
  `XiaomiBandImporter` is **re-derived** from those findings and verified against a real
  Mi Band 10 export; **no code is copied** (the reference tool is AGPL, NOOP is not).
- **Gadgetbridge** (`Freeyourgadget/Gadgetbridge`) — referenced only for *protocol facts*
  about the live Mi-protobuf BLE stack in the roadmap's research notes. GPLv3; NOOP copies
  **none** of its code and has not built the live lane.

## Facts learned from a decompiled app

The facts-only doctrine above is usually applied to other open-source RE projects. It applies the same
way when a fact originates in a **decompile of the vendor's own app** — a case that recurs because
several third-party WHOOP projects are decompile-derived.

The line is between the fact and the expression of it:

- **A protocol fact may be re-derived** — a byte offset, field width, enum value, scale factor. It is
  attributed at the point of use, and it ships as an **unvalidated candidate**: decoded and logged,
  never backing a shipped metric, until independent captures from real hardware clear it.
- **The implementation may not be copied** — not verbatim, not transcribed. Nor may string literals,
  assets, or anything else that is authored expression rather than an observation about the wire.

This is long-standing practice, not a one-off. Worked examples already in the tree:

- **`spo2_candidate_82`** (`Interpreter.swift`) — WHOOP 5 v18 byte `@82` read as a strap-computed SpO₂
  percentage, attributed as *"a decompile-sourced decode (gen5.rs `spo2_pct`), reimplemented here as a
  protocol fact with attribution"*. A guard test stops it ever writing `spo2Pct`, and it is still a
  candidate because the cross-device evidence is split.
- **The R22 config opcodes** (`Whoop5Config.swift` / `.kt`) — `SET_FF_VALUE (0x78)` and the flag key
  names, corroborated against *Asherlc/dofek docs/whoop-ble-protocol.md (Android APK decompilation)*
  and validated byte-for-byte against a decrypted HCI capture.
- **The disputed battery opcode** (`Commands.swift`, `Enums.kt`) — a decompile reads
  `GET_EXTENDED_BATTERY_INFO` as 87 where our table says 98. Both readings are recorded and the
  question is settled by probing real firmware, not by picking a source.
- **The MG ECG ("Labrador") packet layouts** (`Whoop5Ecg.swift` / `.kt`, `docs/PROTOCOL.md` §9.1) —
  field order, widths and enum values for `FilteredLabradorPacket` / `RawLabradorPacket`, plus the
  `{revision, arg, padding}` command payload shape, re-derived from static analysis of the vendor's iOS
  client. The four command NUMBERS were already in our own `CommandNumber` table from the whoomp/goose
  work above. Most of it still ships as an **unvalidated candidate**: the decode backs no metric, and the
  fields nobody can attest (the wrist enum's raw values, the progress "timed out" sentinel, the ECG
  sample unit, the packet type byte) are carried raw rather than named. **One exception is now attested
  rather than inferred:** `ControlSignal`'s raw values were also read off the client's enum order, and a
  WHOOP MG (`WS50_r00`, fw `50.39.1.0`) contradicts that reading — `1` stops generation, `2` starts it,
  `0` is refused. The enum now carries the hardware values with the device and firmware named. A
  hardware-observed value is strictly stronger than the inference it replaces, but it is one device.
  The on-strap rhythm classifier's verdict is decoded as a byte and is never presented as a finding —
  NOOP is not a medical device.

And the line held from the other side:

- **`StrainTargetNotifier`** (both platforms) — *"CLEAN-ROOM: this reimplements the BEHAVIOUR only. The
  copy is NOOP's own — NOT WHOOP's decompiled strings."* Behaviour re-derived; the user-facing text
  written fresh.
- **The `LINK_VALID` handshake**, rejected in
  [`docs/BLE_REVERSE_ENGINEERING.md`](docs/BLE_REVERSE_ENGINEERING.md) — its reply payload is a
  literal string lifted from the app. Expression, not a fact about the wire, so it stays out
  regardless of whether the handshake turns out to be real.

## Oura ring (gen 3/4/5) protocol
NOOP's Oura code is **original clean-room** work. The local BLE source
(`Strand/BLE/OuraLiveSource.swift` + `android/.../ble/OuraLiveSource.kt`) and the JVM/Swift-pure
`OuraProtocol` package (`Packages/OuraProtocol/`, `android/.../com/noop/oura/`) were written from
**documented protocol facts only**, cited tersely in `docs/OURA_PROTOCOL.md`. The community
reverse-engineering resources below were consulted as **facts-only references** (byte layouts,
service/characteristic UUIDs, framing and auth shapes); **no RE source code is copied** into NOOP.
- **`open_ring`**: consulted for protocol facts only. Licensed **GPL-3.0**; NOOP copies none of
  its code and is not a derivative work of it.
- **`open_oura`**, **`ringverse`**, **`relue`**: consulted for protocol facts only. These carry
  **no license**, so NOOP treats them as reference documentation of observed behaviour only and
  copies no code from them.

NOOP reads only the ring's own decoded raw signals and its own open event tags, computes NOOP's
own Charge/Rest, and **never** reads or displays Oura's encrypted readiness or sleep scores. The
documented Oura file-import lane (`Packages/StrandImport/Sources/StrandImport/OuraExportParser.swift`)
remains available as a fallback.

## Garmin watch protocol (experimental)
NOOP's Garmin code (`Packages/GarminProtocol/`, `android/.../com/noop/garmin/`) is **original
clean-room** work written from **protocol facts only**: the multi-link control-frame and MLR header
layouts, the COBS variant with leading and trailing `0x00`, CRC-16/ARC, the GFDI message frame, message
ids and payload layouts, service numbers, capability bit positions, and the FileAccess protobuf field
numbers.
- **Gadgetbridge** (`Freeyourgadget/Gadgetbridge`, codeberg.org): consulted for those facts at commit
  `edbea98cbe1377001c0b8154ac20cb930e1db7f6` (2026-10-30), together with its Garmin protocol notes
  (gadgetbridge.org/internals/specifics/garmin-protocol/). Licensed **AGPL-3.0**; NOOP copies **none** of
  its code, tables, test fixtures or literals beyond the wire values themselves, and is not a derivative
  work of it. Every value remains an unvalidated candidate until checked on a real watch.
- **Garmin wellness FIT messages** (`GarminWellness.swift`, `GarminWellness.kt`): the global message
  and field numbers, base types, scales and enum values of the monitoring, stress, SpO2, sleep, HRV,
  respiration, beat-interval and skin-temperature messages were taken as facts from Gadgetbridge's
  `fit_profile.json` and FIT importer at the same commit. No code or fixtures were copied; NOOP's
  fixtures are written by its own test builders. Beat intervals (290) and skin temperature (397/398)
  are not imported upstream either, so they decode only into raw `*Candidate` fields.
- **Garmin history sync** (`GarminHistorySync.swift`, `GarminHistorySync.kt`): the legacy directory and
  DOWNLOAD_REQUEST / FILE_TRANSFER_DATA flow, the FileAccess list / pull flow with its file-transfer
  pipe start packet and leading acknowledgement, the FIT sub-type numbers and the "uid top 32 bits are
  the creation time" rule are Gadgetbridge facts at the same commit. The 5054 COMPRESSED_FILE_DATA
  layout (sequence, last flag, checkpoint, checkpoint CRC, zlib piece), the `FIT_TYPE_<n>` FileAccess
  names and the watch restarting a file on CONTINUE were OBSERVED on a Forerunner 165 (fw 29.05), not
  taken from Gadgetbridge, which names id 5054 only. The zlib inflater is written from RFC 1950/1951.
- **Garmin realtime channels** (`GarminRealtime.swift`, `GarminRealtime.kt`): the realtime service
  numbers and the payload layouts of heart rate (6), steps (7), floors (9), beat intervals (12), SpO2 (19),
  Body Battery (20) and respiration (21), with their "unknown" codes, were taken as facts from
  Gadgetbridge's `CommunicatorV2` realtime callbacks at the same commit. The heart-rate type byte and the
  u32 after each beat interval are undecoded upstream and stay logged-only candidates in NOOP. Checked on a
  Forerunner 165 (firmware 29.05): every layout's length and the unknown codes 0x7F (Body Battery), -2
  (respiration) and -1 (SpO2) as sent off the wrist.
- **Garmin pairing, onboarding and UI** (Add Device wizard, Devices / Data Sources panel, Live and Test
  Centre sections, [docs/GARMIN.md](docs/GARMIN.md)): written for NOOP. The pairing steps follow the
  watch's own "Pair Phone" menu; the supported-model table, coexistence guidance and battery figures come
  from NOOP's own runs on a Forerunner 165. Nothing from Gadgetbridge beyond the protocol facts above, at
  the same pinned commit.

## Huawei band protocol (fork, Android only)
NOOP's Huawei transport and authentication code (`android/.../com/noop/huawei/protocol/`,
`android/.../com/noop/huawei/auth/`) is **re-derived** Kotlin written from **protocol facts**: the Link
Protocol frame (`5A | len | 00/slice flag | sid | cid | TLV | CRC-16/XMODEM`) and its slicing, the TLV
VarInt, the `{7C, 7D, 7E}` encryption envelope and its AES-GCM/AES-CBC and IV-counter rules, the
LinkParams / SecurityNegotiation / PinCode / Auth / BondParams / Bond / HiChain message layouts and key
schedules, and the capability packet layouts (SupportedServices, SupportedCommands, ExpandCapabilities,
SettingRelated, ProductInfo, Battery).
- **Gadgetbridge** (`Freeyourgadget/Gadgetbridge`, codeberg.org): consulted for those facts at commit
  `6e4220682feb6917a5644304d9d7c760f30b6eaf`, chiefly `devices/huawei/HuaweiPacket.java`, `HuaweiTLV.java`,
  `HuaweiCrypto.java`, `packets/DeviceConfig.java`, `util/CryptoUtils.java`,
  `service/devices/huawei/HuaweiSupportProvider.java` and `requests/Get*Request.java`. Licensed
  **AGPL-3.0**; NOOP copies none of its code.
- **Vendor constants** taken from those Gadgetbridge files and committed verbatim because every band
  expects them: the digest secrets v1/v2/v3, the bond mixing keys `SECRET_KEY_1/2` (v1 and v2/3), the
  challenge/response message bytes, the HiChain `GROUP_ID`, and the HiChain strings (`com.huawei.health`,
  `com.huawei.devicegroupmanage`, `health_group_name`, the version strings, and the HKDF/AAD labels
  `hichain_iso_session_key`, `hichain_return_key`, `hichain_iso_exchange`, `hichain_iso_result`). They live
  in `HuaweiVendorConstants` (`HuaweiAuthCrypto.kt`).
- **Test vectors**: the expected byte literals in `HuaweiTlvTest`, `HuaweiFramingTest`,
  `HuaweiDeviceConfigTest`, `HuaweiAuthPacketsTest` and `GadgetbridgeHuaweiVectors` reproduce
  Gadgetbridge's `TestHuaweiPacket`, `TestHuaweiTLV`, `TestVarInt` and `packets/TestDeviceConfig` at the
  same commit, used as protocol test vectors with attribution. The HMAC, HKDF and PBKDF2 vectors are
  RFC 4231, RFC 5869 and RFC 7914; the GCM known answer is NIST test case 2.
- **Not validated on hardware.** Nothing here has been run against a Huawei Band 10; every value remains an
  unvalidated candidate until checked on a real band. Honor PAKE / STS framing is not implemented.

## Huawei Band 10 metric decoders (fork)
- **Gadgetbridge** (`Freeyourgadget/Gadgetbridge`, codeberg.org), consulted at commit
  `6e4220682feb6917a5644304d9d7c760f30b6eaf`, licensed **AGPL-3.0**. The Kotlin decoders under
  `android/app/src/main/java/com/noop/huawei/decode/` and the fold `HuaweiWatchIngest.kt` are
  **re-derived** from protocol facts only: the FitnessData step-data feature bitmaps and field widths and
  the legacy sleep record layout (`FitnessData.java`, `GetSleepDataRequest.java`); the sequence_data
  sleep file layout, its summary value ids and the 8-byte stage records (`HuaweiSequenceDataFileParser.java`,
  `HuaweiSequenceDataParserBase.java`, `HuaweiTrueSleepSequenceDataParser.java`, `HuaweiSleepSessionProvider.kt`);
  the `sleep_state.bin` records (`HuaweiTruSleepParser.java`); the `rrisqi_data.bin` stress layout
  (`HuaweiStressParser.java`); the P2P dictionary class / value ids 500044 / 500044831 (HRV RMSSD) and
  400012 / 400012430 (skin temperature) with their value ranges (`p2p/dictionarysync/*`, `HuaweiDictTypes.java`);
  the workout totals tags, data header, bitmap and inner-block widths and scales (`Workout.java`,
  `HuaweiWorkoutGbParser.java`); and the GPS file layout and its 6378245 m radius (`HuaweiGpsParser.java`).
  No code is copied. Byte vectors from Gadgetbridge's `TestFitnessData.java`, `TestWorkout.java` and
  `TestHuaweiTruSleepParser.java` are used as expected literals in NOOP's tests, attributed there. Every
  value is an unvalidated candidate: none of it has been checked on a Huawei Band 10.

## Huawei Band 10 history sync (fork)
- **Gadgetbridge** (`Freeyourgadget/Gadgetbridge`, codeberg.org), consulted at commit
  `6e4220682feb6917a5644304d9d7c760f30b6eaf`, licensed **AGPL-3.0**. `HuaweiHistorySync.kt`,
  `HuaweiFileDownload.kt`, `HuaweiSyncRunner.kt` and the new `HuaweiCommand` entries under
  `android/app/src/main/java/com/noop/huawei/` are **re-derived** from protocol facts only: the
  FitnessData count / data request layouts and the measurement-switch payloads (`packets/FitnessData.java`);
  the 0x2C and 0x0A file-download message layouts, file type bytes, block arithmetic and raw block layouts
  (`packets/FileDownloadService2C.java`, `FileDownloadService0A.java`, `HuaweiFileDownloadManager.java`);
  the workout count / totals / data / capability layouts and the range-split retry (`packets/Workout.java`,
  `HuaweiWorkoutSyncManager.java`); the P2P message layout, package names, fingerprints, ping / ack codes
  and the dictionary request, reply and cursor rules (`packets/P2P.java`, `p2p/HuaweiBaseP2PService.java`,
  `HuaweiP2PManager.java`, `p2p/HuaweiP2PDataDictionarySyncService.java`); the sync order and its
  capability gates and expand-capability bits (`HuaweiSupportProvider.java`, `HuaweiState.java`,
  `packets/DeviceConfig.java`); and the workout type codes mapped to sports (`HuaweiWorkoutGbParser.java`).
  No code is copied. None of it has been checked on a Huawei Band 10.

## Other
- **GRDB.swift** (`groue/GRDB.swift`) — SQLite persistence (via Swift Package Manager).
- **MarkdownUI** (`gonzalezreal/swift-markdown-ui`) — renders the AI Coach's Markdown
  replies (via Swift Package Manager).

NOOP contains no WHOOP proprietary code, binaries, firmware, logos, or assets, and
performs no DRM circumvention. It operates only with the user's own device and data.
NOOP is **not a medical device**; all metrics (HR, HRV, recovery, strain, sleep,
SpO₂, temperature) are approximations and not clinically validated.
