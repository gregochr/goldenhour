# A triage pass is not a verdict — the `UNRATED` display verdict (2026-10-13)

## 1. The defect

`DisplayVerdict.resolve(null, triage)` maps a weather-triage `GO` to `WORTH_IT` and `MARGINAL` to
`MAYBE`, so a cheap pre-screen at 72 hours wears the same word, tint and bloom as a Claude 4★
average. It was written so early-pipeline cells "still show a sensible colour". Since the verdict
sample gate (`VerdictSampleGate`, 2026-09-29) it is the COMMON case at T+2 and T+3: a region whose
rated sample fails the gate falls back to that call, so on 2026-10-13 Friday 16 Oct's sunrise read
"Worth it" for Northumberland & Tyneside (38 rated, mean 3.6, `sampleSufficient` false) on weather
alone, and its sunset read "Maybe" over a 2.1 mean the gate withheld. The persisted briefing
(`daily_briefing_cache`) carried both. It is also incoherent with `BriefingHonestyFilter`: a region
with ZERO ratings is blanked to "Not scored at this range", while a region with 38 ratings that fail
the gate claims "Worth it" — more evidence buying a bigger claim with less basis. The
plan-card-verdict-honesty series (H1–H3, #1126) made this visible: its mean and Region line are
silent for a triage-fallback word, by design, so the card read "Worth it" with nothing beside it.

## 2. The fix — at the source, both layers

### Backend (U1)

- **`DisplayVerdict.UNRATED`**, declared between `STAND_DOWN` and `AWAITING`, JSON name `UNRATED`:
  "the weather screen did not rule it out, and no trustworthy rating has called it".
  `resolve(null, GO)` and `resolve(null, MARGINAL)` return it. `resolve(null, STANDDOWN)` keeps
  `STAND_DOWN` — a weather stand-down is a decision against the slot (the two-question rule, and
  the product has always read it as "Poor"). `resolve(null, null)` keeps `AWAITING`. A rating still
  decides as before. Update the enum's and every resolver's javadoc; the "sensible colour" rationale
  is retired, say so.
- **Region level**: `BriefingRegionEvaluationRollup`'s gate fallback (`DisplayVerdict.resolve(null,
  region.verdict())`) and `BriefingRatingStats.resolveRegionDisplayVerdict`'s empty-stats branch
  change outcome through the same call — no new code path. `BriefingRegion.verdictEligible()` is
  unchanged. `sampleSufficient`/`forcedSample` unchanged.
- **Slot level**: `BriefingSlot`'s three resolvers and `EvaluationViewService`'s three follow
  through `resolve` (an unrated slot whose triage is GO/MARGINAL reads `UNRATED`; a triage
  STANDDOWN slot still `STAND_DOWN`).
- **`PlanWindowProjector`**: the window verdict is still the top region's word, so a window whose
  only regions are `UNRATED` reads `UNRATED`. `bandRank` (`WORTH_IT` → 0, else 1) is unchanged;
  BEST BET/ALSO GOOD already need `verdictEligible()`, so an `UNRATED` region can never be a pick —
  pin it. The legacy slot-derived branch (lines ~255–280, "unreachable on the serve path") must
  treat `UNRATED` like `AWAITING` (not counted toward WORTH_IT/MAYBE). Ranking: eligibility first
  already puts every `UNRATED` region below every rated one; among `UNRATED` regions, by mean then
  name as today.
- **`BriefingHonestyFilter`**: unchanged — a blanked region stays `AWAITING`/`STAND_DOWN` with its
  reason wording. Pin that a blanked region never reads `UNRATED`.
- **Confidence**: unchanged on the server (an ineligible region is floored LOW as today).
- **Ask**: `AskTools` serialises `verdict.name()` on `list_windows` and `rank_spots`, so the model
  will see `UNRATED`; `AskPromptBuilder` — if the system prompt lists verdict words, add one clause
  ("UNRATED: not ruled out by the weather, but too few ratings to call; never lead with it"). Pick
  eligibility (`AskSnapshot.isPickEligible`) already needs `verdictEligible()`, unchanged.
  `AskReadyFreshness` compares each PICK's stored `verdictAtAnswer` to its live SLOT verdict; a pick
  is rated ≥ 3★, so a slot verdict that can matter is never `UNRATED`, and no stored Ready answer is
  withheld by this change (corrected after review: an earlier draft said one would be, once).
- **Digest** (`GET /api/briefing/digest`): passes the value through; no client yet.
- **No database column holds a `DisplayVerdict`** (checked: no `@Enumerated` on it), so no migration
  and no `LocalH2EnumWidener` line. Persisted `daily_briefing_cache` payloads carry the OLD word
  until the next build, but `enrichWithCachedScores` recomputes every region's verdict on serve, so
  the API is right from the first request after deploy.
- Tests: `DisplayVerdictTest` (every resolve cell), rollup gate fallback → `UNRATED` for GO and
  MARGINAL and `STAND_DOWN` for STANDDOWN, projector window verdict and pick refusal, honesty filter
  never emits `UNRATED`, `AskTools` serialisation, a `JsonDateFormatContractTest`-style wire pin
  that the enum serialises by name. JaCoCo is 80% per class.

### Frontend (U2)

- **Vocabulary**: `VERDICT_LABEL.UNRATED` = `'Too few rated'` at window/region level (the card
  word, the map pill, the drilldown rows, the popup rail, the grid cell, `VerdictPill`); at SLOT
  level (the location sheet's window rows, popup chips, callout) `'Not rated'`. Find every module
  that switches on verdict values (`grep -rl WORTH_IT frontend/src | grep -v /test/` lists twelve:
  `index.css`, `windowFirstCards.js`, `mapLabels.js`, `mapLanding.js`, `tierUtils.js`,
  `verdictWord.js`, `briefingDisplay.js`, `WindowSheetDialog.jsx`, `mapPeek.js`, `HeatmapGrid.jsx`,
  `WindowFirstHeatStrip.jsx`, `VerdictPill.jsx`) and give each an explicit `UNRATED` arm — never let
  it fall into a `default` that reads as AWAITING silently; where a `default` exists, make the
  `UNRATED` arm explicit beside it.
- **Treatment**: the AWAITING treatment — neutral tint (`data-verdict="UNRATED"` styled as
  AWAITING in `index.css`, with its own selector, not a shared one, so a future divergence is one
  edit), `computeCellTier` → 5, NOT in `CONFIDENCE_VERDICTS` (no fill decay, no provisional mark —
  the word already says it). `mapLanding`'s `allPoor` fold counts `STAND_DOWN` only (an `UNRATED`
  window is not poor); `nextWorthIt` is unchanged.
- **The mean shows beside the new word**: `card.verdictMean` (H1) becomes non-null when the top
  region is verdict-eligible OR its word is `UNRATED` and it has a finite mean — `Too few rated
  3.6★` is the honest card. It stays null for an ineligible region whose word is a triage
  `STAND_DOWN` (a figure beside "Poor" would contradict it). H2's Region line keys on
  `verdictMean` and therefore now draws under `Too few rated` too.
- **Width**: "Too few rated" is 13 characters at 11px/600 ≈ 85.8px by H1's 6.6px-per-character
  measure; with the 24px mean and a 4px gap it is ~114px against the ~88.3px value column at 320px
  (A27). So at 320px the verdict word and the mean cannot share a line. Decide and record: the
  mean moves UNDER the word on a second line for this one verdict (a `wf-hc-mean` block variant),
  or the word shortens. Prefer keeping the word and wrapping the mean; measure with the real IBM
  Plex Mono metrics as H1 did and state the arithmetic in the CSS comment.
- **Spread row and best line**: unchanged.
- **Ask client**: `askModel.js` joins `verdict` to the card's chip tier colour — give `UNRATED` the
  AWAITING colour (null tier), explicitly.
- **Tests** per `docs/engineering/frontend-test-standards.md`: a fixture region with
  `displayVerdict: 'UNRATED'`, `meanRating: 3.6`, `sampleSufficient: false` renders "Too few rated"
  and "3.6★" on the card, no BEST BET, tier 5 in the grid, no confidence decay, "Too few rated" on
  the map pill with the region named, "Not rated" on a slot row; an ineligible `STAND_DOWN` region
  renders "Poor" with no mean; every `switch`/map with an `UNRATED` arm has a test that fails if
  the arm is removed.

## 3. Process

Two agents in parallel (backend tree, frontend tree — disjoint files), then a read-only adversarial
review over both diffs, a fix-up, CLAUDE.md, one PR. Backend local gate runs on JDK 23 with
`-Djava.version=23` and `EvaluationModelTest` excluded (no JDK 25 on this Mac); CI's Backend job
on JDK 25 is the proof. Changelog entries: one per layer in `changelog.d/`.
