# Sitrep (Android, Munich)

Android port of the desktop Sitrep for Munich. A foreground service runs a
loopback-only NanoHTTPD server that serves `assets/index.html` and answers its
`/api/*` calls from `SitrepApi.kt`, so it works away from home Wi-Fi too.

## Features
- Weather + 12-hour rain warning (Open-Meteo), Windy map backdrop
- Live departures from the stops nearest your position (MVG)
- Route to your home stop (MVG journey planner) when you're away from it
- METAR summary for a configurable airport (AVWX, token needed)
- Prayer times (Aladhan, configurable calculation method)
- Nearby aircraft (OpenSky)
- Lock-screen summary notification

## Configuration
Copy `local_config.example.json` to `app/src/main/assets/local_config.json` and
set your home stop (MVG/DHID `globalId`, name, coordinates, lines worth showing),
airport ICAO code, AVWX token and prayer method. Without it the app uses neutral
defaults (Marienplatz, EDDM, no METAR).

## Build
JDK 17, Android SDK 34 (`local.properties` → `sdk.dir`), Gradle 8.7:
`gradle assembleDebug`.

## Power
Same measures as Sitrep Ulm (see `../sitrep-ulm/POWER.md`): no standing location
requests, WebView paused off screen, screen-on/15-min screen-off refresh with an
exact alarm.
