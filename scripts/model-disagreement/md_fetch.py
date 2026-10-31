"""T3: fetch every model's cone-point cloud layers for the sampled units (resumable, takes days).

Plan: docs/engineering/model-disagreement-sizing-plan.md section 4 (T3).

* ONE request per model (``models=<m>``, a single model): the response keys stay unsuffixed and P1's
  ``openmeteo.Client`` caches, resumes and checks "served" exactly as it does for its own studies. A
  comma-separated ``models`` list is never sent through ``Client.get`` (it would never cache).
* Grouped by event date so one request carries many coordinates (``Client.get`` chunks 20 per request),
  deduplicated by (date, ``geometry.grid_key``) as production's cloud-point cache is.
* Model and variable order are fixed (``md_common.MODELS`` / ``HOURLY``): the cache key is the full URL, so
  the probe (T0) and this step must send identical requests.
* The cache directory is P1's ``cache/`` so one budget counter covers both studies. Pass ``--budget`` set to
  the daily limit Open-Meteo actually allows, and never run both studies on the same UTC day.
* Observer points are fetched only with ``--observer`` (secondary; fetch them last, if budget allows).

The run stops cleanly at the budget, a rate limit or a network error; re-run the same command the next UTC
day and it carries on from the cache. Standard library only.
"""
from __future__ import annotations

import argparse
import sys
from typing import List, Optional

import md_common as mc
import md_sample


def run(client: mc.om.Client, units: List[mc.Unit], observer: bool = False, log=print) -> int:
    """Fetch the plan date by date, model by model. Returns the number of (date, model) groups finished.
    Raises the client's BudgetExhausted / RateLimited / OpenMeteoError for the caller to report."""
    plan = mc.plan_requests(units, observer)
    done = 0
    for date in sorted(plan):
        coords = sorted(plan[date].values())
        for model in mc.MODELS:
            # Single model, single day, fixed variable order. Cached coordinates cost nothing.
            client.get(mc.KIND, coords, mc.HOURLY, date, date, models=model)
            done += 1
        log("%s: %d coordinates x %d models done (%s)" % (date, len(coords), len(mc.MODELS), client.summary()))
    return done


def main(argv: Optional[List[str]] = None, client: Optional[mc.om.Client] = None) -> int:
    """CLI entry point. Exit 0 when everything is fetched, 2 when it stopped early (re-run to resume)."""
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("fetch_list", help="out/fetch_list.csv from md_sample.py --go")
    ap.add_argument("--cache", default=mc.DEFAULT_CACHE, help="cache dir (default: far-cloud-p1/cache)")
    ap.add_argument("--budget", type=int, default=600,
                    help="weighted calls per UTC day to spend (default %(default)s; set to the real daily limit)")
    ap.add_argument("--pause", type=float, default=mc.om.DEFAULT_PAUSE_S)
    ap.add_argument("--observer", action="store_true", help="also fetch the observer point (secondary)")
    ap.add_argument("--dry-run", action="store_true", help="print the calls that would go live, then stop")
    ap.add_argument("--no-wait", action="store_true",
                    help="stop cleanly at the hourly cap instead of sleeping to the next hour")
    a = ap.parse_args(argv)

    units = md_sample.units_from_fetch_rows(md_sample.load_fetch_list(a.fetch_list))
    try:
        client = client or mc.om.Client(a.cache, a.budget, pause_s=a.pause, dry_run=a.dry_run,
                                        wait_for_hour=not a.no_wait)
        run(client, units, a.observer)
    except mc.om.BudgetFileCorrupt as e:
        print("REFUSING TO RUN: %s" % e)
        return 2
    except mc.om.RateLimited as e:
        print("RATE LIMITED, stopping (run again later; the cache keeps what was fetched): %s" % e)
        return 2
    except mc.om.BudgetExhausted as e:
        print("BUDGET REACHED, stopping (run again after 00:00 UTC; the cache keeps what was fetched): %s" % e)
        return 2
    except mc.om.OpenMeteoError as e:
        print("STOPPED on an Open-Meteo error (the cache keeps what was fetched): %s" % e)
        return 2
    print(client.summary())
    return 0


if __name__ == "__main__":
    sys.exit(main())
