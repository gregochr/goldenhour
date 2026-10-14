"""Cached, budgeted Open-Meteo client for the P1 scripts (stdlib only).

Design rules (docs/engineering/far-cloud-p1-plan.md section 8, T5):

* dates are always ``start_date``/``end_date`` -- ``past_days`` is never sent;
* ``timezone=UTC`` always; latitude/longitude rounded to 4 dp and sorted within a request;
* at most 20 coordinates per request (larger sets are chunked);
* the archive always carries ``models=era5`` unless the caller says otherwise;
* a response is cached on disk (SHA-256 of the full URL) only if it is HTTP 200 and not an error
  body. Open-Meteo reports rate limits as HTTP 200 ``{"error": true, "reason": ...}``, so the body
  is checked, never the status alone. Errors are never cached;
* a daily weighted-call budget is persisted in ``cache/budget.json`` keyed by UTC date.

Weighted calls: Open-Meteo's published rule, as understood (to be confirmed by ``probe.py``), is

    weight = n_locations * max(1, ceil(n_vars / 10)) * max(1, ceil(n_days / 14))

Previous Runs naming: day 0 is the plain variable, day N (N >= 1) is ``<var>_previous_dayN``.
``_previous_day0`` is never emitted.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from typing import Callable, Dict, Iterable, List, Optional, Sequence, Set, Tuple

HOSTS = {
    "forecast": "https://api.open-meteo.com/v1/forecast",
    "archive": "https://archive-api.open-meteo.com/v1/archive",
    "previous_runs": "https://previous-runs-api.open-meteo.com/v1/forecast",
    "historical_forecast": "https://historical-forecast-api.open-meteo.com/v1/forecast",
}
MAX_COORDS = 20
DEFAULT_BUDGET = 8000
DEFAULT_PAUSE_S = 3.0
PRESSURE_VARS = ["cloud_cover_850hPa", "cloud_cover_700hPa", "cloud_cover_600hPa",
                 "cloud_cover_500hPa", "cloud_cover_400hPa", "cloud_cover_300hPa"]
CLOUD_VARS = ["cloud_cover_low", "cloud_cover_mid", "cloud_cover_high"]

Coord = Tuple[float, float]
FetchFn = Callable[[str], Tuple[int, str]]


class BudgetExhausted(Exception):
    """The daily weighted-call budget would be exceeded by the next request."""


class RateLimited(Exception):
    """Open-Meteo answered with an error body mentioning a limit. The run must stop."""


class OpenMeteoError(Exception):
    """Open-Meteo answered with an error body (not a limit), or a non-200 status."""


def weight(n_locations: int, n_vars: int, n_days: int) -> int:
    """Weighted API calls for one request (see module docstring for the rule)."""
    return (n_locations * max(1, math.ceil(n_vars / 10)) * max(1, math.ceil(n_days / 14)))


def prev_var(var: str, day: int) -> str:
    """Previous Runs variable name: day 0 is the plain variable, day N>=1 ``<var>_previous_dayN``."""
    if day < 0:
        raise ValueError("day must be >= 0")
    return var if day == 0 else f"{var}_previous_day{day}"


def round_coord(lat: float, lon: float) -> Coord:
    """Round to 4 dp (about 11 m)."""
    return (round(float(lat), 4), round(float(lon), 4))


def n_days(start: str, end: str) -> int:
    """Inclusive day count between two ISO dates."""
    return (date.fromisoformat(end) - date.fromisoformat(start)).days + 1


def build_url(kind: str, coords: Sequence[Coord], hourly: Sequence[str], start: str, end: str,
              models: Optional[str] = None) -> str:
    """Build the request URL. ``coords`` are used in the order given (callers sort).

    The archive gets ``models=era5`` unless ``models`` is given.
    """
    if kind not in HOSTS:
        raise ValueError(kind)
    if not 1 <= len(coords) <= MAX_COORDS:
        raise ValueError(f"1..{MAX_COORDS} coordinates per request, got {len(coords)}")
    if kind == "archive" and models is None:
        models = "era5"
    rc = [round_coord(*c) for c in coords]
    params = [
        ("latitude", ",".join(f"{c[0]:.4f}" for c in rc)),
        ("longitude", ",".join(f"{c[1]:.4f}" for c in rc)),
        ("hourly", ",".join(hourly)),
        ("start_date", start),
        ("end_date", end),
        ("timezone", "UTC"),
    ]
    if models:
        params.append(("models", models))
    q = "&".join(f"{k}={v}" for k, v in params)  # commas are legal in a query string
    return f"{HOSTS[kind]}?{q}"


@dataclass
class Point:
    """One coordinate's parsed response."""
    hours: Dict[str, Dict[str, Optional[float]]] = field(default_factory=dict)
    not_served: Set[str] = field(default_factory=set)  # variables that are null for EVERY hour
    latitude: Optional[float] = None    # the grid cell the API actually answered for
    longitude: Optional[float] = None

    def value(self, hour: str, var: str) -> Optional[float]:
        """Value at an ISO hour string like ``2026-10-13T17:00`` (None if missing/null)."""
        return self.hours.get(hour, {}).get(var)

    def served(self, var: str) -> bool:
        """True if the variable has at least one non-null value."""
        return any(var in h and h[var] is not None for h in self.hours.values())


