"""Live API probe: confirms every Open-Meteo assumption the P1 scripts rely on.

Run on a machine that can reach open-meteo.com:  ``python3 probe.py``
Prints a checklist, exits non-zero on any FAIL. Costs about 50 weighted calls in total (it checks itself against a cap of 60 and prints the figure).
INFO lines are findings that change nothing by themselves (but read them).

What the 2026-10-14 run found (plan section 11): Previous Runs documents ``_previous_dayN`` for TOTAL
cloud cover only, so low/mid/high "as issued the day before" does not exist; the forecast arm of
Parts A and C is the Historical Forecast API's day-0 low/mid/high. A multi-coordinate archive request
keeps request order and does not merge points that share a 0.25 degree cell; Open-Meteo's default
``cell_selection=land`` can answer a sea point from the neighbouring land cell (one cell, 0.25 degrees).
"""
from __future__ import annotations

import argparse
import sys
from datetime import date, datetime, timedelta, timezone
from typing import Callable, List, Optional, Tuple

import openmeteo as om

MODELS = ["best_match", "ukmo_seamless", "ecmwf_ifs025", "icon_seamless", "gfs_seamless"]
ANGEL = (54.914, -1.589)
MAX_PROBE_WEIGHT = 60
EXPORT_START = date(2026, 2, 1)   # the forecast export starts here; every arm must serve it
CELL_DEG = 0.25                   # ERA5 grid; a land-snapped sea point moves by at most this
Result = Tuple[str, str, str]  # (PASS|FAIL|INFO, name, detail)


def _served(pt: om.Point, var: str) -> bool:
    return pt.served(var)


