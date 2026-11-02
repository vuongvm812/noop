# Garmin realtime channels 8 / 10 / 13: capture and candidate layouts

Garmin watches offer realtime multi-link services for **calories (8)**, **intensity minutes (10)** and
**stress (13)**. Gadgetbridge lists the service numbers but closes them on registration, so no public source
documents their payloads. NOOP captures them itself and writes down what the bytes appear to mean. Everything
below is a **candidate** until it has been compared against the watch screen (see "Promotion").

Code: `GarminRealtimeCapture` / `GarminRealtimeCandidates` in `Packages/GarminProtocol` (Swift) and
`com.noop.garmin` (Kotlin), with a byte-identical parity oracle (`GarminCaptureOracleCases`, `/tmp/garmin-oracle capture`).

## Capture mode

Test Centre → **Garmin realtime capture** (shown only when a Garmin watch is paired over GFDI, default off).
The channels are opened by the same live-channel table as every other live stream
(`GarminLiveChannelTable`), which admits 8 / 10 / 13 only while the session's capture flag is on
(`GarminSession.setUndecodedCapture`). Without it, a request for them opens nothing. While the toggle is on
and the app is in the foreground, the capture counts as one more viewer for `GarminRadioPolicy` (a live
screen with the calories / intensity / stress tiles), so HR (6) and R-R (12) open with 8 / 10 / 13. HR and
R-R feed the normal live path, as they would for the Live screen. Backgrounding closes everything. The
source writes one strap-log line per 8 / 10 / 13 payload:

```
garmin-rt ch=8 calories t=<unix ms> len=8 hr=<ch6 bpm|-> raw=<hex> u16=<…> u32=<…> i8=<…> | candidate calories total=… active=… kcal
```

`raw` is the exact payload; `u16` / `u32` are every little-endian reading; `hr` is the watch's own live HR from
channel 6 for context (`-` when the watch reports none, e.g. 0xFF off-wrist). Turning the toggle off sends
CLOSE_HANDLE for 8 / 10 / 13 at once; HR and R-R follow after the policy's 30 s linger unless another viewer
holds them. The watch confirms each close. Opening and closing a channel is read-only on the watch: it only
registers and releases a multi-link handle.

Once every undecoded channel has answered, one availability line is logged per connect:

```
garmin-rt availability model=Forerunner 165 fw=29.05 ch8=opened ch10=opened ch13=opened
```

## Per-model availability

| Model | Firmware | 8 calories | 10 intensity | 13 stress | Source |
|---|---|---|---|---|---|
| Forerunner 165 | 29.05 | opened | opened | opened | NOOP capture, 2026-11-01 (two connects, off-wrist) and 2026-11-02 (worn) |
| Forerunner 245 | 13.00 | listed | listed | listed | Gadgetbridge docs (service list only, no payloads) |

A model that refuses a channel answers REGISTER with a non-zero status; the line then shows `refused(<status>)`
and the source does not ask again on that connect.

## Candidate layouts (UNVALIDATED)

Captured 2026-11-01, 23:13–23:26 local, Forerunner 165 fw 29.05, **off-wrist on the charger**, two connects
about 10 minutes apart. Every message on a channel has had the same length so far; each decoder accepts only
that length.

### 8 calories: 8 bytes, `[a u32][b u32]`

| Time (s into capture) | a | b |
|---|---|---|
| 0 (connect 1) | 3077 | 993 |
| 2.4 | 3079 | 993 |
| 62 → 362, every ~60 s | 3080, 3082, 3083, 3085, 3086, 3088 | 993 |
| connect 2, 0 → 148 | 3092, 3094, 3095, 3097 | 993 |

- The watch pushes once on open and then about once a minute.
- `a` rises by +1 / +2 alternately each minute at rest, about 1.5 kcal/min (about 2,160 kcal/day) — a
  plausible resting burn added to a running daily total.
