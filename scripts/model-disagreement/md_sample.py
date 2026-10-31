"""T2: deduplicate to units, filter to P, draw the stratified sample, price it, and STOP for the owner's go.

Plan: docs/engineering/model-disagreement-sizing-plan.md section 4 (T2) and section 2 ("Unit", "Population").

Input: the P1 export (``scripts/far-cloud-p1/export_forecasts.sql``, unchanged). The sample is T+0 only,
stratified PROPORTIONALLY to month within event (sunrise, sunset), so every unit has the same chance of
selection and raw counts apply to the decision. A fixed seed makes it repeatable.

It prints the funnel, the strata, the expected number of fetch DAYS against the observed daily budget
(about 600 weighted calls per day, P1 plan section 15) and a simulated power table, then stops. Nothing is
written, and no network is touched, until you pass ``--go``; ``--go`` writes ``out/fetch_list.csv`` (the
sample plus the trigger/training cases, which are fetched for the worked examples). Standard library only.
"""
from __future__ import annotations

import argparse
import csv
import math
import os
import random
import sys
from collections import defaultdict
from typing import Dict, List, Optional, Sequence, Tuple

import md_common as mc

DEFAULT_N = 300
DEFAULT_SEED = 20261031
DEFAULT_DAILY_CALLS = 600          # observed weighted calls per UTC day (P1 plan section 15)
GUIDE_CALLS_PER_UNIT = 18          # 6 models x 3 cone points, before grid-cell deduplication
POWER_PRECISIONS = (0.55, 0.65, 0.75)
POWER_FLAG_RATE = 0.20
POWER_SIMS = 2000
FETCH_COLUMNS = ["role", "evaluation_id", "location_id", "loc_name", "loc_lat", "loc_lon", "azimuth_deg",
                 "target_date", "target_type", "solar_event_time", "event_date", "month", "stratum", "weight"]


# ------------------------------------------------------------------------------ the sample
def allocate(sizes: Dict[Tuple[str, str], int], n: int) -> Dict[Tuple[str, str], int]:
    """Largest-remainder allocation of ``n`` draws across strata in proportion to their size, never more
    than a stratum holds. ``n`` at or above the population takes everyone."""
    total = sum(sizes.values())
    if n >= total:
        return dict(sizes)
    exact = {k: n * v / total for k, v in sizes.items()}
    alloc = {k: min(sizes[k], int(math.floor(x))) for k, x in exact.items()}
    left = n - sum(alloc.values())
    for k in sorted(exact, key=lambda k: (-(exact[k] - math.floor(exact[k])), k)):
        if left <= 0:
            break
        if alloc[k] < sizes[k]:
            alloc[k] += 1
            left -= 1
    return alloc


def stratified_sample(members: Sequence[mc.Unit], n: int, seed: int = DEFAULT_SEED
                      ) -> List[Tuple[mc.Unit, float]]:
    """(unit, weight) pairs. Strata are (target type, month); each stratum's draw is proportional to its
    share of P, so the sample is self-weighting up to rounding. ``weight`` = stratum size / stratum draws
    (used only for the Kish effective n in the report; the decision takes raw counts)."""
    strata: Dict[Tuple[str, str], List[mc.Unit]] = defaultdict(list)
    for u in members:
        strata[(u.ttype, u.month)].append(u)
    alloc = allocate({k: len(v) for k, v in strata.items()}, n)
    rng = random.Random(seed)
    out: List[Tuple[mc.Unit, float]] = []
    for k in sorted(strata):
        pool = sorted(strata[k], key=lambda u: u.eval_id)       # sort first: the draw never depends on file order
        take = alloc[k]
        if take <= 0:
            continue
        chosen = rng.sample(pool, take)
        out.extend((u, len(pool) / take) for u in sorted(chosen, key=lambda u: u.eval_id))
    return out


# ------------------------------------------------------------------------------ cost and power
def uncached_calls(plan: Dict[str, Dict[str, mc.Coord]], client: mc.om.Client,
                   models: Sequence[str] = mc.MODELS) -> Tuple[int, int]:
    """(weighted calls still to fetch, weighted calls already in the cache) for the plan."""
    todo = done = 0
    for date, cells in plan.items():
        for model in models:
            for c in cells.values():
                url = mc.om.build_url(mc.KIND, [c], mc.HOURLY, date, date, model)
                w = mc.om.weight(1, len(mc.HOURLY), 1)
                if client.is_cached(url):
                    done += w
                else:
                    todo += w
    return todo, done