def run(client: om.Client, today: date) -> List[Result]:
    """Run all checks; never raises except RateLimited/BudgetExhausted (which abort the probe)."""
    out: List[Result] = []
    d10 = (today - timedelta(days=10)).isoformat()
    d2 = (today - timedelta(days=2)).isoformat()
    d20 = (today - timedelta(days=20)).isoformat()
    d30 = (today - timedelta(days=30)).isoformat()
    d260 = (today - timedelta(days=260)).isoformat()  # the export starts 2026-02-01
    lat, lon = ANGEL

    def check(name: str, fn: Callable[[], Tuple[str, str]]) -> None:
        try:
            status, detail = fn()
        except (om.RateLimited, om.BudgetExhausted):
            raise
        except om.OpenMeteoError as e:
            status, detail = "FAIL", f"API error: {e}"
        except Exception as e:  # noqa: BLE001 - a probe reports, it does not crash
            status, detail = "FAIL", f"{type(e).__name__}: {e}"
        out.append((status, name, detail))

    # (a) each host answers a tiny request
    for kind, day in (("forecast", today.isoformat()), ("archive", d10),
                      ("previous_runs", d30), ("historical_forecast", d10)):
        def a(kind=kind, day=day):
            pt = client.get_one(kind, ANGEL, ["cloud_cover_low"], day, day)
            ok = pt.served("cloud_cover_low")
            return ("PASS" if ok else "FAIL",
                    f"{len(pt.hours)} hours, cloud_cover_low {'served' if ok else 'NOT served'}")
        check(f"(a) host {kind} answers", a)

    # (b) Previous Runs: the day-before arm is only documented for TOTAL cloud cover
    def b1():
        vs = [om.prev_var(v, 1) for v in om.CLOUD_VARS]
        pt = client.get_one("previous_runs", ANGEL, om.CLOUD_VARS + vs, d30, d30, models="best_match")
        plain = all(pt.served(v) for v in om.CLOUD_VARS)
        day1 = [v for v in vs if pt.served(v)]
        if not plain:
            return "FAIL", f"{d30}: plain day-0 low/mid/high NOT served by previous_runs"
        if day1:
            return "INFO", (f"{d30}: layer _previous_day1 IS NOW SERVED ({', '.join(day1)}): the plan's "
                            f"day-before arm could be restored (plan section 11)")
        return "INFO", (f"{d30}: layer _previous_day1 not served, as documented (total cloud_cover only); "
                        f"plain day-0 low/mid/high served, so the forecast arm is day 0 (plan section 11)")
    check("(b) previous_runs: low/mid/high _previous_day1 vs plain day 0", b1)

    def b2():
        v = "cloud_cover_low_previous_day0"   # deliberately raw: the only place this is built
        try:
            pt = client.get_one("previous_runs", ANGEL, [v], d30, d30)
        except om.OpenMeteoError as e:
            return "INFO", f"rejected as expected ({e}); we never emit it"
        return "INFO", f"ACCEPTED (served={pt.served(v)}); we still never emit it"
    check("(b) previous_day0 suffix", b2)

    def b3():
        rows, missing_best = [], False
        for m in MODELS:
            try:
                pt = client.get_one("historical_forecast", ANGEL, om.CLOUD_VARS, d10, d10, models=m)
                ok = all(pt.served(v) for v in om.CLOUD_VARS)
                rows.append(f"{m}={'low/mid/high ok' if ok else 'MISSING ' + ','.join(sorted(pt.not_served))}")
                missing_best = missing_best or (m == "best_match" and not ok)
            except om.OpenMeteoError as e:
                rows.append(f"{m}=rejected ({e})")
                missing_best = missing_best or m == "best_match"
        return ("FAIL" if missing_best else "PASS"), f"{d10}: " + "; ".join(rows)
    check(f"(b) historical_forecast serves low/mid/high per model for {d10} (best_match must)", b3)

    def b4():
        vs = om.CLOUD_VARS
        a = client.get_one("historical_forecast", ANGEL, vs, d10, d10, models="best_match")
        b = client.get_one("previous_runs", ANGEL, vs, d10, d10, models="best_match")
        diffs = sum(1 for h in a.hours for v in vs if a.value(h, v) != b.hours.get(h, {}).get(v))
        n = len(a.hours) * len(vs)
        return ("PASS" if n and diffs == 0 else "INFO"), (
            f"{d10}: historical_forecast and previous_runs plain day 0 differ in {diffs} of {n} values")
    check("(b) previous_runs plain day 0 equals historical_forecast (best_match)", b4)

    # (b') the exact request shapes size.py and case.py use
    def hist_shape():
        pt = client.get_one("historical_forecast", ANGEL, om.CLOUD_VARS, d30, d30, models="best_match")
        bad = [v for v in om.CLOUD_VARS if not pt.served(v)]
        return ("FAIL" if bad else "PASS",
                f"{d30} models=best_match: " + (f"NOT served: {', '.join(bad)}" if bad
                                               else f"all of {', '.join(om.CLOUD_VARS)} served"))
    check("(i) historical_forecast best_match, cloud_cover_{low,mid,high}, one point, ~30 days ago",
          hist_shape)

    def archive20():
        # 20 distinct cells (0.5 degrees apart), sent in the sorted order Client.get uses
        coords = [(50.0 + 0.5 * ((i * 7) % 20), -4.0 + 0.5 * ((i * 3) % 20)) for i in range(20)]
        coords = [om.round_coord(*c) for c in coords]
        uniq = sorted(set(coords))
        if len(uniq) != 20:
            return "FAIL", "probe bug: coordinates not distinct"
        pts = client.get("archive", uniq, om.CLOUD_VARS, d20, d20, models="era5")
        unserved = [i for i, p in enumerate(pts) if not all(p.served(v) for v in om.CLOUD_VARS)]
        ans = [(p.latitude, p.longitude) for p in pts]
        broken = om.order_violations(uniq, ans, CELL_DEG + 0.05)
        snapped = [i for i, (c, a) in enumerate(zip(uniq, ans))
                   if a[0] is not None and (abs(a[0] - c[0]) > 0.01 or abs(a[1] - c[1]) > 0.01)]
        ok = len(pts) == 20 and not unserved and not broken
        return ("PASS" if ok else "FAIL",
                f"{d20}: {len(pts)} points; not served at {unserved or 'none'}; order broken or "
                f"answered more than one cell away at {broken or 'none'}; land-snapped by one cell "
                f"(cell_selection=land, harmless) at {snapped or 'none'}")
    check("(iii) 20-coordinate archive request (models=era5, low/mid/high, ~20 days ago): 20 served points in order",
          archive20)

    def same_cell():
        # three coordinates inside ONE 0.25 degree cell, deliberately unsorted: must not be merged
        coords = [(54.95, -1.55), (54.90, -1.60), (54.93, -1.58)]
        url = om.build_url("archive", coords, ["cloud_cover_low"], d20, d20)
        pts = client.request(url, om.weight(3, 1, 1)) or []
        ok = len(pts) == 3 and all(p.served("cloud_cover_low") for p in pts)
        return ("PASS" if ok else "FAIL"), (
            f"{len(pts)} points returned for 3 coordinates in one cell, cells "
            f"{sorted(set((p.latitude, p.longitude) for p in pts))}")
    check("(iv) coordinates sharing a 0.25 degree cell are answered one-for-one, not merged", same_cell)

    # (c) pressure-level variable on Previous Runs
    def c():
        v = om.prev_var("cloud_cover_700hPa", 1)
        try:
            pt = client.get_one("previous_runs", ANGEL, [v], d30, d30)
        except om.OpenMeteoError as e:
            return "PASS", f"{v} rejected as the owner found 2026-10-13 ({e})"
        return "INFO", f"{v} ACCEPTED (served={pt.served(v)}): the 2026-10-13 finding has changed"
    check("(c) previous_runs rejects pressure-level _previous_day1", c)

    # (d) archive with/without models=era5
    def d():
        vs = ["cloud_cover_mid", "cloud_cover_high"]
        a = client.get_one("archive", ANGEL, vs, d10, d10, models="era5")
        b = client.get_one("archive", ANGEL, vs, d10, d10, models="")  # no models parameter
        ok = all(a.served(v) and b.served(v) for v in vs)
        diffs = sum(1 for h in a.hours for v in vs
                    if a.value(h, v) != b.hours.get(h, {}).get(v))
        return ("PASS" if ok else "FAIL",
                f"mid/high served with and without models=era5: {ok}; {diffs} of "
                f"{len(a.hours) * len(vs)} hourly values differ")
    check("(d) archive mid/high, models=era5 vs default", d)

    # (e) historical forecast pressure levels
    def e():
        pt = client.get_one("historical_forecast", ANGEL, om.PRESSURE_VARS,
                            "2026-10-13", "2026-10-13")
        h = "2026-10-13T17:00"
        vals = {v: pt.value(h, v) for v in om.PRESSURE_VARS}
        ok = all(x is not None for x in vals.values())
        return ("PASS" if ok else "FAIL", f"{h}: {vals}")
    check("(e) historical_forecast six pressure levels at the Angel", e)

    # (f) coordinate order preserved (deliberately sent in REVERSE sorted order)
    def f():
        coords = [(55.5, -2.5), (54.5, -1.5)]   # descending latitude
        url = om.build_url("archive", coords, ["cloud_cover_low"], d10, d10)
        pts = client.request(url, om.weight(2, 1, 1)) or []
        ok = (len(pts) == 2 and pts[0].latitude is not None and pts[1].latitude is not None
              and abs(pts[0].latitude - 55.5) < 0.3 and abs(pts[1].latitude - 54.5) < 0.3)
        return ("PASS" if ok else "FAIL",
                f"returned latitudes {[p.latitude for p in pts]} for requested [55.5, 54.5]")
    check("(f) coordinate order preserved", f)

    # (g) does each model serve day-0 low cloud at the export start (Historical Forecast)?
    def g():
        day = EXPORT_START.isoformat()
        res, best_ok = [], False
        for m in MODELS:
            try:
                pt = client.get_one("historical_forecast", ANGEL, ["cloud_cover_low"], day, day, models=m)
                ok = pt.served("cloud_cover_low")
            except om.OpenMeteoError as e:
                ok = False
                res.append(f"{m}: rejected ({e})")
                continue
            best_ok = best_ok or (m == "best_match" and ok)
            res.append(f"{m}: {'served' if ok else 'NOT served'}")
        return ("PASS" if best_ok else "FAIL"), f"{day}: " + "; ".join(res)
    check("(g) historical_forecast day-0 low cloud at the export start, per model (best_match must)", g)

    cost = client.weight_spent
    out.append(("PASS" if cost < MAX_PROBE_WEIGHT else "FAIL", "probe cost under the cap",
                f"{cost} weighted calls spent by this probe (cap {MAX_PROBE_WEIGHT}; cached re-runs cost less)"))
    return out


