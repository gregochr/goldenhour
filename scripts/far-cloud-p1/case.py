"""Part A: reconstruct what lay on the sun line for one case (default: the Angel of the North,
2026-10-13 SUNSET). Writes out/case_<date>.csv and out/case_<date>.md.

Hours are [slot-1, slot, slot+1] with slot from ``slots.find_best_index`` on the sunset (for the
Angel: 16:00, 17:00, 18:00 UTC). Each hour gets its OWN solar azimuth and a transect every 25 km
from 0 to 400 km (deduplicated by production's grid cell). Sources: ERA5 (``models=era5``),
Previous Runs (day 0 and ``_previous_day1``) per model, Historical Forecast pressure levels.
"""
from __future__ import annotations

import argparse
import csv
import math
import os
import sys
from datetime import date, datetime, timedelta
from typing import Dict, List, Optional, Sequence, Tuple

import geometry
import openmeteo as om
import slots
import sun

ANGEL = (54.914, -1.589, "2026-10-13", "SUNSET")
STEP_KM = 25
MAX_KM = 400
DEFAULT_MODELS = ["best_match", "ukmo_seamless", "ecmwf_ifs025", "icon_seamless"]
BANDS = {"low": (0.0, 3.0), "mid": (3.0, 8.0), "high": (8.0, float("inf"))}  # km
LAYER_THRESHOLD = 60.0
EARTH_RADIUS_KM = 6371.0
METEOSAT_WINDOW = "15:30-17:20 UTC"


def hour_strings(day: str) -> List[str]:
    """The 24 hourly time strings of a UTC day, as Open-Meteo returns them."""
    return [f"{day}T{h:02d}:00" for h in range(24)]


def event_time(lat: float, lon: float, day: str, ttype: str) -> datetime:
    """Naive-UTC solar event from sun.py."""
    d = date.fromisoformat(day)
    ev = sun.sunset_utc(d, lat, lon) if ttype == "SUNSET" else sun.sunrise_utc(d, lat, lon)
    if ev is None:
        raise ValueError("no solar event for that date/place")
    return ev


def case_hours(ev: datetime, day: str, ttype: str) -> List[str]:
    """[slot-1, slot, slot+1] hour strings clipped to the day."""
    times = hour_strings(day)
    idx = slots.find_best_index(times, ev.replace(microsecond=0), ttype)
    return [times[i] for i in (idx - 1, idx, idx + 1) if 0 <= i < 24]


def sun_line_height_km(d_km: float, elevation_deg: float) -> float:
    """Parent plan 2(A): h(d) = 0.1 + d*tan(alpha) + d^2/(2*6371) km (observer 100 m up, flat
    sun ray over a curved earth). For alpha <= 0 this is the ray to the sub-horizon sun."""
    return 0.1 + d_km * math.tan(math.radians(elevation_deg)) + d_km ** 2 / (2 * EARTH_RADIUS_KM)


def band_overlaps(layer: str, h_km: float) -> bool:
    """True if the layer's height band contains the sun-line height."""
    lo, hi = BANDS[layer]
    return lo <= h_km <= hi


def flag_points(values: Dict[float, Dict[str, Optional[float]]], elevation_deg: float,
                threshold: float = LAYER_THRESHOLD) -> List[Tuple[float, str, float, float]]:
    """Flag (distance_km, layer, value, h_km) where a layer >= threshold has its band overlapping
    the sun-line height at that distance. ``values`` maps distance -> {low,mid,high}."""
    out = []
    for d in sorted(values):
        h = sun_line_height_km(d, elevation_deg)
        for layer in ("low", "mid", "high"):
            v = values[d].get(layer)
            if v is not None and v >= threshold and band_overlaps(layer, h):
                out.append((d, layer, v, h))
    return out


