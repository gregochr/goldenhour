### Added — far-cloud P1 measures the over-forecast low deck (scripts only)

`scripts/far-cloud-p1/lowdeck.py` counts how often the forecast puts a low-cloud deck where ERA5 reads
clear (and the reverse), for observer and cone low cloud, across the whole verified history, with
Wilson intervals by model, event, month, days ahead, region and coastal/inland. It also reports what
the rating or triage did on those slots and whether an ERA5 mid/high canopy overhead co-occurred, and
lists the 20 largest. It makes no network calls and recommends nothing; the headline figures are in
`far-cloud-p1-plan.md` §18. No app, API or database change.
