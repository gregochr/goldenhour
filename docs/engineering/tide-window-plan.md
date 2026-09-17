# Map tab — tide on the window: implementation plan

**Source**: the design bundle vendored at `docs/design/tide-window/` — `README.md` (the spec),
`CLAUDE_CODE_PROMPT.md` (the designer's working order), `Map Tide Window.html` + `map-tide-v5.js`
(the prototype; the notes column down the right of the HTML is part of the spec), four screenshots.
`docs/design/tide-window/VENDORING.md` records what was copied and what was not. Read the spec before
any phase; **this document is the *port* plan**: what the codebase already has, what is genuinely
new, where the spec and the code disagree on purpose, and how the work cuts into single-session
phases for **Sonnet** sessions.

**The one-sentence version.** A strong sunrise at mid water is the commonest coastal disappointment,
and today the map answers it by making the coast *disappear*: a coastal slot whose tide misses is
withheld from Claude, served with no rating, and hidden by the map's default "hide unknown" filter,
so an empty coast reads as "nothing here" rather than "not this morning". This increment (1) draws
the day's tide as a curve, bottom-left, with the light marked on it; (2) keeps every coastal location
on the map, **dimmed** with an arrow saying it wants the water higher or lower, its rating (where one
exists) untouched; (3) names the next window that gives that water, and jumps to it. **The heat is
never touched** — tide is a fact about the window *and the spot*, light is a fact about the window,
and folding one into the other's colour would make them inseparable.

**Cadence per phase** (CLAUDE.md "UI Work — Review Cadence"): build → tests → adversarial review of
the diff (~6 prosecutor lenses + one refuter per charge, all read-only) → fix survivors → browser
verification (§9 recipe) → commit. Frontend gate before any push-request: `npm run lint && npm test
&& npm audit --audit-level=high && npm run build`, gated on exit codes. Backend gate: `./mvnw clean
verify -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false`, gated on the exit code (there is
no Docker on this machine and never will be — CLAUDE.md). Never push; never tag. Every phase adds a
`changelog.d/YYYYMMDD-<slug>.md` entry (never a direct `CHANGELOG.md` edit). Paste the relevant
section of THIS plan and the spec into every review agent's prompt — review agents cannot see
untracked context, and a compliance lens with no spec returns zero findings.

**Check open PRs first.** `gh pr list --state open` and grep the titles for *tide*, *strip*, *chip*,
*dim*, *gate*, *callout*. The owner writes specs and sometimes builds against them in parallel; the
overlap only surfaces at merge. Checked 2026-09-17 at plan time: no overlap (#843 aurora docs, #836
dependabot, #815 a Q5 measurement doc). ⚠️ #866 (merged 2026-09-16) is the closest prior work: it
added `BriefingSlot.evaluationGate`, the callout's `.wf-callout-gate` row and the sheet's
`.wf-loc-gate` row. This plan builds on it; T5 must not print the same fact twice on one card
(§5 #6).

---

## §0 Status

**Status: T1, T2, T3 and T4 BUILT, T5–T8 not started.** T1's `TideCurveCalculator` lift and T2's window
facts were built in parallel sessions against the same `75ff1f90` base and merged independently
(T2 first, as #876; T1's merge commit folds the two together) — exactly the parallel-phase shape
§8 describes for T1/T2. Post-merge, `WindowTideRollupBuilder.rollup` calls `TideCurveCalculator`
for every one of T2's new facts (`positionOf`, `clockMinutesFrom`, `heightAt`), with no second copy
of any of them left behind — T2's own instruction 2 anticipated exactly this, and the merge
resolution carries it out. Owner decisions this plan needs are listed in §6; **none blocks T3 or
T4**, and §5 #1 (keep the gate) lets T4–T8 proceed without one — the owner challenges §5 on the plan
PR, not in code. Plan written 2026-09-17 against `origin/main` at `75ff1f90` (#871).

Phase log (T1 creates the first row; every phase appends its own in the same commit as its code —
the commit column names the PR once it lands, since a phase cannot name its own hash):

| phase | branch | commit | date | notes |
|---|---|---|---|---|
| T2 | `feature/tide-t2-window-facts` | `ddbfe1ff` (#876) | 2026-09-17 | Adds `sunrisePosition`/`sunsetPosition`, `extremes` (`record Extreme(kind, position, time)`) and `heightAtWindow` to `BriefingWindowTide`, computed inside `WindowTideRollupBuilder.rollup` from data it already fetches — both solar events now called unconditionally rather than only the window's own (T1 had not merged; computed from the private statics in place per T2's instruction 2). New legacy twelve-field constructor mirrors `BriefingSlot.TideInfo`'s own, keeping the three existing direct-construction call sites (`BriefingEventSummaryWindowSerializationTest`, `PlanWindowProjectorTest`, `BriefingServiceTest`) compiling unchanged. ⚠️ Adversarial review (3 read-only lenses: correctness/wiring, test quality, docs/contract/conventions) found one real test gap and two documentation overclaims, all fixed pre-commit. Test gap: both `heightAtWindow` tests were degenerate — one landed exactly on a stored extreme (f=1, collapsing interpolation to the raw height), the other used a flat day (every extreme equal) — so the cosine arithmetic itself was unverified; added a hand-computed interior-point case (11:00 between an 08:35 high and a 14:45 low, height = 3.8996 m → `"3.9 m"`) plus the symmetric sunset-null guard. Documentation: the `sunrisePosition`/`sunsetPosition` Javadoc claimed the sun-does-not-rise-or-set case is a live path "for a high-latitude anchor near midsummer" — empirically false of the vendored solar-utils 2.1.0 (`SolarCalculator.sunrise`/`sunset` at 78°N returns a degenerate midnight instant, never null, confirmed by direct invocation); reworded to state the real justification — `SolarService`'s contract carries no non-null guarantee, and `NlcTwilightWindowCalculator` already treats the identical call as nullable for the same reason. And `extremes`/`heightAtWindow`'s Javadoc claimed nullability was for "a payload cached before this field existed" — false: `BriefingHierarchyBuilder.buildSummary` always attaches `window = null` on the build path `persistBriefing` serialises, so `BriefingWindow`/`BriefingWindowTide` is never itself part of `daily_briefing_cache`, old shape or new; corrected to name the true reason, the legacy twelve-field constructor's existing call sites. Gate green: `./mvnw checkstyle:check` 0 violations, `./mvnw clean verify -Dtest='!**/integration/**'` 8156 tests, 0 failures, 0 errors, BUILD SUCCESS. No browser-visible change — backend only. |
| T1 — per-slot tide facts (backend) | `feature/tide-t1-slot-facts` | pending PR | 2026-09-17 | Re-verified every file:line the plan cited against the tree before editing — all current, no drift since 2026-09-17. **Task 5's stop condition did not fire**: `TideFactDeriver.java:96–97` still computes `tideAligned` from the *tight* `tideData` and `widenedAligned` from `dualMaybe.get().widened()` separately, `BriefingSlotBuilder` still serves the tight one, and the new tide-fit fields read that same served field — confirmed by a dedicated test (`tideFitFieldsTrackTightAlignmentNotWidened`) that gives `calculateTideAligned` two distinguishable `TideData` objects and proves the miss form prints when tight says no even though widened says yes. **The lift is behaviourally identical**: `WindowTideRollupBuilderTest` (43 tests) passes with a literal `git diff --stat` of zero on that file, both before and after a follow-on cleanup that also moved the day-axis position formula (`positionOf`) and the rounding helper into `TideCurveCalculator`, deleting the two duplicate copies the first cut had left behind in `WindowTideRollupBuilder`. ⚠️ **Adversarial review (5 lenses: correctness, the lift's behavioural identity, test quality, Checkstyle/Javadoc, what it makes harder for T2/T3) found one crash the local gate's own green run had not exercised**: `curveFacts` guarded on the fetched rows being non-empty, not on the *date-filtered* series `TideCurveCalculator.seriesAround` narrows them to — a location whose stored extremes sit right at the ingestion horizon, briefed two days past it, hit a non-empty fetch and an empty series, and `heightAt` indexed off the end of it. Fixed with a second guard and a regression test that reproduces the exact `IndexOutOfBoundsException` (proven by removing the guard and watching the new test fail on cue, then restoring it). A second, narrower finding — the miss phrase's height could print a fraction above the "of X m" day-high figure beside it, since one is an unclamped interpolation and the other a 30-minute sample max — is fixed by flooring the day-high at the light's own height. Test suite grew from the review pass too: eq()-pinned mock windows in place of `any()` where the value was knowable, a tight-vs-widened distinguishing test, a BST case for the date/clock conversions, boundary tests at `bracket`'s exact-midnight edges and the flat-span threshold, and an exact-string assertion for the match-tier fit phrase that had been asserted only by its component parts before. **Post-T2-merge**: resolved the merge against `origin/main` (T2 had landed as #876 while T1 was in review) by keeping T1's `TideCurveCalculator` delegation and re-pointing T2's `sunrisePosition`/`sunsetPosition`/`extremes`/`heightAtWindow` computations onto it — `WindowTideRollupBuilder` now calls `TideCurveCalculator.heightAt`/`clockMinutesFrom`/`positionOf` exclusively, with `heightAt` for the event hoisted into one local (`heightAtEvent`) so `windowLevel` and `heightAtWindow` share the one call rather than each computing it separately. `DailyBriefingResponseJsonTest` keeps both phases' test sections, unmodified, one after the other. Nothing in T1 is browser-visible. |
| T3 — plumbing and `mapTideFit` (frontend) | `feature/tide-t3-plumbing` | pending PR | 2026-09-17 | Re-verified every symbol T3 names against the tree before editing — all field names (`BriefingSlot.TideInfo`'s `tideLevel`/`tideDirection`/`tideHeight`/`tideShortfall`/`tideFitPhrase`, `BriefingWindowTide`'s `sunrisePosition`/`sunsetPosition`/`extremes`/`heightAtWindow`) matched T1/T2's merged shape exactly, no drift. All five T3 tasks built as specified: `buildTideAlignmentIndex` now skips only on a missing `tideState` (not `tideOnTheLight`) and carries the full `{aligned, onTheLight, phrase, level, direction, height, shortfall, fitPhrase, gated}` family; `spotOf` copies `tideTypes`/`coastal`/`tideTier`/`tideShortfall`/`tideGated` onto `labelSpots`; `mapEvents.solarRow` forwards `tide: served.tide ?? null` on the served branch and `null` on the filler branch, the D-13 interleave untouched; the new `utils/mapTideFit.js` holds `tierOf`/`nextAlignedRow`/`stripModel` exactly to the tie-break (HIGH > LOW > MID) and `bounds.pad(0.12)` spec, with `stripModel` taking three params beyond the plan's own illustrative `{row, spots, bounds}` (`evRows`/`evIndex`/`idx`) because `nextFitRow` cannot be answered without them — recorded as a deliberate, documented deviation rather than a silent one. ⚠️ **Adversarial review (4 read-only lenses: runtime behaviour, project conventions including the Backend-heavy licence and the two-tide-axes rule, test quality, what it makes harder for T4–T6) found and fixed six things, two of them independently by separate lenses**: `spotOf` never set an `id` on label spots, so `nextAlignedRow`'s documented-and-tested id-first lookup was unreachable in production — a silent trap for the first two coastal locations ever to share a display name (found by both the runtime-behaviour and the T4–T6-impact lens independently); `spotOf` was also missing `tideFitPhrase`, which T4's own tooltip requirement (plan text, not inferred) needs for both tiers — added rather than left for T4 to discover, since T3's stated goal is that every fact reaches its future reader; `mapTideFit.js` named its tide-index parameter `index`, sitting beside the unrelated array-position `evIndex` in the same call and inviting exactly the kind of misread CLAUDE.md's Jackson-mapper story warns about — renamed to `idx`, matching `locationSheet.js#lookupForWindow`'s own parameter name; the `bounds.pad(0.12)` boundary tests bounded the ratio to a loose `(0.05, 1.0)` range rather than pinning the documented `0.12` figure itself — tightened to bracket the true edge (1.10 in, 1.14 out) so a mutation to any other common ratio fails at least one of the pair; a "does not disturb the interleave" test duplicated an existing ordering test's exact fixture and re-asserted tide values a sibling test already pinned, violating one-behaviour-per-test for no added mutation coverage — trimmed to a smaller, distinct fixture asserting order alone; `buildTideAlignmentIndex`'s now much-larger doc comment sat above `buildEvaluationGateIndex`'s declaration rather than its own (a pre-existing ordering issue T3 tripled in size) — reordered so each doc sits directly above the function it describes. One further, out-of-file fix: `MapCallout.jsx`'s existing `tideOnLight` prop doc, untouched by T3's own task list, asserted "never `tideAligned`, a different question" — literally true of what that component currently renders, but now false as a description of the object's *shape*, which T3 changed; corrected to state both axes are present and why reading the preference one is safe here, so a T5 author reading that file first is not misled into building a duplicate lookup path. **Declined, and documented rather than fixed**: `stripModel` computes the viewport-dependent and window-dependent halves of its answer in one function, which prevents the exact `[evIndex, idx]` vs `mapBounds` two-memo split T3's own task text describes for `MapView` (T6) — flagged by the project-conventions lens as a real but *inert* tension, since T3 wires no call site into `MapView` at all (confirmed by grep) and restructuring a module against a consumer that does not exist yet would be speculative complexity against CLAUDE.md's "extend, never fork" spirit; a `⚠️` doc block records the tension and the concrete split T6 should make if the whole-function recompute on every pan ever proves measurably expensive. Gate green: `npm run lint && npm test && npm audit --audit-level=high && npm run build` — 6302 tests, 0 failures, 0 vulnerabilities, build succeeded. **Browser-verified, not merely asserted**: backend + frontend run locally per §9, three coastal locations seeded with real `tide_extreme` rows (one wanting LOW, one HIGH, one both — reaching the null-shortfall arrow case), a briefing built and served — `GET /api/briefing` carried every T1/T2/T3 field correctly, including `tideFitPhrase: "wants high water or low water · mid tide, falling…"` with `tideShortfall: null` for the two-value want exactly as designed. The Map tab, its callout, its four-day location sheet and Pins mode all rendered with the seeded data, zero console errors, zero failed network requests, across a `computer` screenshot at each step. Not seen in the browser: an aligned (`match`-tier) chip on screen — the seeded synthetic tide cycles happened to miss every window in the fixture window, so that state is tested by the automated suite (`tierOf`, `buildTideAlignmentIndex`'s "reads every tide-fit field" case) rather than seen. |
| T4 — the chip: dimmed, not dropped (frontend) | `feature/tide-t4-chip` | pending PR | 2026-09-17 | Re-verified every file:line the plan and T3's own merged shape cited against the tree before editing — `spotOf`'s `tideTier`/`tideShortfall`/`tideFitPhrase`/`onTheLight` fields, `buildTideAlignmentIndex`'s full family and `buildRegionLocationRows`'s prior `tideOnLight` boolean all matched exactly, no drift since T3 merged. All seven tasks built as specified: `visibleLocations` lets a served tide fact past the rating stage via `if (getTideOnLightForLocation(loc)) return true;` before the wildlife/`showUnrated` branch, with `hasUnrated` excluding the same locations from the admin "unknown" toggle's own count; the chip's `data-tide` moved to `'match'`\|`'miss'`, `.wf-maplab-chip[data-tide='miss']` dims to `opacity:.72` (restored on hover/selection), `TideWave` grew a `shortfall` prop drawing the bundle's 21×8 arrow (up for `HIGHER`, down for `LOWER`, the plain wave for null) behind a new `data-wide` attribute the CSS widens on; the label-budget tiebreak in `utils/mapLabels.js` promotes a match and never demotes a miss; the tooltip's third line reads the served `fitPhrase` for both tiers behind two new shared helpers (`mapTideFit.tideTierHeading`/`tideAccessibleClause`); Pins mode (`PinsLayer.jsx`) gained tide data for the first time (`data-tide`, the same `.72` dim, the same third tooltip line); the region panel's row and `mapDrilldown.buildRegionLocationRows` moved from `tideOnLight` onto `tideTier`/`tideShortfall`. ⚠️ **Adversarial review (6 read-only lenses: runtime behaviour, CSS/tokens, test quality, accessibility, project conventions, impact on T5/T6) found and fixed five things, confirmed three deliberate choices as sound rather than silently accepting them**: `PinsLayer`'s pin carried no accessible-name statement of the tide fact at all — only a mouse-hover tooltip, unlike the chip's aria-label and the region panel's `sr-only` span — so a keyboard/screen-reader user in Pins mode had no textual equivalent of the visual dim; fixed by extending the pin's aria-label with the same `tideAccessibleClause` the chip uses (the pre-existing, file-wide lack of a keyboard trigger for either tooltip was left alone as out of this phase's scope, since it is identical for a match and predates T4). A CSS comment overclaimed `--color-plex-text-secondary` (.66) as "the mapped token for" the design's bespoke `.72` miss-tooltip ink; corrected to name it as the closest existing token with an honest ~0.06 gap stated. `toBeTruthy()` on one new chip-glyph assertion replaced with `toBeInTheDocument()` (the banned pattern per the frontend test standards). Two test gaps closed: a missing swatch-fill-unchanged assertion for a miss chip (§7 check 12 has two halves — the star ink was pinned, the swatch fill was not) and a missing gated-miss (no rating + `tideTier:'miss'`) combination test at the chip level, since `hasRating` and `tideTier` branch independently and every other miss test paired a miss with a real rating. Two §7 checks strengthened to match their own literal wording: check 1 (heat untouched) compared only HIGH vs LOW, now also MID — the design's own "straddling want" case and the most plausible target for a "dim the field too" mistake; check 2 (nothing dropped) flipped only one location between fixtures, now every coastal slot in the pool at once, with an added premise assertion that the tide index actually reached every rendered spot. **Confirmed sound, not changed**: the miss tooltip's canonical heading ("Wrong water, not wrong light") diverges from the plan text's literal quoted example ("Wants high water —", the design bundle's own want-formatted tooltip line) — recorded as a deliberate reuse of the callout/sheet block's own heading in `tideTierHeading`'s doc, since formatting the want client-side would violate "the client concatenates headings to phrases and nothing else" and would restate what the served `fitPhrase` already says; the rating-stage bypass admits ANY served tide fact rather than only a served-`gated` one, which is §5 #9's own literal worked example (`if (tideFact) return true`) even though its prose says "tide-gated…and nothing else" — narrowing it would reopen the "coast disappears" defect for a miss rated null for an unrelated reason, so the breadth is deliberate and now commented at the call site; `.wf-reg-tide`'s ink stays `--color-tide` for both tiers (no opacity split on that row), matching the design bundle's own region-row rule, which likewise has none. No confirmed runtime-behaviour or project-convention defects; `OBSTACLE_SELECTOR` untouched in both files as required. Gate green: `npm run lint` (0 warnings), `npm test` (249 files, 6324 tests, 0 failures — the full suite, re-run after every review fix), `npm audit --audit-level=high` (0 vulnerabilities), `npm run build` (succeeded). **Not browser-verified this phase**: the coordinating machine ran under sustained, extreme shared load for the whole review-and-verify window (`uptime` load averages 120–235 against a normal single-digit baseline — other concurrent sessions' work, not this phase's own), which made a reliable local backend start and browser walkthrough impractical to complete; a backend build was attempted and aborted mid-compile once it was clear it would not finish in reasonable time, and was not left running. The `.72` vs `.8` chip-ink contrast measurement item 2 asks for is therefore UNRESOLVED — shipped at the design's `.72` pending a live measurement, not a confirmed winner. Every other claim in this row is machine-checked (lint/test/audit/build) or adversarially read against the diff, not merely asserted; a live walkthrough of the plan's §9 seeded mid-water-morning scenario, and the opacity measurement, are both owed before merge. |

---

## §1 Corrections to the spec — where the codebase has already moved

Numbered so a phase log can cite them. Every file:line was read against `75ff1f90`; **re-verify
before editing**, code moves.

### 1. The spec's one new field already exists — as a *set*, with an editor

The spec's `OPEN 1` asks for `want: 'high'|'mid'|'low'` (nullable), a column, an API field and an
editor. All three exist:

- `LocationEntity.tideType` is `Set<TideType>` (`entity/LocationEntity.java:74–87`,
  `@ElementCollection` on `location_tide_type`, V9), `TideType` is `HIGH | MID | LOW`
  (`entity/TideType.java`). **An empty set means inland** — the entity's own Javadoc, and
  `LocationService.isCoastal()` (`service/LocationService.java:394–398`) is exactly `!tideType.isEmpty()`.
- Served as `tideType` (singular) on `LocationDto` (`model/LocationDto.java:51`), reaching the map's
  roster as `location.tideType` (`hooks/useForecasts.js:43`); written as `tideTypes` (plural) on the
  add/update requests.
- Edited by `TideToggleChips` in `components/LocationManagementView.jsx` (defined `:64`, used
  `:712`, `:803–808`, `:876–882`), **disabled unless the location is `SEASCAPE`**, and dropping
  `SEASCAPE` blanks the set (`LocationService.java:259–262`).

Three consequences. **No migration, no DTO field, no editor** is built (§4 #1). "Fit" is against a
*set*: a location may want HIGH *or* LOW (production has locations with two values). And the spec's
`null` question dissolves — there is no "coastal with no preference"; a coastal location *is* one
with a preference, so nothing is ever dimmed for a want it does not have.

### 2. This codebase's tide axis is TIME-based, not level-based — and four surfaces already stand on it

The spec models fit as `1 − |level(lightTime) − target[want]|` on a 0..1 water level, with bands at
0.75/0.25 and tiers at 0.80/0.62, and its own `OPEN 2` calls those numbers editorial. This codebase
answers "is the tide right for this spot at this light" already, and differently:

- `TideService.classifyTideState` (`service/TideService.java:581`, "the single tide-state rule") is
  **time-proximity**: within the window of a HIGH → `HIGH`, within it of a LOW → `LOW`, else `MID`.
  It reads no heights. The window is **dynamic** — half the location's blue+golden span
  (`TideFactDeriver.tightAlignmentWindowMinutes`, `service/TideFactDeriver.java:152–161`, ~41 min at
  54.5°N near the equinox), widened by `+60` for a second, looser classification.
- `TideService.calculateTideAligned` (`:340`) tests that state against the location's `Set<TideType>`
  (MID additionally requires `nearMidPoint`). This is the served `BriefingSlot.tideAligned` — the
  **tight** one (`TideFactDeriver.java:96–97`; the widened twin is computed beside it and not served).
- **The gate stands on it**: `BriefingSlotBuilder.java:169–176` overrides a coastal slot's verdict to
  STANDDOWN on `!tideAligned`, and `BriefingGatingPolicy.HARD_CONSTRAINT_REASONS` is exactly
  `{TIDE_MISMATCH}` — the one reason that still withholds a slot from Claude after the Gate 2
  redesign.
- **The score stands on it**: `TideVisitor` (`service/evaluation/visitor/TideVisitor.java:29–57`,
  rule R1) contributes 5 (king/spring aligned) / 4 (aligned, tight) / 3 (aligned only widened) /
  **1** (outside), averaged into the star by `RatingCombiner` at `ForecastResultHandler.buildResult`
  (`:533–540`), the one seam both batch and sync results flow through.
- **The Plan tab stands on it**: `BriefingWindowTide.state` is classified by the same rule at the
  same dynamic window (`WindowTideRollupBuilder.java:311–316`, with a comment explaining why a fixed
  ±60 would be "a second rule").
- `TideSurfaceAgreementTest` exists **specifically** to stop two surfaces calling the same water
  different things.

So the spec's level model would be a *third* definition of HIGH/MID/LOW and a *second* answer to
"does the tide fit", and it would disagree with the gate on the very slots this increment is about
(e.g. a level of 0.85 reads `HIGH` in the spec's bands and `MID` in the shipped rule, since a HIGH
within the ~41-minute window sits at level ≥ ~0.94). **Tiers therefore come off the served
time-based facts** (§4 #2, §5 #2–#3); the water *level* is built and served as a **display fact**
for the strip's dot and the block's "2.6 m of 4.3 m", never as the fit's input. Whether the app's
definition should move to the spec's is a product question for the owner (§6 Q2), not this series'.

### 3. `OPEN 5` — the shipped score penalises tide mismatch, AND the pipeline withholds the slot

The spec asks whether tide is "in the score"; it is, and in a second place the spec could not see:

| path | tide in the **prompt**? | tide in the **score**? | tide in **eligibility**? |
|---|---|---|---|
| batch (writes `cached_evaluation`, the rating the UI shows) | **No** — `PromptBuilder` has zero tide mentions; `CoastalPromptBuilder.java:11–16` records the v2.13.2 decision that Claude scores the *sky alone* | **Yes** — `TideVisitor` averaged in at `ForecastResultHandler:538` | **Yes** — the briefing gate (§1 #2); `BriefingCandidateCollector.java:230–249` drops the slot as `SKIPPED_HARD_CONSTRAINT` |
| sync (hand-started; writes `forecast_evaluation`) | No | Yes, same seam (`:462 → 538`) | Yes, twice: the same gate, plus the SEASCAPE-only `TIDE_ALIGNMENT` strategy (`ForecastService.java:391–419`, persists a triaged row with `rating = null`) |

The consequence for this design is the whole point: **a tide-mismatched coastal slot is served
UNRATED, not low-rated.** It is in the payload with `verdict = STANDDOWN`, `claudeRating = null`,
`couldCarryRating() == false` and (since #866) a non-null `evaluationGate` sentence. The map's
`visibleLocations` predicate (`components/MapView.jsx:2960–2966`) then hides it behind
`showUnrated`, which defaults `false` (`:1405`). That is the "coast disappears" defect, mechanically.
So the spec's "the rating is untouched" is *vacuous* for the commonest miss — there is no rating.
§5 #1 decides what to do about it this series; §6 Q1 costs the alternative.

One more path a reviewer will ask about: a coastal slot that is **weather**-stood-down first is never
tide-overridden (the override runs only when `verdict != STANDDOWN`), so it is *not* a hard-constraint
skip, reaches Claude, and comes back **rated** with `TideVisitor`'s 1 averaged in and
`tideAligned == false`. The map must therefore handle a miss **with** a star as well as a miss
without one — tier and rating are orthogonal (§5 #2).

### 4. The strip's data is already served — the Plan tab draws it

`BriefingWindow.tide` is a `BriefingWindowTide` (`model/BriefingWindowTide.java:73–85`): the
representative coastal `locationName`, `state`, `direction` (`RISING`/`FALLING`), `nearestType`
(`HW`/`LW`), `nearestTime` (London clock), `nearestOffset` (`"1h43 before sunset"`), `range`,
`rangeAnomaly` (`"1.2 m above an average tide"` / `"about average"`), `seas`, **`curve`** (49
samples every 30 min across the local day, normalised 0 at the series' lowest water to 1 at its
highest), **`windowPosition`** (0..1 through the local day) and **`windowLevel`** (the water at the
light on the same 0..1 scale). Built at serve time by `WindowTideRollupBuilder.rollup`
(`service/WindowTideRollupBuilder.java:289–331`), drawn today by `WindowTideSparkline.jsx` via
`utils/windowFirstRows.js#tideSparkline` (`:154–176`). **It is not on any map EV row** —
`utils/mapEvents.js#solarRow` (`:172–255`) forwards `served.time`, `bestRating`, `badges`, picks, and
nothing tidal. The strip is therefore mostly **plumbing**, plus four facts the rollup holds and does
not serve: the representative's own sunrise/sunset (recomputed inside `rollup` at `:296–299`, then
dropped), the day's extremes as a list, and the height in metres at the light (`heightAt(series,
eventMinutes)` at `:603`, normalised away by `Shape.levelOf` on the next line). T2 adds them.

**`OPEN 3` is answered by the codebase.** `TideRepresentativeSelector.select`
(`service/TideRepresentativeSelector.java:121–146`) picks the configured
`photocast.tide-run.preferred-anchor` when it is drawable, else the biggest single-day range across
every date, **once for the whole briefing** — its Javadoc says why per-window choice was rejected
("lets the curve jump coastlines between Tuesday and Thursday"). The strip states *that* station's
curve and names it, so the map's strip and the Plan tab's sparkline can never describe two waters
(§4 #4). The spec's alternatives (viewport-centroid nearest, highest-rated in view, the selection) are
not built.

### 5. The level maths exists once, privately — lift it, do not re-port it

`WindowTideRollupBuilder` holds the only height interpolation in the app, and it is the cosine the
spec asks for: `heightAt` (`:603–617`, eased `(1 − cos(π·f))/2` between consecutive extremes), `shape`
(`:586–595`), `Shape.levelOf` (`:568–573`), `directionAt` (`:625–639`, read from the *kind* of the next
extreme, not a height comparison), `seriesAround`/`fillInteriorGaps`/`bracket` (`:452–538`, which
extend the series half a `CYCLE_MINUTES = 745` past each end so the curve has no flat shoulders). All
`private static`. T1 lifts them into one package-private `TideCurveCalculator` in `service/`, leaving
the rollup's outputs byte-identical (its existing tests are the guard), and uses the same class to
put a per-slot level, height and direction on `BriefingSlot.TideInfo`. The spec's `HALF = 372.5`
anchor, `TIDEX` and `WANT` are scaffolding and are not ported (the README says so itself).

⚠️ **Day boundaries.** `GET /api/tides?locationName&date` is a **UTC** day; every curve in the app is
a **Europe/London** day (`WindowTideRollupBuilder.clockMinutesFrom`, `:662`; `TideWording.londonMinutesOfDay`).
Do not have the client fetch `/api/tides` to draw the strip — it would put the timezone rule in two
places and get the day wrong for an hour every night under BST (§4 #9).

### 6. The map already draws a tide glyph — for a DIFFERENT question

Bundle rev 2's tide-chip increment (`docs/design/map-tab-v2/INCREMENT_sheet_and_tide.md` §3) shipped:
`BriefingSlot.TideInfo.tideOnTheLight` (the nearest extreme, **type-blind**, within the dynamic tight
window) with `nearestSolarOffsetPhrase`, `nearestSolarOffsetMinutes`, `nearestExtremeKind`;
`utils/locationSheet.js#buildTideAlignmentIndex` (`:324–342`, keeps only `onTheLight` + `phrase`);
`TideWave.jsx` mounted on the label chip (`MapLabels.jsx:620`, teal inset ring via
`[data-tide='true']`, `index.css:5120–5122`), the callout row `.wf-callout-tide` (`MapCallout.jsx:675–687`,
copy *Tide lands on the light*) and the region panel row (`MapRegionPanel.jsx:202`); and the label
budget's tiebreak `score → onTheLight → drive` (`utils/mapLabels.js:241–251`).

`tideOnTheLight` answers *"does an extreme land on the light"*; the design's fit answers *"is it the
water this spot wants"*. They disagree exactly where the design cares: a HIGH-wanting spot with LOW
water on the light is `onTheLight == true` (wave, ring, promoted) and a **miss**. `locationSheet.js:274–277`
bans reading `tideAligned` for the on-the-light axis under CLAUDE.md's two-tide-axes rule — correctly,
because it is a different question. **This increment asks the other question**, so the chip's glyph,
ring, tiebreak, tooltip line, the callout row and the region-panel row all move onto the served
**preference** axis (`tideAligned`), and the on-the-light fact survives only as the *offset clause*
inside the phrase (§4 #11, §5 #3). One channel per chip; two waves saying different things on one
pin is the defect INCREMENT_sheet_and_tide warns about in another form.

### 7. The chip already renders unrated — and `PinsLayer` is tide-blind

`MapLabels.jsx:584` branches on `hasRating` and draws a chip with no `N★` when the rating is null (the
"unknown" toggle's state), so a dimmed, star-less chip for a gated slot needs **no new chip shape**.
`PinsLayer` receives no tide data at all (`PinsLayer.jsx:443–456` propTypes) and its dot has a
`data-stand-down`/`STAND_DOWN_COLOUR` precedent (`:372`) for reduced emphasis. The heat pool
(`utils/heatSpots.js:113–176`) carries neither `tideType` nor a coastal flag; `MapView.spotOf`
(`:3080–3102`) copies `onTheLight` onto `labelSpots` but not `tideType`. The predicate to reuse is
`utils/mapCallout.js#isCoastalTidalLocation` (`:204–206`, twin of `locationSheet.js#isCoastalTidal`).

### 8. The chrome the strip lands in

Bottom-left is `.wf-map-chrome-bl` (`MapView.jsx:5477`, `index.css:3309–3318`): `absolute; bottom:8px;
left:8px; z-index:1100; display:flex; flex-direction:column; align-items:flex-start; gap:6px`, holding
the LITE viewline upsell chip and `MapLegendPanel` (its panel opens **upward**, `bottom: calc(100% +
6px)`, z 1500). The count line is bottom-**centre** (`wf-map-counts-footer`, `:5553`, `index.css:3324–3341`,
`pointer-events:none`) and counts **scope**, not viewport (`scopedVisibleLocations`, `:3027–3031`).
The scored legend is bottom-right (`right-[54px]`, clearing Leaflet's zoom + ⌂ stack). Z-ladder:
chrome 1100 / landing card 1050 / callout 1350 / tooltip 1400 / menus 1500; Leaflet label and pin
panes at 650. **`.wf-map-chrome-bl` is already in both `OBSTACLE_SELECTOR` lists** (`PinsLayer.jsx:44`,
`MapLabels.jsx`), so a strip mounted *inside* it is seeded as an obstacle for free — the wrapper's
rect grows to include it. No `bounds.pad()` exists anywhere; `MapLabels` computes its own in-view set
with `bounds.contains` (`MapLabels.jsx:305–311`) and the overlay's `pinsInView` is a plain box test.

**Phone** (`useIsMobile`, `(max-width: 639px)`): the design's `#gnav` is `.wf-map-chrome-tr`
re-positioned to `bottom:8px; left:8px; right:8px` as a three-segment row (`index.css:3499–3508`,
assumed 48px tall). Above it a **five-row lifted stack** (`index.css:3567–3624`): attribution 76 /
counts footer 112 / scored legend 152 / chrome-bl 192, each clearing the one below by ≥ 8px, pinned
arithmetically by `test/mapPhoneChromeCascade.test.jsx` ("THE SWEEP"). `MapLegendPanel` is not mounted
on the phone. A tide strip is a new row in that matrix (T7).

### 9. Tokens — the bundle's names are all re-mapped here; no new hex

| bundle | this repo | value |
|---|---|---|
| `--ink` | `--color-plex-text` | `#F2E7D3` |
| `--ink-2` | `--color-plex-text-secondary` | `rgba(242,231,211,.66)` |
| `--ink-3` | `--color-plex-text-muted` | `.42` page-wide; **`.66` under `.wf-body--map`** (`index.css:1272–1300`, the bundle's `#mapwrap { --ink-3 }` override, scoped to the *shell's* map wrapper so it cannot reach the frozen overlay; a second copy for `BottomSheet`'s portal at `:1309–1311`) |
| `--tide` | `--color-tide` | `#6FA8B0` — borders, strokes, accents |
| `#8FC0C7` (state word, dot, band hit) | **`--color-badge-tide`** | `#9CCBD1`, the repo's small-text tide ink, measured 9.68:1 (`index.css:182`) |
| `--marginal` | `--color-verdict-marginal` | `#E0A542` |
| `--border` | `--color-plex-border` | `#3A2C23` |
| `--mono` | `--font-mono` | IBM Plex Mono |

`index.css` counts the times it has had to correct a bundle `--ink-3` into secondary ink (`:1592`
"the eighth time", `:6805` "SEVENTH time"); a new rule reaching for muted ink at 8–9.5px will be
reviewed against that history. The design's `.ctide` class does not exist — the callout's row is
`.wf-callout-tide`, built on `.wf-frow`'s bordered look (`index.css:5966–6001`), and the flat
`wf-callout-<part>` naming is the convention.

### 10. The sheet has NO per-window tide row, and the gate row is two days old

INCREMENT_sheet_and_tide §2 promised "per window the rows also carry the tide-alignment block and
glyph"; it was never built. `LocationFourDaySheet.jsx:444–500` renders per row the light times, the
gate sentence (`.wf-loc-gate`, #866, `≈ Tide not right at sunrise · needs low water, mid tide instead
· HW 09:19 · 2h35 after sunrise`) and the prose or `.wf-loc-nowhy`; its only tide fact is the
location-level `Coastal · the tide matters here` (`utils/locationSheet.js:612`). It takes **no
`tideAlignmentIndex`** (props at `:583–632`). The callout has the same gate row (`.wf-callout-gate`,
`MapCallout.jsx:612–617`) above the prose. Both are the pipeline's own statement of *why there is no
score*; T5's block must sit beside them without restating their offset clause (§5 #6).

### 11. The jump exists

`MapCallout` already takes `evRows` (`:180`) and `onSelectEv(row)` (`:185`, wired to `MapView.selectEvRow`
at `:5056`), which the strip cells call at `:735`. "Next fit" is one more caller of that callback with
a *row object*, and `selectEvRow` handles the date forwarding and the floor reset. The strip's own jump
uses the same function through a prop. No new plumbing.

### 12. Client-side derivation — what this increment may and may not compute

CLAUDE.md's Backend-heavy bullet licenses exactly two client classes: per-user joins (reach, scope,
arrivals) and *filter/map/select over served facts*. The spec's `tideFitOf`, `bandOf`, `LVL` and
`nextFitEv` are **formulas over shared data** and belong on the server (§4 #2, #7). What the client
does here is all the second class: a viewport filter for the strip's visibility and its counts (the
same shape as `MapLabels`' in-view set), a tally of served `tideType` values over the dimmed in-view
locations, a forward scan over served per-window `tideAligned` for the next fit, and `tier = aligned ?
'match' : 'miss'` — a served boolean read. The chart's path from the served `curve` and the `TY`
mapping are rendering, like `tideSparkline`. **Nothing on the client reads a height, a minute offset
or a threshold to decide anything.**

### 13. Everything else the increment leans on already exists

`TideWording` (`service/TideWording.java`) is the one vocabulary for clocks, metres and offsets, and
already builds the gate sentence — the fit phrase belongs beside it. `BriefingSlot.solarEventTime` is
on every slot. `TideInfo` has a `NONE` constant every test helper passes (`PlanWindowProjectorTest`'s
`identifiedSlot`/`ratedSlot`/`unratedSlot`/`slotAt`), so adding components touches `NONE` and the
builder, not every fixture — keep the 9-arg legacy constructor. Latest migration is
`V154__aurora_forecast_result_simulated.sql`; **this series writes no V155.** `forecast_run_disposition`
holds `SKIPPED_HARD_CONSTRAINT` rows with the tide reason as `detail` — the measurement §6 Q1 needs
is one `GROUP BY` away (`scripts/diagnose-stale-forecast.sh` Q8 is the template).

---

## §2 Strategy

- **Serve, then draw.** T1 and T2 put every fact the design needs on the payload the map already reads
  (`GET /api/briefing`): per slot — level, height, direction, shortfall and a formatted fit phrase;
  per window — the representative's sunrise/sunset positions, the day's extremes and the height at the
  light. No new endpoint, no migration, no client fetch: the cache JSON carries the slot fields
  (legacy payloads read null and every reader fails soft), the window fields are serve-time like the
  rest of `BriefingWindowTide`.
- **One tide question on the map, and it is the preference one.** The chip, its ring, its tiebreak,
  its tooltip, the callout block and the region-panel row all key on `tideAligned`. The type-blind
  on-the-light fact is demoted to the offset clause inside the phrase. §4 #11 records what that
  changes on screen.
- **The gate and the score are untouched this series** (§5 #1). A gated location is drawn dimmed
  *without* a star, with the region's labelled sky gloss (#866) as its prose — "the sky could be good,
  shame about the tide". Lifting the gate is costed in §6 Q1 and has Plan-tab blast radius; it is the
  owner's call, and it would be its own series.
- **Extend, never fork.** `TideCurveCalculator` is lifted out of `WindowTideRollupBuilder`, not
  copied; `TideWave` grows an arrow variant rather than a second SVG; `.wf-callout-tide` grows two
  tiers rather than a `.ctide` sibling; `buildTideAlignmentIndex` grows fields rather than a second
  index; the strip mounts inside `.wf-map-chrome-bl` so the legend chip clears it by flex order and
  the obstacle seed is inherited.
- **Nothing is dropped.** A coastal location with a served tide fact is a chip and a pin in every
  mode, whatever its rating, subject only to the filters a reader set on purpose (type, drive,
  dark-sky, scope). That is the increment's *first* rule and its check 2.
- **Sonnet sessions, one phase each, review before commit.** The builder stops before committing;
  the adversarial review runs on the working tree (read-only agents); surviving findings are fixed;
  then the commit. §8 is the session map, `tide-window-prompts.md` the kickoff prompts.

---

## §3 Phases

Sizes: S ≈ half a session, M ≈ one, L ≈ one long one. Every phase ends with the phase-log row and any
§4 additions **in the same commit**.

### T1 — Backend: the per-slot tide facts — M

**Goal.** Every coastal `BriefingSlot` carries the display facts the chip, tooltip, callout and sheet
need, in the wording the rest of the app uses, built by the one interpolation the app already has.

1. **Lift `TideCurveCalculator`.** New package-private `service/TideCurveCalculator.java` holding
   `WindowTideRollupBuilder`'s `Point`, `Shape`, `seriesAround`, `fillInteriorGaps`, `bracket`,
   `counterpartHeight`, `shape`, `heightAt`, `directionAt`, `clockMinutesFrom` and the constants
   (`SAMPLE_MINUTES`, `CYCLE_MINUTES`, `FLAT_LEVEL`, `MINUTES_PER_DAY`). `WindowTideRollupBuilder`
   delegates; its outputs are byte-identical, pinned by the **existing** `WindowTideRollupBuilderTest`
   with no assertion changed (that is the point of the test surviving).
2. **Extend `BriefingSlot.TideInfo`** with five `@JsonInclude(NON_NULL)` components, null for
   inland or when no extremes are stored: `Double tideLevel` (0..1 on the same normalisation as the
   window curve — series min → 0, max → 1), `String tideDirection` (`RISING`/`FALLING`), `String
   tideHeight` (formatted, `"2.6 m"`), `String tideShortfall` (`HIGHER` / `LOWER` / null), `String
   tideFitPhrase` (formatted, both tiers). Keep `TideInfo.NONE` and the 9-arg legacy constructor;
   add the canonical constructor. Build them in `BriefingSlotBuilder.calculateTideData` (`:311–361`)
   / `TideFactDeriver.derive` from the extremes `TideService.deriveDualWindowTideData` already
   fetched — **no new query**.
3. **`tideShortfall`** is derived from the served state and the wanted set: state below every wanted
   band → `HIGHER`; above every wanted band → `LOWER`; a set that straddles the state (`{HIGH, LOW}`
   at MID) → **null**, and the chip draws the plain miss wave with no arrow rather than guess (§5 #4).
   Null also when aligned.
4. **`tideFitPhrase`** in `TideWording`, beside `tideGatePhrase`, one form per tier:
   - match: `high water, falling · HW 19:52 · 36m before sunset · 3.9 m`
   - miss: `wants low water · mid tide, rising at 05:42 · 2.6 m of 4.3 m`
   The "wants" clause uses the same `joinOr` ordering as the gate phrase; "of 4.3 m" is the day's
   high water (series max), never the average. Clock times London, via `londonMinutesOfDay`. ⚠️ The
   miss phrase deliberately does **not** repeat the nearest-extreme offset (`HW 09:19 · 2h35 after`)
   — the gate sentence beside it on a gated card already says that (§5 #6).
5. **Confirm and record** that `TideInfo.tideAligned` is the *tight* alignment. Read at plan time:
   `TideFactDeriver.java:96–97` computes `tideAligned` from the tight `tideData` and `widenedAligned`
   from `dualMaybe.get().widened()` separately, and `BriefingSlotBuilder` serves the first — the
   gate's own input. Re-read it against the tree; if it has moved to widened, stop and report before
   T3 — the chip's match tier would otherwise be looser than the gate.
6. **Tests.** `TideCurveCalculatorTest` (the cosine's midpoint is exactly the mean height; a flat
   series levels at 0.5; bracketing adds no interior sample; direction flips at an extreme);
   `BriefingSlotBuilderTest` cases for each shortfall outcome and both phrases with exact strings;
   `DailyBriefingResponseJsonTest` round-trips the five fields and proves a pre-field payload
   deserialises to nulls; **`TideSurfaceAgreementTest` extended**: for the representative location on
   one window, the slot's `tideLevel` equals the window's `windowLevel` within 0.01 and the slot's
   `tideDirection` equals `BriefingWindowTide.direction` — driven from one set of extremes so neither
   fixture can pre-satisfy its own predicate. Gate on the exit code.

### T2 — Backend: the strip's window facts — S/M

**Goal.** `BriefingWindowTide` carries what the strip draws that the sparkline did not need. All from
data `rollup` already holds; **additive**, so `WindowTideSparkline` keeps working untouched.

1. Add `@JsonInclude(NON_NULL)` components: `Double sunrisePosition`, `Double sunsetPosition` (0..1
   through the representative's **local** day, the same axis as `windowPosition`; null when the sun
   does not rise/set — high-latitude midsummer is a real input for a Highlands anchor),
   `List<Extreme> extremes` (`record Extreme(String kind, double position, String time)` — `HW`/`LW`,
   position on the same axis, London clock via `TideWording.clock`; **every** extreme in the local
   day, from the same `extremes` list `rollup` filters), `String heightAtWindow` (formatted,
   `TideWording.metres(heightAt(series, eventMinutes))`). Compute the solar positions from the
   sunrise/sunset the method already gets at `:296–299` — call both, not only the event's.
2. Reuse T1's `TideCurveCalculator` if T1 has merged; if not, compute from the private statics and
   let T1's lift move the call — **do not** duplicate `heightAt`.
3. **Tests.** `WindowTideRollupBuilderTest`: positions monotone with clock time, an extreme at 23:59
   lands at ≤ 1.0, a DST day still yields a 1440-unit axis (the class's own rule), `heightAtWindow`
   equals `TideWording.metres` of the interpolated height, and every pre-existing assertion
   unchanged. `DailyBriefingResponseJsonTest` round-trip.

### T3 — Frontend: plumbing, and the pure model — M

**Goal.** Every fact reaches the components that will draw it, through the indexes and rows that
already exist, and the client's four licensed derivations (§1 #12) live in one tested pure module.
**No visible change.**

1. **`buildTideAlignmentIndex`** (`utils/locationSheet.js:324–342`) indexes every coastal slot with a
   `tideState` (not only `tideOnTheLight != null`) and carries `{aligned, onTheLight, phrase,
   level, direction, height, shortfall, fitPhrase, gated}` where `gated = slot.evaluationGate != null`.
   Rename nothing yet; the doc block at `:268–300` is rewritten to say the index now answers the
   preference question and why the two-axes ban does not apply to a reader asking it.
2. **`spotOf`** (`MapView.jsx:3080–3102`) copies `tideTypes: loc.tideType ?? []`, `coastal:
   isCoastalTidalLocation(loc)`, `tideTier` (`'match' | 'miss' | null`), `tideShortfall`,
   `tideGated` onto `labelSpots`; the same fields reach the Pins pool.
3. **`mapEvents.solarRow`** (`utils/mapEvents.js:172–255`) forwards `tide: served.tide ?? null` on the
   served branch, `null` on the filler branch. `findEvIndex` and the interleave are untouched.
4. **`utils/mapTideFit.js`** (new, pure, no React): `tierOf(fact)`; `nextAlignedRow(evRows, index,
   locationKey, fromIndex)` (first later `kind === 'solar'` row whose slot for this location is
   `aligned`; −1 when none); `stripModel({row, spots, bounds})` returning `{visible, representative,
   namedCoastal, dimmed, matched, dominantWant, nextFitRow}` where `visible = row.kind === 'solar' &&
   row.tide != null && coastalInView.length > 0`, in-view uses `bounds.pad(0.12)`, counts are over the
   **chip pool** (named coastal spots), `dominantWant` tallies every `TideType` in each dimmed spot's
   set and takes the mode (ties: HIGH > LOW > MID, stated), and `nextFitRow` is the first later solar
   row in which **any currently-dimmed spot wanting `dominantWant`** is served aligned — a lookup, not
   a formula (§5 #7). `MapView` memoises the per-window part on `[evIndex, index]` and the viewport
   part on `mapBounds` (the design's §5 split).
5. **Tests.** `mapTideFit.test.js` — every branch, both tie-break directions, `pad` proven with a spot
   just outside the raw bounds; `locationSheet.test.js` — the index carries a miss whose
   `tideOnTheLight` is null (the case the old skip dropped); `mapEvents.test.js` — `tide` forwarded on
   served, null on filler, and the D-13 interleave unchanged.

### T4 — The chip: dimmed, not dropped — L

**Goal.** Design §3, on both layers. A coastal location whose tide misses stays on the map at reduced
emphasis with the shortfall on its chip; a match keeps the wave; the star and the swatch are never
touched; nothing coastal leaves for tide.

1. **Membership.** `visibleLocations` (`MapView.jsx:2955–2998`): a location with a served tide fact
   passes the rating stage whatever its rating (`if (tideFact) return true;` before the `rating ==
   null` branch) — it is not "unknown", it is withheld for a stated reason, or rated. Type, drive,
   dark-sky, scope and `focus` still apply (§5 #9). The counts footer's `+ unknown` summary must not
   count these.
2. **The label chip** (`MapLabels.jsx:582–630`): `data-tide` becomes `'match' | 'miss'`; `.wf-maplab-chip[data-tide='miss']
   { opacity: .72 }`, `:hover`/`[data-selected='true']` restore `1` (design §3 — the `.wf-loc-row`
   precedent at `index.css:8361–8400` argues `.8` on contrast grounds; **measure the chip's ink at
   `.72` over the basemap** and record which won in §4). The wave's ink on a miss is
   `rgba(242,231,211,.5)`-equivalent — use `--color-plex-text-muted` and measure. `TideWave` gains a
   `shortfall` prop (`'HIGHER' | 'LOWER' | null`) drawing the bundle's 21×8 arrow variant
   (`map-tide-v5.js:157–161`); null draws the plain wave. The `N★`, the swatch and the chip's
   background are **untouched** (the `.wf-maplab-chip-n` rule in `index.css` — `--color-plex-text`, never ramp colour — stands). The accessible name gains
   `'tide right here'` / `'wants the water higher'` / `'wants the water lower'` / `'wrong water'`.
3. **The tiebreak** (`utils/mapLabels.js:241–251`): `onTheLight ? 1 : 0` becomes `tideTier === 'match'
   ? 1 : 0`. **A miss is not demoted** — it sorts by score like everything else; only matches are
   promoted. Pin this with a test where a miss and an inland spot share a score and the miss keeps
   its label under a budget of one (§7 check 2's teeth).
4. **The tooltip** (`MapLabels.jsx:662–668`): the third line reads the served `fitPhrase` for both
   tiers, prefixed by the tier heading (`Tide lands on the light —` / `Wants high water —`).
5. **Pins mode** (`PinsLayer.jsx`): pool gains `tideTier`; `data-tide='miss'` draws the dot at `.72`
   with the ramp colour untouched; the pin tooltip gains the same third line. The unrated coastal dot
   keeps whatever the layer already draws for `rating == null` (it must render one — check).
6. **The other TideWave mounts.** `MapRegionPanel.jsx:202`'s row and its `sr-only` span move onto the
   tier (`mapDrilldown.js:341` forwards it). The callout's row is T5.
7. **Tests.** `MapLabels.test.jsx` (chip miss: opacity class present, glyph arrow direction per
   shortfall, plain wave when null, star text unchanged, accname exact); `mapLabels.test.js` (the
   promote-not-demote comparator, both directions); `MapViewHeat.test.jsx` (a gated coastal location
   renders a chip with `showUnrated` false AND absent from the `+ unknown` count; an inland unrated
   one still does not); a Pins test with the same pair. **§7 checks 1 and 2 as tests**: field alpha at
   a coastal core identical across `tideType` = HIGH/MID/LOW on one fixture; named-coastal chip count
   equal on an all-aligned and an all-miss fixture at one viewport.

### T5 — The callout and the location sheet: one block, two tiers, and the jump — M/L

**Goal.** Design §4. One component renders the tier block in the anchored callout and in every
solar row of the location sheet, with the exact headings, and the miss block carries the next-fit
jump or its honest denial.

1. **`components/map/TideFitBlock.jsx`** (new): props `{fact, evRow, evRows, onSelectEv, horizonWord}`.
   Match: heading **Tide lands on the light**, body = `fitPhrase`. Miss: heading **Wrong water, not
   wrong light**, body = `fitPhrase`, then on its own line either a text button `Next <want> on the
   light · <dayLabel> <sunrise|sunset> <time> ›` (calls `onSelectEv(row)`) or the italic denial
   `Nothing in these four days puts <want> on the light here.` — `<want>` is the location's wanted
   set joined with "or" (the sheet already says "four days"; if the served horizon is not four, the
   phase changes the word once, in `horizonWord`, and records it). The glyph is `TideWave` with the
   served shortfall. Colours: `--color-tide` border/background tints per tier as `.wf-callout-tide`
   already does; the miss tier uses the plex-text tint the design specifies, via tokens.
2. **Callout** (`MapCallout.jsx:675–687`): the existing row becomes the block, keyed on
   `tideFact != null` rather than `onTheLight`, kept at the same position in the card. **The gate row
   stays** (`.wf-callout-gate`, `:612–617`) — it is the pipeline's statement of why there is no score,
   and the block is the reader's statement of what the water does; the block's miss phrase does not
   repeat the gate's offset clause (T1 #4), so no fact prints twice (§5 #6). The block joins the
   repaint dependencies the tide row already sits in (`:276–306`'s two ⚠️ notes).
3. **Sheet** (`LocationFourDaySheet.jsx`): new `tideAlignmentIndex` prop, passed from
   `WindowFirstShell`'s mount and the map's `handleOpenLocationSheet` route (the shell already holds
   the briefing); each solar row renders the block after the light-times line and before the gate
   row. The next-fit affordance inside the sheet **focuses the target row** (`focusWindowKey`) rather
   than changing the map's window under a dialog — the sheet is the destination of two doors and must
   not move the surface beneath it (`plan-to-map-doors-plan.md` §5 #4's reasoning).
4. **Tests.** `TideFitBlock.test.jsx` — both headings exact, the jump calls `onSelectEv` with the
   *row object* (not an index), the denial exact, `horizonWord`; `MapCallout.test.jsx` — the block on a
   rated miss AND on a gated miss with the gate row present and the offset clause appearing exactly
   once in the card's text; `LocationFourDaySheet.test.jsx` — a block per solar row, none on a night
   row, the focus move.

### T6 — The strip — L

**Goal.** Design §2. Bottom-left, always on where the view holds coast and the window is solar,
collapsible, publishing its height; the day's curve with high/mid/low ruled across it, night shaded,
sunrise and sunset marked, the light on the curve with its height, and a footer that counts what is
dimmed and jumps to the next fit.

1. **`components/map/MapTideStrip.jsx`** (new), mounted as the **last child** of `.wf-map-chrome-bl`
   so the legend chip and the upsell chip sit above it by flex order (§4 #6) and the obstacle seed
   is inherited (§1 #8). Renders `null` when `stripModel.visible` is false — and then the chrome is
   exactly as today (§7 check 3). Width `474px`, `max-width: calc(100% - 24px)`; surface, radius,
   shadow and blur per design §2 via tokens; `--font-mono` throughout.
2. **Header**: kicker `Tide at this light`; state phrase from the served `state` + `direction`
   through `windowFirstRows.js`'s `STATE_WORD`/`DIRECTION_WORD` (one vocabulary, already there);
   meta `<heightAtWindow> · <rangeAnomaly>` — the codebase's anomaly wording, not the design's
   (§4 #8); a `measured at <locationName>` clause (§4 #4 — the strip must say whose water it states);
   collapse button `▾` / `Open ▴`.
3. **Chart**: `svg viewBox="0 0 1000 32" preserveAspectRatio="none"`, path from the served `curve`
   (49 points across 1000 units), area fill under it, `vector-effect="non-scaling-stroke"` on the
   curve (**mandatory**, the SVG is non-uniformly scaled), night rects from `0..sunrisePosition` and
   `sunsetPosition..1`, three dashed rules at `TY(1)/TY(0.5)/TY(0)` with the gutter labels HIGH/MID/LOW
   and the served `state` as the hit label, sunrise/sunset verticals with `↑ 05:42`/`↓ 20:25` labels
   in `--color-verdict-marginal`, extrema labels `HW 08:47` clamped 5–95%, the light dot at
   `left: windowPosition, top: TY(windowLevel)` with `heightAtWindow` above it clamped 9–91%, hour axis
   `00 06 12 18 24`. `TY(l) = (26 − l·20) / 32 · 100`. The whole chart `aria-hidden`; the header
   phrase, the height and the footer sentence carry the meaning.
4. **Footer** (design §2 copy, exact): `13 of 16 coastal spots are dimmed — 9 of them want high
   water` (collapsing to `they want high water` when every miss shares the want) / `6 of 16 coastal
   spots have the water they want` / `No coastal spot here has its water on this light`; right-aligned
   `Next <want> on the light · <dayLabel> <sunrise|sunset> ›` calling `selectEvRow(nextFitRow)`, or
   `Next <want> on a <sunrise|sunset> is beyond these four days`. Counts are the **named coastal
   spots in the padded viewport** and update on `moveend` (the `BoundsTracker` state already exists).
5. **Collapsed row**: `Tide · <state phrase> · <height> at <time> · <n> dimmed · Open ▴`. Collapse is
   `MapView` state (the pane is never unmounted, so it persists for the session without storage) and
   does not reset on window change.
6. **`--tsh`**: a `ResizeObserver` on the strip sets `--tsh: <offsetHeight>px` on the `.wf-map-tab`
   root and toggles a `wf-tide-strip-on` class; `.wf-map-tab.wf-tide-strip-on .wf-map-counts-footer {
   bottom: calc(var(--tsh, 120px) + 16px) }`. Only the count footer needs it — the legend panel opens
   upward from a chip that has already moved, and the bottom-right legend is out of the strip's
   column. Remove both on unmount.
7. **Contrast**: every text node ≥ 4.5:1 against `rgba(20,15,12,.94)` composited over the basemap's
   darkest tile; the hour axis and gutter labels use `--color-plex-text-muted` (which is `.66` under
   `.wf-body--map` — do not hard-code `.42`).
8. **Tests.** `MapTideStrip.test.jsx` — visibility both ways for each of the three conditions; the
   dot's `top` equals `TY(windowLevel)` (jsdom reads the style string); night rect widths from the
   positions; footer copy for all three count cases and both next-fit outcomes with exact strings;
   `aria-hidden` on the svg and the state phrase present as text; collapse toggles and survives an
   `evIndex` change; `--tsh` written and removed. `mapChromeZLadderCascade.test.jsx` gains the strip's
   `z-index` and the footer's lifted `bottom`.

### T7 — Phone — S/M

**Goal.** Design §6. Strip full-width above the bar; the count line hidden while the strip is up;
nothing overlaps.

1. `.wf-map-tab .wf-map-tide-strip { left: 8px; right: 8px; width: auto; max-width: none }` under
   the phone query; it takes the count footer's row of the stack (`bottom: 112px`) and the footer is
   `display: none` while `wf-tide-strip-on` (design §6: "the tide sentence is the more useful line at
   that width"). The scored legend and `.wf-map-chrome-bl`'s other children are lifted by `var(--tsh)`
   rather than the footer's assumed 28px — the strip is 3× taller open than collapsed, so a constant
   is wrong for one of them (the design's own argument for `--tsh`).
2. **`mapPhoneChromeCascade.test.jsx`** gains the strip's row in both states, asserting each pairwise
   clearance ≥ 8px on the arithmetic (open at a stubbed 166px, collapsed at 34px), and that the footer
   is hidden only while the strip is on. Measure the real open/collapsed heights at 390×844 and put
   them in the phase log.
3. Verify the bar (`.wf-map-chrome-tr`) is never covered and the callout's `CALLOUT_WIDTH_MOBILE` card
   still clears the strip's top edge (the callout band already clamps to the chrome — confirm it reads
   the lifted footer, or extend its band to the strip).

### T8 — Sweep, docs and the OPEN answers — S

1. Reconcile §4 against what shipped (renumber, then grep this document's own references); flip §0 to
   complete; fill the phase log's final rows.
2. **CLAUDE.md**: the Map tab (v2) bullet gains the strip, the dimming rule and the one-question rule
   (the chip keys on the preference axis; on-the-light is the offset clause); the Locations bullet's
   `TideType` line says the map reads it; the Backend-heavy bullet's Map-tab class gains the four
   members §1 #12 lists — *and only those* ("Members: … nothing else" is the convention). The "Two tide
   axes" bullet gains one sentence: the map's chip asks the preference question, so it reads
   `tideAligned`, and `tideOnTheLight` keeps the offset phrase.
3. Answer the spec's five OPENs in §6 with what shipped; record the §7 measurements.
4. `docs/design/tide-window/VENDORING.md` gains a line naming the plan's final §4 count.

---

## §4 Disagreements with the spec, on purpose

Numbered so a phase log can cite them. Each phase appends; T8 reconciles.

1. **No new field, column, API field or editor (spec `OPEN 1`, working order step 1).** `Set<TideType>`
   on the location is the want; `LocationManagementView`'s `TideToggleChips` is the editor; coastal is
   the non-empty set. Fit is against a set, and "null" means inland — there is no "no preference"
   state to decide (§1 #1).
2. **Fit tiers are read off the served time-based alignment, not computed from a level.** The spec's
   `fit = 1 − |level − target|` with tiers at 0.80/0.62 and bands at 0.75/0.25 is not ported. This
   codebase's alignment is `classifyTideState` (time-proximity, dynamic half-golden-blue window) →
   `tideAligned` (state ∈ wants), and the gate, `TideVisitor`, the Plan tab's badge and
   `BriefingWindowTide.state` all stand on it; `TideSurfaceAgreementTest` exists to forbid a second
   definition (§1 #2). The level is served and drawn, never decided on.
3. **Two tiers on the map, not three.** The codebase's `widenedAligned` (the natural "near") is gated
   exactly like a miss and is not served; a silent tier would draw nothing anyway. `match` ⇔ served
   `tideAligned`; `miss` ⇔ served `!tideAligned` on a coastal slot with a tide state. The spec's
   "Close on the tide" block is not built (§5 #2).
4. **The strip states the served rollup's representative, named on the strip** — `TideRepresentativeSelector`'s
   anchor-or-biggest-range, chosen once per briefing — not the viewport-nearest, highest-rated-in-view
   or selected station the spec offers for `OPEN 3`. The Plan tab's sparkline states the same water;
   two representatives would be two answers for one coast (§1 #4).
5. **A miss may have no star.** For a gated slot the design's "rating untouched" has nothing to touch;
   the chip renders star-less (the existing unrated shape) with the miss glyph, and the callout/sheet
   show the region's labelled sky gloss (#866) as the light's proxy. The rule that survives: *where a
   star exists, tide never changes it* (§5 #1).
6. **The legend chip clears the strip by flex order, not by `--tsh`.** The strip is the last child of
   the existing bottom-left flex column, so the chip above it needs no offset arithmetic; `--tsh` is
   published for the one element in a different column, the bottom-centre count footer (and on the
   phone, the lifted stack). Same behaviour, one fewer coupled constant.
7. **Next fit is a lookup over served per-window alignment, not a single-station formula.** The
   callout's jump finds the first later solar window where *this location* is served aligned; the
   strip's finds the first where *any currently-dimmed spot wanting the dominant want* is. The spec's
   `nextFitEv` scans one anchor's cosine, which for a coast 40–60 minutes wide can name a window in
   which no actual spot fits (§1 #12).
8. **Copy from the codebase's vocabulary where it already has one.** `rangeAnomaly` reads `about
   average` / `1.2 m above an average tide`, not the spec's `0.5 m below average`; state words are
   `windowFirstRows.js`'s `STATE_WORD`/`DIRECTION_WORD`; clocks and metres are `TideWording`'s. The
   spec's headings (*Tide lands on the light*, *Wrong water, not wrong light*, the footer sentences,
   *beyond these four days*) are taken verbatim.
9. **The client fetches nothing.** The spec's working order step 2 reads `fetchTidesForDate` on the
   client; that endpoint is a UTC day and every curve here is a London day, and formatting on the
   client puts the timezone rule in two places. Everything rides `GET /api/briefing` (§1 #5).
10. **Tokens, not hex.** `#8FC0C7` → `--color-badge-tide` (`#9CCBD1`, the repo's measured small-text
    tide ink); `--ink-3` → `--color-plex-text-muted` under the existing `.wf-body--map` override;
    `.ctide` → `.wf-callout-tide`; `#gnav` → `.wf-map-chrome-tr` under the phone query (§1 #9).
11. **The chip's existing wave changes meaning.** INCREMENT_sheet_and_tide's glyph marked *an extreme
    on the light* (type-blind); from T4 it marks *the water this spot wants*, and the label budget
    promotes that instead. A HIGH-wanting spot with low water on the light loses its wave and gains
    the arrow — the design's intended reading. The offset fact survives inside the phrase (§1 #6).
12. **Collapse persists in `MapView` state, not storage.** The pane is never unmounted
    (`plan-to-map-doors-plan.md`'s D2 note), so "persists for the session" needs no `sessionStorage`
    and its try/catch.
13. **T4's tooltip heading for a miss is the canonical "Wrong water, not wrong light", not the
    design's own tooltip-specific "Wants high water —".** The design bundle's chip tooltip composes
    its miss line from the location's own want (`map-tide-v5.js:434-435`,
    `'Wants '+BANDW[tf.want]+' — '+state+…`) — a heading the client would have to FORMAT from the
    served `tideTypes` set to match literally, which the client is not licensed to do (§5 #5: "the
    client concatenates headings to phrases and nothing else"). The served `fitPhrase` a miss opens
    with already states "wants low water · …" (T1 item 4), so a want-specific heading on top would
    also restate it. `mapTideFit.tideTierHeading` reuses the SAME fixed miss heading the callout/sheet
    block (T5) and the design's own callout table already use, so the tooltip and the block agree on
    one vocabulary instead of the tooltip alone chasing a dynamic example. Recorded in the function's
    own doc (adversarial review finding, T4).
14. **The rating-stage bypass (item 1) is ANY served tide fact, not only a served `gated` one.** §5
    #9's own prose says "tide-gated…and nothing else", but its own worked example is the literal rule
    shipped: `if (tideFact) return true` before ever reading `.gated`. A coastal slot's tide fact is
    computed from stored extremes independently of the evaluation pipeline (§1 #4), so a `null`
    rating beside one is already evidence the pipeline reached that slot, even on the rare path where
    the null is not itself the tide gate's doing (a miss rated null for an unrelated reason on this
    window). Narrowing the check to `.gated` would put such a slot back behind the "unknown" toggle —
    the exact defect this item exists to fix — so the broader check is deliberate, not a residual
    (adversarial review, T4; recorded inline at the call site).

## §5 Decisions taken in this plan (challenge in review, not in code)

1. **The gate and the score are untouched this series.** Lifting `TIDE_MISMATCH` from
   `HARD_CONSTRAINT_REASONS` would send every mismatched coastal slot to Claude (a measurable cost —
   §6 Q1's query) and, unless `TideVisitor`'s penalty went with it, lower the star *and* dim the chip
   ("told twice", the spec's own `OPEN 5` warning); removing the penalty raises the Plan tab's stars
   and lets a best bet land on a wrong-water beach with no Plan-tab surface to say so. That is a
   product change with Plan-tab blast radius, and its own series. This one draws what the pipeline
   already knows.
2. **Tier and rating are orthogonal.** `tier ∈ {match, miss}` from `tideAligned`; the star from the
   rating, present or not; the gate row from `evaluationGate`. A rated miss (the weather-stood-down
   path, §1 #3) shows its star dimmed; a gated miss shows no star dimmed; an aligned slot shows its
   star at full strength with the wave. No state is invented for any combination.
3. **The chip reads `tideAligned`, and the two-axes rule allows it.** The ban in `locationSheet.js`
   guards the *on-the-light* axis from being answered by the preference; here the question *is* the
   preference. Every surface that used to key on `onTheLight` (chip, ring, tiebreak, tooltip,
   callout row, region-panel row) moves together in T4/T5, so no screen carries both answers.
4. **The shortfall arrow is served, and null means "no arrow".** A set that straddles the state
   (`{HIGH, LOW}` at MID) has no single direction; the chip draws the plain wave in miss ink rather
   than pick one. Never derive the arrow on the client from state and set — that is the formula the
   server owns.
5. **Every string is server-formatted.** Clock times, metres, offsets, the fit phrase, the anomaly
   note. The client concatenates headings to phrases and nothing else.
6. **No fact prints twice on one card.** The gate row (`≈ Tide not right at sunrise · needs low water,
   mid tide instead · HW 09:19 · 2h35 after sunrise`) owns the offset clause; the block's miss phrase
   owns the level, height and "wants" clause. T5's test asserts the offset clause appears once in the
   card's text on a gated miss.
7. **Strip visibility = solar row ∧ served window tide ∧ ≥ 1 coastal spot in `bounds.pad(0.12)`.**
   Not a mode, no toggle, the only control is collapse. A night row hides it; an inland viewport
   hides it and the chrome returns to `bottom: 8px`.
8. **Counts are named coastal spots in the padded viewport** — the chip pool, so "here" is true and
   "16 coastal spots" is what the reader can see or nearly see. Wants are tallied per `TideType`
   across each dimmed spot's set (a `{HIGH, LOW}` spot counts once in each), the mode wins, ties break
   HIGH > LOW > MID.
9. **Tide-gated coastal locations bypass the rating stage of `visibleLocations`, and nothing else.**
   They are not "unknown" (they carry a reason) and the star floor has nothing to test; type, drive,
   dark-sky, scope and `focus` still apply. They are excluded from the `+ unknown` count.
10. **Heat is untouched, mechanically and by assertion.** A gated slot has no score today and
    contributes nothing to the field; an aligned or rated-miss slot contributes its rating as before.
    T4 asserts §7 check 1 anyway, because the rule is easy to break by a well-meaning "dim the field
    too".

---

## §6 Owner decisions / OPEN items

**Nothing here blocks T1–T8** under §5's decisions. The owner challenges §5 on the plan PR.

- **Q1 — Lift the tide gate (and the `TideVisitor` penalty), so a mismatched coastal spot carries its
  light score and tide lives in emphasis alone?** This is the spec as written; §5 #1 declines it
  *this series*. Measure before deciding — the extra Claude calls per cycle are exactly the
  hard-constraint skips:

  ```sql
  SELECT date(created_at AT TIME ZONE 'UTC') AS day,
         count(*) FILTER (WHERE disposition = 'SKIPPED_HARD_CONSTRAINT')            AS tide_gated,
         count(*) FILTER (WHERE disposition IN ('EVALUATED', 'FORCE_EVALUATED'))    AS evaluated
  FROM forecast_run_disposition
  WHERE created_at > now() - interval '14 days'
  GROUP BY 1 ORDER BY 1;
  ```
  (`scripts/diagnose-stale-forecast.sh` Q8 is the template; run over SSH against production.) If
  taken, the series after this one needs: the gate lifted, `TideVisitor` made to abstain, a Plan-tab
  dimming counterpart, and a re-read of the best-bet advisor's tide language
  (`BestBetPromptText.java:63–89`).
- **Q2 — Should the app's alignment rule become level-based (the spec's model)?** Today: within the
  half-golden-blue window of an extreme, else MID. The spec's argument — "the level at the moment of
  the light is what you stand in" — is a real one, and the two rules disagree in the shoulder (a
  level of ~0.85 is MID today). Product question; touches the gate, the score, the Plan tab and this
  increment's tiers. Not this series.
- **Q3 — `OPEN 2`, the thresholds.** Moot for the level bands (not ported); the live knobs are
  `TideService.HIGH_LOW_THRESHOLD_MINUTES = 60` (the widened extension) and the dynamic tight window.
  The spec's advice stands: ask two or three photographers where their foreground stops working.
- **Q4 — `OPEN 4`, next fit beyond the forecast.** A tide-plus-ephemeris query needing no forecast:
  *"the next sunrise with high water at Bamburgh is Tue 22"*. Deterministic, cheap, and the strongest
  follow-on. Would be a new endpoint (`GET /api/tides/next-aligned?locationName&want&from`) and its own
  small series.
- **Q5 — Stroke the coastline thicker at high water and thinner at low** (spec §7's cheap alternative
  to shading the sea). A width function on `drawCoast`, covering nothing. Worth one experiment after
  the strip ships; not this series.
- **Q6 — Should the Plan tab's location sheet rows and the matrix's chips dim on tide the same way?**
  T5 gives the sheet the block; the matrix's field chips have no tide channel. Parity question for the
  owner once T4 is on screen.
- **Q7 — `TideIndicator.jsx` and the duplicate `fetchTidesForDate`/`fetchTideStats` in
  `forecastApi.js`.** Overlay-only, pre-v2, not a building block here. Cleanup candidate for O-6
  (overlay convergence), recorded so nobody reaches for it.

---

## §7 Verify by measurement — the spec's eight checks, and how each is measured

Report **numbers, not screenshots**. Each row names the phase that owns it.

| # | check | phase | how |
|---|---|---|---|
| 1 | Tide never moves the heat | T4 | One fixture, one window, one coastal location; render the field with `tideType` = `[HIGH]`, `[MID]`, `[LOW]` in turn; read the canvas alpha at the location's core; assert the three are equal to the byte. |
| 2 | Nothing is dropped | T4 | Two fixtures differing only in every coastal slot's `tideAligned`; same viewport and zoom; count `[data-testid="map-label-chip"]` for coastal names and Pins dots; assert equality. Then the tiebreak test: a miss and an inland spot at equal score under a budget of one — the miss keeps its label. |
| 3 | Strip visibility | T6 | Solar row + coastal in `pad(0.12)` → strip in DOM; astro row → absent; inland viewport → absent AND `.wf-map-counts-footer`'s computed `bottom` is `8px`. |
| 4 | `--tsh` clearance | T6 | Stub `offsetHeight` 166 and 34; assert the footer's `bottom` ≥ strip height + 16 in both; in the browser, `getBoundingClientRect()`: footer bottom edge − strip top edge ≥ 16 at 1280×800, both states. |
| 5 | Chart mapping | T6 | For each served solar window: dot `top` (percent) equals `TY(windowLevel)` within 0.1; `left` equals `windowPosition·100`; and the dot lies on the drawn path — sample the served `curve` at `windowPosition` (linear between the two nearest samples) and assert `|sample − windowLevel| ≤ 0.02` (the curve is 30-minute samples of the same cosine the level came from). |
| 6 | Next fit is real | T3/T5/T6 | For every returned row, the slot it was chosen for is served `tideAligned === true`; where no row is returned the copy contains `beyond`; no row is ever the current one or an earlier one. |
| 7 | Contrast | T6 | Every text node in the strip against `rgba(20,15,12,.94)` composited over the basemap's darkest sampled tile; ≥ 4.5:1 each; put the table in the phase log. Composite the text's own alpha (INCREMENT_sheet_and_tide §6's trap). |
| 8 | Phone | T7 | At 390×844: strip's bottom edge < bar's top edge; footer `display: none` while the strip is on; scored legend bottom edge ≥ strip top + 8; the callout card's bottom ≤ strip top. |

Checks this plan adds, because they guard decisions the spec does not know about:

| # | check | phase | how |
|---|---|---|---|
| 9 | The strip and the slot agree | T1 | `TideSurfaceAgreementTest`: for the representative location on one window, slot `tideLevel` = window `windowLevel` ± 0.01 and directions equal, from one set of extremes. |
| 10 | The lift changed nothing | T1 | `WindowTideRollupBuilderTest` passes with **zero** assertion edits after `TideCurveCalculator` is extracted; `git diff --stat` on that file is 0. |
| 11 | No fact twice | T5 | On a gated miss the card's `textContent` contains the offset clause exactly once and the "wants" clause exactly once. |
| 12 | A star is never re-coloured | T4 | On a miss chip, `.wf-maplab-chip-r`'s computed `color` equals an aligned chip's; the swatch's background equals the ramp's for the same rating. |
| 13 | Gated ≠ unknown | T4 | With `showUnrated` false: a gated coastal location renders; an inland unrated one does not; the counts footer's `+ unknown` is absent on the first fixture. |

---

## §8 Phase → session map

| session | phase | size | depends on |
|---|---|---|---|
| 1 | T1 — per-slot tide facts (backend) | M | — |
| 2 | T2 — strip window facts (backend) | S/M | — (independent of T1; shares the lift if T1 has merged) |
| 3 | T3 — plumbing and `mapTideFit` (frontend) | M | T1, T2 (field names) |
| 4 | T4 — the chip, both layers | L | T3 |
| 5 | T5 — callout + sheet block, the jump | M/L | T3 (independent of T4; may run in parallel) |
| 6 | T6 — the strip | L | T3 (independent of T4/T5) |
| 7 | T7 — phone | S/M | T6 |
| 8 | T8 — sweep and docs | S | all |

T1 and T2 can run as two sessions in parallel; T4, T5 and T6 can too, once T3 has merged — they
touch different files (`MapLabels`/`PinsLayer`/`mapLabels.js` · `MapCallout`/`LocationFourDaySheet`/
`TideFitBlock` · `MapTideStrip`/chrome CSS). `MapView.jsx` is the one shared file; each phase adds a
prop or a mount, and merge conflicts there are mechanical.

---

## §9 Local verification recipe

Backend on **8083** (not the 8082 CLAUDE.md records elsewhere):

```bash
cd backend && ./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local
```

⚠️ Another session may already hold 8083 and serve **its own empty H2** — check before assuming a
wall of 401s is your bug; run yours on a free port with
`-Dspring-boot.run.jvmArguments=-Dserver.port=8093` (a bare `-Dserver.port` on the Maven command line
does not reach the forked app JVM) and clear `localStorage`. Frontend: `cd frontend && npm run dev`
with `frontend/.env.local` carrying `VITE_API_TARGET=http://localhost:8083` (gitignored) or the Vite
proxy 502s at login. Sign in as `admin` / `golden2026`. A stale `backend/data/goldenhour.mv.db` may not
boot after schema changes — move it aside, do not delete.

**A local DB has no coastal locations, no tide extremes and no ratings, so every state this increment
renders needs a fixture.** ⚠️ Do **not** trigger `POST /api/forecast/run` — it bills the real Anthropic
key. Seed instead, in this order (all deterministic; gloss generation fails soft with an invalid key):

1. `scripts/dev-seed-locations.sh` for the catalogue (BASE defaults to 8083).
2. Make **at least three** locations coastal with **different wants**, via the H2 shell while the
   backend runs (`jdbc:h2:file:./data/goldenhour;AUTO_SERVER=TRUE`, user `sa`, empty password, jar
   from `~/.m2/repository/com/h2database/h2/`): `INSERT INTO location_tide_type (location_id,
   tide_type) SELECT id, 'LOW' FROM locations WHERE name = 'Bamburgh Beach'`, one `'HIGH'`, and one with
   two rows (`'HIGH'` and `'LOW'`) so the null-shortfall arrow case (§5 #4) is reachable. Each must
   also carry `SEASCAPE` in `location_location_type` or the admin editor will show the chips disabled.
3. Tide extremes: insert a 12h25 cycle of alternating `HIGH`/`LOW` rows into `tide_extreme
   (location_id, event_time, height_metres, type, fetched_at)` for each coastal location, **UTC**
   `event_time`, spanning today −2 to today +5 days (the rollup brackets half a cycle each side and
   `deriveDualWindowTideData` queries ±2 days). Offset one location's cycle by ~3 hours from another's
   so one is aligned at a sunrise the other misses — that is the mid-water morning the design opens
   on. Heights 0.9–4.3 m give the design's `2.6 m of 4.3 m`.
4. Seed `cached_evaluation` rows for the region's **non-coastal** spots (recipe at the bottom of the
   seed script) so the honesty filter does not blank the region, then **restart** (rehydrated at
   startup only). Optionally rate the aligned coastal location too, so an aligned chip carries a star.
5. `POST /api/briefing/run` (ADMIN); `GET /api/briefing` and confirm, per coastal slot, `tideState`,
   `tideAligned`, `evaluationGate` (on the miss), and after T1 the five new fields; per window, `tide`
   with `curve`/`windowLevel` and after T2 the positions and extremes.
6. Browser login: obtain the JWT via `POST /api/auth/login` with curl and seed `localStorage` keys
   `goldenhour_token/refresh/role/must_change/username/refresh_expires` on `http://localhost:5173`,
   then reload — never type the password into the page.
7. Before T4 lands, the miss location is hidden by default: Filters → "Toggle locations with no
   evaluation". After T4 it must appear without that toggle — that is check 13.
8. Recompiling via `./mvnw test` while `spring-boot:run` is up swaps `target/classes` under a live
   JVM → `NoSuchMethodError` on the serve path. Restart after any backend edit.

⚠️ **The Browser pane can wedge permanently.** The fallback every phase of the map series shipped with
is headless Chromium driven through `playwright-core` directly, not `npm run test:e2e`. It is also the
only instrument for checks 4, 7 and 8 — jsdom resolves specificity but not `var()`, and the Browser
pane composites nothing while hidden.

State plainly which claims were **seen** and which were **tested**.