def hour_transect(lat: float, lon: float, hour: str) -> Tuple[float, float, List[Tuple[float, om.Coord, str]]]:
    """For one hour: (elevation, azimuth, [(distance_km, rounded coord, cell_key)]) with the
    distance list covering 0..400 km (every distance kept; cells shared between distances are
    fetched once)."""
    dt = datetime.strptime(hour, "%Y-%m-%dT%H:%M")
    elev, az = sun.sun_position(dt, lat, lon)
    pts = [(0.0, (lat, lon))]
    for i, p in enumerate(geometry.transect(lat, lon, az, STEP_KM, MAX_KM), start=1):
        pts.append((float(i * STEP_KM), p))
    rows = [(d, om.round_coord(*p), geometry.grid_key(*p)) for d, p in pts]
    return elev, az, rows


def build_case(client: om.Client, lat: float, lon: float, day: str, ttype: str,
               models: Sequence[str], ev: Optional[datetime] = None) -> dict:
    """Fetch everything and return the case structure used by the writers."""
    ev = ev or event_time(lat, lon, day, ttype)
    hours = case_hours(ev, day, ttype)
    per_hour = {}
    unique: Dict[str, om.Coord] = {}
    for h in hours:
        elev, az, rows = hour_transect(lat, lon, h)
        per_hour[h] = {"elevation": elev, "azimuth": az, "rows": rows}
        for _, c, key in rows:
            unique.setdefault(key, c)
    keys = sorted(unique)
    coords = [unique[k] for k in keys]

    sources: Dict[str, Dict[str, om.Point]] = {}   # source label -> cell key -> Point
    def add(label: str, pts: List[om.Point]) -> None:
        sources[label] = dict(zip(keys, pts))

    add("era5", client.get("archive", coords, om.CLOUD_VARS, day, day, models="era5"))
    notes: List[str] = []
    for m in models:
        vs = om.CLOUD_VARS + [om.prev_var(v, 1) for v in om.CLOUD_VARS]
        try:
            pts = client.get("previous_runs", coords, vs, day, day, models=m)
        except (om.RateLimited, om.BudgetExhausted, om.NetworkError):
            raise       # a limit (HTTP 429 / limit body) stops the whole run; never "model skipped"
        except om.OpenMeteoError as e:   # a model the API rejects is skipped, not fatal
            notes.append(f"model {m} skipped: {e}")
            continue
        add(f"prev:{m}:day0", pts)
        add(f"prev:{m}:day1", pts)  # same Points; the var name selects the lead
    add("hist:pressure", client.get("historical_forecast", coords, om.PRESSURE_VARS, day, day))
    return {"lat": lat, "lon": lon, "day": day, "type": ttype, "event": ev, "hours": hours,
            "per_hour": per_hour, "sources": sources, "keys": keys, "notes": notes}


def reading(source: str, pt: om.Point, hour: str, layer_or_var: str) -> Optional[float]:
    """Value for a source label and logical column (low|mid|high|p850..p300)."""
    cv = {"low": "cloud_cover_low", "mid": "cloud_cover_mid", "high": "cloud_cover_high"}
    if layer_or_var.startswith("p"):
        if not source.startswith("hist"):
            return None
        return pt.value(hour, f"cloud_cover_{layer_or_var[1:]}hPa")
    if source.startswith("hist"):
        return None
    var = cv[layer_or_var]
    if source.endswith(":day1"):
        var = om.prev_var(var, 1)
    return pt.value(hour, var)


COLUMNS = ["low", "mid", "high", "p850", "p700", "p600", "p500", "p400", "p300"]


def csv_rows(case: dict) -> List[dict]:
    """One row per point x hour x source (wide in layer columns)."""
    out = []
    for h in case["hours"]:
        ph = case["per_hour"][h]
        for d, c, key in ph["rows"]:
            for label, by_key in case["sources"].items():
                pt = by_key[key]
                row = {"hour_utc": h, "distance_km": d, "lat": c[0], "lon": c[1], "cell": key,
                       "source": label, "sun_elevation": round(ph["elevation"], 2),
                       "sun_azimuth": round(ph["azimuth"], 1),
                       "sun_line_km": round(sun_line_height_km(d, ph["elevation"]), 2)}
                for col in COLUMNS:
                    v = reading(label, pt, h, col)
                    row[col] = "" if v is None else v
                out.append(row)
    return out


