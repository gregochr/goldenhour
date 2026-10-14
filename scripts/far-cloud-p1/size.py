"""Part C: size H1-H5 (far decks, parent plan far-cloud-lid-plan.md section 6 P1).

Input: p1_forecasts.csv (header from export_forecasts.sql). Output: out/part_c.md; a --pilot run writes out/pilot.md + out/pilot.json instead.

Population: prompted region-events (T+0/T+1, sky locations, latest run before the event with
batch_state NULL/SCORED and no triage reason) whose forecast passes the cone gate: solar_low_cloud
< 20 at at least half of the region-event's prompted locations. Three fixed locations per
region-event are chosen (seeded, among the firing locations). Fetch only what the CSV lacks.

Region-event decision rule: a region-event is "flagged" (forecast side) or "observed" (ERA5 side)
for a hypothesis when at least half of its sampled, valid locations are. Failure population for a
hypothesis = observed region-events as a share of ALL prompted region-events (not of the sample).

H1: forecast far(226 km) high >= 60.   observed: ERA5 far high >= 60.
H2: forecast far mid >= 60.            observed: ERA5 far mid >= 60.
H3: forecast ~165 km low >= 50 or mid >= 60.   observed: same on ERA5.
H4: forecast ~320 km high >= 60.       observed: ERA5 high >= 60 at 320 km.
H5: forecast any of 600/500/400 hPa >= 60 at 226 km.   observed: ERA5 far mid or high >= 60.
All H are conditional on the cone gate (the population).
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import random
import shlex
import statistics
import sys
from collections import defaultdict
from datetime import date, datetime, timedelta
from typing import Dict, List, Optional, Sequence, Tuple

import geometry
import openmeteo as om
import premise
import slots

try:  # another agent's module; the bootstrap here is local so the two cannot drift apart
    import stats as _stats  # noqa: F401
    STATS_PRESENT = True
except Exception:  # noqa: BLE001
    STATS_PRESENT = False

SKY_TYPES = {"LANDSCAPE", "SEASCAPE", "WATERFALL"}
NON_SKY = {"WILDLIFE", "WOODLAND", "BLUEBELL"}
CONE_GATE = 20.0
LOC_PER_EVENT = 3
DEFAULT_CAP = 2000
DEFAULT_SEED = 20261014
DISTANCES = (165, 226, 320)
HNAMES = ["H1", "H2", "H3", "H4", "H5", "H1|H2"]
PRESS_COLS = {"600": "far_solar_cloud_600hpa", "500": "far_solar_cloud_500hpa",
              "400": "far_solar_cloud_400hpa"}
PRESS_VAR = {"600": "cloud_cover_600hPa", "500": "cloud_cover_500hPa", "400": "cloud_cover_400hPa"}
MODEL = "best_match"
MIN_EVENTS, MIN_DATES = 80, 25          # FLAGGED region-events / distinct dates they fall on
MIN_EVALUABLE = 30                      # below this a hypothesis is "not evaluable"
MIN_PILOT_PAIRS = 20                    # cone-low pairs needed before a pilot can say "reliable"
PRECISION_BAR, FAILURE_BAR, RELIABLE_MAD = 0.7, 0.02, 15.0
BLOCK_DAYS, DRAWS, BOOT_SEED = 5, 2000, 20261014
BLOCK_SENSITIVITY = (3, 5, 7)


# ---------------------------------------------------------------------------------- loading
def num(s) -> Optional[float]:
    """CSV cell to float; empty/None to None."""
    if s is None or str(s).strip() == "":
        return None
    try:
        return float(s)
    except ValueError:
        return None


def is_sky(types: Optional[str]) -> bool:
    """Sky subject per heatSpots: no types counts; otherwise at least one non-WILDLIFE/WOODLAND/BLUEBELL."""
    parts = {t.strip() for t in (types or "").split(",") if t.strip()}
    return True if not parts else bool(parts - NON_SKY)


def load_rows(path: str) -> List[dict]:
    """Read the export."""
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def is_training(row: dict) -> bool:
    """A plan section 4 training case (Angel, Low Hauxley, Copt Hill), reported never pooled."""
    return premise.training_label(row.get("loc_name"), row.get("target_date"),
                                  row.get("target_type")) is not None


def latest_prompted(rows: Sequence[dict]) -> List[dict]:
    """Plan section 4: per (location, date, type) the latest run strictly before the event with
    batch_state NULL/SCORED; then keep it if prompted (no triage reason), T+0/T+1, sky location,
    with coordinates, an azimuth and an event time."""
    best: Dict[tuple, dict] = {}
    for r in rows:
        if r.get("batch_state") not in (None, "", "SCORED"):
            continue
        ev, run = r.get("solar_event_time") or "", r.get("forecast_run_at") or ""
        if not ev or not run or not run < ev:
            continue
        k = (r["location_id"], r["target_date"], r["target_type"])
        cur = best.get(k)
        if cur is None or (run, int(r["evaluation_id"])) > (cur["forecast_run_at"], int(cur["evaluation_id"])):
            best[k] = r
    out = []
    for r in best.values():
        if (r.get("triage_reason") or "").strip():
            continue
        if r["target_type"] not in ("SUNRISE", "SUNSET") or num(r.get("days_ahead")) not in (0.0, 1.0):
            continue
        if not is_sky(r.get("loc_types")):
            continue
        if num(r.get("loc_lat")) is None or num(r.get("loc_lon")) is None or num(r.get("azimuth_deg")) is None:
            continue
        out.append(r)
    return sorted(out, key=lambda r: int(r["evaluation_id"]))


def region_events(prompted: Sequence[dict]) -> List[dict]:
    """Group prompted rows into region-events and apply the cone gate."""
    groups: Dict[tuple, List[dict]] = defaultdict(list)
    for r in prompted:
        groups[(r["loc_region_id"], r["target_date"], r["target_type"])].append(r)
    events = []
    for key in sorted(groups):
        locs = groups[key]
        firing = [r for r in locs if (num(r.get("solar_low_cloud")) is not None
                                      and num(r["solar_low_cloud"]) < CONE_GATE)]
        events.append({"key": key, "region": key[0], "date": key[1], "type": key[2],
                       "month": key[1][:7], "lead": int(min(num(r["days_ahead"]) for r in locs)),
                       "locs": locs, "firing": firing,
                       "gated": len(firing) * 2 >= len(locs) and len(firing) > 0, "weight": 1.0})
    return events


def pick_locations(ev: dict, seed: int, k: int = LOC_PER_EVENT) -> List[dict]:
    """Seeded choice of up to k firing locations (same inputs -> same picks)."""
    pool = sorted(ev["firing"], key=lambda r: int(r["location_id"]))
    rng = random.Random(f"{seed}|{ev['region']}|{ev['date']}|{ev['type']}")
    return sorted(rng.sample(pool, min(k, len(pool))), key=lambda r: int(r["location_id"]))


def stratify(events: Sequence[dict], cap: int, seed: int) -> List[dict]:
    """Weighted stratified sample by (month, event, lead) when over cap; else all, weight 1."""
    events = sorted(events, key=lambda e: e["key"])
    if len(events) <= cap:
        return [dict(e, weight=1.0) for e in events]
    strata: Dict[tuple, List[dict]] = defaultdict(list)
    for e in events:
        strata[(e["month"], e["type"], e["lead"])].append(e)
    out = []
    for sk in sorted(strata):
        members = strata[sk]
        k = min(len(members), max(1, round(cap * len(members) / len(events))))
        rng = random.Random(f"{seed}|{sk}")
        for e in sorted(rng.sample(members, k), key=lambda e: e["key"]):
            out.append(dict(e, weight=len(members) / k))
    return out


# ---------------------------------------------------------------------------------- requests
class Ctx:
    """Everything needed to fetch and read one sampled location-row."""

    def __init__(self, row: dict):
        self.row = row
        self.lat, self.lon = float(row["loc_lat"]), float(row["loc_lon"])
        self.az = float(row["azimuth_deg"])
        ev = datetime.strptime(row["solar_event_time"], "%Y-%m-%dT%H:%M:%S")
        self.day = ev.date().isoformat()
        times = [f"{self.day}T{h:02d}:00" for h in range(24)]
        self.hour = times[slots.find_best_index(times, ev, row["target_type"])]
        self.lead = int(float(row["days_ahead"]))
        p = geometry.probe_points(self.lat, self.lon, self.az, DISTANCES)
        self.pts = dict(zip(DISTANCES, p))
        self.cone = geometry.cone_points(self.lat, self.lon, geometry.java_round(self.az))


def src_spec(src: tuple):
    """(kind, hourly vars, models) for a source key."""
    if src[0] == "era5":
        return "archive", om.CLOUD_VARS, "era5"
    if src[0] == "prev":
        return "previous_runs", [om.prev_var(v, src[1]) for v in om.CLOUD_VARS], MODEL
    return "historical_forecast", om.PRESSURE_VARS, None


def collect_needs(ctxs: Sequence[Ctx], pilot: bool, fc: bool = True) -> Dict[tuple, Dict[str, om.Coord]]:
    """(source, day) -> {grid cell: coord}: only what the CSV does not already hold.

    ``fc`` False means the forecast side is stored-only: Previous Runs and Historical Forecast are
    not requested at all (ERA5 still is)."""
    needs: Dict[tuple, Dict[str, om.Coord]] = defaultdict(dict)

    def need(src: tuple, ctx: Ctx, coord: om.Coord) -> None:
        needs[(src, ctx.day)].setdefault(geometry.grid_key(*coord), om.round_coord(*coord))

    for c in ctxs:
        r = c.row
        need(("era5",), c, c.pts[165])
        need(("era5",), c, c.pts[320])
        if num(r.get("cv_far_mid_cloud")) is None or num(r.get("cv_far_high_cloud")) is None:
            need(("era5",), c, c.pts[226])
        if not fc:
            continue
        need(("prev", c.lead), c, c.pts[165])
        need(("prev", c.lead), c, c.pts[320])
        if pilot or num(r.get("far_solar_mid_cloud")) is None or num(r.get("far_solar_high_cloud")) is None:
            need(("prev", c.lead), c, c.pts[226])
        if pilot or any(num(r.get(f"far_solar_cloud_{lv}hpa")) is None for lv in (600, 500, 400)):
            need(("hist",), c, c.pts[226])  # H5 reads 600, 500 and 400 hPa; any gap is fetched
        if pilot:
            for cp in c.cone:
                need(("prev", c.lead), c, cp)
    return needs


def fetch_needs(needs: Dict[tuple, Dict[str, om.Coord]], client: om.Client) -> Dict[tuple, om.Point]:
    """Execute every need (chunked by the client) -> {(source, day, cell): Point}."""
    values: Dict[tuple, om.Point] = {}
    for (src, day), cells in sorted(needs.items(), key=lambda kv: (str(kv[0][0]), kv[0][1])):
        keys = sorted(cells)
        kind, vs, models = src_spec(src)
        pts = client.get(kind, [cells[k] for k in keys], vs, day, day, models=models)
        for k, p in zip(keys, pts):
            values[(src, day, k)] = p
    return values


def rd(values: dict, src: tuple, ctx: Ctx, coord: om.Coord, var: str) -> Optional[float]:
    """Fetched reading at the production hour for the coord's grid cell."""
    p = values.get((src, ctx.day, geometry.grid_key(*coord)))
    return None if p is None else p.value(ctx.hour, var)


