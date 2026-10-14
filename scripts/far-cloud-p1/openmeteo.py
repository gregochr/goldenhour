"""Cached, budgeted Open-Meteo client for the P1 scripts (stdlib only).

Design rules (docs/engineering/far-cloud-p1-plan.md section 8, T5):

* dates are always ``start_date``/``end_date`` -- ``past_days`` is never sent;
* ``timezone=UTC`` always; latitude/longitude rounded to 4 dp and sorted within a request;
* at most 20 coordinates per request (larger sets are chunked);
* the archive always carries ``models=era5`` unless the caller says otherwise;
* the cache is PER COORDINATE (one file per (endpoint, variables, start, end, models, rounded
  coordinate), named by the SHA-256 of that single-coordinate URL). Adding one coordinate to a request
  set therefore costs at most one new request and never reshuffles chunks; missing coordinates are
  fetched in sorted chunks of 20. A node is cached only if the body was HTTP 200, not an error
  envelope, has an ``hourly`` block and at least one requested variable has a non-null value.
  Open-Meteo reports rate limits as HTTP 200 ``{"error": true, "reason": ...}``, so the body is
  checked, never the status alone. Errors, all-null nodes and nodes without ``hourly`` are never
  cached (ERA5 lags ~5 days, so an early run would otherwise cache nulls for good). Archive nodes
  whose ``end_date`` is within 7 days of today (UTC) are cached with a 24 h TTL stored in the entry.
  Expired, unparseable or legacy-format cache files are misses; cache and budget writes are atomic
  (temp file + ``os.replace``);
* weighted-call accounting is persisted in ``cache/budget.json`` (UTC day and UTC hour counters). A
  corrupt budget file is a hard stop (``BudgetFileCorrupt``), never a reset to zero. Limits enforced:
  daily budget (default 8000), 4,500 per UTC hour (sleep to the next hour, or stop with
  ``wait_for_hour=False``), 500 per minute. A rate-limited rejection and a request that never got an
  answer (network error) are not charged.

Weighted calls: the rule below is ASSUMED, not confirmed (``probe.py`` does not measure it; check
Open-Meteo's dashboard / response headers after the first run and correct ``weight`` if it differs):

    weight = n_locations * max(1, ceil(n_vars / 10)) * max(1, ceil(n_days / 14))

Previous Runs naming: day 0 is the plain variable, day N (N >= 1) is ``<var>_previous_dayN``.
``_previous_day0`` is never emitted. ⚠️ Open-Meteo documents ``_previous_dayN`` for TOTAL cloud cover
only: ``cloud_cover_{low,mid,high}_previous_dayN`` answer all-null (probe, 2026-10-14, five models, four
dates), so the P1 forecast arm is the plain day-0 value, served identically by the Historical
Forecast API (the documented host for low/mid/high and pressure levels). See plan section 11.

Batching: a multi-coordinate request returns one point per coordinate in request order and does not
merge coordinates that share a 0.25 degree cell (probe, 2026-10-14). The default
``cell_selection=land`` can answer a sea point from the neighbouring land cell, so ``Point.latitude``
and ``Point.longitude`` are the cell actually used and may differ from the request by one cell. Do not
send ``cell_selection``: production's calls do not, so the default keeps like with like.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import socket
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
HOURLY_CAP = 4500          # free tier is understood to be 5,000/hour; keep headroom
MINUTE_CAP = 500           # free tier is understood to be 600/minute
FRESH_ARCHIVE_DAYS = 7     # archive responses ending within this many days of today may be incomplete
FRESH_TTL = timedelta(hours=24)
SNAP_TOL = 0.55            # degrees: a point answered further than ~2 grid cells away is a broken batch
PRESSURE_VARS = ["cloud_cover_850hPa", "cloud_cover_700hPa", "cloud_cover_600hPa",
                 "cloud_cover_500hPa", "cloud_cover_400hPa", "cloud_cover_300hPa"]
CLOUD_VARS = ["cloud_cover_low", "cloud_cover_mid", "cloud_cover_high"]

Coord = Tuple[float, float]
FetchFn = Callable[[str], Tuple[int, str]]


class BudgetExhausted(Exception):
    """The daily weighted-call budget would be exceeded by the next request."""


class RateLimited(Exception):
    """Open-Meteo answered with an error body mentioning a limit. The run must stop."""


class HourlyBudgetExhausted(BudgetExhausted):
    """The hourly cap would be exceeded and the client was told not to wait for the next hour."""


class BudgetFileCorrupt(Exception):
    """cache/budget.json exists but cannot be read; refusing to run rather than assume 0 used."""


class OpenMeteoError(Exception):
    """Open-Meteo answered with an error body (not a limit), or a non-200 status."""


class NetworkError(OpenMeteoError):
    """No answer from Open-Meteo (DNS, connection, timeout). Not charged; the run should stop."""


def weight(n_locations: int, n_vars: int, n_days: int) -> int:
    """Weighted API calls for one request (see module docstring for the rule)."""
    return (n_locations * max(1, math.ceil(n_vars / 10)) * max(1, math.ceil(n_days / 14)))


def prev_var(var: str, day: int) -> str:
    """Previous Runs variable name: day 0 is the plain variable, day N>=1 ``<var>_previous_dayN``."""
    if day < 0:
        raise ValueError("day must be >= 0")
    return var if day == 0 else f"{var}_previous_day{day}"


def order_violations(requested: Sequence[Coord], answered: Sequence[Tuple[Optional[float], Optional[float]]],
                     tol: float) -> List[int]:
    """Indices where the answered cell is missing, more than ``tol`` degrees from the request, or
    nearer to a DIFFERENT requested coordinate than to its own (a shuffled or merged batch).

    Open-Meteo's default ``cell_selection=land`` may answer a sea point from the neighbouring land
    cell, one grid cell away (2026-10-14: 52.0, 4.0 answered as 51.75, 4.0), which is not a
    violation, so ``tol`` is a cell plus a little. The nearer-to-another test only means something
    when the requested coordinates are further apart than a cell (the probe's 0.5 degree grid);
    ``Client.get`` checks position against ``SNAP_TOL`` alone, because a transect's points are closer."""
    bad = []
    for i, (c, a) in enumerate(zip(requested, answered)):
        if a[0] is None or a[1] is None:
            bad.append(i)
            continue
        own = max(abs(a[0] - c[0]), abs(a[1] - c[1]))
        if own > tol:
            bad.append(i)
            continue
        for j, o in enumerate(requested):
            if j != i and max(abs(a[0] - o[0]), abs(a[1] - o[1])) < own - 1e-9:
                bad.append(i)
                break
    return bad


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


def is_rate_limit(reason: str, status: int) -> bool:
    """True for HTTP 429 or an error reason that names a limit / too many requests."""
    r = (reason or "").lower()
    return status == 429 or "limit" in r or "too many" in r


def node_cacheable(node: dict, hourly: Sequence[str]) -> bool:
    """A node is worth caching only with an ``hourly`` block in which some requested variable has
    at least one non-null value."""
    h = node.get("hourly") if isinstance(node, dict) else None
    if not isinstance(h, dict) or not h.get("time"):
        return False
    return any(any(v is not None for v in (h.get(var) or [])) for var in hourly)


def atomic_write(path: str, text: str) -> None:
    """Write via a temp file in the same directory, then ``os.replace``."""
    tmp = f"{path}.{os.getpid()}.tmp"
    try:
        with open(tmp, "w") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


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
    """Live GET via urllib. HTTP errors return (status, body); network failures and timeouts raise
    OpenMeteoError (such a request got no answer and is not charged)."""
    req = urllib.request.Request(url, headers={"User-Agent": "goldenhour-far-cloud-p1/1"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as e:
        raise NetworkError(f"network error talking to Open-Meteo ({type(e).__name__}: {e}); "
                             f"nothing was charged, re-run to resume from cache")


class Client:
    """Cache + budget + pacing wrapper. ``fetch``, ``sleep``, ``today``, ``now`` and ``mono`` are
    injectable."""

    def __init__(self, cache_dir: str = "cache", budget: int = DEFAULT_BUDGET,
                 fetch: Optional[FetchFn] = None, pause_s: float = DEFAULT_PAUSE_S,
                 dry_run: bool = False, sleep: Callable[[float], None] = time.sleep,
                 today: Optional[Callable[[], str]] = None,
                 now: Optional[Callable[[], datetime]] = None,
                 mono: Callable[[], float] = time.monotonic,
                 wait_for_hour: bool = True, hourly_cap: int = HOURLY_CAP,
                 minute_cap: int = MINUTE_CAP):
        self.cache_dir = cache_dir
        self.budget = budget
        self.fetch = fetch or default_fetch
        self.pause_s = pause_s
        self.dry_run = dry_run
        self.wait_for_hour = wait_for_hour
        self.hourly_cap = hourly_cap
        self.minute_cap = minute_cap
        self._sleep = sleep
        self._now = now or (lambda: datetime.now(timezone.utc))
        self._today = today or (lambda: self._now().date().isoformat())
        self._mono = mono
        self._minute_log: List[Tuple[float, int]] = []
        self._last_live: Optional[float] = None
        self.live_requests = 0
        self.weight_spent = 0       # weighted calls spent by THIS process (live only)
        self.cache_hits = 0         # coordinate-level cache hits
        self.uncached_nodes = 0     # nodes served but not cached (null / no hourly)
        self.projected_weight = 0   # dry-run: weight of requests that would go live
        self.projected_requests = 0
        self.log: List[str] = []
        os.makedirs(cache_dir, exist_ok=True)
        self._load_budget()         # fail closed on a corrupt budget file, before any work

    # ---- budget -------------------------------------------------------------------------
    @property
    def _budget_path(self) -> str:
        return os.path.join(self.cache_dir, "budget.json")

    def _load_budget(self) -> dict:
        try:
            with open(self._budget_path) as f:
                d = json.load(f)
        except FileNotFoundError:
            return {}
        except (OSError, ValueError) as e:
            raise BudgetFileCorrupt(self._corrupt_msg(e))
        if (not isinstance(d, dict) or not isinstance(d.get("date"), str)
                or not isinstance(d.get("used"), int) or isinstance(d.get("used"), bool)
                or not isinstance(d.get("hour_used", 0), int)):
            raise BudgetFileCorrupt(self._corrupt_msg("unexpected structure"))
        return d

    def _corrupt_msg(self, why) -> str:
        return (f"{self._budget_path} is unreadable ({why}). Refusing to run: resetting it to 0 could "
                f"overspend the Open-Meteo quota. Check today's usage on Open-Meteo's dashboard, then "
                f"fix or delete the file by hand.")

    def _hour_key(self) -> str:
        return self._now().strftime("%Y-%m-%dT%H")

    def used_today(self) -> int:
        """Weighted calls already spent today (UTC date)."""
        d = self._load_budget()
        return d["used"] if d.get("date") == self._today() else 0

    def used_this_hour(self) -> int:
        """Weighted calls already spent in the current UTC hour."""
        d = self._load_budget()
        return d.get("hour_used", 0) if d.get("hour") == self._hour_key() else 0

    def _write_budget(self, used: int, hour_used: int) -> None:
        atomic_write(self._budget_path, json.dumps(
            {"date": self._today(), "used": used, "hour": self._hour_key(), "hour_used": hour_used}))

    def _charge(self, w: int) -> None:
        self._write_budget(self.used_today() + w, self.used_this_hour() + w)  # read BEFORE writing

    def _ensure_hour_room(self, w: int) -> None:
        if self.used_this_hour() + w <= self.hourly_cap:
            return
        if w > self.hourly_cap:
            raise BudgetExhausted(f"one request weighs {w}, above the hourly cap {self.hourly_cap}")
        if not self.wait_for_hour:
            raise HourlyBudgetExhausted(
                f"hourly cap {self.hourly_cap}: {self.used_this_hour()} used this UTC hour, next "
                f"request weighs {w} (re-run after the hour, or without --no-wait)")
        now = self._now()
        nxt = now.replace(minute=0, second=0, microsecond=0) + timedelta(hours=1)
        secs = (nxt - now).total_seconds() + 2
        msg = (f"hourly cap {self.hourly_cap} reached ({self.used_this_hour()} used); sleeping "
               f"{secs:.0f} s until {nxt.strftime('%H:%M')} UTC")
        print(msg, flush=True)
        self.log.append(msg)
        self._sleep(secs)
        self._write_budget(self.used_today(), 0)   # a new hour: counter restarts

    def _ensure_minute_room(self, w: int) -> float:
        """Block until the last 60 s hold at most ``minute_cap`` weight; returns the request time."""
        t = self._mono()
        while True:
            self._minute_log = [(ts, x) for ts, x in self._minute_log if t - ts < 60]
            if not self._minute_log or sum(x for _, x in self._minute_log) + w <= self.minute_cap:
                return t
            wait = self._minute_log[0][0] + 60 - t
            self._sleep(wait)
            t = max(t + wait, self._mono())

    # ---- cache --------------------------------------------------------------------------
    def _cache_path(self, url: str) -> str:
        return os.path.join(self.cache_dir, hashlib.sha256(url.encode()).hexdigest() + ".json")

    def _cache_read_url(self, url: str) -> Optional[dict]:
        """The cached node for a single-coordinate URL, or None (missing, expired, unparseable)."""
        try:
            with open(self._cache_path(url)) as f:
                env = json.load(f)
            node = env["node"]
            if not isinstance(node, dict):
                return None
            exp = env.get("expires")
            if exp is not None and self._now() >= datetime.fromisoformat(exp):
                return None
            return node
        except (OSError, ValueError, KeyError, TypeError):
            return None

    def _cache_write_url(self, url: str, node: dict, end: str, kind: str) -> None:
        expires = None
        if kind == "archive" and (date.fromisoformat(end)
                                  >= date.fromisoformat(self._today()) - timedelta(days=FRESH_ARCHIVE_DAYS)):
            expires = (self._now() + FRESH_TTL).isoformat()
        atomic_write(self._cache_path(url), json.dumps({"v": 1, "expires": expires, "node": node}))

    def is_cached(self, url: str) -> bool:
        """True if a valid, unexpired entry exists for this single-coordinate URL."""
        return self._cache_read_url(url) is not None

    # ---- requests -----------------------------------------------------------------------
    def _live(self, url: str, w: int) -> List[dict]:
        """One live request -> list of node dicts. Enforces daily/hourly/minute limits and charges
        only a request that got an answer other than a rate-limit rejection.

        Raises BudgetExhausted, RateLimited or OpenMeteoError."""
        if self.used_today() + w > self.budget:
            raise BudgetExhausted(
                f"budget {self.budget}: used {self.used_today()}, next request weighs {w}")
        self._ensure_hour_room(w)
        t = self._ensure_minute_room(w)
        if self._last_live is not None and self.pause_s > 0:
            self._sleep(self.pause_s)
        self._last_live = 1.0
        self.live_requests += 1
        status, body = self.fetch(url)     # OpenMeteoError (network) propagates: nothing charged
        try:
            obj = json.loads(body)
        except ValueError:
            if status == 429:
                raise RateLimited(f"HTTP 429: {body[:120]!r}")
            self._account(w, t)
            raise OpenMeteoError(f"HTTP {status}: non-JSON body {body[:120]!r}")
        reason = error_reason(obj)
        if reason is None and isinstance(obj, list):
            for n in obj:
                reason = error_reason(n)
                if reason:
                    break
        if status == 429 or (reason is not None and is_rate_limit(reason, status)):
            self.log.append(f"rate limited: {reason or 'HTTP 429'} <- {url}")
            raise RateLimited(reason or f"HTTP 429: {body[:120]}")     # not charged
        self._account(w, t)
        if reason is not None:
            self.log.append(f"error: {reason} <- {url}")
            raise OpenMeteoError(reason)
        if status != 200:
            raise OpenMeteoError(f"HTTP {status}: {body[:120]}")
        return obj if isinstance(obj, list) else [obj]

    def _account(self, w: int, t: float) -> None:
        self._charge(w)
        self.weight_spent += w
        self._minute_log.append((t, w))

    def request(self, url: str, w: int) -> Optional[List[Point]]:
        """One URL -> parsed points, live and UNCACHED (None in dry-run). Used by the probe for
        shapes that must hit the API as sent. Raises like ``_live``."""
        if self.dry_run:
            self.projected_weight += w
            self.projected_requests += 1
            return None
        return [parse_point(n) for n in self._live(url, w)]

    def get(self, kind: str, coords: Sequence[Coord], hourly: Sequence[str], start: str,
            end: str, models: Optional[str] = None) -> List[Point]:
        """Fetch many coordinates. Returns one Point per INPUT coordinate, in input order;
        duplicates after rounding share one slot. Cached per coordinate; coordinates not cached are
        fetched in sorted chunks of 20. In dry-run, un-cached coordinates yield empty Points."""
        rounded = [round_coord(*c) for c in coords]
        unique = sorted(set(rounded))
        by_coord: Dict[Coord, Point] = {}
        urls: Dict[Coord, str] = {}
        missing: List[Coord] = []
        days = n_days(start, end)
        for c in unique:
            urls[c] = build_url(kind, [c], hourly, start, end, models)
            node = self._cache_read_url(urls[c])
            if node is not None:
                self.cache_hits += 1
                by_coord[c] = parse_point(node)
            else:
                missing.append(c)
        for i in range(0, len(missing), MAX_COORDS):
            chunk = missing[i:i + MAX_COORDS]
            w = weight(len(chunk), len(hourly), days)
            if self.dry_run:
                self.projected_weight += w
                self.projected_requests += 1
                for c in chunk:
                    by_coord[c] = Point()
                continue
            nodes = self._live(build_url(kind, chunk, hourly, start, end, models), w)
            if len(nodes) != len(chunk):
                raise OpenMeteoError(f"expected {len(chunk)} points, got {len(nodes)}")
            far = [c for c, n in zip(chunk, nodes)
                   if isinstance(n.get("latitude"), (int, float)) and isinstance(n.get("longitude"), (int, float))
                   and max(abs(n["latitude"] - c[0]), abs(n["longitude"] - c[1])) > SNAP_TOL]
            if far:   # never cache or use a batch whose points are not the ones asked for
                raise OpenMeteoError(f"{len(far)} point(s) answered for a cell more than {SNAP_TOL} "
                                     f"degrees from the request, e.g. {far[0]}: batch order is not trusted")
            for c, node in zip(chunk, nodes):
                by_coord[c] = parse_point(node)
                if node_cacheable(node, hourly):
                    self._cache_write_url(urls[c], node, end, kind)
                else:
                    self.uncached_nodes += 1
                    self.log.append(f"not cached (no hourly data / all variables null): {urls[c]}")
        return [by_coord[c] for c in rounded]

    def get_one(self, kind: str, coord: Coord, hourly: Sequence[str], start: str, end: str,
                models: Optional[str] = None) -> Point:
        """Convenience for one coordinate."""
        return self.get(kind, [coord], hourly, start, end, models)[0]

    def summary(self) -> str:
        """One-line accounting summary."""
        if self.dry_run:
            return (f"dry-run: {self.projected_requests} requests, {self.projected_weight} weighted "
                    f"calls to fetch, {self.cache_hits} coordinates served from cache")
        return (f"{self.live_requests} live requests, {self.cache_hits} cached coordinates, "
                f"{self.used_today()} weighted calls used today of {self.budget}"
                + (f", {self.uncached_nodes} empty node(s) not cached" if self.uncached_nodes else ""))