def power_table(n: int, flag_rate: float = POWER_FLAG_RATE, precisions: Sequence[float] = POWER_PRECISIONS,
                sims: int = POWER_SIMS, seed: int = DEFAULT_SEED) -> List[dict]:
    """Simulated Wilson lower bound on precision for ``n`` units with ``flag_rate`` flagged, at several
    true precisions. Independent draws (no day-block clustering), so the real bootstrap bound is wider:
    this is a ceiling on the power, not a promise."""
    n_flag = int(round(n * flag_rate))
    rng = random.Random(seed)
    rows = []
    for p in precisions:
        lbs = []
        for _ in range(sims):
            k = sum(1 for _ in range(n_flag) if rng.random() < p)
            w = mc.safe_wilson(k, n_flag)
            lbs.append(0.0 if w is None else w[1])
        lbs.sort()
        rows.append({"true_precision": p, "n_flagged": n_flag,
                     "median_lower_bound": lbs[len(lbs) // 2] if lbs else None,
                     "p_lower_bound_ge_50": sum(1 for x in lbs if x >= 0.5) / len(lbs) if lbs else None})
    return rows


# ------------------------------------------------------------------------------ output
def fetch_rows(sample: Sequence[Tuple[mc.Unit, float]], worked: Sequence[mc.Unit]) -> List[dict]:
    """The fetch list: the sample, then the worked-example units (role 'worked', fetched, never decisive)."""
    rows = []
    for role, pairs in (("sample", sample), ("worked", [(u, 1.0) for u in worked])):
        for u, w in pairs:
            rows.append({"role": role, "evaluation_id": u.eval_id, "location_id": u.location_id,
                         "loc_name": u.name, "loc_lat": u.lat, "loc_lon": u.lon, "azimuth_deg": u.az,
                         "target_date": u.date, "target_type": u.ttype,
                         "solar_event_time": u.event.strftime(mc.EVENT_FORMAT), "event_date": u.event_date,
                         "month": u.month, "stratum": "%s|%s" % (u.ttype, u.month), "weight": "%.6f" % w})
    return rows


def load_fetch_list(path: str) -> List[dict]:
    """Read ``fetch_list.csv`` back."""
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def units_from_fetch_rows(rows: Sequence[dict]) -> List[mc.Unit]:
    """Minimal Units (geometry, date, type, event) for the fetch step, from fetch-list rows."""
    out = []
    for r in rows:
        ev = mc.parse_event(r["solar_event_time"])
        az = mc.num(r["azimuth_deg"])
        out.append(mc.Unit(
            eval_id=int(r["evaluation_id"]), location_id=r["location_id"], name=r["loc_name"],
            lat=mc.num(r["loc_lat"]), lon=mc.num(r["loc_lon"]), az=None if az is None else int(az),
            date=r["target_date"], ttype=r["target_type"], days_ahead=0, run_at=ev, event=ev, rating=None,
            stored_low=None, stored_mid=None, stored_high=None))
    return out


def write_fetch_list(path: str, rows: Sequence[dict]) -> None:
    """Write ``fetch_list.csv`` (creating the directory)."""
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FETCH_COLUMNS)
        w.writeheader()
        w.writerows(rows)