def readings(ctx: Ctx, values: dict, use_fetched_fc: bool) -> dict:
    """Forecast-side and ERA5-side readings for one location (None where unavailable)."""
    r, lead = ctx.row, ctx.lead

    def fc(stored_col: Optional[str], var: str, dist: int) -> Optional[float]:
        v = num(r.get(stored_col)) if stored_col else None
        if v is not None:
            return v
        if not use_fetched_fc:
            return None
        return rd(values, ("prev", lead), ctx, ctx.pts[dist], om.prev_var(var, lead))

    def obs(stored_col: Optional[str], var: str, dist: int) -> Optional[float]:
        v = num(r.get(stored_col)) if stored_col else None
        return v if v is not None else rd(values, ("era5",), ctx, ctx.pts[dist], var)

    press = {}
    for lvl, col in PRESS_COLS.items():
        v = num(r.get(col))
        if v is None and use_fetched_fc:
            v = rd(values, ("hist",), ctx, ctx.pts[226], PRESS_VAR[lvl])
        press[lvl] = v
    return {
        "fc226_mid": fc("far_solar_mid_cloud", "cloud_cover_mid", 226),
        "fc226_high": fc("far_solar_high_cloud", "cloud_cover_high", 226),
        "fc165_low": fc(None, "cloud_cover_low", 165), "fc165_mid": fc(None, "cloud_cover_mid", 165),
        "fc320_high": fc(None, "cloud_cover_high", 320), "press": press,
        "ob226_mid": obs("cv_far_mid_cloud", "cloud_cover_mid", 226),
        "ob226_high": obs("cv_far_high_cloud", "cloud_cover_high", 226),
        "ob165_low": obs(None, "cloud_cover_low", 165), "ob165_mid": obs(None, "cloud_cover_mid", 165),
        "ob320_high": obs(None, "cloud_cover_high", 320),
    }


