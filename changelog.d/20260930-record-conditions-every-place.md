### Changed — atmospheric conditions are now recorded for every place the pipeline looks at, not only the ones it goes on to rate

Hot topics and the Coming up feed answer "what is happening?" — knowing there is Saharan dust,
lying snow or a storm surge at a place is interesting on its own terms. Stars, verdicts and picks
answer the separate question "where is worth going?" Through 2026-09-29 the two questions shared
one gate by accident: `SurvivorAtmosphereWriter.write` (`survivor_atmosphere` — dust, snow,
freezing level, humidity, storm surge) was called only for a candidate that survived weather triage
*and* the Gate 4 stability policy, so a place forecast to be cloudy or stability-gated had no dust,
snow or surge chip at all, even when the condition was genuinely there.

The write happens immediately after `fetchWeatherAndTriage` assembles the atmospheric data — before
the triage verdict and before the Gate 4 decision — so a `SKIPPED_TRIAGED` or `SKIPPED_STABILITY`
candidate now carries a reading exactly like an `EVALUATED` one. A candidate that never had its
weather fetched this cycle (`SKIPPED_PAST_DATE`, `SKIPPED_CACHED`, `SKIPPED_TRAVEL_DAY`,
`SKIPPED_UNKNOWN_LOCATION`, `SKIPPED_HARD_CONSTRAINT` — all decided before the candidate loop from
an earlier cycle's own cached verdict — or a `SKIPPED_ERROR` where the fetch itself failed) still
writes nothing, since there is no reading to record. No feature switch — every place is recorded
from the first deploy, matching the existing `photocast.survivor-atmosphere.write` all-or-nothing
flag.

**Round 2: the write moved to a single seam, after a Codex review of the first cut found two more
gaps.** The first cut of this change (commit 9c01491f) put the write directly at three call
sites — `ForecastTaskCollector`'s scheduled batch path, and both of `ForceSubmitBatchService`'s
entry points (JFDI and admin force-submit). A Codex review of the resulting PR found this missed
two OTHER real callers of `ForecastService.fetchWeatherAndTriage`: the batch collector's own admin
`collectRegionFilteredBatches`, and the synchronous engine's `ForecastCommandExecutor
.runTriagePhase` — both fetch weather and triage/Gate-4-skip candidates exactly like the scheduled
path, but neither had a write at all, so a triaged-out slot on an admin region-filtered run or a
hand-started `/api/forecast/run*` call still had no reading recorded. Rather than add two more call
sites, the write moved inside `ForecastService.fetchWeatherAndTriage` itself — the one place every
caller already shares, and the same method that CLAUDE.md already documents as saving a
`forecast_evaluation` row as a side effect. This covers all six current callers (the two batch
collector paths, both `ForceSubmitBatchService` entry points, `BatchRetryService`'s failed-request
reconstruction, and the synchronous engine) and any future one, with exactly one write per fetch.
The three call-site writes added in the first cut, and `evaluateAndPersist`'s own write (which a
triaged candidate never reached anyway, since its caller discards triaged results before calling
it), were removed — a caller that already writes would otherwise write the same fetch twice.
`ForecastTaskCollector` and `ForceSubmitBatchService` no longer depend on `SurvivorAtmosphereWriter`
at all, since neither calls it directly any more.

**Round 3: a woodland-only candidate on the admin region-filtered path was still excluded BEFORE
reaching the seam, because it never went through the collector's canopy check at all until this
round.** A Codex review of PR #947 (round 3, against commit 1f637d56) found
`ForecastTaskCollector.collectRegionFilteredBatches` tested `candidate.location().isWoodlandOnly()`
and skipped the candidate before ever calling `fetchWeatherAndTriage` — so a wood-only location on
an admin region-filtered run fetched weather (the batch prefetch upstream already covers every
candidate) but never reached the one seam that records it, while the scheduled path
(`collectScheduledBatches`), which decides its own woodland lane strictly AFTER the identical call,
recorded it correctly. The canopy check moved to after the call, matching the scheduled loop's
shape exactly: every candidate on this path now reaches `fetchWeatherAndTriage` unconditionally, and
a canopy candidate is excluded from the inland/coastal buckets afterward, regardless of what colour
triage said about it — this path has no woodland bucket of its own to route a canopy candidate
into (unlike the scheduled loop's `woodland` list), so the two paths agree on the one thing that
matters here: a canopy site never lands in the sky lane, and its reading is recorded either way.

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

**Dust copy updated to match.** `DustHotTopicStrategy`'s chip and its science-tooltip description
both promised colour outright — "vivid colour potential", "producing unusually vivid orange and red
skies" — which was accurate when the topic could only ever fire on a slot Claude had already rated
GO-adjacent. Now that the chip shows at a place whose sky is forecast blocked (a triaged-out or
Gate-4-stood-down slot), that promise is no longer true by construction, so the copy now states the
condition and makes the colour conditional on a clear sky rather than promising it: the chip reads
"Elevated dust aloft — colour potential where the sky is clear", and the tooltip reads "Saharan dust
carried north by upper winds can scatter light into unusually vivid orange and red skies at sunrise
and sunset — when the sky is clear enough to show it." The identical description string duplicated
inside `HotTopicSimulationService`'s admin DUST demo template is updated the same way, so the
simulated topic an admin previews matches the live one.
