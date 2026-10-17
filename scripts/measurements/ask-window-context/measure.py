#!/usr/bin/env python3
"""Character cost of the `list_windows` spread (docs/engineering/ask-window-context-plan.md §2.2).

A mock of the JSON `AskTools.finish` serialises (compact, NON_NULL, the shared `AppConfig.objectMapper`
which does not indent), for six solar windows plus two astro rows and one aurora row, with and without the
spread of §2.1. Token figures are character counts divided by 4 and by 3.3 — the two ends of what compact
JSON costs on Claude's tokeniser — because this machine has no API key for `count_tokens`. The exact figure
is `AskTools.ToolCall.chars` in a dry-run trace, and `api_call_log.input_tokens` per turn (plan §7).

Run: python3 scripts/measurements/ask-window-context/measure.py
"""
import json

SOLAR = [("Today", "sunset", "18:12"), ("Tomorrow", "sunrise", "07:35"), ("Tomorrow", "sunset", "18:10"),
         ("Sunday", "sunrise", "07:37"), ("Sunday", "sunset", "18:08"), ("Monday", "sunrise", "07:39")]
NIGHTS = [{"id": "2026-10-17_astro", "day": "Tonight", "event": "astro", "time": "19:35"},
          {"id": "2026-10-18_astro", "day": "Tomorrow night", "event": "astro", "time": "19:33"},
          {"id": "2026-10-17_aurora", "day": "Tonight", "event": "aurora", "time": "19:35"}]
REGIONS_4 = ["Northumberland", "North York Moors & Coast", "Lake District", "Yorkshire Dales"]
REGIONS_8 = REGIONS_4 + ["Durham & Teesdale", "Cheviots", "Solway", "Eden Valley"]
MAX_POCKETS = 4


def solar_windows():
    out = []
    for i, (day, ev, t) in enumerate(SOLAR):
        w = {"id": f"2026-10-1{7 + i // 2}_{ev}", "day": day, "event": ev, "time": t, "verdict": "MAYBE",
             "bestRating": 4}
        if i == 1:
            w["bestBet"] = {"region": "Northumberland", "location": "Bamburgh", "locationId": 12}
        if i == 3:
            w["alsoGood"] = {"region": "North York Moors & Coast", "location": "Saltwick Bay", "locationId": 77}
        out.append(w)
    return out


def spread(rated, bands, pockets, unrated=0, stood_down=0, held_back=0, regions=(4, 0, 0, 0),
           over="every region"):
    game_on = bands[3] + bands[4]
    s = {"over": over, "rated": rated, "bands": bands, "gameOn": game_on,
         "gameOnPct": round(100 * game_on / rated) if rated else 0, "go": bands[4],
         "below4": rated - game_on, "stars1to2": bands[0] + bands[1]}
    if unrated:
        s["unrated"] = unrated
    if stood_down:
        s["stoodDown"] = stood_down
    if held_back:
        s["heldBack"] = held_back
    counted, thin, stood, not_scored = regions
    r = {"counted": counted}
    if thin:
        r["thin"] = thin
    if stood:
        r["stoodDown"] = stood
    if not_scored:
        r["notScored"] = not_scored
    s["regions"] = r
    ps = sorted(pockets, key=lambda p: (-p["gameOn"], -p["mean"], p["region"]))
    s["pockets"] = ps[:MAX_POCKETS]
    if len(ps) > MAX_POCKETS:
        s["morePockets"] = len(ps) - MAX_POCKETS
    return s


# The owner's weekend (plan §0), with a plausible Friday/Monday either side.
QUIET = [
    (180, [0, 56, 122, 2, 0], [{"region": "Northumberland", "gameOn": 2, "mean": 2.8}], 0, 20, 1),
    (201, [1, 98, 100, 2, 0], [{"region": "North York Moors & Coast", "gameOn": 2, "mean": 2.6}], 12, 0, 0),
    (180, [0, 43, 133, 4, 0], [{"region": "Northumberland", "gameOn": 3, "mean": 2.9},
                               {"region": "North York Moors & Coast", "gameOn": 1, "mean": 2.7}], 0, 0, 0),
    (160, [5, 80, 70, 5, 0], [{"region": "Lake District", "gameOn": 5, "mean": 2.6}], 20, 30, 0),
    (120, [2, 60, 55, 3, 0], [], 60, 0, 0),
    (60, [0, 30, 28, 2, 0], [{"region": "Yorkshire Dales", "gameOn": 2, "mean": 2.9}], 120, 0, 0),
]


def busy(regions):
    return [(240, [5, 20, 80, 110, 25], [{"region": r, "gameOn": 20 + i, "mean": 3.9} for r in regions],
             13, 0, 2) for i in range(6)]


def build(with_spread, data=None, region_count=4):
    ws = []
    for i, w in enumerate(solar_windows()):
        if with_spread:
            rated, bands, pockets, unrated, stood, held = data[i]
            w["spread"] = spread(rated, bands, pockets, unrated, stood, held, (region_count, 0, 0, 0))
        ws.append(w)
    return json.dumps({"windows": ws + NIGHTS}, separators=(",", ":"))


def main():
    rows = [("today, no spread", build(False)),
            ("the owner's weekend", build(True, QUIET)),
            ("busy, 4 regions", build(True, busy(REGIONS_4))),
            ("busy, 8 regions, pockets capped", build(True, busy(REGIONS_8), 8))]
    for name, s in rows:
        print(f"{name:34s} chars={len(s):5d}  tokens ÷4={len(s) / 4:5.0f}  ÷3.3={len(s) / 3.3:5.0f}")
    spot = {"locationId": 12, "name": "Bamburgh", "region": "Northumberland", "windowId": "2026-10-17_sunset",
            "rating": 4, "verdict": "WORTH_IT", "tideState": "HIGH", "tideAligned": True,
            "tideFitPhrase": "High water at the light: the castle on a full sea.",
            "headline": "Clear low horizon to the west, broken mid cloud overhead to catch the colour",
            "pick": "BEST"}
    for n in (5, 8):
        rs = json.dumps({"spots": [spot] * n}, separators=(",", ":"))
        print(f"rank_spots, {n} spots{'':20s} chars={len(rs):5d}  tokens ÷4={len(rs) / 4:5.0f}  ÷3.3={len(rs) / 3.3:5.0f}")
    print()
    print(build(True, QUIET)[:420] + " …")


if __name__ == "__main__":
    main()