# ---------------------------------------------------------------------------------- hypotheses
def any_ge(tests: Sequence[Tuple[Optional[float], float]]) -> Optional[bool]:
    """True if any (value >= threshold); None if no True and any value missing; else False."""
    if any(v is not None and v >= t for v, t in tests):
        return True
    return None if any(v is None for v, _ in tests) else False


def _or(a: Optional[bool], b: Optional[bool]) -> Optional[bool]:
    if a is True or b is True:
        return True
    return None if (a is None or b is None) else False


def loc_flags(rd_: dict) -> Dict[str, Tuple[Optional[bool], Optional[bool]]]:
    """Per location: hypothesis -> (forecast flag, observed flag); None = not evaluable."""
    p = rd_["press"]
    h = {
        "H1": (any_ge([(rd_["fc226_high"], 60)]), any_ge([(rd_["ob226_high"], 60)])),
        "H2": (any_ge([(rd_["fc226_mid"], 60)]), any_ge([(rd_["ob226_mid"], 60)])),
        "H3": (any_ge([(rd_["fc165_low"], 50), (rd_["fc165_mid"], 60)]),
               any_ge([(rd_["ob165_low"], 50), (rd_["ob165_mid"], 60)])),
        "H4": (any_ge([(rd_["fc320_high"], 60)]), any_ge([(rd_["ob320_high"], 60)])),
        "H5": (any_ge([(p["600"], 60), (p["500"], 60), (p["400"], 60)]),
               any_ge([(rd_["ob226_mid"], 60), (rd_["ob226_high"], 60)])),
    }
    h["H1|H2"] = (_or(h["H1"][0], h["H2"][0]), _or(h["H1"][1], h["H2"][1]))
    return h


def event_outcomes(ev: dict, loc_readings: Sequence[dict]) -> Dict[str, Optional[Tuple[bool, bool]]]:
    """Region-event level (flagged, observed) per hypothesis from its sampled locations; None if
    no location is evaluable on both sides."""
    out = {}
    flags = [loc_flags(r) for r in loc_readings]
    for h in HNAMES:
        valid = [(f[h][0], f[h][1]) for f in flags if f[h][0] is not None and f[h][1] is not None]
        if not valid:
            out[h] = None
            continue
        n = len(valid)
        out[h] = (2 * sum(1 for a, _ in valid if a) >= n, 2 * sum(1 for _, b in valid if b) >= n)
    return out


