"""T0: probe the API assumptions the study rests on (a few calls; the owner runs it).

Plan: docs/engineering/model-disagreement-sizing-plan.md section 4 (T0).

For one location and one past date it sends ONE single-model Historical Forecast request per model
(``ukmo_seamless``, ``best_match``, ``icon_seamless``, ``gfs_seamless``, ``meteofrance_seamless``,
``ecmwf_ifs025``) for low/mid/high and ``cloud_cover_700hPa/500hPa/300hPa`` at the observer point and the
three cone points, prints which variables each model serves, and prints the cone value M per model at the
production slot. By default it reproduces the 31 Oct 2026 Angel of the North sunset (plan section 1).

It also asks the Single Runs API whether it serves the layer bands per model. That is RECORD ONLY: a lead-fair
T+1 would be a follow-up, not this study.

⚠️ Real cost is NOT what this script prints. The weighted-call count below is P1's assumed formula
(``openmeteo.weight``, which ignores ``models``). Read Open-Meteo's usage (dashboard or response headers)
before and after the run and pass the measured factor to ``md_sample.py --call-weight``.
Standard library only.
"""
from __future__ import annotations

import argparse
import sys
from datetime import date as _date
from datetime import timedelta
from typing import List, Optional, Tuple

import md_common as mc

# Angel of the North, the trigger (plan section 1). Sunset 31 Oct 2026 is about 16:36 UTC.
DEFAULT_LAT, DEFAULT_LON, DEFAULT_AZ = 54.9141, -1.5895, 245
DEFAULT_DATE, DEFAULT_EVENT = "2026-10-31", "16:36:00"
SINGLE_RUNS_HOST = "https://single-runs-api.open-meteo.com/v1/forecast"

Result = Tuple[str, str, str]      # (PASS|FAIL|INFO, name, detail)


def single_runs_url(model: str, lat: float, lon: float, day: str) -> str:
    """A Single Runs request for one model: the previous day's 00:00 run, three days of the layer bands."""
    run = (_date.fromisoformat(day) - timedelta(days=1)).isoformat() + "T00:00"
    return ("%s?latitude=%.4f&longitude=%.4f&hourly=%s&models=%s&run=%s&forecast_days=3&timezone=UTC"
            % (SINGLE_RUNS_HOST, lat, lon, ",".join(mc.LAYER_VARS), model, run))


def run(client: mc.om.Client, lat: float, lon: float, az: int, day: str, event_time: str, ttype: str,
        single_runs: bool = True) -> List[Result]:
    """All probe checks. Observer first, then the cone points, in one request per model."""
    out: List[Result] = []
    event = mc.parse_event("%sT%s" % (day, event_time))
    coords = [mc.om.round_coord(lat, lon)] + [mc.om.round_coord(*p) for p in mc.geometry.cone_points(lat, lon, az)]
    table = ["%-22s %-12s %-12s %-14s" % ("model", "obs mid/high", "cone M", "cone mid/high means")]
    for model in mc.MODELS:
        # One single-model request per model; never a comma-separated list.
        pts = client.get(mc.KIND, coords, mc.HOURLY, day, day, models=model)
        obs, cone = pts[0], pts[1:]
        unserved = sorted({v for p in pts for v in mc.LAYER_VARS if not p.served(v)})
        pl_unserved = sorted({v for p in pts for v in mc.PL_VARS if not p.served(v)})
        out.append(("FAIL" if unserved else "PASS", "%s serves low/mid/high at all four points" % model,
                    "not served: %s" % ", ".join(unserved) if unserved else "all served"))
        out.append(("INFO" if pl_unserved else "PASS", "%s serves 700/500/300 hPa" % model,
                    ("not served: %s (S2-PL loses this model)" % ", ".join(pl_unserved)) if pl_unserved
                    else "all served"))
        hour = mc.slot_hour(mc.Unit(0, "0", "", lat, lon, az, day, ttype, 0, event, event, None,
                                    None, None, None), obs)
        if hour is None:
            table.append("%-22s (no hours returned)" % model)
            continue
        m = mc.model_value(cone, hour)
        ov = (obs.value(hour, "cloud_cover_mid"), obs.value(hour, "cloud_cover_high"))
        cone_mid = [p.value(hour, "cloud_cover_mid") for p in cone]
        cone_high = [p.value(hour, "cloud_cover_high") for p in cone]
        table.append("%-22s %-12s %-12s %s / %s" % (model, "%s/%s" % ov, "no vote" if m is None else m,
                                                   cone_mid, cone_high))
    out.append(("INFO", "values at the production slot", "\n         " + "\n         ".join(table)))
    if single_runs:
        out.extend(probe_single_runs(client, lat, lon, day))
    cost = client.weight_spent
    out.append(("INFO", "weighted calls by P1's formula (NOT the real cost)",
                "%d. Read Open-Meteo's usage before and after this run, then pass the measured factor to "
                "md_sample.py --call-weight." % cost))
    return out