def error_reason(obj) -> Optional[str]:
    """Return the reason if ``obj`` is an Open-Meteo error envelope, else None."""
    if isinstance(obj, dict) and obj.get("error") in (True, "true", 1):
        return str(obj.get("reason", "unknown error"))
    return None


def parse_point(node: dict) -> Point:
    """Parse one ``hourly`` block. An all-null series is flagged in ``not_served`` (distinct from
    zeros, which are real clear-sky readings)."""
    hourly = node.get("hourly") or {}
    times = hourly.get("time") or []
    pt = Point(latitude=node.get("latitude"), longitude=node.get("longitude"))
    for t in times:
        pt.hours[t] = {}
    for var, series in hourly.items():
        if var == "time":
            continue
        series = series or []
        for i, t in enumerate(times):
            pt.hours[t][var] = series[i] if i < len(series) else None
        if not any(v is not None for v in series):
            pt.not_served.add(var)
    return pt


def parse_body(body: str) -> List[Point]:
    """Parse a 200 body: an object for one coordinate, an array for several."""
    obj = json.loads(body)
    reason = error_reason(obj)
    if reason is not None:
        raise OpenMeteoError(reason)
    nodes = obj if isinstance(obj, list) else [obj]
    for n in nodes:
        r = error_reason(n)
        if r is not None:
            raise OpenMeteoError(r)
    return [parse_point(n) for n in nodes]


