"""Live API probe: confirms every Open-Meteo assumption the P1 scripts rely on.

Run on a machine that can reach open-meteo.com:  ``python3 probe.py``
Prints a checklist, exits non-zero on any FAIL. Costs about 30 weighted calls (limit ~60).
INFO lines are findings that change nothing by themselves (but read them).
"""
from __future__ import annotations

import argparse
import sys
from datetime import date, datetime, timedelta, timezone
from typing import Callable, List, Optional, Tuple

import openmeteo as om

MODELS = ["best_match", "ukmo_seamless", "ecmwf_ifs025", "icon_seamless", "gfs_seamless"]
ANGEL = (54.914, -1.589)
BISECT_CANDIDATES = ["2023-01-01", "2024-01-01", "2025-01-01", "2025-07-01"]
Result = Tuple[str, str, str]  # (PASS|FAIL|INFO, name, detail)


def _served(pt: om.Point, var: str) -> bool:
    return pt.served(var)


def run(client: om.Client, today: date) -> List[Result]:
    """Run all checks; never raises except RateLimited/BudgetExhausted (which abort the probe)."""
    out: List[Result] = []
    d10 = (today - timedelta(days=10)).isoformat()
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

    # (b) Previous Runs variable shapes
    for label, day in (("~1 month ago", d30), ("~9 months ago (export start)", d260)):
        def b1(day=day):
            vs = ["cloud_cover_low", om.prev_var("cloud_cover_low", 1)]
            pt = client.get_one("previous_runs", ANGEL, vs, day, day)
            ok = all(pt.served(v) for v in vs)
            return ("PASS" if ok else "FAIL",
                    f"{day}: " + ", ".join(f"{v}={'ok' if pt.served(v) else 'null/absent'}" for v in vs))
        check(f"(b) previous_runs low + low_previous_day1 {label}", b1)

    def b2():
        v = "cloud_cover_low_previous_day0"   # deliberately raw: the only place this is built
        try:
            pt = client.get_one("previous_runs", ANGEL, [v], d30, d30)
        except om.OpenMeteoError as e:
            return "INFO", f"rejected as expected ({e}); we never emit it"
        return "INFO", f"ACCEPTED (served={pt.served(v)}); we still never emit it"
    check("(b) previous_day0 suffix", b2)

    def b3():
        day = "2026-10-12"
        rows = []
        for m in MODELS:
            try:
                pt = client.get_one("previous_runs", ANGEL, om.CLOUD_VARS, day, day, models=m)
                ok = all(pt.served(v) for v in om.CLOUD_VARS)
                rows.append(f"{m}={'low/mid/high ok' if ok else 'MISSING ' + ','.join(sorted(pt.not_served))}")
            except om.OpenMeteoError as e:
                rows.append(f"{m}=rejected ({e})")
        return "INFO", f"{day}: " + "; ".join(rows)
    check("(b) models returning low/mid/high for 2026-10-12", b3)

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

    # (g) earliest date served by Previous Runs per model (bisect over a few candidates)
    def g():
        res = []
        for m in MODELS:
            lo, hi, first_ok = 0, len(BISECT_CANDIDATES) - 1, None
            while lo <= hi:
                mid = (lo + hi) // 2
                day = BISECT_CANDIDATES[mid]
                try:
                    pt = client.get_one("previous_runs", ANGEL,
                                        [om.prev_var("cloud_cover_low", 1)], day, day, models=m)
                    ok = pt.served(om.prev_var("cloud_cover_low", 1))
                except om.OpenMeteoError:
                    ok = False
                if ok:
                    first_ok, hi = day, mid - 1
                else:
                    lo = mid + 1
            res.append(f"{m}: earliest tested date served = {first_ok or 'none of ' + str(BISECT_CANDIDATES)}")
        return "INFO", "; ".join(res)
    check("(g) earliest Previous Runs date per model (day-1 low cloud)", g)

    return out


def main(argv: Optional[List[str]] = None, client: Optional[om.Client] = None) -> int:
    """CLI entry point."""
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--cache", default="cache")
    ap.add_argument("--budget", type=int, default=om.DEFAULT_BUDGET)
    ap.add_argument("--pause", type=float, default=om.DEFAULT_PAUSE_S)
    ap.add_argument("--today", default=None, help="UTC date override (testing)")
    a = ap.parse_args(argv)
    today = date.fromisoformat(a.today) if a.today else datetime.now(timezone.utc).date()
    client = client or om.Client(a.cache, a.budget, pause_s=a.pause)
    try:
        results = run(client, today)
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
