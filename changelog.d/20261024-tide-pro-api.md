### Changed — Lite's briefing omits the window tide curve and per-location tide matching (Plan tab included)

`GET /api/briefing` now leaves out, for readers who are not Pro or Admin, the window tide curve (the
curve, the position and level at the light, the extremes and the height), each location's tide match
(aligned, quality, shortfall, fit phrase, height and level) and the "Tide aligned" / "Tide not aligned"
slot flags. The tide state, direction, nearest extreme, range and the hot topics stay free. On the Plan
tab a Lite reader no longer sees the tide sparkline, the "N of M coastal locations on tide" line, the
"N tides aligned" count or the tide chips on slots. Pro and Admin responses are unchanged.