def default_fetch(url: str) -> Tuple[int, str]:
    """Live GET via urllib. HTTP errors return (status, body) rather than raising."""
    req = urllib.request.Request(url, headers={"User-Agent": "goldenhour-far-cloud-p1/1"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


class Client:
    """Cache + budget + pacing wrapper. ``fetch`` and ``sleep`` and ``today`` are injectable."""

    def __init__(self, cache_dir: str = "cache", budget: int = DEFAULT_BUDGET,
                 fetch: Optional[FetchFn] = None, pause_s: float = DEFAULT_PAUSE_S,
                 dry_run: bool = False, sleep: Callable[[float], None] = time.sleep,
                 today: Optional[Callable[[], str]] = None):
        self.cache_dir = cache_dir
        self.budget = budget
        self.fetch = fetch or default_fetch
        self.pause_s = pause_s
        self.dry_run = dry_run
        self._sleep = sleep
        self._today = today or (lambda: datetime.now(timezone.utc).date().isoformat())
        self._last_live: Optional[float] = None
        self.live_requests = 0
        self.weight_spent = 0       # weighted calls spent by THIS process (live only)
        self.cache_hits = 0
        self.projected_weight = 0   # dry-run: weight of requests that would go live
        self.projected_requests = 0
        self.log: List[str] = []
        os.makedirs(cache_dir, exist_ok=True)

    # ---- budget -------------------------------------------------------------------------
    @property
    def _budget_path(self) -> str:
        return os.path.join(self.cache_dir, "budget.json")

    def used_today(self) -> int:
        """Weighted calls already spent today (UTC date)."""
        try:
            with open(self._budget_path) as f:
                d = json.load(f)
            return int(d["used"]) if d.get("date") == self._today() else 0
        except (OSError, ValueError, KeyError):
            return 0

    def _charge(self, w: int) -> None:
        new = {"date": self._today(), "used": self.used_today() + w}  # read BEFORE truncating
        with open(self._budget_path, "w") as f:
            json.dump(new, f)

    # ---- cache --------------------------------------------------------------------------
    def _cache_path(self, url: str) -> str:
        return os.path.join(self.cache_dir, hashlib.sha256(url.encode()).hexdigest() + ".json")

    def _cache_read(self, url: str) -> Optional[str]:
        try:
            with open(self._cache_path(url)) as f:
                return f.read()
        except OSError:
            return None

    def _cache_write(self, url: str, body: str) -> None:
        with open(self._cache_path(url), "w") as f:
            f.write(body)

    def is_cached(self, url: str) -> bool:
        """True if a successful body for this exact URL is on disk."""
        return os.path.exists(self._cache_path(url))

    # ---- requests -----------------------------------------------------------------------
    def request(self, url: str, w: int) -> Optional[List[Point]]:
        """One URL -> parsed points (None in dry-run when it would go live).

        Raises BudgetExhausted, RateLimited (error body mentioning 'limit') or OpenMeteoError.
        """
        cached = self._cache_read(url)
        if cached is not None:
            self.cache_hits += 1
            return parse_body(cached)
        if self.dry_run:
            self.projected_weight += w
            self.projected_requests += 1
            return None
        if self.used_today() + w > self.budget:
            raise BudgetExhausted(
                f"budget {self.budget}: used {self.used_today()}, next request weighs {w}")
        if self._last_live is not None and self.pause_s > 0:
            self._sleep(self.pause_s)
        self._last_live = 1.0
        self.live_requests += 1
        status, body = self.fetch(url)
        self._charge(w)  # a request was made, rate-limited or not
        self.weight_spent += w
        try:
            obj = json.loads(body)
        except ValueError:
            raise OpenMeteoError(f"HTTP {status}: non-JSON body {body[:120]!r}")
        reason = error_reason(obj)
        if reason is None and isinstance(obj, list):
            for n in obj:
                reason = error_reason(n)
                if reason:
                    break
        if reason is not None:
            self.log.append(f"error: {reason} <- {url}")
            if "limit" in reason.lower():
                raise RateLimited(reason)
            raise OpenMeteoError(reason)
        if status != 200:
            raise OpenMeteoError(f"HTTP {status}: {body[:120]}")
        self._cache_write(url, body)
        return parse_body(body)

    def get(self, kind: str, coords: Sequence[Coord], hourly: Sequence[str], start: str,
            end: str, models: Optional[str] = None) -> List[Point]:
        """Fetch many coordinates (chunked by 20, sorted for determinism). Returns one Point per
        INPUT coordinate, in input order; duplicates after rounding share one request slot.
        In dry-run, un-cached chunks yield empty Points."""
        rounded = [round_coord(*c) for c in coords]
        unique = sorted(set(rounded))
        by_coord: Dict[Coord, Point] = {}
        days = n_days(start, end)
        for i in range(0, len(unique), MAX_COORDS):
            chunk = unique[i:i + MAX_COORDS]
            url = build_url(kind, chunk, hourly, start, end, models)
            pts = self.request(url, weight(len(chunk), len(hourly), days))
            if pts is None:
                pts = [Point() for _ in chunk]
            if len(pts) != len(chunk):
                raise OpenMeteoError(f"expected {len(chunk)} points, got {len(pts)}")
            for c, p in zip(chunk, pts):
                by_coord[c] = p
        return [by_coord[c] for c in rounded]

    def get_one(self, kind: str, coord: Coord, hourly: Sequence[str], start: str, end: str,
                models: Optional[str] = None) -> Point:
        """Convenience for one coordinate."""
        return self.get(kind, [coord], hourly, start, end, models)[0]

    def summary(self) -> str:
        """One-line accounting summary."""
        if self.dry_run:
            return (f"dry-run: {self.projected_requests} requests, {self.projected_weight} weighted "
                    f"calls to fetch, {self.cache_hits} served from cache")
        return (f"{self.live_requests} live requests, {self.cache_hits} cache hits, "
                f"{self.used_today()} weighted calls used today of {self.budget}")
