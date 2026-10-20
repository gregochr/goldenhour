### Fixed — NOAA solar wind (Bz, speed, density) read from the live `rtsw` feeds

NOAA withdrew `products/solar-wind/mag-1-day.json` and `plasma-1-day.json` (both now 404), so every
aurora poll logged `NOAA solar wind fetch failed` and the Bz / speed / density readings were empty.
The defaults now point at `json/rtsw/rtsw_mag_1m.json` and `json/rtsw/rtsw_wind_1m.json`, and the parser
reads their shape (an array of objects, newest first, one row per spacecraft per minute): `bz_gsm`,
`proton_speed` and `proton_density` from the spacecraft flagged `active`, merged by minute. If no row
is flagged active every row is used. `application-prod.yml` and `application-example.yml` set the
URLs explicitly, so both are updated; a hand-edited production file that still names the old
`products/solar-wind/…` URLs overrides the new default and must be changed
(`aurora.noaa.solar-wind-url` and `aurora.noaa.solar-wind-plasma-url`). The contract fixtures are now real
samples of the new feeds.

A failed fetch's WARN no longer carries the whole error body: NOAA and light-pollution client failures
(the NLC scraper was fixed separately) log through `LogSanitizer`, which flattens the message to one line of at most 200
characters.
