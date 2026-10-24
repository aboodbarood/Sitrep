# Power analysis — Sitrep Ulm (and the Munich build)

Measured 2026-09-28 on the test phone (Xiaomi 2602EPTC0G, HyperOS, Android 16,
MediaTek GNSS `MTK_MNLD`), over Wi-Fi. Numbers are the app's own counters from
`dumpsys batterystats --checkin` (per-uid: GPS on-time, bytes, CPU), taken as
before/after snapshots — HyperOS refuses `batterystats --reset` from adb.
`dumpsys battery unplug` made the phone count as on battery while on USB.

## Where the energy went (old build)

Ranked by measured size. "Per hour" = measured rate × 3600 s.

| # | Drain | Mechanism | Measured |
|---|---|---|---|
| 1 | **GPS** | `LocationTracker` kept `GPS_PROVIDER` registered permanently (8 min / 300 m) | **312 s on in 632 s (49%)** ≈ 30 min/h |
| 2 | **Page polling in background** | WebView JS timers keep running when the app leaves the screen, and the foreground service keeps the process alive, so the dashboard polled forever: departures every 30 s (3 boards), both routes every 2 min, meds every 60 s, clock + meds re-render every 1 s | most of the 590 KB / 10.5 min ≈ **3.4 MB/h** |
| 3 | **Lock-screen summary loop** | every 5 min, screen on or off: 1 trip plan + weather (+ 3 boards when at home) | ~62 KB per refresh |
| 4 | **Oversized responses** | trip plans carry path geometry the app never draws: 316 KB JSON (62 KB gzipped) per request; departure boards `limit=20` | 10 KB gz per board |
| 5 | Map tab | animated Windy wind map loaded at startup and kept alive while hidden | (startup data + CPU) |
| — | Negligible | medication alarms (3/day), idle NanoHTTPD thread, Geocoder per location change | — |

### Why "every 8 minutes" meant GPS on half the time
`requestLocationUpdates(GPS, 8 min, 300 m)` is a *standing* request, not a
ping. This phone's GNSS reports the `SCHEDULING` capability, so Android opens
one continuous GPS session and passes `mFixInterval=480000` to the MediaTek
firmware instead of duty-cycling the chip itself. Indoors (no fix), the chip
keeps searching — its most expensive state. The 300 m filter is applied after a
position is computed, so it doesn't save the chip any work. Evidence: GPS
switch-on count went 1 → 2 while on-time grew by 312 s (long sessions, not
pings), and the Munich app with the same code had held the GPS engine
**started for 10 h straight** (`mStarted=true … +10h8m`).

Caveat: both apps had GPS requests during the old-build run, and Android bills
GPS time to every app with an active request while the engine is on, so part of
the 49% may have been kept alive by the Munich app. Irrelevant to the result —
the new build has no standing GPS request at all.

## Follow-up: no open location request at all (2026-09-28, later)

Xiaomi's privacy indicator kept showing both apps as "accessing location".
`appops` showed why: `MONITOR_LOCATION … (running)` — the indicator counts any
open request, including the cheap standing Wi-Fi/cell registration that
replaced GPS — while `MONITOR_HIGH_POWER_LOCATION` (GPS) was *not* running. So
the fix was applied, but a permanently open request is still the wrong shape.

Now nothing is ever registered. Every position is a one-shot request released
as soon as it answers:

| When | What | GPS? |
|---|---|---|
| App opened | GPS + network together, first ≤50 m fix wins | yes, typically released in ms (network answers first) |
| Screen on: at screen-on, then every 5 min | Wi-Fi/cell quick fix if the last one is >5 min old, then summary refresh | only if Wi-Fi/cell is missing or worse than 150 m, max 20 s |
| Screen off: every 15 min (exact alarm — HyperOS held the inexact one back indefinitely: 0 ticks in 30 min) | same quick fix + summary refresh, under a ≤60 s wake lock | same fallback rule |
| Manual mode | only the 📍 buttons | yes (one-shot) |

Worst case for the fallback (GPS needed on every tick): 20 s per 15 min
≈ 32 min/day, vs ~12 h/day for the original standing GPS request. Verified on
the phone after install: both apps' standing registrations removed, no open
requests, `MONITOR_LOCATION` no longer running, the open-app fix released GPS
after 4–18 ms, and the tick alarms armed.

