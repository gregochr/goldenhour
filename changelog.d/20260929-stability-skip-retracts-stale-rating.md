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