# ---------------------------------------------------------------------------------- statistics
def wilson(k: float, n: float, z: float = 1.96) -> Tuple[float, float]:
    """Wilson score interval for k successes of n (n may be an effective, fractional size)."""
    if n <= 0:
        return (float("nan"), float("nan"))
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, c - h), min(1.0, c + h))


def calendar_days(dates: Sequence[str]) -> List[str]:
    """Every ISO calendar day from min to max of ``dates`` inclusive (empty days included)."""
    if not dates:
        return []
    d0, d1 = date.fromisoformat(min(dates)), date.fromisoformat(max(dates))
    return [(d0 + timedelta(days=i)).isoformat() for i in range((d1 - d0).days + 1)]


def draw_blocks(n_days: int, block: int, rng: random.Random) -> List[Tuple[int, int]]:
    """Non-circular moving-block draw over ``n_days`` calendar days: (start, length) pairs, each
    start uniform over the valid starts 0..n_days-block, concatenated until the original length is
    reached (the last block is trimmed)."""
    b = max(1, min(block, n_days))
    out, have = [], 0
    while have < n_days:
        take = min(b, n_days - have)
        out.append((rng.randrange(0, n_days - b + 1), take))
        have += take
    return out


def _pct_ci(vals: List[float]) -> Tuple[float, float]:
    vals = sorted(vals)
    return (vals[int(0.025 * (len(vals) - 1))], vals[int(round(0.975 * (len(vals) - 1)))])


def block_bootstrap(per_date: Dict[str, Tuple[float, float, float, float]], block: int = BLOCK_DAYS,
                    draws: int = DRAWS, seed: int = BOOT_SEED) -> dict:
    """Moving-block bootstrap over CALENDAR days (days without events count as zeros).

    ``per_date``: date -> (flagged, true_pos, observed, n) weighted sums. Returns
    {"lift": (lo, hi, valid), "recall": (lo, hi, valid)} as 2.5/97.5 percentiles; NaN when fewer
    than 20 draws are valid."""
    nan = (float("nan"), float("nan"), 0)
    if not per_date:
        return {"lift": nan, "recall": nan}
    days = calendar_days(list(per_date))
    D = len(days)
    pre = [[0.0] * 4]
    for d in days:
        a = per_date.get(d, (0.0, 0.0, 0.0, 0.0))
        pre.append([pre[-1][i] + a[i] for i in range(4)])
    rng = random.Random(seed)
    lifts, recalls = [], []
    for _ in range(draws):
        f = t = o = n = 0.0
        for st, ln in draw_blocks(D, block, rng):
            lo_, hi_ = pre[st], pre[st + ln]
            f += hi_[0] - lo_[0]; t += hi_[1] - lo_[1]; o += hi_[2] - lo_[2]; n += hi_[3] - lo_[3]
        if f > 0 and o > 0 and n > 0:
            lifts.append((t / f) / (o / n))
        if o > 0:
            recalls.append(t / o)
    out = {}
    for name, vals in (("lift", lifts), ("recall", recalls)):
        out[name] = _pct_ci(vals) + (len(vals),) if len(vals) >= 20 else (float("nan"), float("nan"), len(vals))
    return out


def block_bootstrap_lift(per_date, block: int = BLOCK_DAYS, draws: int = DRAWS,
                         seed: int = BOOT_SEED) -> Tuple[float, float, int]:
    """Lift CI only (see ``block_bootstrap``)."""
    return block_bootstrap(per_date, block, draws, seed)["lift"]


