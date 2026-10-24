#!/usr/bin/env python3
"""
Sitrep diagnostics — probes every upstream and prints a compact report.

Run:  python3 diagnose.py
Then paste the whole output back into the chat.

Output is trimmed to stay paste-sized. It prints the SHAPE of responses
(field names, one sample record) which is exactly what's needed to fix parsing.
"""

import json
import ssl
import sys
import time
import urllib.parse
import urllib.request

try:
    import certifi
    SSL_CTX = ssl.create_default_context(cafile=certifi.where())
except ImportError:
    SSL_CTX = None  # falls back to the interpreter's own (possibly stale) CA bundle

# Same configuration as the dashboard (neutral defaults + git-ignored local_config.json).
from sitrep import AVWX_TOKEN, ICAO, LAT, LON, PRAYER_METHOD, TRANSIT_PLACE, TRANSIT_QUERIES
UA = {"User-Agent": "Mozilla/5.0 (sitrep diagnostics)"}


def probe(name, url, headers=None, show=None):
    """Fetch a URL, report status, and show a trimmed view of the result."""
    print(f"\n=== {name} ===")
    print(f"URL: {url[:110]}")
    try:
        req = urllib.request.Request(url, headers={**UA, **(headers or {})})
        t0 = time.time()
        with urllib.request.urlopen(req, timeout=20, context=SSL_CTX) as r:
            body = r.read().decode("utf-8")
            code = r.status
        ms = int((time.time() - t0) * 1000)
        print(f"STATUS: {code}  ({ms} ms, {len(body)} bytes)")
        try:
            data = json.loads(body)
        except Exception:
            print("NOT JSON. First 200 chars:")
            print(body[:200])
            return None
        if show:
            show(data)
        else:
            print(json.dumps(data, ensure_ascii=False)[:400])
        return data
    except Exception as e:
        print(f"FAILED: {type(e).__name__}: {e}")
        return None


print("python:", sys.version.split()[0])

# ---------------------------------------------------------------- weather
probe("OPEN-METEO (weather)",
      f"https://api.open-meteo.com/v1/forecast?latitude={LAT}&longitude={LON}"
      "&current=temperature_2m,precipitation,weather_code&timezone=auto",
      show=lambda d: print("current:", json.dumps(d.get("current"), ensure_ascii=False)))


# ---------------------------------------------------------------- transit
def show_locations(d):
    items = d if isinstance(d, list) else d.get("locations", [])
    print(f"{len(items)} results. Stations found:")
    for loc in items[:12]:
        if loc.get("type") == "STATION" or loc.get("globalId"):
            print(f"  globalId={loc.get('globalId')!r:28} name={loc.get('name')!r:32} "
                  f"place={loc.get('place')!r}")


QUERY = TRANSIT_QUERIES[0]
locs = probe(f"MVG locations (query={QUERY})",
             f"https://www.mvg.de/api/bgw-pt/v3/locations?query={urllib.parse.quote(QUERY)}",
             show=show_locations)

# Grab station IDs and pull departures from each
station_ids = []
if locs:
    items = locs if isinstance(locs, list) else locs.get("locations", [])
    for loc in items:
        gid = loc.get("globalId")
        if gid and loc.get("place") == TRANSIT_PLACE:
            station_ids.append((gid, loc.get("name")))

if not station_ids:
    print(f"\n!! No station IDs with place={TRANSIT_PLACE!r} resolved — departures probe skipped.")
else:
    for gid, name in station_ids[:3]:
        def show_deps(d):
            items = d if isinstance(d, list) else d.get("departures", [])
            print(f"{len(items)} departures.")
            if items:
                print("FIELD NAMES:", sorted(items[0].keys()))
                print("SAMPLE RECORD:")
                print(json.dumps(items[0], ensure_ascii=False, indent=2)[:600])
                labels = sorted({str(x.get('label')) for x in items})
                print("ALL LINE LABELS SEEN:", labels)

        probe(f"MVG departures — {name} ({gid})",
              f"https://www.mvg.de/api/bgw-pt/v3/departures?"
              f"globalId={urllib.parse.quote(gid)}&limit=40",
              show=show_deps)


# ---------------------------------------------------------------- METAR
def show_metar(d):
    print("FIELD NAMES:", sorted(d.keys())[:25])
    print("raw:", d.get("raw"))
    print("flight_rules:", d.get("flight_rules"))
    for k in ("wind_direction", "wind_speed", "visibility", "altimeter"):
        print(f"  {k}:", json.dumps(d.get(k), ensure_ascii=False)[:120])
    print("units:", json.dumps(d.get("units"), ensure_ascii=False)[:200])


probe(f"AVWX METAR ({ICAO})",
      f"https://avwx.rest/api/metar/{ICAO}?format=json",
      headers={"Authorization": AVWX_TOKEN},
      show=show_metar)

# ---------------------------------------------------------------- prayer
probe(f"ALADHAN prayer times (method {PRAYER_METHOD})",
      f"https://api.aladhan.com/v1/timings/{time.strftime('%d-%m-%Y')}"
      f"?latitude={LAT}&longitude={LON}&method={PRAYER_METHOD}",
      show=lambda d: print("timings:", json.dumps(d["data"]["timings"], ensure_ascii=False)))

# ---------------------------------------------------------------- flights
probe("OPENSKY flights",
      f"https://opensky-network.org/api/states/all?"
      f"lamin={LAT-0.7}&lomin={LON-0.98}&lamax={LAT+0.7}&lomax={LON+0.98}",
      show=lambda d: print("aircraft:", len(d.get("states") or []),
                           "| sample:", json.dumps((d.get("states") or [[]])[0])[:200]))

print("\n=== done — paste everything above ===")
