"""Shared pieces of the model-disagreement sizing study (docs/engineering/model-disagreement-sizing-plan.md).

Everything here is offline and deterministic: loading the P1 export, unit deduplication (plan section 2,
"Unit"), the population P and its funnel ("Population"), the per-model value M and the S2 vote
("Model value", "Signal"), and the request plan the sampler, the fetch step and the report all share
(section 4, T2/T3/T4).

P1's directory is put on ``sys.path`` with ``append`` (never ``insert(0)``: P1 owns ``probe.py`` and
``size.py``, and a P1-first path must not shadow this directory's own ``md_*`` modules). Only P1's uniquely
named modules are imported: ``openmeteo``, ``geometry``, ``slots`` and ``stats``. P1's files are never edited.
Standard library only.
"""
from __future__ import annotations

import csv
import os
import sys
from dataclasses import dataclass, field
from datetime import datetime
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

HERE = os.path.dirname(os.path.abspath(__file__))
P1_DIR = os.path.normpath(os.path.join(HERE, "..", "far-cloud-p1"))
if P1_DIR not in sys.path:
    sys.path.append(P1_DIR)          # append, never insert(0): see the module docstring

import geometry  # noqa: E402
import openmeteo as om  # noqa: E402
import slots  # noqa: E402
import stats  # noqa: E402

# ------------------------------------------------------------------------------ constants
UKMO, BEST, ICON, GFS, MF, ECMWF = ("ukmo_seamless", "best_match", "icon_seamless", "gfs_seamless",
                                    "meteofrance_seamless", "ecmwf_ifs025")
# Fixed order: the cache key is the full URL, so T0 and T3 must send identical requests.
MODELS = (UKMO, BEST, ICON, GFS, MF, ECMWF)
VOTERS = (ICON, GFS, MF)             # the decision signal S2. ECMWF is never in it (plan section 2).
SHORT = {UKMO: "UKMO", BEST: "best_match", ICON: "ICON", GFS: "GFS", MF: "Meteo-France", ECMWF: "ECMWF"}

LAYER_VARS = list(om.CLOUD_VARS)                                  # low, mid, high
PL_VARS = ["cloud_cover_700hPa", "cloud_cover_500hPa", "cloud_cover_300hPa"]
HOURLY = LAYER_VARS + PL_VARS        # six variables: one weighted call per coordinate (ceil(6/10) == 1)
KIND = "historical_forecast"

THRESH = 30                          # one threshold on a 0-100 proxy, never retuned (plan section 5)
Y50 = 50
LOW_BLOCK = 60                       # low >= 60 means a blocked horizon: no canvas can be lit
LEAD_FAIR_MAX = 10                   # P': refetched UKMO and best_match must read M < 10 (plan section 3)
EVENT_FORMAT = "%Y-%m-%dT%H:%M:%S"
DEFAULT_CACHE = os.path.join(P1_DIR, "cache")    # one cache and one budget counter for both studies
MOVED_TOL_DEG = 0.01                 # moved-location guard

# (label, name fragment, dates, target type). The trigger and far-cloud-lid-plan.md section 1's training
# cases: excluded from the decision, shown as worked examples.
TRIGGER = ("31 Oct 2026 Angel of the North sunset (trigger)", "angel of the north", ("2026-10-31",), "SUNSET")
CASES = [
    TRIGGER,
    ("13 Oct 2026 Angel of the North sunset (training)", "angel of the north", ("2026-10-13",), "SUNSET"),
    ("4 Oct 2026 Low Hauxley sunrise (training)", "low hauxley", ("2026-10-04",), "SUNRISE"),
    ("Copt Hill sunset (training)", "copt hill", ("2026-03-05", "2026-03-11"), "SUNSET"),
]


# ------------------------------------------------------------------------------ parsing
def num(s) -> Optional[float]:
    """CSV cell to float; empty or unparseable to None."""
    if s is None or str(s).strip() == "":
        return None
    try:
        return float(s)
    except ValueError:
        return None


def inum(s) -> Optional[int]:
    """CSV cell to int (via float, so '3.0' works); None when empty."""
    v = num(s)
    return None if v is None else int(v)


