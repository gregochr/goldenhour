### Changed — atmospheric conditions are now recorded for every place the pipeline looks at, not only the ones it goes on to rate

Hot topics and the Coming up feed answer "what is happening?" — knowing there is Saharan dust,
lying snow or a storm surge at a place is interesting on its own terms. Stars, verdicts and picks
answer the separate question "where is worth going?" Through 2026-09-29 the two questions shared
one gate by accident: `SurvivorAtmosphereWriter.write` (`survivor_atmosphere` — dust, snow,
freezing level, humidity, storm surge) was called only for a candidate that survived weather triage
*and* the Gate 4 stability policy, so a place forecast to be cloudy or stability-gated had no dust,
snow or surge chip at all, even when the condition was genuinely there.

The write now happens in `ForecastTaskCollector` immediately after `fetchWeatherAndTriage` returns
— before the triage verdict and before the Gate 4 decision — so a `SKIPPED_TRIAGED` or
`SKIPPED_STABILITY` candidate now carries a reading exactly like an `EVALUATED` one. A candidate
that never had its weather fetched this cycle (`SKIPPED_PAST_DATE`, `SKIPPED_CACHED`,
`SKIPPED_TRAVEL_DAY`, `SKIPPED_UNKNOWN_LOCATION`, `SKIPPED_HARD_CONSTRAINT` — all decided before
the candidate loop from an earlier cycle's own cached verdict — or a `SKIPPED_ERROR` where the
fetch itself failed) still writes nothing, since there is no reading to record. The two hand-started
admin paths that bypass every gate (`ForceSubmitBatchService`'s JFDI and force-submit) now make the
same write, so a bypass run is no sparser on this surface than a scheduled cycle. No feature switch
— every place is recorded from the first deploy, matching the existing
`photocast.survivor-atmosphere.write` all-or-nothing flag.

**Bluebell stays the named exception.** It and cloud inversion read `forecast_score`, not
`survivor_atmosphere` — a genuinely Claude-scored component, unaffected by this change, since it is
written only from a completed evaluation. Bluebell has no deterministic substitute for the display
rating and stays scored-only by design; cloud inversion's own deterministic substitute (the
calculator's score for every place, without Claude) is planned separately as a Phase 2 with its own
migration.

The "survivor" name on the table and every class built around it (`SurvivorAtmosphereWriter`,
`SurvivorAtmosphereEntity`, `SurvivorAtmosphereRepository`, `SurvivorSignalReader`,
`SurvivorSignals`) is now historical — every javadoc that claimed the rows were survivor-only has
been corrected, and each class carries a note that a rename is pending, deliberately not folded
into this change (a rename needs its own migration and would otherwise bury the behaviour change
under a mechanical diff).

CLAUDE.md's Almanac section is corrected: the four `survivor_atmosphere`-backed hot-topic
strategies (dust, fresh snow, snow on the tops, storm surge) now see readings out to the batch's
full 5-day candidate window, not only the slots that reached Claude — the "record conditions for
every place" bullet in the Backend-heavy notes records the rule.