def probe_single_runs(client: mc.om.Client, lat: float, lon: float, day: str) -> List[Result]:
    """Record whether the Single Runs API serves the layer bands per model. Never fails the probe."""
    out: List[Result] = []
    for model in mc.MODELS:
        try:
            pts = client.request(single_runs_url(model, lat, lon, day), mc.om.weight(1, len(mc.LAYER_VARS), 3))
        except mc.om.RateLimited:
            raise
        except mc.om.OpenMeteoError as e:
            out.append(("INFO", "Single Runs: %s" % model, "error: %s" % e))
            continue
        if not pts:
            out.append(("INFO", "Single Runs: %s" % model, "dry run"))
            continue
        served = [v for v in mc.LAYER_VARS if pts[0].served(v)]
        out.append(("INFO", "Single Runs: %s" % model, "serves %s" % (", ".join(served) or "none of the layers")))
    return out


def main(argv: Optional[List[str]] = None, client: Optional[mc.om.Client] = None) -> int:
    """CLI entry point. Exit 1 if any model fails to serve the cloud layers, 2 if stopped early."""
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lat", type=float, default=DEFAULT_LAT)
    ap.add_argument("--lon", type=float, default=DEFAULT_LON)
    ap.add_argument("--az", type=int, default=DEFAULT_AZ, help="solar azimuth in whole degrees")
    ap.add_argument("--date", default=DEFAULT_DATE, help="past UTC date, YYYY-MM-DD")
    ap.add_argument("--event-time", default=DEFAULT_EVENT, help="solar event time UTC, HH:MM:SS")
    ap.add_argument("--type", default="SUNSET", choices=["SUNSET", "SUNRISE"])
    ap.add_argument("--no-single-runs", action="store_true", help="skip the (record-only) Single Runs probe")
    ap.add_argument("--cache", default=mc.DEFAULT_CACHE, help="cache dir (default: far-cloud-p1/cache)")
    ap.add_argument("--budget", type=int, default=600)
    ap.add_argument("--pause", type=float, default=mc.om.DEFAULT_PAUSE_S)
    ap.add_argument("--no-wait", action="store_true")
    a = ap.parse_args(argv)
    try:
        client = client or mc.om.Client(a.cache, a.budget, pause_s=a.pause, wait_for_hour=not a.no_wait)
        results = run(client, a.lat, a.lon, a.az, a.date, a.event_time, a.type, not a.no_single_runs)
    except mc.om.BudgetFileCorrupt as e:
        print("REFUSING TO RUN: %s" % e)
        return 2
    except (mc.om.RateLimited, mc.om.BudgetExhausted, mc.om.OpenMeteoError) as e:
        print("STOPPED: %s" % e)
        return 2
    failed = 0
    for status, name, detail in results:
        print("[%-4s] %s\n         %s" % (status, name, detail))
        failed += status == "FAIL"
    print("\nRESULT:", "FAIL (%d)" % failed if failed else "OK")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
