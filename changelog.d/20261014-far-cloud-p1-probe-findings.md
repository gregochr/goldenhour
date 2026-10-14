### Fixed — far-cloud P1 tools rest on what Open-Meteo actually serves

The first live run of the P1 probe failed four checks. Previous Runs documents `_previous_dayN` for
total cloud cover only, so low, mid and high "as issued the day before" never existed; `case.py` and
`size.py` now read each model's day-0 layers from the Historical Forecast API (identical values for the
same model) and report no day-before column. The 20-coordinate archive "order" failure was a sea point
answered from the neighbouring land cell, so the probe now tolerates one cell and tests for a point
nearer a different request, `Client.get` refuses a batch answered more than 0.55 degrees from its
requests, and a new check pins that coordinates sharing a 0.25 degree cell are not merged. The plan
records the findings (section 11) and the T+1 limit this puts on H1–H4 before V175.
