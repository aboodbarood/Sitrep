#!/usr/bin/env python3
"""
Sitrep — personal environment monitor.

Run:    python3 sitrep.py
Then:   open http://localhost:8000

Needs `certifi` (pip install certifi) — older Python CA bundles don't trust
mvg.de's chain, which silently breaks transit. Falls back to the interpreter's
own bundle if certifi isn't installed. Otherwise stdlib only. No API keys
needed except the optional AVWX token for METAR (env AVWX_TOKEN or local_config.json).

Why a server instead of a plain HTML file: browsers block a page from
reading another site's data directly (CORS). A local server has no such
restriction, so it fetches upstream and hands clean JSON to the page.
"""

import json
import os
import re
import ssl
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn

try:
    import certifi
    SSL_CTX = ssl.create_default_context(cafile=certifi.where())
except ImportError:
    SSL_CTX = None  # falls back to the interpreter's own CA bundle

# ----------------------------------------------------------------------
# Config — neutral defaults here; personal values (your stop, lines, airport,
# API token) go in local_config.json next to this script, which is git-ignored.
# See local_config.example.json. The AVWX token can also come from the
# AVWX_TOKEN environment variable.
# ----------------------------------------------------------------------

def _load_local_config():
    base = os.path.dirname(sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__))
    try:
        with open(os.path.join(base, "local_config.json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}

_LOCAL = _load_local_config()

CITY_NAME = _LOCAL.get("city", "Munich")
LAT, LON = _LOCAL.get("lat", 48.1374), _LOCAL.get("lon", 11.5755)

# MVG `locations` query for the stop area to watch, and the `place` value to
# keep from its results. Filtering on `place` rather than on the stop name keeps
# every stop of a district, including ones whose own name differs from the query.
TRANSIT_QUERIES = _LOCAL.get("transit_queries", ["Marienplatz"])
TRANSIT_PLACE = _LOCAL.get("transit_place", "München")
STATION_LABEL = _LOCAL.get("station_label", TRANSIT_QUERIES[0])

# Only these lines are shown (case-insensitive, exact). Empty = all lines.
WANTED_LINES = {s.upper() for s in _LOCAL.get("lines", [])}

ICAO = _LOCAL.get("icao", "EDDM")
AVWX_TOKEN = os.environ.get("AVWX_TOKEN") or _LOCAL.get("avwx_token", "")

PRAYER_METHOD = _LOCAL.get("prayer_method", 3)  # Aladhan calculation method id
PORT = 8000

UA = {"User-Agent": "Mozilla/5.0 (sitrep personal dashboard)"}
_cache = {}


def get_json(url, headers=None, timeout=12, cache_s=0):
    """Fetch JSON with a small TTL cache so we stay polite to upstreams."""
    now = time.time()
    if cache_s and url in _cache:
        ts, val = _cache[url]
        if now - ts < cache_s:
            return val
    req = urllib.request.Request(url, headers={**UA, **(headers or {})})
    # Old/system CA bundles don't trust mvg.de's chain (confirmed on Python 3.6);
    # certifi's bundle does, and costs nothing for the sources that already worked.
    with urllib.request.urlopen(req, timeout=timeout, context=SSL_CTX) as r:
        val = json.loads(r.read().decode("utf-8"))
    if cache_s:
        _cache[url] = (now, val)
    return val


# ----------------------------------------------------------------------
# Data sources
# ----------------------------------------------------------------------

WMO = {
    0: "Clear", 1: "Mainly clear", 2: "Partly cloudy", 3: "Overcast", 45: "Fog",
    48: "Rime fog", 51: "Light drizzle", 53: "Drizzle", 55: "Dense drizzle",
    61: "Light rain", 63: "Rain", 65: "Heavy rain", 71: "Light snow", 73: "Snow",
    75: "Heavy snow", 80: "Light showers", 81: "Showers", 82: "Violent showers",
    85: "Snow showers", 86: "Heavy snow showers", 95: "Thunderstorm",
    96: "Thunderstorm, hail", 99: "Severe thunderstorm",
}


def api_weather():
    url = (
        f"https://api.open-meteo.com/v1/forecast?latitude={LAT}&longitude={LON}"
        "&current=temperature_2m,apparent_temperature,precipitation,weather_code,"
        "relative_humidity_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m,"
        "surface_pressure,cloud_cover"
        "&hourly=precipitation_probability,precipitation"
        "&forecast_days=2&timezone=auto"
    )
    data = get_json(url, cache_s=300)
    c = data["current"]
    h = data["hourly"]
    try:
        idx = h["time"].index(c["time"][:13] + ":00")
    except ValueError:
        idx = 0
    probs = h["precipitation_probability"][idx:idx + 12]
    mms = h["precipitation"][idx:idx + 12]
    times = h["time"][idx:idx + 12]
    onset = next((i for i, (p, m) in enumerate(zip(probs, mms)) if p >= 50 or m >= 0.1), None)
    return {
        "temp": round(c["temperature_2m"]),
        "feels": round(c["apparent_temperature"]),
        "precip": c["precipitation"],
        "desc": WMO.get(c["weather_code"], ""),
        "humidity": c["relative_humidity_2m"],
        "windSpeed": round(c["wind_speed_10m"]),
        "windDir": c["wind_direction_10m"],
        "windGust": round(c["wind_gusts_10m"]),
        "pressure": round(c["surface_pressure"]),
        "cloud": c["cloud_cover"],
        "rainExpected": onset is not None,
        "rainIn": onset,
        "rainMaxProb": max(probs) if probs else 0,
        "rainTotal": round(sum(mms), 1),
        "rainHours": [
            {"t": t[-5:], "prob": p, "mm": m}
            for t, p, m in zip(times, probs, mms)
        ],
    }


MVG_BASE = "https://www.mvg.de/api/bgw-pt/v3"


def _mvg_stations():
    """Resolve the configured stop names to MVG global station IDs."""
    ids = {}
    for q in TRANSIT_QUERIES:
        try:
            url = f"{MVG_BASE}/locations?query={urllib.parse.quote(q)}"
            for loc in get_json(url, cache_s=86400):
                if (loc.get("type") == "STATION" and loc.get("globalId")
                        and loc.get("place") == TRANSIT_PLACE):
                    ids[loc["globalId"]] = loc.get("name", "")
        except Exception:
            continue
    return ids


def api_transit():
    stations = _mvg_stations()
    if not stations:
        raise RuntimeError(f"no {STATION_LABEL} stops resolved")

    rows, errors = [], []
    for gid, name in stations.items():
        try:
            url = f"{MVG_BASE}/departures?globalId={urllib.parse.quote(gid)}&limit=40"
            for d in get_json(url, cache_s=30):
                label = str(d.get("label", "")).strip()
                if WANTED_LINES and label.upper() not in WANTED_LINES:
                    continue
                planned = d.get("plannedDepartureTime")
                real = d.get("realtimeDepartureTime") or planned
                if not real:
                    continue
                delay = d.get("delayInMinutes")
                if delay is None and planned and real:
                    delay = round((real - planned) / 60000)
                rows.append({
                    "line": label,
                    "dest": d.get("destination", ""),
                    "stop": name,
                    "at": real,
                    "delay": delay or 0,
                    "cancelled": bool(d.get("cancelled")),
                    "type": d.get("transportType", ""),
                })
        except Exception as e:
            errors.append(f"{name}: {e}")

    rows.sort(key=lambda r: r["at"])
    now_ms = time.time() * 1000
    rows = [r for r in rows if r["at"] > now_ms - 60000][:12]

    # One soonest departure per line, so each line you care about is visible.
    per_line, seen = [], set()
    for r in rows:
        if r["line"] not in seen:
            seen.add(r["line"])
            per_line.append(r)

    return {"next": rows[:6], "perLine": per_line, "stops": list(stations.values()),
            "errors": errors}


def api_metar():
    if not AVWX_TOKEN:
        raise RuntimeError("no AVWX token set")
    url = f"https://avwx.rest/api/metar/{ICAO}?format=json"
    m = get_json(url, headers={"Authorization": AVWX_TOKEN}, cache_s=600)
    u = m.get("units", {})

    def val(k):
        v = m.get(k) or {}
        return v.get("value")

    wd, ws = val("wind_direction"), val("wind_speed")
    wind = f"{str(wd).zfill(3)}/{ws}{u.get('wind_speed','')}" if wd is not None else "—"
    vis = f"{val('visibility')}{u.get('visibility','')}" if val("visibility") is not None else "—"
    qnh = f"{val('altimeter')}{u.get('altimeter','')}" if val("altimeter") is not None else "—"
    clouds = [c.get("repr") for c in (m.get("clouds") or []) if c.get("repr")]
    wx = [w.get("repr") if isinstance(w, dict) else str(w) for w in (m.get("wx_codes") or [])]
    return {
        "rules": m.get("flight_rules", "—"),
        "summary": f"wind {wind} · vis {vis} · QNH {qnh}",
        "raw": m.get("raw", ""),
        "temp": val("temperature"),
        "dewpoint": val("dewpoint"),
        "gust": val("wind_gust"),
        "clouds": clouds,
        "wx": wx,
        "remarks": m.get("remarks", ""),
    }


PRAYERS = ["Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"]


def api_prayer():
    def day(d):
        url = (f"https://api.aladhan.com/v1/timings/{d.strftime('%d-%m-%Y')}"
               f"?latitude={LAT}&longitude={LON}&method={PRAYER_METHOD}")
        return get_json(url, cache_s=3600)["data"]["timings"]

    today, tomorrow = datetime.now(), datetime.now() + timedelta(days=1)
    t1, t2 = day(today), day(tomorrow)

    out = []
    for base, times, names in ((today, t1, PRAYERS), (tomorrow, t2, ["Fajr"])):
        for n in names:
            m = re.match(r"(\d{1,2}):(\d{2})", times.get(n, ""))
            if m:
                at = base.replace(hour=int(m.group(1)), minute=int(m.group(2)),
                                  second=0, microsecond=0)
                out.append({"name": n, "at": int(at.timestamp() * 1000)})

    now_ms = time.time() * 1000
    upcoming = [p for p in out if p["at"] > now_ms]
    return {"all": out, "next": upcoming[0] if upcoming else None}


def api_flights():
    d = 0.7
    url = (f"https://opensky-network.org/api/states/all?"
           f"lamin={LAT-d}&lomin={LON-d*1.4}&lamax={LAT+d}&lomax={LON+d*1.4}")
    # Anonymous OpenSky access gets 400 credits/day, and a bounding-box query
    # like this one costs 4 credits — i.e. ~100 requests/day max, not "one
    # every 30s" (2880/day) as this used to poll. That mismatch is exactly
    # what was producing the 429s seen in testing. cache_s here must stay
    # aligned with the JS poll interval (tickFlights, below) for the same reason.
    states = get_json(url, timeout=20, cache_s=1200).get("states") or []
    out = []
    for s in states:
        if s[5] is None or s[6] is None:
            continue
        out.append({"cs": (s[1] or "").strip() or s[0], "lon": s[5], "lat": s[6],
                    "alt": s[7], "ground": s[8], "spd": s[9], "hdg": s[10]})
    return {"count": len(out), "flights": out}


ROUTES = {
    "/api/weather": api_weather,
    "/api/transit": api_transit,
    "/api/metar": api_metar,
    "/api/prayer": api_prayer,
    "/api/flights": api_flights,
}


# ----------------------------------------------------------------------
# HTTP
# ----------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path in ("/", "/index.html"):
            return self._send(200, PAGE, "text/html; charset=utf-8")
        if path == "/api/config":
            return self._send(200, json.dumps({
                "city": CITY_NAME, "lat": LAT, "lon": LON, "icao": ICAO,
                "lines": sorted(WANTED_LINES), "station": STATION_LABEL,
            }), "application/json")
        if path in ROUTES:
            try:
                return self._send(200, json.dumps(ROUTES[path]()), "application/json")
            except Exception as e:
                return self._send(200, json.dumps({"error": str(e)}), "application/json")
        self._send(404, "not found", "text/plain")

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/api/quit":
            self._send(200, json.dumps({"ok": True}), "application/json")
            # Delayed so the response above actually reaches the page before
            # the process (and the Firefox window it closes) disappears.
            threading.Timer(0.3, _shutdown).start()
            return
        self._send(404, "not found", "text/plain")


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


# subprocess.CREATE_NO_WINDOW isn't defined until Python 3.7 — this machine
# runs 3.6, where referencing it raises AttributeError. Raw Win32 value instead.
_CREATE_NO_WINDOW = 0x08000000

# Set by launcher.py, immediately after it spawns Firefox, to the exact PID
# of the window it opened — identified there by a before/after diff, not by
# title text (title text alone can't tell "our" window apart from the owner's
# own everyday Firefox if they've separately navigated it to this same page;
# confirmed live during testing). Left None when running `python3 sitrep.py`
# directly (no launcher involved) or if the launcher couldn't uniquely
# identify its window — in both cases we deliberately do NOT guess by title,
# since guessing is exactly what risked closing an unrelated window before.
OUR_FIREFOX_PID = None


def _close_our_firefox_window():
    """Best-effort: close the specific firefox.exe window launcher.py opened.

    Backs the Quit button — without this, quitting would only kill the
    server and leave a dead browser window on screen. Only acts on
    OUR_FIREFOX_PID (an exact PID, set by launcher.py) — never searches for
    "a firefox window titled Sitrep", since that match is ambiguous with any
    other window the owner might have separately open on the same page.
    """
    if not OUR_FIREFOX_PID:
        return
    try:
        subprocess.run(
            ["taskkill", "/PID", str(OUR_FIREFOX_PID), "/F"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            universal_newlines=True, timeout=5, creationflags=_CREATE_NO_WINDOW,
        )
    except Exception:
        pass


def _shutdown():
    _close_our_firefox_window()
    os._exit(0)


PAGE = r"""<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sitrep</title>
<style>
:root{--bg:#0B0E11;--text:#F5F9FC;--muted:#C3D0D8;--accent:#6FE0EC;
--ok:#6FE39B;--caution:#FFC35C;--alert:#FF7A63;
--mono:ui-monospace,"SFMono-Regular",Menlo,Consolas,monospace;
--sans:-apple-system,"Segoe UI",Roboto,sans-serif}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font-family:var(--sans);overflow:hidden}
#bar{display:flex;align-items:center;gap:1rem;padding:.5rem .9rem;
border-bottom:1px solid #232A31;position:relative;z-index:5}
#bar .brand{font-family:var(--mono);color:var(--accent);font-weight:600;font-size:1rem}
#bar button{background:none;border:none;color:var(--muted);font-size:.95rem;
cursor:pointer;padding:.3rem .6rem;border-bottom:2px solid transparent}
#bar button.on{color:var(--text);border-bottom-color:var(--accent)}
#quitBtn{color:var(--alert);border:1px solid #3a2422!important;border-radius:4px;margin-left:.5rem}
#quitBtn:hover{background:rgba(255,122,99,.15)}
#bar .right{margin-left:auto;font-family:var(--mono);font-size:.92rem;color:var(--muted);
display:flex;gap:.7rem;align-items:center}
.view{position:absolute;inset:44px 0 0 0;display:none}
.view.on{display:block}
iframe{position:absolute;inset:0;width:100%;height:100%;border:0}
#scrim{position:absolute;inset:0;pointer-events:none;
background:radial-gradient(ellipse at center,transparent 30%,rgba(11,14,17,.45) 100%)}
#flightOverlay{position:absolute;inset:0;width:100%;height:100%;pointer-events:none;overflow:visible}
.drag{position:absolute;z-index:3;cursor:grab;touch-action:none;user-select:none;
text-shadow:0 0 3px #000,0 0 6px #000,0 0 14px rgba(0,0,0,.95),1px 1px 1px #000,-1px -1px 1px #000,
1px -1px 1px #000,-1px 1px 1px #000;max-width:48%}
.drag:active{cursor:grabbing}
.lbl{font-family:var(--mono);font-size:.85rem;color:var(--muted);margin-bottom:.15rem;
display:flex;align-items:center;gap:.35rem}
.dot{width:8px;height:8px;border-radius:50%;background:var(--muted);display:inline-block}
.dot.ok{background:var(--ok)}.dot.err{background:var(--alert)}
.big{font-family:var(--mono);font-size:2.6rem;font-weight:600}
.md{font-family:var(--mono);font-size:1.4rem;font-weight:600}
.sm{font-family:var(--mono);font-size:1rem;color:var(--muted)}
.acc{color:var(--accent)}.cau{color:var(--caution)}.okc{color:var(--ok)}.alc{color:var(--alert)}
.row{font-size:1.1rem;line-height:1.55;white-space:nowrap}
#chime{background:none;border:none;color:var(--muted);cursor:pointer;font-size:.9rem}
table{border-collapse:collapse;font-family:var(--mono);font-size:.9rem;width:100%}
th,td{text-align:left;padding:.3rem .5rem;border-bottom:1px solid #232A31}
th{color:var(--muted);font-weight:400}
#v3{padding:1rem;overflow:auto}
#radar{max-width:340px;width:100%;display:block;margin:0 auto 1rem}
.ring{fill:none;stroke:#232A31}.blip{fill:var(--accent)}.gnd{fill:var(--muted)}
.fblip{fill:var(--caution);stroke:#0B0E11;stroke-width:.75px}
.fgnd{fill:var(--muted);stroke:#0B0E11;stroke-width:.75px}
.ring-label{font-family:var(--mono);font-size:11px;fill:var(--muted);text-anchor:start}
</style></head><body>

<div id="bar">
  <span class="brand">Sitrep</span>
  <button data-v="1" class="on">Summary</button>
  <button data-v="2">Weather Map</button>
  <button data-v="3">Traffic</button>
  <button id="quitBtn" title="Shut down Sitrep">&#9211; Quit</button>
  <span class="right"><button id="resetMapBtn" title="Reset Summary map to its default position, zoom and overlay">&#8635; reset view</button><span id="clock"></span></span>
</div>

<div class="view on" id="v1">
  <div id="map1"></div><div id="scrim"></div>
  <svg id="flightOverlay"><g id="fblips2"></g></svg>
  <div class="drag" id="w-weather" style="left:4%;top:6%"></div>
  <div class="drag" id="w-transit" style="left:55%;top:6%"></div>
  <div class="drag" id="w-clock" style="left:37%;top:4%"></div>
  <div class="drag" id="w-atis" style="left:4%;top:84%"></div>
  <div class="drag" id="w-prayer" style="left:55%;top:84%"></div>
  <div class="drag" id="w-rain" style="left:30%;top:45%"></div>
</div>

<div class="view" id="v2"><div id="map2"></div></div>

<div class="view" id="v3">
  <svg id="radar" viewBox="0 0 400 400">
    <circle cx="200" cy="200" r="190" class="ring"/>
    <circle cx="200" cy="200" r="127" class="ring"/>
    <circle cx="200" cy="200" r="63" class="ring"/>
    <line x1="200" y1="10" x2="200" y2="390" class="ring"/>
    <line x1="10" y1="200" x2="390" y2="200" class="ring"/>
    <circle cx="200" cy="200" r="4" fill="#4FB8C4"/>
    <text x="203" y="141" class="ring-label">25 km</text>
    <text x="203" y="77" class="ring-label">50 km</text>
    <text x="203" y="14" class="ring-label">75 km</text>
    <g id="blips"></g>
  </svg>
  <div class="sm" style="text-align:center;margin:-.5rem 0 1rem;white-space:normal">
    <span class="acc">&#9650;</span> airborne &nbsp; <span style="color:var(--muted)">&#9650;</span> on ground &nbsp;
    — rings ~25/50/75&nbsp;km from the map centre (approximate)
  </div>
  <table><thead><tr><th>Callsign</th><th>Alt m</th><th>Spd</th><th>Hdg</th></tr></thead>
  <tbody id="ftab"></tbody></table>
</div>

<script>
let CFG = null;
const $ = s => document.querySelector(s);
const pad = n => String(n).padStart(2,'0');
const hhmm = ms => { const d=new Date(ms); return pad(d.getHours())+':'+pad(d.getMinutes()); };

async function api(p){ const r = await fetch(p); return r.json(); }

/* ---- tabs ---- */
document.querySelectorAll('#bar button[data-v]').forEach(b=>b.onclick=()=>{
  document.querySelectorAll('#bar button[data-v]').forEach(x=>x.classList.remove('on'));
  document.querySelectorAll('.view').forEach(x=>x.classList.remove('on'));
  b.classList.add('on'); $('#v'+b.dataset.v).classList.add('on');
  $('#resetMapBtn').style.display = (b.dataset.v==='1') ? '' : 'none';
});
setInterval(()=>{ const d=new Date(); $('#clock').textContent=pad(d.getHours())+':'+pad(d.getMinutes()); },1000);

const DAY_NAMES=['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'];
const MONTH_NAMES=['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
function renderBigClock(){
  const el=$('#w-clock'); const d=new Date();
  let h = '<div class="lbl">'+dot(1)+'Time</div>'+
    '<div class="row"><span class="big">'+pad(d.getHours())+':'+pad(d.getMinutes())+'</span> '+
    '<span class="sm">:'+pad(d.getSeconds())+'</span></div>';
  if(expanded.clock){
    const tzMin=-d.getTimezoneOffset(), tzH=Math.trunc(tzMin/60), tzM=Math.abs(tzMin%60);
    const tz='UTC'+(tzMin>=0?'+':'-')+Math.abs(tzH)+(tzM?':'+pad(tzM):'');
    const startOfYear=new Date(d.getFullYear(),0,1);
    const dayOfYear=Math.floor((d-startOfYear)/86400000)+1;
    h += '<div class="row sm">'+DAY_NAMES[d.getDay()]+' '+d.getDate()+' '+MONTH_NAMES[d.getMonth()]+' '+d.getFullYear()+' · '+tz+'</div>'+
         '<div class="row sm">day '+dayOfYear+' of '+(365+( (d.getFullYear()%4===0&&(d.getFullYear()%100!==0||d.getFullYear()%400===0))?1:0 ))+'</div>';
  }
  el.innerHTML=h;
}

/* ---- drag + remember position; a tap (no movement) toggles detail via onTap ---- */
function draggable(el, onTap){
  const key='pos:'+el.id;
  try{ const s=JSON.parse(localStorage.getItem(key)); if(s){el.style.left=s.x+'%';el.style.top=s.y+'%';} }catch(e){}
  let st=null, moved=false;
  el.addEventListener('pointerdown',e=>{
    if(e.target.tagName==='BUTTON') return;
    el.setPointerCapture(e.pointerId);
    const p=el.parentElement.getBoundingClientRect();
    st={x:e.clientX,y:e.clientY,l:parseFloat(el.style.left),t:parseFloat(el.style.top),w:p.width,h:p.height};
    moved=false;
  });
  el.addEventListener('pointermove',e=>{
    if(!st) return;
    const dx=e.clientX-st.x, dy=e.clientY-st.y;
    if(Math.abs(dx)>4||Math.abs(dy)>4) moved=true;
    const nx=Math.max(0,Math.min(95,st.l+dx/st.w*100));
    const ny=Math.max(0,Math.min(94,st.t+dy/st.h*100));
    el.style.left=nx+'%'; el.style.top=ny+'%';
  });
  const end=()=>{ if(!st)return; st=null;
    localStorage.setItem(key,JSON.stringify({x:parseFloat(el.style.left),y:parseFloat(el.style.top)}));
    if(!moved && onTap) onTap(); };
  el.addEventListener('pointerup',end); el.addEventListener('pointercancel',end);
}
const expanded = {weather:false, transit:false, atis:false, prayer:false, clock:false, rain:false};

/* ---- widgets ---- */
function dot(ok){ return '<span class="dot '+(ok?'ok':'err')+'"></span>'; }

let lastWeather=null;
async function tickWeather(){
  const el=$('#w-weather');
  try{ const d=await api('/api/weather');
    if(d.error) throw new Error(d.error);
    lastWeather=d; renderWeather();
  }catch(e){ el.innerHTML='<div class="lbl">'+dot(0)+CFG.city+'</div><div class="sm">'+e.message+'</div>'; }
}
function renderWeather(){
  const el=$('#w-weather'), d=lastWeather; if(!d) return;
  let h = '<div class="lbl">'+dot(1)+CFG.city+'</div>'+
    '<div class="row"><span class="big">'+d.temp+'°</span> <span class="sm">'+d.precip+' mm · '+d.desc+'</span></div>';
  if(expanded.weather){
    h += '<div class="row sm">feels '+d.feels+'° · '+d.humidity+'% humidity · '+d.cloud+'% cloud</div>'+
         '<div class="row sm">wind '+d.windDir+'° '+d.windSpeed+' km/h, gusts '+d.windGust+' · '+d.pressure+' hPa</div>';
  }
  el.innerHTML=h;
  renderRain();
}

function renderRain(){
  const el=$('#w-rain'), d=lastWeather; if(!d) return;
  let h = '<div class="lbl">'+dot(1)+'Rain (12h)</div>';
  if(d.rainExpected){
    h += '<div class="row"><span class="big alc">&#9748; rain in '+d.rainIn+'h</span> '+
         '<span class="sm">'+d.rainMaxProb+'% · '+d.rainTotal+' mm</span></div>';
  } else {
    h += '<div class="row"><span class="big okc">no rain</span> <span class="sm">'+d.rainMaxProb+'% max chance</span></div>';
  }
  if(expanded.rain){
    h += '<div class="row sm" style="white-space:normal">'+
      d.rainHours.map(x=>x.t+' '+x.prob+'%/'+x.mm+'mm').join(' &nbsp; ')+'</div>';
  }
  el.innerHTML=h;
}

let lastTransit=null;
async function tickTransit(){
  const el=$('#w-transit');
  try{ const d=await api('/api/transit');
    if(d.error) throw new Error(d.error);
    lastTransit=d; renderTransit();
  }catch(e){ el.innerHTML='<div class="lbl">'+dot(0)+CFG.station+'</div><div class="sm">'+e.message+'</div>'; }
}
function renderTransit(){
  const el=$('#w-transit'), d=lastTransit; if(!d) return;
  const list = expanded.transit ? d.next : d.perLine;
  if(!list.length){ el.innerHTML='<div class="lbl">'+dot(1)+CFG.station+'</div><div class="sm">no '+(CFG.lines.join('/')||'')+' departures</div>'; return; }
  let h='<div class="lbl">'+dot(1)+CFG.station+'</div>';
  list.forEach(r=>{
    const late = r.cancelled ? '<span class="alc"> cancelled</span>'
               : r.delay>0 ? '<span class="cau"> +'+r.delay+'</span>' : '';
    h+='<div class="row"><span class="acc">'+r.line+'</span> '+r.dest+
       ' <span class="md">'+hhmm(r.at)+'</span>'+late+'</div>';
  });
  el.innerHTML=h;
}

let lastAtis=null;
async function tickAtis(){
  const el=$('#w-atis');
  try{ const d=await api('/api/metar');
    if(d.error) throw new Error(d.error);
    lastAtis=d; renderAtis();
  }catch(e){ el.innerHTML='<div class="lbl">'+dot(0)+CFG.icao+'</div><div class="sm">'+e.message+'</div>'; }
}
function renderAtis(){
  const el=$('#w-atis'), d=lastAtis; if(!d) return;
  const c = d.rules==='VFR'?'okc':d.rules==='MVFR'?'cau':'alc';
  let h='<div class="lbl">'+dot(1)+CFG.icao+'</div>'+
    '<div class="row"><span class="md '+c+'">'+d.rules+'</span> <span class="sm">'+d.summary+'</span></div>';
  if(expanded.atis){
    h += '<div class="row sm">temp '+d.temp+'° · dewpoint '+d.dewpoint+'°'+(d.gust?(' · gust '+d.gust):'')+'</div>';
    if(d.clouds.length) h += '<div class="row sm">clouds '+d.clouds.join(', ')+'</div>';
    if(d.wx.length) h += '<div class="row sm">wx '+d.wx.join(', ')+'</div>';
    if(d.remarks) h += '<div class="row sm">'+d.remarks+'</div>';
    h += '<div class="row sm">'+d.raw+'</div>';
  }
  el.innerHTML=h;
}

let prayerNext=null, prayerAll=null, chimeOn=true, chimedFor=null;
function chime(){
  try{ const C=window.AudioContext||window.webkitAudioContext; const ctx=new C();
    [880,660,880].forEach((f,i)=>{ const o=ctx.createOscillator(),g=ctx.createGain();
      o.type='sine'; o.frequency.value=f; const t=ctx.currentTime+i*.5;
      g.gain.setValueAtTime(0,t); g.gain.linearRampToValueAtTime(.2,t+.05);
      g.gain.exponentialRampToValueAtTime(.001,t+.9);
      o.connect(g).connect(ctx.destination); o.start(t); o.stop(t+1); });
  }catch(e){}
}
async function loadPrayer(){
  try{ const d=await api('/api/prayer'); if(!d.error){ prayerNext=d.next; prayerAll=d.all; } }catch(e){}
}
function renderPrayer(){
  const el=$('#w-prayer');
  if(!prayerNext){ el.innerHTML='<div class="lbl">'+dot(0)+'Prayer</div><div class="sm">unavailable</div>'; return; }
  const left=prayerNext.at-Date.now();
  if(left<=0){ loadPrayer(); return; }
  const h=Math.floor(left/3600000), m=Math.floor(left%3600000/60000), s=Math.floor(left%60000/1000);
  const cd = h>0 ? h+'h '+pad(m)+'m' : pad(m)+':'+pad(s);
  const key=prayerNext.name+prayerNext.at;
  if(chimeOn && left<=600000 && chimedFor!==key){ chimedFor=key; chime(); }
  let hh='<div class="lbl">'+dot(1)+'Prayer <button id="chime">'+(chimeOn?'♪ on':'♪ off')+'</button></div>'+
    '<div class="row"><span class="md">'+prayerNext.name+'</span> <span class="acc">'+cd+'</span>'+
    ' <span class="sm">'+hhmm(prayerNext.at)+'</span></div>';
  if(expanded.prayer && prayerAll){
    hh += '<div class="row sm">'+prayerAll.map(p=>p.name+' '+hhmm(p.at)).join(' &nbsp;·&nbsp; ')+'</div>';
  }
  el.innerHTML=hh;
  $('#chime').onclick=e=>{ e.stopPropagation(); chimeOn=!chimeOn; };
}

// Web Mercator, in CSS-px units at the given zoom (256px tiles) — same
// projection Windy/Leaflet use internally. There's no API to read Windy's
// actual pan/zoom state back out of its iframe, so this assumes the map1
// backdrop is still centered on CFG at MAP1_ZOOM (true right after load;
// drifts out of alignment if the now-interactive backdrop gets panned/zoomed).
const MAP1_ZOOM = 8;
function mercPx(lat, lon, zoom){
  const s = Math.min(Math.max(Math.sin(lat*Math.PI/180), -0.9999), 0.9999);
  const W = 256 * Math.pow(2, zoom);
  return [W*(0.5+lon/360), W*(0.5 - Math.log((1+s)/(1-s))/(4*Math.PI))];
}

async function tickFlights(){
  try{ const d=await api('/api/flights');
    if(d.error){
      $('#blips').innerHTML=''; $('#fblips2').innerHTML='';
      $('#ftab').innerHTML='<tr><td colspan="4" class="sm">'+d.error+'</td></tr>';
      return;
    }
    const g=$('#blips'); g.innerHTML='';
    d.flights.forEach(f=>{
      const x=200+((f.lon-CFG.lon)/(0.7*1.4))*190, y=200-((f.lat-CFG.lat)/0.7)*190;
      g.innerHTML+='<g transform="translate('+x.toFixed(1)+','+y.toFixed(1)+') rotate('+(f.hdg||0)+')">'+
        '<path d="M0,-6 L4,5 L0,2 L-4,5 Z" class="'+(f.ground?'gnd':'blip')+'"/></g>';
    });
    $('#ftab').innerHTML=d.flights.slice(0,15).map(f=>'<tr><td>'+f.cs+'</td><td>'+
      (f.ground?'gnd':Math.round(f.alt||0))+'</td><td>'+Math.round(f.spd||0)+'</td><td>'+
      Math.round(f.hdg||0)+'°</td></tr>').join('');

    const ov = $('#flightOverlay'), fg = $('#fblips2');
    const rect = ov.getBoundingClientRect();
    if(rect.width && rect.height){
      const cx = rect.width/2, cy = rect.height/2;
      const [ccx,ccy] = mercPx(CFG.lat, CFG.lon, MAP1_ZOOM);
      let html='';
      d.flights.forEach(f=>{
        const [fx,fy] = mercPx(f.lat, f.lon, MAP1_ZOOM);
        const x = cx+(fx-ccx), y = cy+(fy-ccy);
        html += '<g transform="translate('+x.toFixed(1)+','+y.toFixed(1)+') rotate('+(f.hdg||0)+')">'+
          '<path d="M0,-9 L6,7 L0,3 L-6,7 Z" class="'+(f.ground?'fgnd':'fblip')+'"/></g>';
      });
      fg.innerHTML = html;
    }
  }catch(e){}
}

/* ---- boot ---- */
function windy(ov,z,inter){
  return '<iframe src="https://embed.windy.com/embed2.html?lat='+CFG.lat+
    '&lon='+CFG.lon+'&detailLat='+CFG.lat+'&detailLon='+CFG.lon+'&zoom='+z+
    '&level=surface&overlay='+ov+'&menu=&message='+inter+'&marker=true&calendar=now'+
    '&pressure=true&type=map&location=coordinates&metricWind=default&metricTemp=default"></iframe>';
}
// 'temp' instead of 'radar': radar is mostly flat grey with no precipitation
// nearby (which is most of the time), making the backdrop and everything
// drawn over it harder to read. Temperature is a colorful gradient always.
const MAP1_OVERLAY = 'temp';

(async function(){
  CFG = await api('/api/config');
  $('#map1').innerHTML = windy(MAP1_OVERLAY,8,'false');
  $('#map2').innerHTML = windy('wind',7,'true');
  $('#resetMapBtn').onclick = ()=>{ $('#map1').innerHTML = windy(MAP1_OVERLAY,8,'false'); };

  draggable($('#w-weather'), ()=>{ expanded.weather=!expanded.weather; renderWeather(); });
  draggable($('#w-transit'), ()=>{ expanded.transit=!expanded.transit; renderTransit(); });
  draggable($('#w-clock'),   ()=>{ expanded.clock=!expanded.clock; renderBigClock(); });
  draggable($('#w-atis'),    ()=>{ expanded.atis=!expanded.atis; renderAtis(); });
  draggable($('#w-prayer'),  ()=>{ expanded.prayer=!expanded.prayer; renderPrayer(); });
  draggable($('#w-rain'),    ()=>{ expanded.rain=!expanded.rain; renderRain(); });

  $('#quitBtn').onclick = async ()=>{
    if(!confirm('Shut down Sitrep?')) return;
    $('#quitBtn').textContent = 'shutting down…';
    try{ await fetch('/api/quit', {method:'POST'}); }catch(e){}
  };

  tickWeather(); setInterval(tickWeather, 300000);
  tickTransit(); setInterval(tickTransit, 30000);
  tickAtis();    setInterval(tickAtis, 600000);
  loadPrayer();  setInterval(loadPrayer, 3600000);
  setInterval(renderPrayer, 1000);
  renderBigClock(); setInterval(renderBigClock, 1000);
  tickFlights(); setInterval(tickFlights, 1200000);
})();
</script></body></html>
"""


def run_server():
    print(f"Sitrep running — open http://localhost:{PORT}")
    print(f"  lines: {', '.join(sorted(WANTED_LINES)) or 'all'} at {STATION_LABEL}")
    print(f"  METAR: {ICAO} ({'token set' if AVWX_TOKEN else 'no AVWX token — set AVWX_TOKEN'})")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()


if __name__ == "__main__":
    run_server()