def hypothesis_stats(events: Sequence[dict], h: str, total_prompted: int,
                     gated_weight: Optional[float] = None) -> dict:
    """Precision/recall/lift with Wilson and block-bootstrap intervals for one hypothesis.

    ``events``: dicts with ``date``, ``weight`` and ``out`` (event_outcomes), ALL sampled gated
    events of the slice (events with ``out[h] is None`` are not evaluable but still count toward
    the gated weight). Weighted sums drive the point estimates; Wilson intervals use Kish
    effective sizes.

    Failure population (share of ALL prompted region-events whose far deck is observed): the
    observed rate among observed-evaluable gated events, scaled by the total gated weight, over
    ``total_prompted``; NaN ("not evaluable") with fewer than ``MIN_EVALUABLE`` evaluable events."""
    per_date: Dict[str, List[float]] = defaultdict(lambda: [0.0, 0.0, 0.0, 0.0])
    used = [e for e in events if e["out"].get(h) is not None]
    gated_w = sum(e["weight"] for e in events) if gated_weight is None else gated_weight
    F = T = O = N = 0.0
    w2 = 0.0
    fl_ev, fl_dates = 0, set()
    for e in used:
        fl, ob = e["out"][h]
        w = e["weight"]
        a = per_date[e["date"]]
        a[3] += w; N += w; w2 += w * w
        if fl:
            a[0] += w; F += w; fl_ev += 1; fl_dates.add(e["date"])
        if ob: a[2] += w; O += w
        if fl and ob: a[1] += w; T += w
    neff = (N * N / w2) if w2 else 0.0
    prec = T / F if F else float("nan")
    rec = T / O if O else float("nan")
    base = O / N if N else float("nan")
    lift = prec / base if F and O and N else float("nan")
    scale = neff / N if N else 0.0
    plo, phi = wilson(T * scale, F * scale) if F else (float("nan"), float("nan"))
    blo, bhi = wilson(O * scale, neff) if N else (float("nan"), float("nan"))
    pd = {d: tuple(v) for d, v in per_date.items()}
    boots = {b: block_bootstrap(pd, block=b) for b in BLOCK_SENSITIVITY}
    main_b = boots[BLOCK_DAYS] if BLOCK_DAYS in boots else block_bootstrap(pd, block=BLOCK_DAYS)
    evaluable = len(used) >= MIN_EVALUABLE
    est_failure = (O / N) * gated_w / total_prompted if (evaluable and N and total_prompted) else float("nan")
    return {"h": h, "n_events": len(used), "n_dates": len(per_date), "flagged": F, "observed": O,
            "n_flagged_events": fl_ev, "n_flagged_dates": len(fl_dates), "evaluable": evaluable,
            "obs_events": sum(1 for e in used if e["out"][h][1]), "precision": prec,
            "prec_ci": (plo, phi), "recall": rec, "recall_ci": main_b["recall"][:2],
            "base": base, "base_ci": (blo, bhi), "lift": lift, "lift_ci": main_b["lift"][:2],
            "boot_valid": main_b["lift"][2],
            "boot_by_block": {b: {"lift": v["lift"][:2], "recall": v["recall"][:2]} for b, v in boots.items()},
            "failure_pop": est_failure}


def _nan(x) -> bool:
    return isinstance(x, float) and math.isnan(x)


def checklist(st: dict, pair: Optional[Tuple[dict, dict]] = None) -> List[Tuple[str, Optional[bool]]]:
    """Parent-plan decision rules for one hypothesis (pooled population). Each item is True, False
    or None (cannot be evaluated: NaN inputs or too few evaluable events).

    The count gate is on FLAGGED (forecast-condition-true) region-events, unweighted, and the
    distinct dates they fall on. For H5, ``pair`` = (H5 stats, H1|H2 stats) computed on the SAME
    events (evaluable for both)."""
    def ok_or_none(cond: bool, *vals: float) -> Optional[bool]:
        return None if any(_nan(v) for v in vals) else bool(cond)

    fe, fd = st["n_flagged_events"], st["n_flagged_dates"]
    items: List[Tuple[str, Optional[bool]]] = [
        (f"at least {MIN_EVENTS} FLAGGED region-events over at least {MIN_DATES} dates "
         f"({fe} over {fd})", fe >= MIN_EVENTS and fd >= MIN_DATES),
        (f"precision >= {PRECISION_BAR} with lift CI excluding 1 "
         f"(precision {f2(st['precision'])}, lift CI {f2(st['lift_ci'][0])}-{f2(st['lift_ci'][1])})",
         ok_or_none(st["precision"] >= PRECISION_BAR and st["lift_ci"][0] > 1.0,
                    st["precision"], st["lift_ci"][0]) if st["n_flagged_events"] else False),
        (f"failure population >= {FAILURE_BAR:.0%} of ALL prompted region-events "
         f"({'not evaluable' if _nan(st['failure_pop']) else format(st['failure_pop'], '.1%')})",
         ok_or_none(st["failure_pop"] >= FAILURE_BAR, st["failure_pop"])),
    ]
    if st["h"] == "H5" and pair is not None:
        h5, un = pair
        items.append((f"H5 lift CI lower bound above H1|H2 lift CI upper bound, same {h5['n_events']} "
                      f"events ({f2(h5['lift_ci'][0])} vs {f2(un['lift_ci'][1])})",
                      ok_or_none(h5["lift_ci"][0] > un["lift_ci"][1], h5["lift_ci"][0], un["lift_ci"][1])))
    return items


def verdict(st: dict, items: Sequence[Tuple[str, Optional[bool]]],
            pair: Optional[Tuple[dict, dict]] = None) -> str:
    """'not evaluable' when there are too few evaluable events (never 'does NOT meet the bar' on
    thin data); otherwise MEETS / does NOT meet / not evaluable (a rule had undefined inputs)."""
    n = st["n_events"] if pair is None or st["h"] != "H5" else min(st["n_events"], pair[0]["n_events"])
    if n < MIN_EVALUABLE:
        return f"not evaluable ({n} evaluable events, need {MIN_EVALUABLE})"
    if any(ok is False for _, ok in items):
        return "does NOT meet the bar"
    if any(ok is None for _, ok in items):
        return "not evaluable (a rule has undefined inputs)"
    return "MEETS the bar"


