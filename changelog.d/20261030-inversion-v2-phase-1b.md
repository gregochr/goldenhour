### Added — cloud inversion v2: the warm layer measured from the valley floor

The inversion score now reads the temperature profile from each site's confirmed valley floor instead of
comparing 925 hPa with the viewpoint cell's own 2 m temperature (which on a fell top is the fell). For a site
with `elevation_m` >= 200, `overlooks_water` and a valley floor set in the location editor, one extra
best-effort Open-Meteo request per run (the floor's own lat/lng with `elevation=<valley_floor_m>`, 15 hourly
variables, batched 20 to a call, rate limiter only: no retry, no `open-meteo` circuit breaker) feeds the new
`InversionScoreCalculatorV2`. It finds the warm layer (base, top, strength; shallow surface-based or
elevated), judges whether the viewpoint is inside it, at its edge or clear of it, and adds a valley-fog
evidence term and an in-cloud check on the viewpoint's own cell. On the owner's reported mornings it scores
the 15 Oct 2025 Cat Bells-style false positive 6 (the old calculator said 7), Blencathra at the edge 7,
Scafell Pike clear 7, Helvellyn clear 7 and the 4 Nov 2024 Skiddaw-like cell in cloud 6. By design no site
below 600 m can score above 8 under v2: STRONG comes only from summits.

The old score is still computed beside it. A site without a valley floor, a failed fetch or a profile with no
usable hours keeps today's score exactly; a profile failure never fails a slot or leaves valley mist
unscored, and mist's inputs, `FORECAST_PARAMS` and `ValleyMistCalculator` are untouched. In the first winter v2
may raise a site below 600 m by at most one point over the old score (summits are uncapped), and may lower one
freely. One `[INVERSION v2]` INFO line per scored slot logs old, new, regime, base, top, strength and the caps
that applied. The user message gains a `Warm layer:` line in place of the 925 hPa `Measured reversal` line
when v2 drove the score; the system prompt is untouched.

V202 adds `slot_atmosphere.inversion_score_v1` and `inversion_score_v2_raw`, written on every write (null when
v2 did not compute), so the `INVERSION_CALC` series can be read in two halves per site
(`inversion_score_v1 IS NOT NULL` marks a v2-era row). `photocast.inversion.v2.enabled` (default true) is the
revert switch and `photocast.inversion.v2.raise-cap-enabled` (default true) lifts the first-winter raise cap;
both are read at startup, so changing either needs a backend restart. Documented in
`docs/engineering/reference/conditions.md`, `configuration.md`, `database-migrations.md` and `pipeline.md`.
