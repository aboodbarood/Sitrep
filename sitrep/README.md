# Sitrep (desktop)

The original Sitrep: `sitrep.py` serves a dashboard on `http://localhost:8000`
and proxies every upstream API server-side (browsers block the page from
calling them directly). Standard library only, plus `certifi` on older Pythons
whose CA bundle doesn't trust mvg.de.

```
pip install certifi
python3 sitrep.py          # then open http://localhost:8000
python3 diagnose.py        # probes every upstream and prints response shapes
```

## Configuration
Copy `local_config.example.json` to `local_config.json` (git-ignored) and set
your city, MVG stop query and `place` filter, lines, airport ICAO, AVWX token and
prayer method. The token can also come from the `AVWX_TOKEN` environment variable.

## Views
1. **Summary** — Windy map backdrop with draggable widgets: weather, rain warning,
   departures, METAR summary, prayer countdown, clock, nearby aircraft.
2. **Weather Map** — larger interactive Windy map.
3. **Traffic** — radar-style plot of nearby aircraft (OpenSky; ~100 requests/day
   anonymous quota, hence the 20-min refresh).

## Standalone .exe
`launcher.py` starts the server and opens Firefox in a dedicated, chrome-less
window; build with `pyinstaller --onefile --noconsole --name Sitrep
--hidden-import encodings.idna launcher.py`.
