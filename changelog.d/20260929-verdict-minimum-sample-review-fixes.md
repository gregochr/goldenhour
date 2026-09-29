### Fixed — the verdict sample gate's "examined" count and force-evaluation exemption both trusted the wrong signal

A Codex review of #943 raised two P1s against `VerdictSampleGate` (2026-09-29), both confirmed and
fixed here.

**`examinedCount` read the briefing's own weather verdict instead of what the batch actually did.**
A voting slot counted as "triaged" whenever its `BriefingSlot.verdict()` was `STANDDOWN` — but that
verdict is the briefing's own, independently-computed weather call, spanning the whole horizon, and
can disagree with the batch's latest decision for that exact slot. Worse: an unrated slot the
briefing still marks STANDDOWN from an older evaluation, which Gate 4 then `SKIPPED_STABILITY`
without fetching fresh weather this cycle, was wrongly counted as examined — letting a region's
ratings cross the 50% coverage threshold on slots nobody looked at this cycle.
`VerdictSampleGate.examinedCount` now takes a `Set<String>` of slots the batch's own latest
disposition named `SKIPPED_TRIAGED`, sourced from a new `EvaluationViewService.loadTriagedByBatch`
bulk read of `ForecastRunDispositionRepository.findLatestNonCachedDispositions` — nothing else
counts: not `SKIPPED_STABILITY`, `SKIPPED_CACHED`, `SKIPPED_ERROR`, `SKIPPED_PAST_DATE`,
`SKIPPED_NO_REFRESH_NEEDED`, an absent disposition, nor the briefing's own verdict.
`SKIPPED_CACHED` is deliberately excluded from both sides of the query's own "latest" comparison
(not merely filtered from the result), so a slot triaged last night and reported cached tonight
still counts as examined — the underlying triage decision stays visible rather than hidden behind
the newer cache-reuse row. `VerdictSampleGate` stays pure (it takes the set as a parameter and
issues no query of its own); the flag rides into the rollup as a synthetic, `@JsonIgnore`d
`BriefingEvaluationResult.triagedByBatch(name)` marker inserted into the resolver map only when a
slot has no rating or triage result of its own — the only place such a slot's disposition can attach
to anything the rollup already reads.

**The force-evaluation exemption was granted on the disposition's existence, not on the rating that
landed.** `ScheduledBatchEvaluationService.persistCycleDispositions` writes a `FORCE_EVALUATED`
disposition at *submission* time, before any Claude result is processed — so while a forced batch is
pending, or if it later fails, an older cached rating from a completely different evaluation was
wrongly stamped `forced` merely because a forced run had been requested for the same slot.
`EvaluationViewService.loadForceEvaluatedAt` now returns each slot's `FORCE_EVALUATED` disposition's
own `created_at` (an `Instant`, not a bare boolean), and the winning result is stamped forced only
when its own evaluation instant — a cached result's `evaluatedAt`, or a forecast row's
`forecastRunInstant`, both already-existing helpers — is at or after that instant, and no newer
`EVALUATED` disposition exists for the slot (the existing tie-at-same-instant-reads-as-not-forced
rule is unchanged). A result with no evaluation instant at all (a legacy row) is never forced. A
`PENDING`/`ABANDONED` row with no rating can never become the winning source in the first place —
`hasSomethingToSay` is false for it, so `cachedWins`'s second clause always prefers whatever the
cache still says — so it can never make an older cached rating look forced either; traced, not
newly guarded, and pinned by a test.

Both fixes are covered by tests with fixed clocks and literal expectations, including two scenarios
proven (via a non-destructive `git worktree` checkout of the pre-fix commit) to fail against the
code these fixes replace:
`BriefingRegionEvaluationRollupTest.farWindow_standdownVerdictButBatchStabilitySkipped_insufficient`
and `EvaluationViewServiceTest.ForcedEvaluationFlag.forceEvaluatedDispositionButWinningRatingPredatesIt_readsNotForced`.

Query cost: two more bulk reads join the existing `loadStabilitySkips` (`loadForceEvaluatedAt`,
`loadTriagedByBatch`), kept separate rather than folded into one flattened fetch because the three
answer genuinely different questions and a shared aggregation risks exactly the kind of subtlety
both P1s were about — one `GET /api/briefing` serve now issues 5 queries for
scores+stability+forced+triaged (was 4); one briefing build issues 10 (was 8), from the two
independent bulk-load call sites (`BriefingService.bulkScoreResolver`,
`BriefingRollupBuilder.loadLiveScores`), each loading both new reads exactly once, never once per
region.
