"""Exact port of production's directional cloud-sampling geometry.

Sources: ``GeoUtils.offsetPoint``/``antisolarBearing``, ``DirectionalSamplingGeometry`` and
``CloudPointCache.gridKey``. Longitude is deliberately NOT normalised (as in Java).
"""
from __future__ import annotations

import math
from typing import Iterable, List, Sequence, Tuple

EARTH_RADIUS_M = 6_371_000.0
DIRECTIONAL_OFFSET_M = 113_000.0
FAR_SOLAR_OFFSET_M = 226_000.0
CONE_HALF_ANGLE_DEG = 15
GRID_STEP = 0.1

Point = Tuple[float, float]


def java_round(x: float) -> int:
    """Java ``Math.round(double)``: ``floor(x + 0.5)`` (half rounds toward +infinity)."""
    return int(math.floor(x + 0.5))


def offset_point(lat: float, lon: float, bearing_deg: float, dist_m: float) -> Point:
    """Spherical forward formula (R = 6,371,000 m). Returns (lat, lon); lon not normalised."""
    lat_r = math.radians(lat)
    lon_r = math.radians(lon)
    b = math.radians(bearing_deg)
    d = dist_m / EARTH_RADIUS_M
    sin_lat, cos_lat = math.sin(lat_r), math.cos(lat_r)
    sin_d, cos_d = math.sin(d), math.cos(d)
    new_lat = math.asin(sin_lat * cos_d + cos_lat * sin_d * math.cos(b))
    new_lon = lon_r + math.atan2(math.sin(b) * sin_d * cos_lat,
                                 cos_d - sin_lat * math.sin(new_lat))
    return math.degrees(new_lat), math.degrees(new_lon)


def antisolar_bearing(az: float) -> float:
    """``(az + 180) % 360`` (Java ``%`` on non-negative input equals Python's)."""
    return (az + 180.0) % 360.0


def cone_points(lat: float, lon: float, az: int) -> List[Point]:
    """Solar cone: bearings az-15, az, az+15 at 113 km, flank-low first."""
    return [offset_point(lat, lon, b, DIRECTIONAL_OFFSET_M)
            for b in (az - CONE_HALF_ANGLE_DEG, az, az + CONE_HALF_ANGLE_DEG)]


def antisolar_point(lat: float, lon: float, az: float) -> Point:
    """Antisolar point: bearing (az+180)%360 at 113 km."""
    return offset_point(lat, lon, antisolar_bearing(az), DIRECTIONAL_OFFSET_M)


def far_point(lat: float, lon: float, az: float, dist_m: float = FAR_SOLAR_OFFSET_M) -> Point:
    """Far-solar point along the solar azimuth (default 226 km)."""
    return offset_point(lat, lon, az, dist_m)


def probe_points(lat: float, lon: float, az: float,
                 distances_km: Sequence[float] = (165, 226, 320)) -> List[Point]:
    """Points along the solar azimuth at each given distance in km."""
    return [offset_point(lat, lon, az, km * 1000.0) for km in distances_km]


def transect(lat: float, lon: float, az: float, step_km: float, max_km: float) -> List[Point]:
    """Points along the azimuth at step_km, 2*step_km, ... up to and including max_km."""
    out: List[Point] = []
    n = int(math.floor(max_km / step_km + 1e-9))
    for i in range(1, n + 1):
        out.append(offset_point(lat, lon, az, i * step_km * 1000.0))
    return out


def grid_key(lat: float, lon: float) -> str:
    """``CloudPointCache.gridKey``: ``round(lat/0.1)_round(lon/0.1)`` with Java half-up rounding."""
    return f"{java_round(lat / GRID_STEP)}_{java_round(lon / GRID_STEP)}"


def dedupe_by_cell(points: Iterable[Point]) -> List[Point]:
    """Keep the first point per grid cell, preserving order."""
    seen = set()
    out: List[Point] = []
    for p in points:
        k = grid_key(*p)
        if k not in seen:
            seen.add(k)
            out.append(p)
    return out