def fmt(v: Optional[float]) -> str:
    """Compact cell text."""
    return "-" if v is None else f"{v:g}"


def write_markdown(case: dict) -> str:
    """The per-hour tables and the plain sun-line summary."""
    L: List[str] = []
    ev = case["event"]
    L.append(f"# Part A case: {case['lat']}, {case['lon']} {case['day']} {case['type']}")
    L.append("")
    L.append(f"Solar event (sun.py): {ev.strftime('%Y-%m-%d %H:%M')} UTC. Hours: "
             + ", ".join(h[11:] for h in case["hours"]) + ". Values are cloud cover % as low/mid/high; "
             "`*` marks a layer >= 60% whose height band overlaps the sun line.")
    L.append("")
    for n in case.get("notes", []):
        L.append(f"> {n}")
        L.append("")
    summary: List[str] = []
    sources = [s for s in case["sources"] if not s.startswith("hist")]
    for h in case["hours"]:
        ph = case["per_hour"][h]
        elev, az = ph["elevation"], ph["azimuth"]
        L.append(f"## {h[11:]} UTC: sun {elev:.1f} deg up, azimuth {az:.0f} deg")
        L.append("")
        L.append("| km | sun line km | " + " | ".join(sources) + " | hist 850/700/600/500/400/300 |")
        L.append("|---|---|" + "---|" * (len(sources) + 1))
        flags: Dict[str, List] = {}
        vals_by_source: Dict[str, Dict[float, Dict[str, Optional[float]]]] = {s: {} for s in sources}
        for d, c, key in ph["rows"]:
            cells = []
            for s in sources:
                pt = case["sources"][s][key]
                v = {l: reading(s, pt, h, l) for l in ("low", "mid", "high")}
                vals_by_source[s][d] = v
                cells.append(None if all(x is None for x in v.values()) else v)
            hp = case["sources"]["hist:pressure"][key]
            pres = "/".join(fmt(reading("hist:pressure", hp, h, p))
                            for p in ("p850", "p700", "p600", "p500", "p400", "p300"))
            h_km = sun_line_height_km(d, elev)
            row_cells = []
            for s, v in zip(sources, cells):
                if v is None:
                    row_cells.append("-")
                    continue
                marks = "".join("*" for l in ("low", "mid", "high")
                                if v[l] is not None and v[l] >= LAYER_THRESHOLD
                                and band_overlaps(l, h_km))
                row_cells.append(f"{fmt(v['low'])}/{fmt(v['mid'])}/{fmt(v['high'])}{marks}")
            L.append(f"| {d:g} | {h_km:.1f} | " + " | ".join(row_cells) + f" | {pres} |")
        L.append("")
        for s in sources:
            fl = flag_points(vals_by_source[s], elev)
            if fl:
                txt = "; ".join(f"{d:g} km {layer} {v:g}% (band {BANDS[layer][0]:g}-"
                                f"{'up' if BANDS[layer][1] == float('inf') else format(BANDS[layer][1], 'g')} km, "
                                f"line {hk:.1f} km)" for d, layer, v, hk in fl[:8])
                more = f" (+{len(fl) - 8} more)" if len(fl) > 8 else ""
                summary.append(f"- {h[11:]} UTC, {s}: {txt}{more}")
            else:
                summary.append(f"- {h[11:]} UTC, {s}: nothing at or above 60% in a band the sun line passes through")
    L.append("## What was on the sun line")
    L.append("")
    L.append("Sun-line height h(d) = 0.1 + d*tan(alpha) + d^2/(2*6371) km; bands: low 0-3, mid 3-8, "
             "high 8+ km. At the sunset hour and after, alpha is near or below zero, so h(d) is the "
             "ray to the sun at that hour, not a sunlit corridor. Models only: not an observation.")
    L.append("")
    L.extend(summary)
    L.append("")
    ev_hour = case["hours"][min(1, len(case["hours"]) - 1)]
    L.append("## Manual step: Meteosat")
    L.append("")
    L.append(f"Open EUMETSAT's public viewer (or any satellite archive) for {case['day']}, "
             f"{METEOSAT_WINDOW}, and look along the {case['per_hour'][ev_hour]['azimuth']:.0f} deg bearing "
             "from the site out to ~400 km. Is there a thick deck or only a thin lid in the sun's path? "
             "This is the plan's only observation, not a model.")
    L.append("")
    return "\n".join(L)