# ---------------------------------------------------------------------------------- pilot
def pilot_report(ctxs: Sequence[Ctx], values: dict) -> dict:
    """Fetched forecast-side values vs the stored ones (needs rows with stored fields)."""
    pairs: Dict[str, List[Tuple[float, float]]] = defaultdict(list)
    for c in ctxs:
        r, lead = c.row, c.lead
        ps = [rd(values, ("prev", lead), c, p, om.prev_var("cloud_cover_low", lead)) for p in c.cone]
        ps = [x for x in ps if x is not None]
        st = num(r.get("solar_low_cloud"))
        if ps and st is not None:
            pairs["cone low (mean of 3)"].append((sum(ps) / len(ps), st))
        for name, col, var in (("far low 226", "far_solar_low_cloud", "cloud_cover_low"),
                               ("far mid 226", "far_solar_mid_cloud", "cloud_cover_mid"),
                               ("far high 226", "far_solar_high_cloud", "cloud_cover_high")):
            st, fv = num(r.get(col)), rd(values, ("prev", lead), c, c.pts[226], om.prev_var(var, lead))
            if st is not None and fv is not None:
                pairs[name].append((fv, st))
        st, fv = num(r.get("far_solar_cloud_500hpa")), rd(values, ("hist",), c, c.pts[226], "cloud_cover_500hPa")
        if st is not None and fv is not None:
            pairs["500 hPa 226"].append((fv, st))
    rep = {}
    for name, ps in pairs.items():
        diffs = [abs(a - b) for a, b in ps]
        rep[name] = {"n": len(diffs), "median_abs_diff": statistics.median(diffs),
                     "within10": sum(d <= 10 for d in diffs) / len(diffs),
                     "within20": sum(d <= 20 for d in diffs) / len(diffs)}
    cone = rep.get("cone low (mean of 3)")
    if cone is None or cone["n"] < MIN_PILOT_PAIRS:
        rep["_verdict"] = "unknown"
        rep["_note"] = (f"fewer than {MIN_PILOT_PAIRS} comparable cone-low pairs "
                        f"({0 if cone is None else cone['n']}): fetched forecast values are NOT used")
    elif cone["median_abs_diff"] > RELIABLE_MAD:
        rep["_verdict"] = "UNRELIABLE"
        rep["_note"] = f"cone low median abs diff {cone['median_abs_diff']:.1f} pp > {RELIABLE_MAD:g}"
    else:
        rep["_verdict"] = "reliable"
        rep["_note"] = f"cone low median abs diff {cone['median_abs_diff']:.1f} pp <= {RELIABLE_MAD:g}"
    return rep


def forecast_side_reliable(verdict_value: Optional[str]) -> bool:
    """Fail closed: only an explicit 'reliable' verdict allows fetched forecast values."""
    return verdict_value == "reliable"


# ---------------------------------------------------------------------------------- report
def pct(x: float) -> str:
    """Percent text, NaN-safe."""
    return "n/a" if x != x else f"{100 * x:.0f}%"


def f2(x: float) -> str:
    """2-dp text, NaN-safe."""
    return "n/a" if x != x else f"{x:.2f}"


