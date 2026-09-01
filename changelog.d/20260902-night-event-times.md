### Added — night events carry a served window (map-v2 P5)

`AstroConditionsDto` and `AuroraForecastResultDto` both gain `nightStart`/`nightEnd`, the first
served night times in the app — the minimum backend the map's future event list (P6) needs to
render honest astro/aurora rows instead of inventing a time.

Astro serves the **stored** `nauticalDuskUtc`/`nauticalDawnUtc` a row was actually scored over
(persisted since V64 by `AstroConditionsService.evaluateAndPersist`), via a new
`AstroConditionsService.resolveNightWindow` that only recomputes — as an explicit, documented
fallback — for a legacy row whose columns are null. Aurora derives the window per result date at
serve time via the existing `AuroraForecastRunService.computeWindowForDate(date)`, never the
clock-based `AuroraPollingJob.calculateTonightWindow()`, so a stored result for a past or future
night carries that night's own window rather than tonight's.

No migration, no new endpoints — both fields ride the existing `GET /api/astro/conditions` and
`GET /api/aurora/forecast/results` payloads.