def write_outputs(case: dict, out_dir: str) -> Tuple[str, str]:
    """Write case_<date>.csv and .md; return their paths."""
    os.makedirs(out_dir, exist_ok=True)
    cp = os.path.join(out_dir, f"case_{case['day']}.csv")
    rows = csv_rows(case)
    fields = ["hour_utc", "distance_km", "lat", "lon", "cell", "source", "sun_elevation",
              "sun_azimuth", "sun_line_km"] + COLUMNS
    with open(cp, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    mp = os.path.join(out_dir, f"case_{case['day']}.md")
    with open(mp, "w") as f:
        f.write(write_markdown(case))
    return cp, mp


def load_row(path: str, evaluation_id: str) -> Tuple[float, float, str, str, Optional[datetime]]:
    """Read (lat, lon, date, type, solar_event_time) from p1_forecasts.csv by evaluation_id."""
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            if r["evaluation_id"] == str(evaluation_id):
                ev = r.get("solar_event_time") or None
                return (float(r["loc_lat"]), float(r["loc_lon"]), r["target_date"], r["target_type"],
                        datetime.strptime(ev, "%Y-%m-%dT%H:%M:%S") if ev else None)
    raise SystemExit(f"evaluation_id {evaluation_id} not found in {path}")


def main(argv: Optional[Sequence[str]] = None, client: Optional[om.Client] = None) -> int:
    """CLI entry point."""
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--lat", type=float, default=ANGEL[0])
    ap.add_argument("--lon", type=float, default=ANGEL[1])
    ap.add_argument("--date", default=ANGEL[2])
    ap.add_argument("--type", default=ANGEL[3], choices=["SUNRISE", "SUNSET"])
    ap.add_argument("--row-csv", help="p1_forecasts.csv to take the case from")
    ap.add_argument("--row-id", help="evaluation_id within --row-csv")
    ap.add_argument("--models", default=",".join(DEFAULT_MODELS))
    ap.add_argument("--out", default="out")
    ap.add_argument("--cache", default="cache")
    ap.add_argument("--budget", type=int, default=om.DEFAULT_BUDGET)
    ap.add_argument("--pause", type=float, default=om.DEFAULT_PAUSE_S)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--no-wait", action="store_true",
                    help="stop cleanly at the hourly cap instead of sleeping to the next hour")
    a = ap.parse_args(argv)
    lat, lon, day, ttype, ev = a.lat, a.lon, a.date, a.type, None
    if a.row_csv:
        lat, lon, day, ttype, ev = load_row(a.row_csv, a.row_id)
    models = [m for m in a.models.split(",") if m]
    try:
        client = client or om.Client(a.cache, a.budget, pause_s=a.pause, dry_run=a.dry_run,
                                     wait_for_hour=not a.no_wait)
        case = build_case(client, lat, lon, day, ttype, models, ev)
    except om.BudgetFileCorrupt as e:
        print(f"REFUSING TO RUN: {e}")
        return 2
    except om.RateLimited as e:
        print(f"RATE LIMITED, stopping (re-run resumes from cache): {e}")
        return 2
    except om.BudgetExhausted as e:
        print(f"BUDGET EXHAUSTED (re-run later, resumes from cache): {e}")
        return 2
    except om.OpenMeteoError as e:
        print(f"OPEN-METEO ERROR, stopping (re-run resumes from cache): {e}")
        return 2
    print(client.summary())
    if a.dry_run:
        return 0
    cp, mp = write_outputs(case, a.out)
    print(f"wrote {cp}\nwrote {mp}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