def render(info: dict) -> str:
    """Markdown report."""
    L = ["# Part C: H1-H5 sizing" + (" (PILOT)" if info["pilot"] else ""), ""]
    L.append(f"Commit: {info['commit']}. Seed {info['seed']}. Mode: {'PILOT (%d rows)' % info['pilot'] if info['pilot'] else 'full'}. "
             f"stats.py {'present (unused: local bootstrap)' if STATS_PRESENT else 'absent (local bootstrap)'}.")
    L += ["", "## Population", "",
          f"- prompted region-events (T+0/T+1, sky, all): {info['n_prompted']}",
          f"- training-case rows excluded (plan section 4, never pooled): {info.get('n_training_excluded', 0)}",
          f"- passing the cone gate (solar_low_cloud < {CONE_GATE:g} at >= half the locations): {info['n_gated']}",
          f"- sampled region-events: {info['n_sampled']} (weights applied: {info['weighted']}); "
          f"locations fetched: {info['n_locs']}",
          f"- forecast side: {'stored values + fetched Previous Runs / Historical Forecast' if info['fc_fetched'] else 'STORED values only (fetched forecast not verified reliable, or unavailable)'}",
          f"- API: {info['summary']}", ""]
    if info.get("pilot_rep"):
        L += ["## Calibration pilot (fetched forecast vs stored)", "",
              "| field | n | median abs diff (pp) | within 10 pp | within 20 pp |", "|---|---|---|---|---|"]
        for k, v in info["pilot_rep"].items():
            if k.startswith("_"):
                continue
            L.append(f"| {k} | {v['n']} | {v['median_abs_diff']:.1f} | {pct(v['within10'])} | {pct(v['within20'])} |")
        L += ["", f"15 pp reliability verdict (cone low median abs diff > {RELIABLE_MAD:g}): "
              f"**{info['pilot_rep']['_verdict']}** ({info['pilot_rep'].get('_note', '')}). "
              "Only an explicit 'reliable' lets the full run use fetched forecast values.", ""]
    L += ["## H1-H5 (pooled, then sunrise first)", ""]
    L += ["| H | slice | evaluable events | dates | flagged (events/dates) | observed | precision (Wilson) | recall (bootstrap CI) | base rate | lift (block-bootstrap CI) | failure pop / all prompted |",
          "|---|---|---|---|---|---|---|---|---|---|---|"]
    for h in HNAMES:
        for sl in ("pooled", "SUNRISE", "SUNSET"):
            s = info["stats"][(h, sl)]
            fp = f"{s['failure_pop']:.1%}" if s["failure_pop"] == s["failure_pop"] else f"not evaluable (<{MIN_EVALUABLE})"
            L.append(f"| {h} | {sl} | {s['n_events']} | {s['n_dates']} | "
                     f"{s['n_flagged_events']}/{s['n_flagged_dates']} | {s['observed']:.0f} | "
                     f"{pct(s['precision'])} ({pct(s['prec_ci'][0])}-{pct(s['prec_ci'][1])}) | "
                     f"{pct(s['recall'])} ({pct(s['recall_ci'][0])}-{pct(s['recall_ci'][1])}) | "
                     f"{pct(s['base'])} | {f2(s['lift'])} ({f2(s['lift_ci'][0])}-{f2(s['lift_ci'][1])}) | {fp} |")
    L += ["", f"Bootstrap: non-circular moving blocks of {BLOCK_DAYS} calendar days (empty days included), "
          f"{DRAWS} draws. Sensitivity to block length (pooled; lift CI / recall CI):", "",
          "| H | " + " | ".join(f"block {b}" for b in BLOCK_SENSITIVITY) + " |",
          "|---|" + "---|" * len(BLOCK_SENSITIVITY)]
    for h in HNAMES:
        bb = info["stats"][(h, "pooled")]["boot_by_block"]
        L.append(f"| {h} | " + " | ".join(
            f"{f2(bb[b]['lift'][0])}-{f2(bb[b]['lift'][1])} / {pct(bb[b]['recall'][0])}-{pct(bb[b]['recall'][1])}"
            for b in BLOCK_SENSITIVITY) + " |")
    L += ["", "Only the pooled rows feed the checklist; slices are descriptive.", "", "## Decision checklist (parent plan)", ""]
    for h in ("H1", "H2", "H3", "H4", "H5"):
        L.append(f"### {h}")
        st = info["stats"][(h, "pooled")]
        pair = info.get("h5_pair") if h == "H5" else None
        items = checklist(st, pair)
        for t, ok in items:
            L.append(f"- [{'x' if ok else '?' if ok is None else ' '}] {t}")
        L.append(f"- verdict: {verdict(st, items, pair)}")
        L.append("")
    L.append("Retrospective figures are exploratory; the decisive H1, H2 and H5 run is prospective "
             "(V175/V176 capture, re-run on the DB alone).")
    return "\n".join(L) + "\n"


# ---------------------------------------------------------------------------------- driver
def run(rows: Sequence[dict], client: om.Client, *, seed: int = DEFAULT_SEED, cap: int = DEFAULT_CAP,
        pilot: int = 0, fc_reliable: bool = True, commit: str = "unrecorded", plan_only: bool = False) -> dict:
    """Whole pipeline on in-memory rows; returns the info dict for ``render``."""
    prompted = latest_prompted(rows)
    training = [r for r in prompted if is_training(r)]
    prompted = [r for r in prompted if not is_training(r)]  # plan section 4: never pooled
    events = region_events(prompted)
    gated = [e for e in events if e["gated"]]
    sampled = stratify(gated, cap, seed)
    for e in sampled:
        e["sample"] = pick_locations(e, seed)
    ctx_by_ev = {e["key"]: [Ctx(r) for r in e["sample"]] for e in sampled}
    all_ctx = [c for e in sampled for c in ctx_by_ev[e["key"]]]
    if pilot:
        shuffled = sorted(all_ctx, key=lambda c: int(c.row["evaluation_id"]))
        random.Random(f"{seed}|pilot").shuffle(shuffled)
        keep = {id(c) for c in shuffled[:pilot]}
        all_ctx = [c for c in all_ctx if id(c) in keep]
        for e in sampled:
            ctx_by_ev[e["key"]] = [c for c in ctx_by_ev[e["key"]] if id(c) in keep]
        sampled = [e for e in sampled if ctx_by_ev[e["key"]]]
    needs = collect_needs(all_ctx, bool(pilot), fc=fc_reliable or bool(pilot))
    values = fetch_needs(needs, client)
    info = {"n_training_excluded": len(training), "n_prompted": len(events), "n_gated": len(gated), "n_sampled": len(sampled),
            "n_locs": len(all_ctx), "weighted": any(e["weight"] != 1.0 for e in sampled),
            "seed": seed, "pilot": pilot, "commit": commit, "fc_fetched": fc_reliable,
            "summary": client.summary(), "needs": sum(len(v) for v in needs.values())}
    if plan_only:
        return info
    if pilot:
        info["pilot_rep"] = pilot_report(all_ctx, values)
        info["fc_fetched"] = fc_reliable = forecast_side_reliable(info["pilot_rep"]["_verdict"])
    evs = []
    for e in sampled:
        rds = [readings(c, values, fc_reliable) for c in ctx_by_ev[e["key"]]]
        evs.append({"date": e["date"], "type": e["type"], "weight": e["weight"], "out": event_outcomes(e, rds)})
    info["stats"] = {}
    for h in HNAMES:
        for sl in ("pooled", "SUNRISE", "SUNSET"):
            sub = evs if sl == "pooled" else [x for x in evs if x["type"] == sl]
            info["stats"][(h, sl)] = hypothesis_stats(sub, h, len(events))
    common = [x for x in evs if x["out"].get("H5") is not None and x["out"].get("H1|H2") is not None]
    gw = sum(x["weight"] for x in evs)
    info["h5_pair"] = (hypothesis_stats(common, "H5", len(events), gw),
                       hypothesis_stats(common, "H1|H2", len(events), gw))
    return info