- `b` did not move with no activity.
- **Hypothesis:** `a` = today's total kcal (resting + active), `b` = today's active kcal. The Gadgetbridge
  guess `value, goal` (the steps layout) is unlikely, because `b < a` and `b` stays fixed while `a` grows.

### 10 intensity: 14 bytes, seven `u16`

Observed (identical on both connects): `172, 26, 73, 357, 0, 150, 0`.

- **Hypothesis:** word 0 = this week's intensity minutes, word 1 = moderate, word 2 = vigorous, word 5 = the
  weekly goal.
- Structural evidence: 172 = 26 + 2 × 73 (Garmin counts vigorous minutes twice), and 150 is Garmin's default
  weekly goal. The decoder reports `check=ok|MISMATCH` for that sum on every message, so a later message can
  disprove the hypothesis on its own.
- Words 3 (357), 4 and 6 are unknown and are carried raw.
- Sent once on open; no change was seen at rest.

### 13 stress: 4 bytes, `[level i16][u16]`

Off-wrist: only `fc ff 00 00` (level −4), sent once on open. Worn (day 1): 781 messages in 46 minutes.

- **Hypothesis:** 0–100 is Garmin's stress score; a negative value means "no reading". The meaning of each
  sentinel is unknown. Seen so far: −4 off-wrist, −3 for the first few seconds worn, −5 / −2 for about
  80 s mid-session.
- Worn, the watch pushes every ~2–5 s, much faster than the stress widget's history. The level and the
  second word update in alternate messages.
- The second word is 0 alongside a sentinel and 161–247 otherwise. Its meaning is unknown.
- i16 and i8 plus a pad byte read identically for every value seen (−5…96).
- Garmin's 0–100 scale is not NOOP's stress scale. It must never be shown beside NOOP's stress as if the two
  were one number.

## Worn validation, day 1 (2026-11-02 00:08–00:54 local, Forerunner 165 fw 29.05)

The user wore the watch and compared it with the capture readout, then reported (verbatim): **"every metrics
is the same as what you seen"**. That statement is recorded as given; no per-reading timestamps were noted.

| Ch | Values during the run | Exercised? |
|---|---|---|
| 13 stress | level 42–96, every ~2–5 s; sentinels −3 (start), −5 / −2 (≈00:29–00:30) | yes: moved and reported matching |
| 8 calories | total 11 → 82 (the day had just rolled over), active 0 → 2; active steps at 00:29 and 00:43 each coincide with a +3 total step | yes: moved and reported matching |
| 10 intensity | one message, `0, 0, 0, 0, 0, 150, 0` (Monday just after midnight; Sunday's capture read 172, consistent with a weekly reset) | **no**: the value never changed |

During the run the watch also sent bursts of protobuf "file access" requests (Smart field 43); the session
declined each one and the link stayed up.

## Promotion

Still needed: a **day 2** worn session for stress and calories, and a session where **intensity minutes
change** (at least 10 minutes in the moderate zone) for channel 10.

A candidate becomes a shipped value only when, **worn**, it matches the watch screen within ±1 across at least
two sessions where the value changed (AGENTS.md: "validate against the artifact, not one match"), ideally on a
second model too. Until then:

- the values appear only in the Test Centre capture lines;
- nothing is stored as a metric (`deviceMetricSample` is not written);
- nothing feeds analytics.

Recorded history keeps coming from FIT (227 stress, 55 monitoring). Models without a channel fall back to
NOOP's own HR/RR-derived values, labelled as NOOP-computed.

Validation steps on the watch, for each channel:

1. **Calories:** note the watch's calories widget (total and active) at the moment a `ch=8` line is logged. Walk
   briskly for 5 minutes, then compare again. Active should move; total should move by at least as much.
2. **Intensity:** note the weekly intensity-minutes widget (total, plus moderate/vigorous if shown). Raise HR
   into the moderate zone for at least 10 minutes. Word 0 should follow the total, and words 1 and 2 the split.
3. **Stress:** sit still for 5+ minutes until the stress widget shows a number, then compare with `level=`.
   Repeat after a short mental task (stress usually rises), then rest again.
