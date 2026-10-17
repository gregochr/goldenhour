"""Statistics for far-cloud P1 (plan section 5): Wilson, Newcombe, moving-block bootstrap, Holm, gates.

Standard library only.
"""
from __future__ import annotations

import math
import random
from datetime import date, timedelta
from typing import Callable, Dict, Hashable, Iterable, List, Optional, Sequence, Tuple

# Plan section 5 minimum counts: no verdict below these.
MIN_UNITS_PER_ARM = 30
MIN_FLAGGED_EVENTS = 10
# Pre-registered addition to plan section 5 (review finding 2): the flagged arm must span at least
# this many DISTINCT dates, so a pass cannot rest on a handful of days.
MIN_FLAGGED_DATES = 8
# Pre-registered: if more than this share of bootstrap draws is dropped (an arm empty in the
# resample), the interval is conditioned on the surviving draws and the verdict is inconclusive.
MAX_DROPPED_FRACTION = 0.05
DEFAULT_SEED = 20261014
DEFAULT_DRAWS = 2000
DEFAULT_BLOCK_DAYS = 5
SENSITIVITY_BLOCKS = (3, 5, 7)


def wilson(k: int, n: int, z: float = 1.96) -> Optional[Tuple[float, float, float]]:
    """Wilson score interval: (p, lo, hi), or None when n == 0."""
    if n <= 0:
        return None
    p = k / n
    z2 = z * z
    denom = 1.0 + z2 / n
    centre = (p + z2 / (2.0 * n)) / denom
    half = z * math.sqrt(p * (1.0 - p) / n + z2 / (4.0 * n * n)) / denom
    return p, max(0.0, centre - half), min(1.0, centre + half)


def newcombe_diff(k1: int, n1: int, k2: int, n2: int,
                  z: float = 1.96) -> Optional[Tuple[float, float, float]]:
    """Newcombe hybrid score interval (method 10) for p1 - p2: (d, lo, hi); None if either n is 0."""
    w1, w2 = wilson(k1, n1, z), wilson(k2, n2, z)
    if w1 is None or w2 is None:
        return None
    p1, l1, u1 = w1
    p2, l2, u2 = w2
    d = p1 - p2
    lo = d - math.sqrt((p1 - l1) ** 2 + (u2 - p2) ** 2)
    hi = d + math.sqrt((u1 - p1) ** 2 + (p2 - l2) ** 2)
    return d, lo, hi


def two_prop_pvalue(k1: int, n1: int, k2: int, n2: int) -> Optional[float]:
    """Two-sided pooled score (z) test for p1 == p2. None if either n is 0; 1.0 if degenerate."""
    if n1 <= 0 or n2 <= 0:
        return None
    pooled = (k1 + k2) / (n1 + n2)
    se = math.sqrt(pooled * (1.0 - pooled) * (1.0 / n1 + 1.0 / n2))
    if se == 0.0:
        return 1.0
    z = (k1 / n1 - k2 / n2) / se
    return math.erfc(abs(z) / math.sqrt(2.0))


def holm(pvals: Sequence[Optional[float]]) -> List[Optional[float]]:
    """Holm-Bonferroni adjusted p-values, same order as input. None entries stay None and do not
    count toward the number of tests."""
    idx = [i for i, p in enumerate(pvals) if p is not None]
    m = len(idx)
    out: List[Optional[float]] = [None] * len(pvals)
    order = sorted(idx, key=lambda i: pvals[i])
    running = 0.0
    for rank, i in enumerate(order):
        adj = min(1.0, (m - rank) * pvals[i])
        running = max(running, adj)
        out[i] = running
    return out


# ---------------------------------------------------------------- moving-block bootstrap

