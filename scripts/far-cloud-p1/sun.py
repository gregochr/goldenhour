"""NOAA solar position (Meeus-based, the NOAA spreadsheet equations).

Expected accuracy: about 1 minute in event times and about 0.1 degree in elevation/azimuth at
mid-latitudes. Elevation is *apparent* (NOAA refraction correction included). Sunrise/sunset use
the 0.833 degree convention, found by bisection on geometric (true) elevation
crossing -0.833 degrees, the NOAA convention, which already folds in refraction and the solar radius.
"""
from __future__ import annotations

import math
from datetime import date, datetime, timedelta, timezone
from typing import Optional, Tuple


def _julian_day(dt: datetime) -> float:
    if dt.tzinfo is not None:
        dt = dt.astimezone(timezone.utc).replace(tzinfo=None)
    y, m = dt.year, dt.month
    d = dt.day + (dt.hour + (dt.minute + dt.second / 60.0) / 60.0) / 24.0
    if m <= 2:
        y -= 1
        m += 12
    a = y // 100
    b = 2 - a + a // 4
    return math.floor(365.25 * (y + 4716)) + math.floor(30.6001 * (m + 1)) + d + b - 1524.5


def _solar_terms(dt: datetime) -> Tuple[float, float]:
    """Return (declination_rad, equation_of_time_minutes)."""
    t = (_julian_day(dt) - 2451545.0) / 36525.0
    l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360.0
    m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
    e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
    mr = math.radians(m)
    c = (math.sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t))
         + math.sin(2 * mr) * (0.019993 - 0.000101 * t) + math.sin(3 * mr) * 0.000289)
    true_long = l0 + c
    omega = 125.04 - 1934.136 * t
    app_long = true_long - 0.00569 - 0.00478 * math.sin(math.radians(omega))
    mean_obl = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
    obl = mean_obl + 0.00256 * math.cos(math.radians(omega))
    decl = math.asin(math.sin(math.radians(obl)) * math.sin(math.radians(app_long)))
    y = math.tan(math.radians(obl) / 2.0) ** 2
    l0r = math.radians(l0)
    eqt = 4.0 * math.degrees(y * math.sin(2 * l0r) - 2 * e * math.sin(mr)
                             + 4 * e * y * math.sin(mr) * math.cos(2 * l0r)
                             - 0.5 * y * y * math.sin(4 * l0r)
                             - 1.25 * e * e * math.sin(2 * mr))
    return decl, eqt


def _refraction(elev_deg: float) -> float:
    """NOAA atmospheric refraction correction in degrees."""
    e = elev_deg
    if e > 85.0:
        return 0.0
    te = math.tan(math.radians(e))
    if e > 5.0:
        r = 58.1 / te - 0.07 / te ** 3 + 0.000086 / te ** 5
    elif e > -0.575:
        r = 1735.0 + e * (-518.2 + e * (103.4 + e * (-12.79 + e * 0.711)))
    else:
        r = -20.772 / te
    return r / 3600.0


def sun_position(dt: datetime, lat: float, lon: float) -> Tuple[float, float]:
    """Return (apparent_elevation_deg, azimuth_deg_from_north_clockwise) for a UTC datetime."""
    if dt.tzinfo is not None:
        dt = dt.astimezone(timezone.utc).replace(tzinfo=None)
    decl, eqt = _solar_terms(dt)
    minutes = dt.hour * 60 + dt.minute + dt.second / 60.0
    tst = (minutes + eqt + 4.0 * lon) % 1440.0
    ha = tst / 4.0 - 180.0
    if ha < -180.0:
        ha += 360.0
    lr, har = math.radians(lat), math.radians(ha)
    cz = math.sin(lr) * math.sin(decl) + math.cos(lr) * math.cos(decl) * math.cos(har)
    cz = max(-1.0, min(1.0, cz))
    zen = math.degrees(math.acos(cz))
    true_elev = 90.0 - zen
    sz = math.sin(math.radians(zen))
    if abs(sz) < 1e-9:
        az = 180.0 if lat >= 0 else 0.0
    else:
        ca = (math.sin(lr) * cz - math.sin(decl)) / (math.cos(lr) * sz)
        ca = max(-1.0, min(1.0, ca))
        a = math.degrees(math.acos(ca))
        az = (a + 180.0) % 360.0 if ha > 0 else (540.0 - a) % 360.0
    return true_elev + _refraction(true_elev), az



def _geo_elev(dt: datetime, lat: float, lon: float) -> float:
    decl, eqt = _solar_terms(dt)
    minutes = dt.hour * 60 + dt.minute + dt.second / 60.0
    ha = ((minutes + eqt + 4.0 * lon) % 1440.0) / 4.0 - 180.0
    lr = math.radians(lat)
    cz = (math.sin(lr) * math.sin(decl) + math.cos(lr) * math.cos(decl) * math.cos(math.radians(ha)))
    return 90.0 - math.degrees(math.acos(max(-1.0, min(1.0, cz))))


def _crossing(d: date, lat: float, lon: float, rising: bool) -> Optional[datetime]:
    """Bisect for the -0.833 degree crossing of geometric elevation within the UTC day.

    Scans in 10-minute steps for the first sign change in the requested direction, then bisects.
    """
    start = datetime(d.year, d.month, d.day)
    step = timedelta(minutes=10)
    prev_t, prev_v = start, _geo_elev(start, lat, lon) + 0.833
    t = start
    for _ in range(24 * 6):
        t = prev_t + step
        v = _geo_elev(t, lat, lon) + 0.833
        if (rising and prev_v < 0 <= v) or (not rising and prev_v >= 0 > v):
            lo, hi = prev_t, t
            for _ in range(40):
                mid = lo + (hi - lo) / 2
                vm = _geo_elev(mid, lat, lon) + 0.833
                if (vm >= 0) == rising:
                    hi = mid
                else:
                    lo = mid
            return lo + (hi - lo) / 2
        prev_t, prev_v = t, v
    return None


def sunrise_utc(d: date, lat: float, lon: float) -> Optional[datetime]:
    """Naive-UTC sunrise on the UTC date ``d`` (0.833 degree convention), None if none."""
    return _crossing(d, lat, lon, True)


def sunset_utc(d: date, lat: float, lon: float) -> Optional[datetime]:
    """Naive-UTC sunset on the UTC date ``d`` (0.833 degree convention), None if none."""
    return _crossing(d, lat, lon, False)