def main(argv: Optional[List[str]] = None, client: Optional[om.Client] = None) -> int:
    """CLI entry point."""
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--cache", default="cache")
    ap.add_argument("--budget", type=int, default=om.DEFAULT_BUDGET)
    ap.add_argument("--pause", type=float, default=om.DEFAULT_PAUSE_S)
    ap.add_argument("--no-wait", action="store_true",
                    help="stop cleanly at the hourly cap instead of sleeping to the next hour")
    ap.add_argument("--today", default=None, help="UTC date override (testing)")
    a = ap.parse_args(argv)
    today = date.fromisoformat(a.today) if a.today else datetime.now(timezone.utc).date()
    try:
        client = client or om.Client(a.cache, a.budget, pause_s=a.pause, wait_for_hour=not a.no_wait)
        results = run(client, today)
    except om.BudgetFileCorrupt as e:
        print(f"REFUSING TO RUN: {e}")
        return 2
    except om.RateLimited as e:
        print(f"RATE LIMITED, stopping: {e}")
        return 2
    except om.BudgetExhausted as e:
        print(f"BUDGET EXHAUSTED: {e}")
        return 2
    failed = 0
    for status, name, detail in results:
        print(f"[{status:4}] {name}\n         {detail}")
        failed += status == "FAIL"
    print(f"\nweighted calls spent by this probe: {client.weight_spent} (cache hits: {client.cache_hits})")
    print("RESULT:", "FAIL (%d)" % failed if failed else "OK")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
