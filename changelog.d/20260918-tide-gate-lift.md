### Changed — lift the tide gate: a mismatched coast now scores, it does not disappear

A coastal slot whose tide missed the light used to be withheld from Claude entirely
(`BriefingGatingPolicy.HARD_CONSTRAINT_REASONS = {TIDE_MISMATCH}`) and served with no rating at
all — the one hard constraint left standing after the Gate 2 redesign. `TideVisitor`'s R1 penalty
(5 king/spring-aligned · 4 tight-aligned · 3 widened-aligned · 1 misaligned), written for exactly
this case, has been dead behind that gate since the day it shipped. The owner decided (Q1 of
`docs/engineering/tide-window-plan.md` §6, option 3): **lift the gate and let the existing visitor
score the shot** — "the star is the whole shot". A coastal slot's sky rating now reaches Claude
regardless of tide alignment and combines with `TideVisitor`'s score exactly as `RatingCombiner`
already did (half-up average): a 4★ sky at wrong water now returns 4★ for the light and combines
to `round((4+1)/2) = 3★`; an aligned coast is unchanged (4★ sky + aligned 4 → 4★; + spring/king 5 →
5★).

Measured before deciding, over the fortnight to 18 Sep 2026: **1,066** gated skips against
**7,051** evaluated — lifting the gate costs roughly **+15%** more evaluations at the fortnight's
upper bound. Accepted: the cost buys a served rating for every formerly-gated slot.

**Backend**: `BriefingGatingPolicy.HARD_CONSTRAINT_REASONS` is now empty — the mechanism (the set,
the label round-trip, `BriefingSlotBuilder`'s `evaluationGate` wording) stays wired for a future
hard constraint rather than being deleted. `TideWording.tideGatePhrase` (its one producer) is
removed with its tests, since no gate fires to word any more. The STANDDOWN verdict override for a
tide mismatch in `BriefingSlotBuilder` stays — it is now a triage *label* only (feeding the region
roll-up, like any weather stand-down), never a Claude-eligibility gate. `TideVisitor` and
`RatingCombiner` are untouched; the fix was entirely upstream, in what reaches them.

**New**: `BriefingSlot.skyRating` / `BriefingEvaluationResult.skyRating` — the sky visitor's own
component score alone, with no tide contribution averaged in, riding the same `cached_evaluation`
results entry the combined `claudeRating` already does (no migration). The map tab's `TideFitBlock`
states it beside a tide-dimmed star (`· sky 4★`) on every miss, and on a match only when it differs
from the combined figure (a spring-aligned tide lifting a 4★ sky to 5★) — so a reader can tell "the
water cost me a star" from "the light itself was mediocre" without a second visit to the sky score.

**Prompt**: `BestBetPromptText`'s tide language was re-read and found to instruct Claude to
re-weight tide alignment on top of the combined rating (`claudeAverageRating` now already includes
tide) — flagged for the owner rather than edited; prompt changes and regression-test assertions
stay the owner's call per CLAUDE.md.

See `docs/engineering/tide-window-plan.md` §4 #20 and §6 Q1 for the full reasoning, including why
this deviates from the vendored design's own `OPEN 5` warning against representing a tide mismatch
twice (the gate lift removes the double-representation rather than adding to it).
