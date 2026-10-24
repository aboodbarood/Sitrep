# Sitrep

A personal "situation report" dashboard: weather, rain warning, live public
transport and more, at a glance. Three variants:

| Folder | What | Transit data |
|---|---|---|
| [`sitrep/`](sitrep/) | Desktop version — a small Python server plus a browser page | MVG (Munich) |
| [`sitrep-android/`](sitrep-android/) | Android app, Munich | MVG |
| [`sitrep-ulm/`](sitrep-ulm/) | Android app for Ulm/Neu-Ulm, laid out for the Fairphone 6 | DING (EFA) |

All three run the data fetching locally (a loopback HTTP server inside the
app, or `sitrep.py` on the desktop) because the upstream APIs can't be called
from a plain web page (CORS). The dashboard itself is one HTML page.

## Personal configuration

Personal values — your stop, lines, work stop, default medications, API token —
are **not** in the source. Each project reads them from a git-ignored
`local_config.json`; copy the project's `local_config.example.json` and edit it.
Without it everything runs on neutral defaults (central Munich / Ulm Hbf).

## Data sources

[Open-Meteo](https://open-meteo.com) (weather), MVG's public web API, DING's
EFA endpoint, [Windy](https://www.windy.com) embeds (maps), and in the Munich
variants [AVWX](https://avwx.rest) (METAR, needs a token),
[Aladhan](https://aladhan.com) (prayer times) and
[OpenSky](https://opensky-network.org) (aircraft). MVG and DING endpoints are
unofficial — keep polling moderate.

Personal, non-commercial project.