def parse_event(v: str) -> datetime:
    """A timestamp as the export writes it (naive UTC, ISO text; any fraction or zone is cut off)."""
    return datetime.strptime(v[:19], EVENT_FORMAT)


def case_label(name: Optional[str], date: Optional[str], ttype: Optional[str]) -> Optional[str]:
    """The worked-example label if this (location name, date, type) is the trigger or a training case."""
    n = (name or "").lower()
    for label, frag, dates, tt in CASES:
        if frag in n and date in dates and ttype == tt:
            return label
    return None


@dataclass
class Unit:
    """One (location, target date, target type, days_ahead) slot, represented by its latest pre-event run."""
    eval_id: int
    location_id: str
    name: str
    lat: Optional[float]
    lon: Optional[float]
    az: Optional[int]                 # int(azimuth_deg): cone_points wants whole degrees
    date: str
    ttype: str
    days_ahead: int
    run_at: datetime
    event: datetime                   # naive UTC
    rating: Optional[int]
    stored_low: Optional[float]       # solar cone (113 km), integer floor means as stored
    stored_mid: Optional[float]
    stored_high: Optional[float]
    stored_extra: List[Optional[float]] = field(default_factory=list)   # other stored mid/high readings
    cv_id: Optional[str] = None
    h_low: Optional[float] = None     # ERA5 coned horizon
    h_mid: Optional[float] = None
    h_high: Optional[float] = None
    o_low: Optional[float] = None     # ERA5 observer point
    o_mid: Optional[float] = None
    o_high: Optional[float] = None
    s_lat: Optional[float] = None     # cv_horizon_sample_lat/lon: where ERA5's cone centre was sampled
    s_lon: Optional[float] = None
    case: Optional[str] = None

    @property
    def lead_h(self) -> float:
        """Hours from the run to the solar event."""
        return (self.event - self.run_at).total_seconds() / 3600.0

    @property
    def month(self) -> str:
        """``YYYY-MM`` of the target date."""
        return self.date[:7]

    @property
    def event_date(self) -> str:
        """The event's UTC date: the fetch date, as production's verifier groups it."""
        return self.event.date().isoformat()


# Columns read from the P1 export (by name; extra columns and any order are fine).
STORED_EXTRA_COLS = ("mid_cloud", "high_cloud", "far_solar_mid_cloud", "far_solar_high_cloud",
                     "far_solar_cloud_700hpa", "far_solar_cloud_600hpa", "far_solar_cloud_500hpa",
                     "far_solar_cloud_400hpa", "far_solar_cloud_300hpa")
REQUIRED_COLS = ("evaluation_id", "location_id", "target_date", "target_type", "forecast_run_at",
                 "solar_event_time", "azimuth_deg", "days_ahead", "batch_state", "solar_low_cloud",
                 "solar_mid_cloud", "solar_high_cloud", "loc_name", "loc_lat", "loc_lon", "cv_id",
                 "cv_horizon_sample_lat", "cv_horizon_sample_lon", "cv_horizon_low_cloud",
                 "cv_horizon_mid_cloud", "cv_horizon_high_cloud")


