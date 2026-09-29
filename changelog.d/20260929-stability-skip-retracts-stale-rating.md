### Fixed — a nightly stability skip now retracts the rating it superseded

A slot the overnight batch declined to re-score — its grid cell too unsettled for its horizon —
kept serving whatever rating an earlier, more eligible cycle had left behind, indefinitely: the
skip writes no row to `cached_evaluation` or `forecast_evaluation`, so nothing on the serve path
ever saw a reason to reconsider. `EvaluationViewService` now bulk-loads each slot's most recent
`SKIPPED_STABILITY` disposition and retracts whichever of the cached result and the
`forecast_evaluation` row predates it, before either reaches precedence — so the slot reads exactly
like any other never-rated one (no star, no verdict word, no prose), never as a weather stand-down.
A later real evaluation, eligible or forced, simply outdates the skip and restores a rating in the
normal way. The rule is applied once and reused by every serve surface that resolves "cached result
vs newer evidence" — the Plan payload, `GET /api/briefing/evaluate/scores`, and the map's
`GET /api/forecast` — so they cannot disagree about the same slot. Only a nightly Gate 4 stability
skip counts: a region-level cache reuse, a past-date or travel-day skip, a triage stand-down, a row
with neither a rating nor a triage reason, and the intraday cycle's "a later look is already
guaranteed" skip are all excluded by construction, and a legacy cached row with no known write time
keeps serving as it always has.

The Plan payload needed a second piece: a persisted `BriefingSlot` already carries whatever rating
the last build gave it, so a resolver that simply had nothing new to say for a slot used to leave
that embedded rating untouched — indistinguishable, to `BriefingRegionEvaluationRollup`, from a slot
it was never asked about. `EvaluationViewService` now returns a distinct retraction marker rather
than a bare absent entry when a skip is the reason nothing survived, and the rollup clears the
slot's rating, sky rating, both potentials, summary and headline on it — never by setting a triage
reason, so the slot's verdict and stand-down text read exactly as a never-rated slot's, not a
weather stand-down.

**Follow-up fix, same day (Codex review of #940):** the rule above compares a forecast row's
`forecast_run_at` against a disposition's true `Instant`, and that comparison was silently wrong.
`EvaluationViewService.forecastRunInstant` zoned the naive `forecast_run_at` column as
`Europe/London`, but the column has only ever been written as a naive UTC wall clock
(`ForecastService.buildEntity`, unchanged since the column's introduction on 2026-02-24). Through
British Summer Time this read every forecast row as one hour OLDER than it actually was, which both
skewed the pre-existing freshness gate (a cached rating written up to an hour before a later triage
row could wrongly outrank it) and could wrongly retract a row written shortly AFTER a stability skip
because it read as shortly before it. Fixed by zoning as UTC — the zone the column is actually
written in — and correcting the method's javadoc, which had asserted the opposite. No other site in
the codebase converts `forecast_run_at` to an `Instant`; the two DTO mappers that serialise it
untouched, and the two direct `LocalDateTime`-to-`LocalDateTime` comparisons in
`ForecastCalibrationService`/`EvaluationViewService.loadLatestForecasts`, are correct as they stand
and were left alone.

**Second follow-up fix, same day (Codex re-review of #940):** two readers still went around all of
the above by reading the raw `BriefingEvaluationService` cache directly instead of through
`EvaluationViewService` — `BriefingRollupBuilder.computeRegionStats` (the best-bet advisor's
`claudeAverageRating`/coverage figures) and `PipelineRunPickService.lookupAverageRating` (the
`pipeline_run_pick.claude_average_rating` cross-run comparison snapshot). Reading before retraction
meant a rating the Plan card and the map had already stopped showing — because a nightly stability
skip superseded it — still counted toward the advisor's pick and toward the run-to-run comparison,
the very "every surface must agree" property this fix exists for, broken one layer further in. The
same gap is older than the stability-skip feature: a rating superseded by a newer *triage* row was
equally still in the raw cache and uncounted nowhere else. Both close together, because both are
instances of one rule: neither caller should ever see a rating the rest of the product has stopped
serving. New sibling accessor `EvaluationViewService.getLiveScoresForEnrichment` applies the same
precedence and stability-skip retraction `getScoresForEnrichment` already does, then filters out
every retraction marker before returning — a caller of this method never has to remember to check
for one, because it never receives one. `BriefingRollupBuilder` and `PipelineRunPickService` now
depend on `EvaluationViewService` instead of `BriefingEvaluationService` directly; no circular
dependency resulted. A deliberate side effect: a location whose only evidence is a scored or triaged
`forecast_evaluation` row (no cache entry at all) is now counted too, since that is what the
precedence-aware read already does for every other surface — narrowing an existing divergence
between what the advisor saw and what the Plan tab and map already served, not only retracting stale
ratings. The pre-existing fail-open roster-hygiene residual on this lookup (a renamed, disabled or
moved location still answering under a name no slot claims) is unchanged by this move, not fixed —
`getScoresForEnrichment` carries the same cache-outlives-the-roster behaviour forward for the
identical, already-documented reason.

**Third follow-up fix, same day (a second Codex re-review of #940):** the retraction-aware read the
previous fix introduced was itself a performance regression, caught before merge. Routing
`BriefingRollupBuilder.computeRegionStats` and `logCacheCoverage` through
`getLiveScoresForEnrichment` meant calling it once per region **and** event — up to 6 events × the
region count × 2 call sites, each call costing three queries — roughly 216 queries per best-bet
rollup against production's roster, where the in-memory cache read it replaced cost none. New
`EvaluationViewService.getLiveScoresForEnrichmentBulk(start, end, types)` closes it: the bulk sibling
of `getScoresForEnrichmentBulk`, filtering out retraction markers the identical way
`getLiveScoresForEnrichment` does. `BriefingRollupBuilder.loadLiveScores` now loads it exactly ONCE
per rollup, and `computeRegionStats`/`logCacheCoverage` both read that one pre-loaded map by key —
never calling `EvaluationViewService` themselves. Single-key `getLiveScoresForEnrichment` keeps
exactly one caller, `PipelineRunPickService.lookupAverageRating`, which persists at most a handful of
picks per run — the right shape for a single-key read, not a bulk one. The build path for the Plan
payload itself carried the same shape of bug, one query narrower: `BriefingService` used to hand its
enrichment rollup the single-key `getScoresForEnrichment` as a resolver, once per region/event — two
queries each until the stability-skip fix above added a third. `BriefingService.bulkScoreResolver`
now loads the (marker-preserving) `getScoresForEnrichmentBulk` once per build instead, mirroring the
shape `ServedBriefingAssembler.reEnrichVerdicts` already used on the serve path. Per-request paths
(`GET /api/briefing`, `GET /api/briefing/evaluate/scores`, `GET /api/forecast`) were untouched by
either fix and still issue one stability-skip query each.
