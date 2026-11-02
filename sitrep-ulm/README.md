# Sitrep Ulm (Android)

Dashboard app for Ulm/Neu-Ulm, laid out for the Fairphone 6 (works on any
Android 8+ phone). A foreground service runs a loopback-only NanoHTTPD server
that serves `assets/index.html` and answers its `/api/*` calls from
`SitrepApi.kt`.

## Features
- **Whether** (sic, by request) + 12-hour rain warning — Open-Meteo
- **Nearby departing transport** — live departures from the nearest DING stops
- **Best way home** — you set an address, pick a nearby station; shows the
  earliest-arriving connection (first departure, platform, arrival) and later options
- **Best way to work** — the same to a fixed stop from `local_config.json`
- **Medication** — daily doses with an exact alarm before each (Taken / Snooze;
  can be silenced in the 💊 editor so it only shows a quiet reminder),
  next dose on the lock screen
- Lock-screen summary notification; weather map tab (Windy)
- Location: automatic (Wi-Fi/cell, GPS only as a fallback) or manual (📍 button)

## Build

```
cp local_config.example.json app/src/main/assets/local_config.json   # optional, then edit
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
JDK 17, Android SDK 34 (`local.properties` → `sdk.dir`), Gradle 8.7.

## Notes
- Transit data from DING's EFA server (`www.ding.eu/ding3`, rapidJSON); it also
  covers much of Bavaria.
- Power use was measured and cut ~95% — see [POWER.md](POWER.md): no standing
  location requests, WebView paused off screen, screen-off refresh every 15 min
  via an exact alarm, slimmed API requests.
- `targetSdk` stays 34 (35 caps dataSync foreground services at 6 h/day).
- Location permission is "while using the app": after a reboot, open the app
  once. On Xiaomi/HyperOS the service also can't start itself without Autostart.
- `GET /api/test/tick?inS=20` (loopback) fires the next screen-off refresh early,
  for testing.