def load_rows(path: str) -> List[dict]:
    """Read the export. A missing required column is a ValueError naming it."""
    with open(path, newline="", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        missing = [c for c in REQUIRED_COLS if c not in (reader.fieldnames or [])]
        if missing:
            raise ValueError("%s: header is missing required column(s): %s" % (path, ", ".join(missing)))
        return list(reader)


def row_to_unit(r: dict) -> Optional[Unit]:
    """A raw CSV row to a Unit, or None when it lacks the identity, run time or event time that
    deduplication needs (such a row can never be 'the latest run before the event')."""
    ev, run = (r.get("solar_event_time") or "").strip(), (r.get("forecast_run_at") or "").strip()
    if not ev or not run or not r.get("evaluation_id") or not r.get("location_id"):
        return None
    try:
        event, run_at = parse_event(ev), parse_event(run)
        eid, days = int(r["evaluation_id"]), inum(r.get("days_ahead"))
    except (ValueError, TypeError):
        return None
    if days is None:
        return None
    az = num(r.get("azimuth_deg"))
    ttype, date = r.get("target_type") or "", r.get("target_date") or ""
    return Unit(
        eval_id=eid, location_id=r["location_id"], name=r.get("loc_name") or "",
        lat=num(r.get("loc_lat")), lon=num(r.get("loc_lon")),
        az=None if az is None else int(az),                     # int(azimuth_deg), per the plan
        date=date, ttype=ttype, days_ahead=days, run_at=run_at, event=event, rating=inum(r.get("rating")),
        stored_low=num(r.get("solar_low_cloud")), stored_mid=num(r.get("solar_mid_cloud")),
        stored_high=num(r.get("solar_high_cloud")),
        stored_extra=[num(r.get(c)) for c in STORED_EXTRA_COLS],
        cv_id=(r.get("cv_id") or "").strip() or None,
        h_low=num(r.get("cv_horizon_low_cloud")), h_mid=num(r.get("cv_horizon_mid_cloud")),
        h_high=num(r.get("cv_horizon_high_cloud")),
        o_low=num(r.get("cv_observer_low_cloud")), o_mid=num(r.get("cv_observer_mid_cloud")),
        o_high=num(r.get("cv_observer_high_cloud")),
        s_lat=num(r.get("cv_horizon_sample_lat")), s_lon=num(r.get("cv_horizon_sample_lon")),
        case=case_label(r.get("loc_name"), date, ttype))


# ------------------------------------------------------------------------------ units
@dataclass
class DedupCounts:
    """Row counts before and after deduplication (the plan asks for both)."""
    rows: int = 0
    eligible: int = 0            # batch_state NULL/SCORED and forecast_run_at < solar_event_time
    units: int = 0


def dedup_units(rows: Iterable[dict]) -> Tuple[List[Unit], DedupCounts]:
    """One unit per (location_id, target_date, target_type, days_ahead): the run with the greatest
    ``forecast_run_at`` strictly before ``solar_event_time`` (ties by ``evaluation_id``), ``batch_state``
    NULL or SCORED as in P1. Triaged rows are kept: their stored cloud is still the forecast. The latest
    run wins even when it carries no ERA5 verification (that unit then simply leaves P); falling back to
    an earlier run would change the lead."""
    counts = DedupCounts()
    best: Dict[tuple, Unit] = {}
    for r in rows:
        counts.rows += 1
        if (r.get("batch_state") or "").strip() not in ("", "SCORED"):
            continue
        u = row_to_unit(r)
        if u is None or not u.run_at < u.event:
            continue
        counts.eligible += 1
        k = (u.location_id, u.date, u.ttype, u.days_ahead)
        cur = best.get(k)
        if cur is None or (u.run_at, u.eval_id) > (cur.run_at, cur.eval_id):
            best[k] = u
    counts.units = len(best)
    return sorted(best.values(), key=lambda u: u.eval_id), counts


# ------------------------------------------------------------------------------ population
def cone_centre(u: Unit) -> Optional[geometry.Point]:
    """The recomputed centre cone point (bearing = azimuth, 113 km), or None without geometry."""
    if u.lat is None or u.lon is None or u.az is None:
        return None
    return geometry.cone_points(u.lat, u.lon, u.az)[1]


def moved(u: Unit) -> bool:
    """Moved-location guard: the recomputed centre differs from ERA5's recorded sample point by more
    than 0.01 degrees (the location was edited after the run). Unknown sample point: not moved."""
    c = cone_centre(u)
    if c is None or u.s_lat is None or u.s_lon is None:
        return False
    return abs(c[0] - u.s_lat) > MOVED_TOL_DEG or abs(c[1] - u.s_lon) > MOVED_TOL_DEG


def era5_y(u: Unit, thresh: float = THRESH, mid_only: bool = False) -> Optional[bool]:
    """Outcome Y: ERA5 coned horizon ``max(mid, high) >= thresh`` and ``low < 60``. ``mid_only`` gives
    Y_mid (mid >= thresh). None when ERA5 is missing a needed layer."""
    if u.h_mid is None or u.h_low is None or (not mid_only and u.h_high is None):
        return None
    top = u.h_mid if mid_only else max(u.h_mid, u.h_high)
    return top >= thresh and u.h_low < LOW_BLOCK


def era5_y_observer(u: Unit) -> Optional[bool]:
    """Secondary outcome: the same at ERA5's observer point (no cone)."""
    if u.o_mid is None or u.o_high is None or u.o_low is None:
        return None
    return max(u.o_mid, u.o_high) >= THRESH and u.o_low < LOW_BLOCK


@dataclass
class Population:
    """P, its funnel and the units set aside."""
    members: List[Unit]
    funnel: List[Tuple[str, int]]
    worked: List[Unit]            # the trigger and training cases (T+0), whatever else they satisfy
    moved: int


def select_population(units: Sequence[Unit], t0_only: bool = True) -> Population:
    """Apply plan section 2's population filters in a fixed order, counting survivors at each step."""
    funnel: List[Tuple[str, int]] = [("units after deduplication", len(units))]
    cur = list(units)
    worked = [u for u in cur if u.case and u.days_ahead == 0]

    def step(label: str, keep) -> None:
        nonlocal cur
        cur = [u for u in cur if keep(u)]
        funnel.append((label, len(cur)))

    step("SUNRISE or SUNSET", lambda u: u.ttype in ("SUNRISE", "SUNSET"))
    if t0_only:
        step("days_ahead = 0 (the decision lead)", lambda u: u.days_ahead == 0)
    step("ERA5 verification row with horizon mid", lambda u: u.cv_id is not None and u.h_mid is not None)
    step("ERA5 horizon high and low present", lambda u: u.h_high is not None and u.h_low is not None)
    step("stored solar mid and high non-null and = 0",
         lambda u: u.stored_mid is not None and u.stored_high is not None
         and u.stored_mid == 0 and u.stored_high == 0)
    step("stored solar low < 60", lambda u: u.stored_low is not None and u.stored_low < LOW_BLOCK)
    step("geometry present (lat, lon, azimuth)", lambda u: cone_centre(u) is not None)
    n_moved = sum(1 for u in cur if moved(u))
    step("location not moved since the run (0.01 deg guard)", lambda u: not moved(u))
    step("not the trigger or a training case", lambda u: u.case is None)
    return Population(cur, funnel, worked, n_moved)


# ------------------------------------------------------------------------------ requests
Coord = Tuple[float, float]


def unit_cone(u: Unit) -> List[Coord]:
    """The three cone points (az-15, az, az+15 at 113 km), rounded to 4 dp as the client rounds."""
    return [om.round_coord(*p) for p in geometry.cone_points(u.lat, u.lon, u.az)]


def unit_points(u: Unit, observer: bool = False) -> List[Coord]:
    """Cone points, plus the observer point when asked (secondary, fetched only if budget allows)."""
    pts = unit_cone(u)
    if observer:
        pts = pts + [om.round_coord(u.lat, u.lon)]
    return pts


def plan_requests(units: Sequence[Unit], observer: bool = False) -> Dict[str, Dict[str, Coord]]:
    """date -> {grid_key: representative coordinate}. Requests are deduplicated by (date, grid_key), as
    production's cloud-point cache is: the first unit (by evaluation_id) to reach a 0.1 degree cell
    supplies the coordinate for everyone in it, so the fetch and the report agree on what was asked.

    Cone points claim their cells first (all units), observer points only fill cells still free, so the
    cone coordinates never depend on whether ``--observer`` was used."""
    plan: Dict[str, Dict[str, Coord]] = {}
    ordered = sorted(units, key=lambda x: x.eval_id)
    for with_observer in ((False, True) if observer else (False,)):
        for u in ordered:
            cells = plan.setdefault(u.event_date, {})
            pts = [om.round_coord(u.lat, u.lon)] if with_observer else unit_cone(u)
            for c in pts:
                cells.setdefault(geometry.grid_key(*c), c)
    return plan


def rep_coord(plan: Dict[str, Dict[str, Coord]], u: Unit, c: Coord) -> Coord:
    """The representative coordinate that stands for ``c`` on the unit's fetch date."""
    return plan[u.event_date][geometry.grid_key(*c)]


def planned_calls(plan: Dict[str, Dict[str, Coord]], models: Sequence[str] = MODELS) -> int:
    """Weighted calls for the whole plan: one per (date, model, coordinate) with six variables, one day."""
    return sum(om.weight(1, len(HOURLY), 1) for cells in plan.values() for _ in cells for _ in models)


# ------------------------------------------------------------------------------ model values
def _floor_mean(vals: Sequence[float]) -> int:
    """Integer floor mean, matching the stored and ERA5 arithmetic."""
    return int(sum(int(round(v)) for v in vals)) // len(vals)


def layer_value(points: Sequence[Optional[om.Point]], hour: str, vars_: Sequence[str],
                n_points: int = 3) -> Optional[int]:
    """Floor-mean each variable across the cone points, then take the max over the variables.

    ``None`` (not voting, never 0) unless every one of the ``n_points`` points exists and carries a
    non-null value for every variable: (mid 30, 30, 29) gives 29 and a missing high anywhere abstains."""
    if len(points) != n_points or any(p is None for p in points):
        return None
    means = []
    for var in vars_:
        vals = [p.value(hour, var) for p in points]
        if any(v is None for v in vals):
            return None
        means.append(_floor_mean(vals))
    return max(means)


def model_value(points: Sequence[Optional[om.Point]], hour: str) -> Optional[int]:
    """``M_m = max(sum(mid)//3, sum(high)//3)`` over the three cone points, or None (not voting)."""
    return layer_value(points, hour, ("cloud_cover_mid", "cloud_cover_high"))


def pressure_value(points: Sequence[Optional[om.Point]], hour: str) -> Optional[int]:
    """The S2-PL analogue: floor-mean each of the 700/500/300 hPa levels over the cone, then the max."""
    return layer_value(points, hour, PL_VARS)


def observer_value(point: Optional[om.Point], hour: str) -> Optional[int]:
    """Observer-point M: ``max(mid, high)`` at the single point (no cone), None unless both are present."""
    return layer_value([point], hour, ("cloud_cover_mid", "cloud_cover_high"), n_points=1)


def slot_hour(u: Unit, point: om.Point) -> Optional[str]:
    """The production slot (``slots.find_best_index``) among the fetched hours, as the API's
    ``YYYY-MM-DDTHH:MM`` string; None when nothing was fetched."""
    times = sorted(point.hours)
    if not times:
        return None
    return times[slots.find_best_index(times, u.event, u.ttype)]


def votes(values: Dict[str, Optional[int]], models: Sequence[str] = VOTERS,
          need: int = 2) -> Optional[bool]:
    """At least ``need`` of ``models`` read >= 30, and ONLY when every one of them is voting. None when
    any listed model abstains (such units are reported separately and never decide)."""
    vs = [values.get(m) for m in models]
    if any(v is None for v in vs):
        return None
    return sum(1 for v in vs if v >= THRESH) >= need


def safe_wilson(k: int, n: int):
    """``stats.wilson`` for integer counts only. Weighted (float) counts are a bug, never an input."""
    for x in (k, n):
        if isinstance(x, bool) or not isinstance(x, int):
            raise TypeError("wilson takes integer counts, got %r" % (x,))
    return stats.wilson(k, n)


def fmt_pct(x: Optional[float], digits: int = 1) -> str:
    """Fraction to a percentage string, '-' for None."""
    return "-" if x is None else "%.*f%%" % (digits, 100.0 * x)


def fmt_ci(k: int, n: int) -> str:
    """'k/n = p% (lo-hi)' using the Wilson interval."""
    w = safe_wilson(k, n)
    if w is None:
        return "%d/%d = -" % (k, n)
    return "%d/%d = %s (%s-%s)" % (k, n, fmt_pct(w[0]), fmt_pct(w[1]), fmt_pct(w[2]))
