"""A small synthetic world for the tests and for the end-to-end run of ``md_size.py``.

``build_rows()`` writes a P1-export-shaped CSV (only the columns the study reads) whose ERA5 outcome and
model values are pure functions of (location, date, type), so the CSV and the fake Open-Meteo provider agree
without sharing state. Deliberate shape:

* two runs before the event per slot (the later one must win), one run AFTER the event and one PENDING batch
  row (both must be ignored);
* ICON, GFS and Meteo-France see cloud mostly when the day is cloudy, UKMO and best_match nearly never;
* one unit's location "moved" (ERA5 sampled elsewhere), plus the 31 Oct 2026 Angel of the North trigger and
  a 13 Oct training case. ``Provider`` can also be told to drop a model or null a cone high for one unit.

CLI (stdout says what it wrote)::

    python3 tests/fixture_tools.py --csv tests/fixtures/export_small.csv
    python3 tests/fixture_tools.py --cache /tmp/md-cache --csv tests/fixtures/export_small.csv
"""
import argparse
import csv
import os
import random
import sys
from datetime import date, datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
import md_common as mc  # noqa: E402

SEED = 4242
LOCATIONS = [(1, "Fixture Point A", 50.5, -4.0), (2, "Fixture Point B", 50.5, 0.5),
             (3, "Fixture Point C", 52.5, -4.0), (4, "Fixture Point D", 52.5, 0.5)]
ANGEL = (9, "Angel of the North", 54.9141, -1.5895)
SUNSET_AZ, SUNRISE_AZ = 255.7, 104.3          # fractional on purpose: the code must int() them
# chance that each model reads M >= 30 on a cloudy / clear day
SEES = {mc.ICON: (0.80, 0.10), mc.GFS: (0.65, 0.20), mc.MF: (0.70, 0.10), mc.ECMWF: (0.85, 0.08),
        mc.UKMO: (0.03, 0.01), mc.BEST: (0.04, 0.01)}
COLUMNS = list(mc.REQUIRED_COLS) + ["rating"] + list(mc.STORED_EXTRA_COLS) + [
    "cv_observer_low_cloud", "cv_observer_mid_cloud", "cv_observer_high_cloud"]


def key_rng(*parts):
    """A Random seeded from a key, so every consumer derives the same truth."""
    return random.Random("%s|%s" % (SEED, "|".join(str(p) for p in parts)))


def truth(loc_id, day, ttype):
    """The latent world for one slot: cloud day?, each model's M, ERA5 outcome, observer values."""
    rng = key_rng(loc_id, day, ttype)
    cloudy = rng.random() < 0.35
    vals = {}
    for m, (p_cloud, p_clear) in SEES.items():
        see = rng.random() < (p_cloud if cloudy else p_clear)
        if m in (mc.UKMO, mc.BEST):
            vals[m] = rng.randint(30, 45) if see else rng.randint(0, 6)
        else:
            vals[m] = rng.randint(30, 80) if see else rng.randint(0, 25)
    y = rng.random() < (0.9 if cloudy else 0.1)
    return {"cloudy": cloudy, "M": vals, "y": y, "lead": 2 + rng.randint(0, 14),
            "stored_nonzero_far": rng.random() < 0.15, "rating": rng.choice([2, 3, 3, 4, 5]),
            "obs_y": rng.random() < (0.6 if cloudy else 0.1)}


def slot_dates(n=30, start=date(2026, 3, 1), step=3):
    """Distinct target dates spread over three months."""
    return [start + timedelta(days=step * i) for i in range(n)]


def row(eval_id, loc, day, ttype, run_at, event, days_ahead=0, batch_state="", az=None, stored=(0, 0, 5), **kw):
    """One export row; unspecified columns are empty."""
    lid, name, lat, lon = loc
    r = {c: "" for c in COLUMNS}
    r.update({"evaluation_id": eval_id, "location_id": lid, "target_date": day.isoformat(),
              "target_type": ttype, "forecast_run_at": run_at.strftime(mc.EVENT_FORMAT),
              "solar_event_time": event.strftime(mc.EVENT_FORMAT),
              "azimuth_deg": SUNSET_AZ if ttype == "SUNSET" else SUNRISE_AZ, "days_ahead": days_ahead,
              "batch_state": batch_state, "loc_name": name, "loc_lat": lat, "loc_lon": lon,
              "solar_mid_cloud": stored[0], "solar_high_cloud": stored[1], "solar_low_cloud": stored[2]})
    r.update(kw)
    return r


def cv_columns(t, lat, lon, ttype, moved=False):
    """ERA5 verification columns for a slot's truth."""
    az = int(SUNSET_AZ if ttype == "SUNSET" else SUNRISE_AZ)
    clat, clon = mc.geometry.cone_points(lat, lon, az)[1]
    if moved:
        clat += 0.05
    mid, high = (45, 20) if t["y"] else (4, 8)
    return {"cv_id": 1, "cv_horizon_sample_lat": "%.6f" % clat, "cv_horizon_sample_lon": "%.6f" % clon,
            "cv_horizon_low_cloud": 20, "cv_horizon_mid_cloud": mid, "cv_horizon_high_cloud": high,
            "cv_observer_low_cloud": 15, "cv_observer_mid_cloud": 40 if t["obs_y"] else 3,
            "cv_observer_high_cloud": 10}