def report(pop: mc.Population, counts: mc.DedupCounts, sample: Sequence[Tuple[mc.Unit, float]], n_req: int,
           todo: int, done: int, a) -> str:
    """The text the owner reads before saying go."""
    L: List[str] = []
    L.append("Export rows: %d; eligible (batch_state NULL/SCORED, run before the event): %d; "
             "units after deduplication: %d" % (counts.rows, counts.eligible, counts.units))
    L.append("")
    L.append("Population funnel:")
    for label, n in pop.funnel:
        L.append("  %-58s %6d" % (label, n))
    L.append("  (moved-location guard dropped %d)" % pop.moved)
    L.append("  trigger / training cases set aside as worked examples: %d" % len(pop.worked))
    L.append("")
    strata: Dict[Tuple[str, str], List[int]] = defaultdict(lambda: [0, 0])
    for u in pop.members:
        strata[(u.ttype, u.month)][0] += 1
    for u, _ in sample:
        strata[(u.ttype, u.month)][1] += 1
    L.append("Strata (event, month): population / sampled")
    for k in sorted(strata):
        L.append("  %-8s %s  %5d / %d" % (k[0], k[1], strata[k][0], strata[k][1]))
    L.append("")
    L.append("Sample: %d of %d units in P (seed %d)" % (len(sample), len(pop.members), a.seed))
    wc = mc.om.weight(1, len(mc.HOURLY), 1)
    L.append("Requests: %d (date, model, 0.1 degree cell) fetches = %d weighted calls by the assumed rule "
             "(%d per coordinate); rough guide %d per unit before cell deduplication."
             % (n_req, n_req * wc, wc, GUIDE_CALLS_PER_UNIT))
    L.append("  already cached: %d; still to fetch: %d weighted calls (x %.2f measured cost factor = %.0f)"
             % (done, todo, a.call_weight, todo * a.call_weight))
    days = math.ceil(todo * a.call_weight / a.daily_calls) if todo else 0
    L.append("EXPECTED FETCH DAYS: %d  (at %d weighted calls per UTC day; never run P1's study the same day)"
             % (days, a.daily_calls))
    L.append("")
    L.append("Power (simulated Wilson lower bound on precision, n=%d, flag rate %.0f%%; independent draws, "
             "so the real block-bootstrap bound is wider):" % (len(sample), 100 * POWER_FLAG_RATE))
    L.append("  true precision | flagged | median lower bound | P(lower bound >= 50%)")
    for r in power_table(len(sample), seed=a.seed):
        L.append("  %14.2f | %7d | %18s | %s" % (r["true_precision"], r["n_flagged"],
                                                 mc.fmt_pct(r["median_lower_bound"]),
                                                 mc.fmt_pct(r["p_lower_bound_ge_50"])))
    return "\n".join(L)


def main(argv: Optional[List[str]] = None, client: Optional[mc.om.Client] = None) -> int:
    """CLI entry point: report and stop, unless ``--go``."""
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv", help="the P1 export (export_forecasts.sql output)")
    ap.add_argument("--out", default="out")
    ap.add_argument("--n", type=int, default=DEFAULT_N, help="sample size in units (default %(default)s)")
    ap.add_argument("--seed", type=int, default=DEFAULT_SEED)
    ap.add_argument("--daily-calls", type=int, default=DEFAULT_DAILY_CALLS,
                    help="weighted calls per UTC day Open-Meteo actually allows (default %(default)s)")
    ap.add_argument("--call-weight", type=float, default=1.0,
                    help="measured weight per assumed call from the T0 probe (default %(default)s)")
    ap.add_argument("--observer", action="store_true", help="also fetch the observer point (secondary)")
    ap.add_argument("--cache", default=mc.DEFAULT_CACHE, help="cache dir (default: far-cloud-p1/cache)")
    ap.add_argument("--go", action="store_true", help="write the fetch list (the owner's explicit go)")
    a = ap.parse_args(argv)

    units, counts = mc.dedup_units(mc.load_rows(a.csv))
    pop = mc.select_population(units)
    sample = stratified_sample(pop.members, a.n, a.seed)
    chosen = [u for u, _ in sample] + list(pop.worked)
    plan = mc.plan_requests(chosen, a.observer)
    n_req = sum(len(c) for c in plan.values()) * len(mc.MODELS)
    client = client or mc.om.Client(a.cache, dry_run=True)
    todo, done = uncached_calls(plan, client)
    print(report(pop, counts, sample, n_req, todo, done, a))
    if not a.go:
        print("\nSTOPPED. Nothing written, no network touched. Check the days and the power table, then re-run "
              "with --go to write %s." % os.path.join(a.out, "fetch_list.csv"))
        return 0
    path = os.path.join(a.out, "fetch_list.csv")
    write_fetch_list(path, fetch_rows(sample, pop.worked))
    print("\nwrote %s (%d sample + %d worked units). Next: md_fetch.py %s" %
          (path, len(sample), len(pop.worked), path))
    return 0


if __name__ == "__main__":
    sys.exit(main())