def projection_client(client: om.Client) -> om.Client:
    """A dry-run twin of ``client`` (same cache, budget, clock) for projecting the same request set."""
    return om.Client(client.cache_dir, client.budget, fetch=client.fetch, pause_s=0, dry_run=True,
                     today=client._today, now=client._now)


def days_needed(weight_total: int, budget: int, used_today: int) -> int:
    """Calendar days (counting today's remaining budget) to spend ``weight_total``."""
    remaining = max(0, budget - used_today)
    if weight_total <= remaining:
        return 1
    return 1 + math.ceil((weight_total - remaining) / budget) if budget else 0


def main(argv: Optional[Sequence[str]] = None, client: Optional[om.Client] = None) -> int:
    """CLI entry point. Exit codes: 0 ok, 2 stopped (limit/budget/error; re-run resumes from cache),
    3 refused to start without --yes."""
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("csv")
    ap.add_argument("--out", default="out")
    ap.add_argument("--cache", default="cache")
    ap.add_argument("--budget", type=int, default=om.DEFAULT_BUDGET)
    ap.add_argument("--pause", type=float, default=om.DEFAULT_PAUSE_S)
    ap.add_argument("--seed", type=int, default=DEFAULT_SEED)
    ap.add_argument("--cap", type=int, default=DEFAULT_CAP)
    ap.add_argument("--pilot", type=int, default=0, metavar="N")
    ap.add_argument("--plan", action="store_true", help="print projected calls and days, then stop")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--yes", action="store_true",
                    help="start spending API calls after seeing the projection (required for a live run)")
    ap.add_argument("--no-wait", action="store_true",
                    help="stop cleanly at the hourly cap instead of sleeping to the next hour")
    ap.add_argument("--commit", default="unrecorded")
    ap.add_argument("--forecast", choices=["auto", "fetched", "stored"], default="auto",
                    help="forecast side: auto uses fetched values ONLY if out/pilot.json says 'reliable'")
    a = ap.parse_args(argv)
    rows = load_rows(a.csv)
    fc_reliable = True
    pj = os.path.join(a.out, "pilot.json")
    if a.forecast == "stored":
        fc_reliable = False
    elif a.forecast == "fetched":
        print("WARNING: --forecast fetched overrides the pilot verdict")
    elif not a.pilot:       # auto: fail closed
        verdict_value = None
        if os.path.exists(pj):
            try:
                with open(pj) as f:
                    verdict_value = json.load(f).get("_verdict")
            except (OSError, ValueError):
                verdict_value = None
        fc_reliable = forecast_side_reliable(verdict_value)
        if not fc_reliable:
            print(f"forecast side: stored values only (pilot verdict: {verdict_value!r}; need an explicit "
                  f"'reliable' in {pj})")
    planning = a.plan or a.dry_run
    kw = dict(seed=a.seed, cap=a.cap, pilot=a.pilot, fc_reliable=fc_reliable, commit=a.commit)
    try:
        client = client or om.Client(a.cache, a.budget, pause_s=a.pause, dry_run=planning,
                                     wait_for_hour=not a.no_wait)
        if not planning:
            proj = projection_client(client)
            run(rows, proj, plan_only=True, **kw)
            print(f"projection: {proj.projected_weight} weighted calls in {proj.projected_requests} requests "
                  f"({proj.cache_hits} coordinates already cached); about "
                  f"{days_needed(proj.projected_weight, client.budget, client.used_today())} day(s) at "
                  f"{client.budget}/day, hourly cap {client.hourly_cap}, "
                  f"{client.used_today()} already used today")
            if proj.projected_requests and not a.yes:
                cmd = shlex.join(["python3", "size.py"] + list(argv if argv is not None else sys.argv[1:]) + ["--yes"])
                print(f"REFUSING to make live calls without --yes. To proceed, re-run:\n  {cmd}")
                return 3
        info = run(rows, client, plan_only=planning, **kw)
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
    if planning:
        print(f"prompted region-events {info['n_prompted']}, gated {info['n_gated']}, sampled "
              f"{info['n_sampled']}, locations {info['n_locs']}")
        print(f"projected {client.projected_weight} weighted calls in {client.projected_requests} requests "
              f"= about {days_needed(client.projected_weight, a.budget, 0)} day(s) at {a.budget}/day "
              f"({client.cache_hits} coordinates already cached)")
        return 0
    os.makedirs(a.out, exist_ok=True)
    if a.pilot:
        om.atomic_write(pj, json.dumps(info["pilot_rep"], indent=1))
    path = os.path.join(a.out, "pilot.md" if a.pilot else "part_c.md")
    om.atomic_write(path, render(info))
    print(client.summary())
    print(f"wrote {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
