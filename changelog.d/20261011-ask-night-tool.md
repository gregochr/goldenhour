### Added — Ask PhotoCast answers night questions: clear skies, stars and the northern lights (N1, backend)

Ask had five tools and all of them read the sunrise and sunset forecast, so "are there clear skies on the
Durham coast tonight?" could only ever be a can't-answer. It now has a sixth, `rank_night_spots`, over the
rows the Map's night rows already read: the astro conditions forecast (cloud, visibility, moon, with the
location's Bortle class) and, for PRO and ADMIN accounts only, the aurora forecast results and the aurora
alert state. `list_windows` gains the night windows (`yyyy-MM-dd_astro` and `_aurora`, tonight and tomorrow
night; an aurora window only for an asker who may see aurora), the system prompt gains the night rules, and
the stub engine answers a night question from the same tool.

"Tonight" is the poller's night (`AuroraForecastRunService.currentNightDate()`), never the civil date, so
before nautical dawn it is the night still running. A night spot is an enabled, Bortle-enriched, regioned,
non-canopy place; for astro only the night's latest generation counts (a run that failed for some places
leaves their rows from the previous afternoon, which a date filter alone would rank beside today's). The
snapshot reads astro rows through a new fetch-joining query, because the Ready precompute builds it outside a
transaction. A night pick must be 4 stars or better for astro (the template's base is 3) or 3 for aurora, and
an aurora pick is never offered, served or re-served to an asker without aurora access: the role is derived
once and handed to the tool, the validator, the thread re-join and the Ready freshness re-check. A pick's
`targetType` is now an Ask-local `AskWindowKind` (`SUNRISE`, `SUNSET`, `ASTRO`, `AURORA`), not a new
`TargetType`; a solar pick reads exactly as before.

A night answer is never stored in the typed cache (the key carries no role, and the aurora state and the
afternoon astro refresh move under the TTL), and it carries an opaque `nightVersion` which the client echoes
on the exchange: any earlier exchange, typed or Ready, whose night forecast has since moved ends the whole
thread (`threadReset`, `"night forecast updated"`), and a night answer's summary is never shown to the model
verbatim in a follow-up. The three tool-schema golden files move once (a new tool). No frontend change and no
Ready `NIGHT_SKY` question yet (N2, N3).
