"""An offline fake of Open-Meteo that switches on ``models=`` in the URL (P1's ``FakeOM`` ignores it).

``provider(model, var, lat, lon, hour, url) -> value|None``. Every URL is recorded. A request whose
``models`` parameter is missing or holds a comma-separated list raises AssertionError: the study sends
exactly one model per request, never a list (plan section 4, T3), and a test that sees the error has found
a bug. Single Runs requests (``run=`` and ``forecast_days=``, no ``start_date``) are served too.
"""
import json
from datetime import date, timedelta
from urllib.parse import parse_qs, urlsplit


class ModelOM:
    """Callable ``fetch(url) -> (status, body)`` for ``openmeteo.Client(fetch=...)``."""

    def __init__(self, provider, status=200):
        self.provider = provider
        self.status = status
        self.urls = []
        self.models_seen = []

    def __call__(self, url):
        self.urls.append(url)
        q = parse_qs(urlsplit(url).query)
        models = q.get("models", [None])[0]
        if not models or "," in models:
            raise AssertionError("exactly one model per request, got models=%r in %s" % (models, url))
        self.models_seen.append(models)
        lats = [float(x) for x in q["latitude"][0].split(",")]
        lons = [float(x) for x in q["longitude"][0].split(",")]
        variables = q["hourly"][0].split(",")
        if "start_date" in q:
            d0, d1 = date.fromisoformat(q["start_date"][0]), date.fromisoformat(q["end_date"][0])
        else:   # Single Runs: the run's date and forecast_days after it
            d0 = date.fromisoformat(q["run"][0][:10])
            d1 = d0 + timedelta(days=int(q.get("forecast_days", ["1"])[0]) - 1)
        hours, d = [], d0
        while d <= d1:
            hours += ["%sT%02d:00" % (d.isoformat(), h) for h in range(24)]
            d += timedelta(days=1)
        nodes = [{"latitude": la, "longitude": lo,
                  "hourly": dict({"time": hours},
                                 **{v: [self.provider(models, v, la, lo, h, url) for h in hours]
                                    for v in variables})}
                 for la, lo in zip(lats, lons)]
        return self.status, json.dumps(nodes if len(nodes) > 1 else nodes[0])
