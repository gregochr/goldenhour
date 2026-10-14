"""Offline fake of Open-Meteo for the T5 tests: builds a response from the request URL."""
import json
import os
import sys
from datetime import date, timedelta
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))


def coord_value(lat, lon, hour):
    """A reading (0..99) that encodes the REQUESTED coordinate (4 dp) and the hour, so a test that
    mixes up coordinates, cells or request order reads a different number."""
    return ((int(round(lat * 10000)) * 31 + int(round(lon * 10000)) * 17 + int(hour[11:13]) * 3) % 997) % 100


def default_provider(var, lat, lon, hour, url):
    """Coordinate-encoding provider; the variable name shifts it so vars are distinguishable."""
    return (coord_value(lat, lon, hour) + len(var)) % 100


class FakeOM:
    """``provider(var, lat, lon, hour_str, url) -> value|None``; records every URL. The default
    provider encodes the requested coordinate (see ``coord_value``). ``latitude``/``longitude`` in
    each node echo the requested coordinate."""

    def __init__(self, provider=None, status=200):
        self.provider = provider or default_provider
        self.urls = []
        self.status = status

    def __call__(self, url):
        self.urls.append(url)
        q = parse_qs(urlsplit(url).query)
        lats = [float(x) for x in q["latitude"][0].split(",")]
        lons = [float(x) for x in q["longitude"][0].split(",")]
        vs = q["hourly"][0].split(",")
        d0, d1 = date.fromisoformat(q["start_date"][0]), date.fromisoformat(q["end_date"][0])
        hours = []
        d = d0
        while d <= d1:
            hours += [f"{d.isoformat()}T{h:02d}:00" for h in range(24)]
            d += timedelta(days=1)
        nodes = [{"latitude": la, "longitude": lo,
                  "hourly": dict({"time": hours},
                                 **{v: [self.provider(v, la, lo, h, url) for h in hours] for v in vs})}
                 for la, lo in zip(lats, lons)]
        return self.status, json.dumps(nodes if len(nodes) > 1 else nodes[0])
