### Added — 250 and 200 hPa cloud recorded at the far point

The far-solar pressure-level capture now runs from 850 down to 200 hPa: two more variables,
`cloud_cover_250hPa` and `cloud_cover_200hPa`, ride the existing separate best-effort request and
are stored in two new nullable `forecast_evaluation` columns (V209). UK cirrus often sits at
250 to 200 hPa, above the old 300 hPa top level, so a far point under a cirrus deck could read 0 at
every stored level. Data only: nothing reaches a prompt, triage or a rating. Even so, pressure-level
cloud sees only about half of single-level high-only cirrus, so "all levels 0" is not "no cloud".
