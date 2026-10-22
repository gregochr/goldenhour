### Added — far-cloud P1: Codex follow-ups and the forecast-canopy sizing (plan §19)

- `scripts/far-cloud-p1/canopydeck.py` sizes the candidate triage relaxation "do not stand down on low cloud alone when
  the forecast also shows a mid/high canopy >= 60" offline: phantom decks with a forecast canopy, what the latest run
  did on them, the ERA5-obstructed exposure among all forecast-deck-plus-canopy slots, and the extra Claude
  evaluations a day by model era at the `application-example.yml` rates (with an assumed token count). No network,
  no spend, no verdict; recorded in plan §19.
- Measurement-quality fixes from the peer review: `replay_set.py select` and `compare` print the basis-against-combined
  rating cross-tabulation (how many gos a reader would also see as a go); `lowdeck.py` reports HIGH_CLOUD triage
  without a low-cloud message as its own outcome category and lists every category in its headline so they sum to
  the total; `canvas.py` and `lowdeck.py` print a 5-day date-block bootstrap interval (3 and 7 days as sensitivity)
  beside each Wilson interval and a sensitivity line without 2026-10-15; `canvas.py` reports the strict Angel shape
  beside the broad one. `stats.bootstrap_counts_ex` now sums blocks by prefix sums (the same draws, the same results).
- Production scoring is unchanged.
