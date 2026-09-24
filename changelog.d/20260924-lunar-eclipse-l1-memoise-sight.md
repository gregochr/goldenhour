### Fixed — memoise the lunar eclipse per-location reduction

Codex review of #913 (PR for the lunar eclipse hot topic, L1) flagged a P1: on every live Plan-tab
serve, `LunarEclipseHotTopicStrategy` and `LunarEclipseAlmanacSource` each re-ran the full
`LunarEclipseCalculator.sight()` reduction — several moonrise/moonset lookups plus, when altitude at
maximum did not already clear the eligibility bar, a once-a-minute altitude scan across the whole
umbral span — for every enabled location, because `BriefingService.getCachedBriefing()` recomputes
hot topics on every request. Cost scaled with roster size and eclipse duration on every serve, and
kept paying after the eclipse had finished because freshness was checked only after the roster loop.

`LunarEclipseCalculator.sight()` is now memoised per `(eclipse date, latitude, longitude)` in an
in-memory `ConcurrentHashMap` carrier on the shared `@Component` bean (never the briefing payload) —
the reduction is a pure function of the catalogue and coordinates, so no TTL is needed and a restart
simply empties it, defensively bounded to 5,000 entries. `LunarEclipseHotTopicStrategy` now checks
`freshness.isAhead(u4)` before reducing the roster at all, so a finished eclipse costs nothing.
