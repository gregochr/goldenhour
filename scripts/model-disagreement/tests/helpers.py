"""Builders shared by the tests (no network, no files)."""
import os
import sys
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.normpath(os.path.join(HERE, "..")))     # this study's own modules first; P1 is appended by md_common
sys.path.insert(0, HERE)
import md_common as mc  # noqa: E402
import md_size  # noqa: E402

HOUR = "2026-03-02T18:00"


def raw_row(eval_id, run="2026-03-02T10:00:00", event="2026-03-02T18:10:00", loc="1", date="2026-03-02",
            ttype="SUNSET", days=0, state="", mid=0, high=0, low=5, **kw):
    """A minimal export row (the columns md_common reads)."""
    r = {c: "" for c in mc.REQUIRED_COLS + ("rating",) + mc.STORED_EXTRA_COLS}
    r.update({"evaluation_id": eval_id, "location_id": loc, "target_date": date, "target_type": ttype,
              "forecast_run_at": run, "solar_event_time": event, "azimuth_deg": "255.7", "days_ahead": days,
              "batch_state": state, "solar_low_cloud": low, "solar_mid_cloud": mid, "solar_high_cloud": high,
              "loc_name": "Somewhere", "loc_lat": "50.5", "loc_lon": "-4.0", "cv_id": 1,
              "cv_horizon_low_cloud": 20, "cv_horizon_mid_cloud": 40, "cv_horizon_high_cloud": 10})
    r.update(kw)
    return {k: "" if v is None else str(v) for k, v in r.items()}      # CSV cells are text


def point(mids=None, highs=None, hour=HOUR, extra=None):
    """An ``openmeteo.Point`` with one hour of values."""
    p = mc.om.Point()
    p.hours[hour] = {"cloud_cover_mid": mids, "cloud_cover_high": highs}
    p.hours[hour].update(extra or {})
    return p


def cone(mids, highs, hour=HOUR):
    """Three cone points from per-point mid and high lists."""
    return [point(m, h, hour) for m, h in zip(mids, highs)]


def mk_unit(i, date="2026-03-02", ttype="SUNSET", lead=8.0, y=True, rating=3, loc="1"):
    """A Unit in P (stored zeros, ERA5 present); ``y`` makes the ERA5 horizon cloudy."""
    ev = datetime.strptime(date + "T18:00:00", mc.EVENT_FORMAT)
    return mc.Unit(eval_id=i, location_id=loc, name="Somewhere", lat=50.5, lon=-4.0, az=255, date=date, ttype=ttype,
                   days_ahead=0, run_at=ev - timedelta(hours=lead), event=ev, rating=rating, stored_low=5,
                   stored_mid=0, stored_high=0, stored_extra=[None] * len(mc.STORED_EXTRA_COLS), cv_id="1",
                   h_low=20, h_mid=45 if y else 3, h_high=10, s_lat=None, s_lon=None)


def mk_rec(i, s2, y, month="2026-03", day=1, ttype="SUNSET", uk=0, bm=0, lead=8.0, ecmwf=0, gfs_hi=None):
    """A Rec with chosen S2 (True: ICON and GFS high, Meteo-France low), outcome and comparator values."""
    u = mk_unit(i, "%s-%02d" % (month, day), ttype, lead, y)
    rec = md_size.Rec(u=u, y=y)
    rec.M = {mc.UKMO: uk, mc.BEST: bm, mc.ICON: 50 if s2 else 5, mc.GFS: 50 if s2 else 5, mc.MF: 5,
             mc.ECMWF: ecmwf}
    if gfs_hi is not None:
        rec.M[mc.GFS] = gfs_hi
    rec.PL = dict(rec.M)
    rec.OBS = {}
    return rec


def build(spec, months=("2026-03", "2026-04", "2026-05"), days_per_month=28, start_id=1):
    """Recs from ``spec``: a list of dicts ``{n, s2, y, ttype, month, uk, bm, day_mod}`` expanded into
    units spread cyclically over the days of their month(s)."""
    out, i = [], start_id
    for item in spec:
        ms = item.get("months", months)
        mod = item.get("day_mod", days_per_month)
        for k in range(item["n"]):
            out.append(mk_rec(i, item["s2"], item["y"], ms[k % len(ms)], 1 + (k // len(ms)) % mod,
                              item.get("ttype", "SUNSET" if k % 2 == 0 else "SUNRISE"),
                              uk=item.get("uk", 0), bm=item.get("bm", 0)))
            i += 1
    return out
