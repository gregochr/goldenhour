### Added — valley mist is read and recorded for flagged sites (M1a)

PhotoCast now measures how likely photographable valley mist is at a place where mist forms around
the camera: the riverside, valley floor or lake shore at dawn, the morning the River Brathay
produced on 17 Oct 2026 that no surface could name. Nothing a reader sees changes yet: this is the
reading half of the first phase (`docs/engineering/valley-mist-plan.md`), and the Valley mist hot
topic, its Coming up condition and the daily log follow in M1b.

An admin marks a place with the new "Mist forms here: valley floor, riverside, lake shore"
checkbox (at add time, or on an existing place from its inline edit). For a marked place only,
`ValleyMistCalculator` scores the radiative set-up from the night's hourly slots (a clear sky, calm
air, a small temperature–dew point gap, the air cooling toward it, wet ground, and the model's own
haze) rather than waiting for the 2 km model to saturate, which it did not on the Brathay morning.
The 0–10 score and its band (NONE, POSSIBLE, LIKELY, FOGBOUND) are recorded in `slot_atmosphere`
(`mist_score`, `mist_scored`, V185) for every marked place the pipeline fetches weather for,
whatever the triage verdict, exactly as the inversion score is. No Claude call is added and no
star changes. The moisture scale is now one shared helper that the cloud inversion calculator
also uses, with its outputs unchanged. The hourly weather request gains the total `cloud_cover`
variable (one more variable on the existing request, not an extra call). V185 is proven by CI
only.