def _percentile(sorted_vals: Sequence[float], q: float) -> float:
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    pos = q * (len(sorted_vals) - 1)
    lo = int(math.floor(pos))
    hi = min(lo + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (pos - lo)


def percentile_interval(draws: Sequence[float], alpha: float = 0.05) -> Optional[Tuple[float, float]]:
    """Percentile interval of bootstrap draws, or None when there are no draws."""
    if not draws:
        return None
    s = sorted(draws)
    return _percentile(s, alpha / 2.0), _percentile(s, 1.0 - alpha / 2.0)


def _calendar_days(dates: Iterable[str]) -> List[str]:
    """Every ISO calendar day from min to max of ``dates`` (inclusive), as ISO strings."""
    ds = sorted({date.fromisoformat(str(d)[:10]) for d in dates})
    if not ds:
        return []
    return [(ds[0] + timedelta(days=i)).isoformat() for i in range((ds[-1] - ds[0]).days + 1)]


def _resample_days(days: Sequence[str], block_days: int, rng: random.Random) -> List[str]:
    """Non-circular moving-block resample of a CALENDAR day list (empty days included): blocks of
    ``block_days`` consecutive calendar days, start uniform over the valid starts, concatenated
    until the resample has as many days as the original (last block trimmed)."""
    n = len(days)
    b = max(1, min(block_days, n))
    starts = n - b + 1
    out: List[str] = []
    while len(out) < n:
        s = rng.randrange(starts)
        out.extend(days[s:s + b])
    return out[:n]


def bootstrap_counts_ex(date_counts: Dict[str, Tuple[int, ...]],
                        stat_fn: Callable[[Tuple[int, ...]], Optional[float]],
                        block_days: int = DEFAULT_BLOCK_DAYS, draws: int = DEFAULT_DRAWS,
                        seed: int = DEFAULT_SEED) -> Tuple[List[float], int]:
    """Fast path. ``date_counts`` maps each present date to a tuple of additive counters; dates with
    no units in the calendar range count as zeros. Returns (defined draws, number of draws dropped
    because ``stat_fn`` returned None, e.g. an empty arm)."""
    if not date_counts:
        return [], 0
    days = _calendar_days(date_counts)
    width = len(next(iter(date_counts.values())))
    zero = (0,) * width
    rng = random.Random(seed)
    out: List[float] = []
    dropped = 0
    for _ in range(draws):
        tot = [0] * width
        for d in _resample_days(days, block_days, rng):
            c = date_counts.get(d, zero)
            for i in range(width):
                tot[i] += c[i]
        v = stat_fn(tuple(tot))
        if v is None:
            dropped += 1
        else:
            out.append(v)
    return out, dropped


def bootstrap_counts(date_counts: Dict[str, Tuple[int, ...]], stat_fn: Callable[[Tuple[int, ...]], Optional[float]],
                     block_days: int = DEFAULT_BLOCK_DAYS, draws: int = DEFAULT_DRAWS,
                     seed: int = DEFAULT_SEED) -> List[float]:
    """As ``bootstrap_counts_ex`` but returns only the defined draws (use _ex to see the dropped count)."""
    return bootstrap_counts_ex(date_counts, stat_fn, block_days, draws, seed)[0]


def moving_block_bootstrap(units: Iterable, stat_fn: Callable[[List], Optional[float]], date_key: Callable,
                           block_days: int = DEFAULT_BLOCK_DAYS, draws: int = DEFAULT_DRAWS,
                           seed: int = DEFAULT_SEED,
                           return_draws: bool = False, return_dropped: bool = False):
    """Non-circular moving-block bootstrap over CALENDAR days (min..max date; days with no units
    are empty). A unit belongs to its date and each drawn day brings all its units. Returns the 95%
    percentile interval (lo, hi), or None if no draw defined the statistic. With ``return_draws``
    returns the list of draws instead; with ``return_dropped`` returns (result, dropped_count)."""
    by_date: Dict[str, List] = {}
    for u in units:
        by_date.setdefault(str(date_key(u))[:10], []).append(u)

    def done(res, dropped):
        return (res, dropped) if return_dropped else res
    if not by_date:
        return done([] if return_draws else None, 0)
    days = _calendar_days(by_date)
    rng = random.Random(seed)
    vals: List[float] = []
    dropped = 0
    for _ in range(draws):
        sample: List = []
        for d in _resample_days(days, block_days, rng):
            sample.extend(by_date.get(d, ()))
        v = stat_fn(sample)
        if v is None:
            dropped += 1
        else:
            vals.append(v)
    return done(vals if return_draws else percentile_interval(vals), dropped)


def block_sensitivity(units: Iterable, stat_fn: Callable[[List], Optional[float]], date_key: Callable,
                      blocks: Sequence[int] = SENSITIVITY_BLOCKS, draws: int = DEFAULT_DRAWS,
                      seed: int = DEFAULT_SEED) -> Dict[int, Optional[Tuple[float, float]]]:
    """The same interval at each block length (plan: 3, 5, 7)."""
    units = list(units)
    return {b: moving_block_bootstrap(units, stat_fn, date_key, b, draws, seed) for b in blocks}


def bootstrap_pvalue(draws: Sequence[float]) -> Optional[float]:
    """Two-sided p for 'difference == 0' from bootstrap draws: 2 * min(P(d<=0), P(d>=0)), floored
    at 1/(N+1) so a finite bootstrap never claims p = 0."""
    n = len(draws)
    if n == 0:
        return None
    le = sum(1 for d in draws if d <= 0)
    ge = sum(1 for d in draws if d >= 0)
    p = 2.0 * min(le, ge) / n
    return min(1.0, max(p, 1.0 / (n + 1)))


# ---------------------------------------------------------------- min-count gate and verdict

def gate_ok(n_flagged: int, events_flagged: int, n_other: int, dates_flagged: Optional[int] = None) -> bool:
    """True when the plan's minimum counts are met (section 5) plus the pre-registered distinct-dates
    gate. ``dates_flagged`` of None means 'not supplied' and FAILS the gate (no silent bypass)."""
    return (n_flagged >= MIN_UNITS_PER_ARM and n_other >= MIN_UNITS_PER_ARM
            and events_flagged >= MIN_FLAGGED_EVENTS
            and dates_flagged is not None and dates_flagged >= MIN_FLAGGED_DATES)


def verdict_reason(n_flagged: int, events_flagged: int, n_other: int, ci_lo: Optional[float] = None,
                   adj_p: Optional[float] = None, alpha: float = 0.05,
                   dates_flagged: Optional[int] = None,
                   dropped_fraction: float = 0.0) -> Tuple[str, str]:
    """(verdict, reason). 'inconclusive' when a count gate fails, when more than
    MAX_DROPPED_FRACTION of bootstrap draws were dropped, or when there is no interval (not the same
    as 'no effect'); else 'pass' when the interval lies above 0 (and, when given, the Holm-adjusted
    p < alpha); else 'fail'."""
    if not gate_ok(n_flagged, events_flagged, n_other, dates_flagged):
        why = []
        if n_flagged < MIN_UNITS_PER_ARM or n_other < MIN_UNITS_PER_ARM:
            why.append('fewer than %d units in an arm' % MIN_UNITS_PER_ARM)
        if events_flagged < MIN_FLAGGED_EVENTS:
            why.append('fewer than %d events in the flagged arm' % MIN_FLAGGED_EVENTS)
        if dates_flagged is None or dates_flagged < MIN_FLAGGED_DATES:
            why.append('flagged arm spans fewer than %d distinct dates' % MIN_FLAGGED_DATES)
        return 'inconclusive', 'count gate: ' + '; '.join(why)
    if dropped_fraction > MAX_DROPPED_FRACTION:
        return 'inconclusive', ('%.1f%% of bootstrap draws dropped (an arm empty in the resample), more than '
                                '%d%%: the interval is conditioned on the survivors'
                                % (100 * dropped_fraction, int(100 * MAX_DROPPED_FRACTION)))
    if ci_lo is None:
        return 'inconclusive', 'no interval'
    if ci_lo > 0 and (adj_p is None or adj_p < alpha):
        return 'pass', ''
    return 'fail', ''


def verdict(n_flagged: int, events_flagged: int, n_other: int, ci_lo: Optional[float] = None,
            adj_p: Optional[float] = None, alpha: float = 0.05, dates_flagged: Optional[int] = None,
            dropped_fraction: float = 0.0) -> str:
    """The verdict word of ``verdict_reason``."""
    return verdict_reason(n_flagged, events_flagged, n_other, ci_lo, adj_p, alpha, dates_flagged,
                          dropped_fraction)[0]