def build_rows():
    """All export rows for the synthetic world."""
    rows, eid = [], 0
    moved_key = (1, slot_dates()[2].isoformat(), "SUNSET")
    for day in slot_dates():
        for loc in LOCATIONS:
            for ttype in ("SUNSET", "SUNRISE"):
                t = truth(loc[0], day.isoformat(), ttype)
                ev = datetime(day.year, day.month, day.day, 18 if ttype == "SUNSET" else 6, 10)
                latest = ev - timedelta(hours=t["lead"])
                common = dict(az=None, **cv_columns(t, loc[2], loc[3], ttype,
                                                    moved=(loc[0], day.isoformat(), ttype) == moved_key))
                # an earlier run that disagrees (non-zero stored mid), the latest pre-event run, a post-event run
                # and a PENDING batch row: only the latest SCORED/NULL pre-event run may become the unit
                eid += 1
                rows.append(row(eid, loc, day, ttype, latest - timedelta(hours=9), ev, stored=(40, 30, 5), **common))
                eid += 1
                stored = (0, 0, 5)
                extra = {"far_solar_mid_cloud": 20} if t["stored_nonzero_far"] else {}
                rows.append(row(eid, loc, day, ttype, latest, ev, stored=stored, rating=t["rating"], **extra,
                                **common))
                eid += 1
                rows.append(row(eid, loc, day, ttype, ev + timedelta(hours=1), ev, stored=(70, 70, 5), **common))
                eid += 1
                rows.append(row(eid, loc, day, ttype, latest + timedelta(minutes=30), ev, stored=(80, 80, 5),
                                batch_state="PENDING", **common))
    # the trigger (no ERA5 yet) and a training case (ERA5 present)
    for day, verified in ((date(2026, 10, 31), False), (date(2026, 10, 13), True)):
        t = truth(ANGEL[0], day.isoformat(), "SUNSET")
        ev = datetime(day.year, day.month, day.day, 16, 36)
        eid += 1
        cv = cv_columns(t, ANGEL[2], ANGEL[3], "SUNSET") if verified else {}
        rows.append(row(eid, ANGEL, day, "SUNSET", ev - timedelta(hours=8), ev, **cv))
    return rows


def write_csv(path, rows=None):
    """Write the export-shaped CSV."""
    rows = build_rows() if rows is None else rows
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS)
        w.writeheader()
        w.writerows(rows)


class Provider:
    """A model-switching Open-Meteo provider derived from the export CSV alone."""

    def __init__(self, csv_path, observer=True):
        units, _ = mc.dedup_units(mc.load_rows(csv_path))
        self.plan = mc.plan_requests(units, observer)
        self.by_coord = {}      # (date, coord) -> (unit, is_observer)
        for u in units:
            for c in mc.unit_cone(u):
                self.by_coord[(u.event_date, mc.rep_coord(self.plan, u, c))] = (u, False)
        for u in units:
            c = mc.om.round_coord(u.lat, u.lon)
            if observer:
                self.by_coord.setdefault((u.event_date, mc.rep_coord(self.plan, u, c)), (u, True))
        self.missing_model = {}     # (unit key) -> model that returns nothing
        self.null_high = set()      # unit keys whose cone high is null for every model

    def __call__(self, model, var, lat, lon, hour, url):
        day = hour[:10]
        hit = self.by_coord.get((day, mc.om.round_coord(lat, lon)))
        if hit is None:
            return 0
        u, is_obs = hit
        key = (u.location_id, u.date, u.ttype)
        if self.missing_model.get(key) == model:
            return None
        t = truth(u.location_id, u.date, u.ttype)
        v = t["M"][model]
        if var == "cloud_cover_high":
            return None if key in self.null_high else v // 2
        if var == "cloud_cover_low":
            return 5
        if var == "cloud_cover_mid" or var == "cloud_cover_500hPa":
            return v
        return v // 2


def populate_cache(csv_path, cache_dir, out_dir, observer=True, provider=None, n=100000):
    """Run the real md_sample then md_fetch against the fake provider. Returns (fetch_list, fake)."""
    import md_fetch
    import md_sample
    from model_fake import ModelOM
    fake = ModelOM(provider or Provider(csv_path, observer))
    md_sample.main([csv_path, "--out", out_dir, "--cache", cache_dir, "--n", str(n), "--go"])
    client = mc.om.Client(cache_dir, 10 ** 7, fetch=fake, pause_s=0, sleep=lambda s: None, wait_for_hour=False,
                           hourly_cap=10 ** 9, minute_cap=10 ** 9)
    path = os.path.join(out_dir, "fetch_list.csv")
    argv = [path, "--cache", cache_dir, "--budget", str(10 ** 7)] + (["--observer"] if observer else [])
    md_fetch.main(argv, client=client)
    return path, fake


def main(argv=None):
    """Write the CSV and/or populate a cache from it."""
    sys.path.insert(0, HERE)
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", required=True, help="where to write (or read, with --cache) the export CSV")
    ap.add_argument("--cache", default=None, help="populate this cache dir through the real sample/fetch scripts")
    ap.add_argument("--out", default=None, help="work dir for the fetch list (default: next to the cache)")
    a = ap.parse_args(argv)
    if not a.cache:
        write_csv(a.csv)
        print("wrote", a.csv)
        return 0
    out = a.out or os.path.join(os.path.dirname(os.path.abspath(a.cache)), "md-fixture-out")
    path, fake = populate_cache(a.csv, a.cache, out)
    print("fetch list %s; %d fake requests, all single-model" % (path, len(fake.urls)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