### Screen-off tick and no-Wi-Fi results
- Inexact `setAndAllowWhileIdle` tick: **0 ticks in 30 min** on HyperOS (alarms
  21 and 16 min past due, phone awake and charging, not in Doze) → switched to
  `setExactAndAllowWhileIdle` (USE_EXACT_ALARM, already held for medication).
- Exact tick, screen off, Wi-Fi **and** Wi-Fi scanning off (Munich build, via
  the `/api/test/tick?inS=20` hook): fired on the second (22:14:14 → 22:14:34),
  took a network-only fix (no GPS needed), lock screen updated.
- With Wi-Fi off but Wi-Fi scanning on, network positions still came from Wi-Fi
  scans (17 m accuracy) — turn scanning off too to test cell-only.
- Location stays "while in use" by the owner's choice. After a reboot the app
  must be opened once; on HyperOS the service doesn't even start before that
  ("process is not permitted to auto start" unless Autostart is enabled).
- Battery-optimisation exemption is requested once so Doze doesn't defer ticks.

## What changed

| Fix | Where | Effect |
|---|---|---|
| No standing GPS. Background = `NETWORK_PROVIDER` (Wi-Fi/cell) every 8 min; one GPS+network fix per app open, released as soon as a ≤50 m fix arrives | `LocationTracker` | GPS 49% → **0%** in background; the one-shot fix released GPS after 6–22 ms in tests |
| WebView paused off screen (`onPause` + `pauseTimers`), page catches up in `sitrepResume()` | `MainActivity`, page | all background polling gone |
| Summary refreshed only while the screen is on (every 5 min) + once on screen-on | `SitrepMonitorService` | no scheduled network use with the screen off |
| Trip plans `genC=0&genP=0`, 5 trips (was 6); boards `limit=12` (was 20) | `SitrepApi` | trip 62 → 27 KB on the wire, 316 → 202 KB to parse; board 10 → 6 KB. Every field the app reads verified identical |
| Map tab's wind map loaded only while that tab is open | page | no hidden animated map |
| Optional **Manual** location mode (⚙) | tracker, service, page | zero background location; 📍 button in app + on notification |

## Result (same conditions: app in background, screen on, ~10 min)

| per hour | old | new | change |
|---|---|---|---|
| GPS on-time | ~1780 s | **0 s** | −100% |
| Data received | 3.36 MB | 0.76 MB | **−77%** |
| CPU time | 12.4 s | 7.4 s | **−41%** |

Screen off (new build, 9 min): GPS 0 s, CPU 0.4 s, 93 KB — the screen came on
at least once during that run, and each screen-on triggers one ~45–50 KB
refresh by design. With the screen staying off there is no scheduled work.

### Energy estimate
Android's own estimate (`pwi`) for the app: 0.19 mAh per 10 min before, <0.05
after — but this ROM's power profile clearly under-counts GPS. Using typical
figures instead (GPS searching ≈ 40 mA incl. keeping the SoC awake, Wi-Fi
≈ 1 mAh per MB for small bursty transfers, CPU ≈ 0.04 mAh per busy second):

- old: 1780 s × 40 mA ≈ 19.8 mAh + 3.4 + 0.5 ≈ **24 mAh/h** (≈ 13% of the
  Fairphone 6's 4415 mAh per day if left running)
- new: 0 + 0.8 + 0.3 ≈ **1.1 mAh/h** with the screen on, ~0 with it off

That's roughly a **95% cut**. Even ignoring GPS completely (in case the Munich
app deserves that blame), network + CPU alone fall ~73% — past the 50% target
either way. On mobile data the old build would look worse still: polling every
30 s keeps the LTE radio from ever dropping back to idle.

## Not measured / limits
- Not measured on a Fairphone 6. Its GNSS (Qualcomm) may duty-cycle
  differently, but the new build no longer depends on that.
- The screen-off run wasn't perfectly clean (screen woke once).
- Estimates in mAh are modelled from measured on-times/bytes, not metered current.

## Munich build (`../sitrep-android`)
Same fixes ported (WebView pause, network-only background location + fix on
open, screen-on-only summary, lazy wind map), plus the boot crash fix:
`startForeground` without an explicit type claimed the `location` FGS type,
which Android 14+ refuses at boot (`SecurityException`, seen in the phone's
crash log on 2026-09-22). After the update the phone's GPS engine went from
started for 10 h to `mStarted=false`.
