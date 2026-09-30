### Fixed — dropping a best-bet pick could leave an orphaned runner-up standing in as the headline

A Codex review of d930d028 (round 4's fixes) found a P1 in that same commit's own fallback
eligibility re-check: when a prior successful run's rank 1 pick had since gone verdict-ineligible
but rank 2 was still eligible, `BestBetFallbackService.findFreshFallback` served rank 2 ALONE — a
stored "Also Good" whose headline, detail and `relationship`/`differsBy` fields were all written (or
persisted) to describe how it differs from rank 1, now rendered as the block's only pick, with
nothing left to be second to or separate from.

Checking the question against `BestBetRanker.dropUnevaluatedPicks` — which round 4's
`dropIneligiblePicks` was modelled on — found the identical defect already latent there: both
methods called `rerankWithRecomputedRelationships` whenever any pick was dropped, which correctly
recomputes `relationship`/`differsBy` for a promoted survivor but never touches its `headline`/
`detail`/`confidence`, all Claude-authored prose composed relative to the ORIGINAL rank 1 (see
`BestBetPromptText`'s ALSO GOOD SELECTION RULE: "make the temporal distinction obvious... the reader
knows immediately this is a different opportunity, not a backup for the same outing"). Promoting
that prose into rank 1's place presents it as the headline it was never written to be. This is the
same orphan defect `BriefingHonestyFilter.withdrawUnsupportedBets` was already written to refuse for
its own case (`losingRankOneWithdraws RankTwo`'s own javadoc), just not yet generalised.

The fix: `BestBetRanker.afterRemoval(original, kept)` is now the one rule every removal site uses —
losing rank 1 withdraws the whole set (no promotion, no rewritten prose, matching
`BriefingHonestyFilter`'s established behaviour); losing anything else renumbers the `rank` field of
the survivors alone, touching no other field, since a still-standing rank 1 makes every trailing
survivor's `relationship`/`differsBy` (computed relative to that same, unchanged rank 1) still
correct without recomputation. `dropUnevaluatedPicks` and `dropIneligiblePicks` both now delegate to
it; `BriefingHonestyFilter.withdrawUnsupportedBets` is refactored onto the same shared method with
no behaviour change (all 32 of its existing tests pass unchanged); `BestBetFallbackService
.findFreshFallback` now builds the run's full original pick list before filtering so it can ask
`afterRemoval` the same question, rather than filtering with a bare `continue` that could not tell
"rank 1 was dropped" apart from "rank 2 was dropped".

`BestBetRanker.applyCoverageAwareRanking`'s own promotion (the pre-existing, independently
calibrated headline-coverage-floor demotion) is explicitly OUT of scope here: it promotes a
different, already-present pick on ranking merit rather than removing anything, so
`rerankWithRecomputedRelationships`'s recomputation is the right operation there. It carries a
related, narrower risk of its own — a promoted pick's prose was equally written as an "Also Good"
relative to the demoted headline — noted but not addressed this round.

New tests: `BestBetRankerTest` (new file) pins `afterRemoval`'s exact field-level behaviour for
rank-1-removed (withdraws), rank-2-removed (rank 1 unchanged, every field asserted), nothing-removed,
everything-removed, and a middle-pick-removed renumbering case, plus direct
`dropUnevaluatedPicks`/`dropIneligiblePicks` parity tests. `BestBetFallbackServiceTest` gained four
two-pick scenarios (rank 1 ineligible/rank 2 eligible → withdrawn; rank 1 eligible/rank 2
ineligible → rank 1 alone; both ineligible → empty; both eligible → both unchanged, the pre-existing
behaviour). `CloseToHomeServiceTest` gained a belt-and-braces confirmation that
`matchingBestBet` never treats a rank-2-only list as a match (it already couldn't, via its existing
`rank() == 1` filter — this pins that explicitly rather than leaving it implicit).
