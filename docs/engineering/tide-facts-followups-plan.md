# Window tide facts: the three follow-ups

Status: **plan, not built.** Draft 1, 2026-10-11. This plan follows the window tide facts series
(`window-tide-facts-plan.md`, P0–P5, released in v2.25.0), which left three items for separate PRs:

| # | Item | Kind | Needs an owner decision first |
|---|---|---|---|
| A | The honesty filter's "Too unsettled to forecast" wording on windows that are unscored by design | user-visible fix | the wording (D1) |
| B | The briefing build reads `tide_extreme` about 1,220–1,400 times | internal efficiency | none |
| C | Removing the inert `evaluationGate` seam and the callerless Close-to-home stack | deletion | whether to delete each (D2, D3) |

Each item is written as a self-contained brief: a Sonnet subagent builds it from the text alone. The decisions it needs are
made here, not by the builder, and so are the hazards, tests, mutants and gates.

**Order.** B is independent and can start at once. C and A both touch `ServedBriefingAssembler` (C3 folds
`assembleWithoutPlan` away; A adds a reason pass to the serve path), so **C3 lands before A**, and A is built on the result.
C1 and C2 are independent of everything.

**Common rules for every PR** (stated once, applied to all):
- One PR per numbered phase, each mergeable alone, with a `changelog.d/YYYYMMDD-<slug>.md` entry (see `changelog.d/README.md`).
- CLAUDE.md's review cadence: build, tests, adversarial review by read-only reviewers, fix, re-verify, commit, PR, CI and Codex
  👍 on the head sha, merge.
- Backend gate (no JDK 25 on this Mac: JDK 23 with `-Djava.version=23 -Dmaven.compiler.enablePreview=true`,
  `MAVEN_OPTS=--enable-preview`, and exclude `EvaluationModelTest`):
  `./mvnw clean verify --batch-mode --no-transfer-progress -Dtest='!**/integration/**,!EvaluationModelTest' -DfailIfNoSpecifiedTests=false`,
  plus `./mvnw checkstyle:check`, gated on the exit code, never on a grep.
- Frontend gate: `npm run lint && npx vitest run && npm audit --audit-level=high && npm run build`.
- Mutation checks restore by editing the file, never with `git checkout` or `git stash`.
- A full run that fails one timing-sensitive test while other builds load the machine (seen: `AnthropicApiClientAskWireTest`,
  `AskShellKey.test.jsx`) is rerun on its own before it counts as a failure.

---

## A. Honest wording for windows that are unscored by design

### A.1 The defect

`BriefingHonestyFilter.fullRewrite` (`service/BriefingHonestyFilter.java`) runs on the API read path for any region with
`scoreable > 0` and `scoredLocationCount == 0`. It empties the slots and stamps:

| Field | Value |
|---|---|
| `displayVerdict` | `STAND_DOWN` |
| `verdictLabel` | `"Too unsettled to forecast"` |
| `summary` | `"No per-location forecast — conditions too unsettled to evaluate"` |
| `glossDetail` | `"Conditions across this region were classified as too unsettled to evaluate confidently at this horizon. …"` |

The filter was written for a failed batch, but zero coverage is the designed state of several windows:

- **T+4 and later**: never scored by Gate 4.
- **T+3** unless the cell is SETTLED.
- **T+2 UNSETTLED**.
- **Travel days**: their batch is skipped.

For those windows every one of these statements is false. They are also false for a failed batch, which is a failure, not
weather. They are true only for a region whose slots were all weather-triaged (`SKIPPED_TRIAGED`).

**What a reader sees today:**

| Window | Surface | What it says |
|---|---|---|
| Unscored T+3 | Plan card | **"Poor"** (`VERDICT_LABEL.STAND_DOWN` from the window verdict) over a hatched plate |
| Unscored T+3 | Plan popup prose (`WindowProseSlot`, via `glossDetail`) | the "too unsettled" detail |
| Any blanked region | Map callout and location-sheet fallbacks (`regionGloss`) | the "too unsettled" detail |
| Any blanked region | Map region panel | the "too unsettled" detail |
| Any blanked region | Regional planner drill-down (`HeatmapGrid`) | the "Too unsettled to forecast" pill and summary |
| Any blanked region | Ask (`AskTools`) | `STAND_DOWN` |
| Any blanked region | Digest | `STAND_DOWN` |

The Map pill and landing card already render nothing for such a window.

### A.2 Design

Each region gets a **coverage reason** on the serve path. `fullRewrite` uses it to choose its wording and verdict.

- **The reason comes from what the pipeline actually did**: the latest non-`SKIPPED_CACHED` disposition for each of the region's
  slots.
  - The serve path already loads exactly that row per slot, in `EvaluationViewService.loadTriagedByBatch`
    (`findLatestNonCachedDispositions`).
  - It then collapses each row to a boolean, "was it `SKIPPED_TRIAGED`", and throws the category away.
  - Keeping the category costs **no new query**.
- A horizon rule (`ForecastHorizon.daysAhead`, the assembler already holds `clock`) covers a slot with no disposition at all,
  where T+4 and later means "not attempted".
- Travel days are written as `SKIPPED_TRAVEL_DAY` dispositions, so the same read covers them.

**`CoverageReason` (new enum, `model/CoverageReason.java`)**: the fold over a region's scoreable slots. The first matching rule
wins, in this order:

| Value | When |
|---|---|
| `AWAY` | every scoreable slot's latest disposition is `SKIPPED_TRAVEL_DAY` |
| `NOT_ATTEMPTED` | every scoreable slot's latest disposition is `SKIPPED_STABILITY`, or the slot has no disposition and its date is T+4 or later |
| `STOOD_DOWN` | every scoreable slot's latest disposition is `SKIPPED_TRIAGED` |
| `FAILED` | any scoreable slot's latest disposition is `SUBMISSION_FAILED` or `SKIPPED_ERROR`, and none of the above applies |
| `UNKNOWN` | anything else: a mix, `EVALUATED` with no result yet (in flight or expired), or no disposition at T+0–T+3 |

The rule is fixed in that order: mixed-category regions fall to `UNKNOWN`, never to a guess. `UNKNOWN` keeps **today's
behaviour exactly**, so the change can only make a region more honest, never less.

**Wording.** These are **owner decision D1**; the defaults below stand unless changed.

| Reason | `displayVerdict` | `verdictLabel` | `summary` | `glossDetail` |
|---|---|---|---|---|
| `AWAY` | `AWAITING` | `"Away — not forecast"` (matches the client's `AWAY_STATE_LABEL`) | `"Away this day — no forecast was made"` | `"You're away this day, so no per-location forecast was made for it."` |
| `NOT_ATTEMPTED` | `AWAITING` | `"Not scored at this range"` | `"Too far ahead to score — forecasts firm up nearer the day"` | `"Locations this far ahead are only scored when the weather pattern is settled. This one isn't yet, so no per-location forecast exists. It will be scored as the day gets closer."` |
| `STOOD_DOWN` | `STAND_DOWN` | `"Too unsettled to forecast"` (today's, true here) | today's | today's |
| `FAILED` | `AWAITING` | `"Forecast unavailable"` | `"No per-location forecast — the forecast run didn't complete"` | `"The forecast run for this window didn't complete, so there is no per-location forecast. It will be retried at the next run."` |
| `UNKNOWN` | `STAND_DOWN` | today's | today's | today's |

- **`AWAITING`, not `STAND_DOWN`, for the three not-weather reasons.** The client already renders `AWAITING` as a neutral
  "Not scored" everywhere: the card word, the popup chip, `VerdictPill`, the strip and the map panel. The Plan card then stops
  saying "Poor".
- **`PlanWindowProjector.verdict()`** takes the top region's verdict, so an all-`NOT_ATTEMPTED` window becomes `AWAITING`. That
  reaches Ask and the digest with no change to either.
- **The slots stay emptied for every reason.** The filter's `blanked` set (`BriefingHonestyFilter`, ~:154: a region counts as
  blanked when `rewritten != region && rewritten.slots().isEmpty()`) still drives Best Bet withdrawal. That predicate must keep
  meaning "a region with zero coverage".

### A.3 Where it is built

- **A1 (backend).** The serve path computes the reason and the filter uses it.
  - `EvaluationViewService`: `getTriagedByBatchLocationNamesBulk` keeps its current contract for existing callers. Add a sibling
    `getLatestDispositionsBulk(start, end, types)` returning `Map<String /* region|date|type */, Map<String /* location name */, DispositionCategory>>`.
    It is built from the same `findLatestNonCachedDispositions` rows, so it costs **no extra query** if
    `ServedBriefingAssembler.reEnrichVerdicts` reads both from one load: refactor `loadTriagedByBatch` so that both views come
    from one query result. The ⚠️ CLAUDE.md note about the `NOT EXISTS` anti-join form stays true; do not touch the SQL.
  - `ServedBriefingAssembler.reEnrichVerdicts` passes the per-region disposition map to the filter. `BriefingHonestyFilter.apply`
    takes it, through a new overload (keep the old one for its 700-line test, defaulting every region to `UNKNOWN`), plus
    `LocalDate today`.
  - `CoverageReasons.of(region, dispositionsByLocation, daysAhead)` is a new package-private static fold: pure, unit-tested, with
    the table above as its spec.
  - `fullRewrite(r, reason)` picks the row. It still uses the 15-arg `BriefingRegion` constructor, which the P0 wither guard
    cannot see, so add a test asserting that every field `fullRewrite` does not set is kept, per reason.
  - **No new `BriefingRegion` component.** The reason only chooses the rewrite's words and verdict; nothing downstream needs
    the enum, and adding a component means the wither guard plus legacy-payload work for no reader. If a later client needs it,
    add it then.
  - Stale text to fix:
    - the `[ZERO COVERAGE]` log in `BriefingRegionEvaluationRollup` (~:177–184), which says the rewrite "should only fire on
      batch failures";
    - the garbled comment tail at `BriefingHonestyFilter` ~:259–262;
    - the comments at `PlanWindowProjector` ~:249 and `BriefingWindow` ~:63, which say the filter writes `STAND_DOWN`.
- **A2 (frontend), only if A1's review finds a client string that still says "Poor" or "too unsettled" for an `AWAITING`
  region.** The research found none: every surface reads `VERDICT_LABEL[tier]` or the served prose. The heat-strip footer
  (`heatField.js` ~:497–503, `WindowFirstHeatStrip` ~:1610–1622) says the client "cannot tell a stability skip from … a failed
  batch". With A1 it no longer has to; reword that comment, with no behaviour change. If nothing else is found, fold A2 into
  A1's PR as that comment change.

### A.4 Tests (A1)

- **`CoverageReasonsTest`.** Every row of the fold table, plus:
  - mixed categories give `UNKNOWN`;
  - a T+4 slot with no disposition gives `NOT_ATTEMPTED`;
  - a T+3 slot with no disposition gives `UNKNOWN`;
  - a T+4 slot that was `SKIPPED_TRIAGED` gives `STOOD_DOWN`, not `NOT_ATTEMPTED`, because triage runs before Gate 4;
  - canopy and other non-scoreable slots are ignored;
  - a location renamed since its disposition (name-keyed join miss) gives `UNKNOWN`.
- **`BriefingHonestyFilterTest`.**
  - The existing 32 cases pass unchanged through the old overload (`UNKNOWN` = today).
  - New per-reason cases cover the verdict, the three strings, emptied slots, and the region staying in the blanked set, so a
    Best Bet on it is still withdrawn.
  - A further case checks that every field `fullRewrite` does not set is preserved.
- **`ServedBriefingAssemblerTest`.**
  - The production shape end to end: a T+4 region with `SKIPPED_STABILITY` dispositions serves `AWAITING` with the
    `NOT_ATTEMPTED` wording.
  - A travel-day region serves `AWAY`.
  - A `SUBMISSION_FAILED` region serves `FAILED`.
  - The window verdict follows: `PlanWindowProjector` gives the window `AWAITING`.
  - The query count is unchanged. Assert the repository's `findLatestNonCachedDispositions` is called **once** per serve.
- **`EvaluationViewServiceTest`.** The new bulk view, and that the old boolean view is unchanged.
- **Mutants:**
  - the fold returns `UNKNOWN` always;
  - `AWAY` checked after `NOT_ATTEMPTED`;
  - the horizon threshold at T+3 instead of T+4;
  - `fullRewrite` ignores the reason;
  - a not-weather reason keeps `STAND_DOWN`;
  - a non-`UNKNOWN` reason drops the region from `blanked`;
  - a second disposition query.

### A.5 Not changed

- Close-to-home, if C3 has not removed it.
- The `TideFitBlock` heading (P2).
- The phone Auto tide rule.
- The `[ZERO COVERAGE]` metric's existence. Only its wording changes.

---

## B. One `tide_extreme` read per briefing build

### B.1 The cost today

`BriefingService.doRefreshBriefing` loops over location × 5 dates × sunrise/sunset. For each coastal slot,
`BriefingSlotBuilder.calculateTideData` triggers these reads of the same location's rows:

| Read | Path | When |
|---|---|---|
| Q1 | `TideFactDeriver.derive` → `TideService.deriveDualWindowTideData` → `fetchExtremesAround`, `[solar−2d, solar+2d]` | every coastal slot |
| Q2 | `BriefingSlotBuilder.curveFacts`, the **identical** query again | every slot where Q1 returned rows |
| Q3 | `TideService.nearestMidpointOffsetMinutes`, the same query again | MID-preference slots only |
| S | `getTideStats` (2–3 queries) | memoised per location per UTC day (`DayScopedMemo`) |

That is about 1,220 to 1,400 queries per build across 61 coastal locations. One bulk query
(`findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc`, already used by `WindowTideRollupBuilder`) returns all of it: about
35 rows per location, about 2,100 rows in total. Q3 also carries a recorded TOCTOU risk (`TideFactDeriver` ~:107–119): a
refresh landing between Q1 and Q3 can make `tideAligned` and the quality figure disagree. One snapshot removes it.

### B.2 Design

- **`TideExtremeSnapshot`** (new, `service/TideExtremeSnapshot.java`): an immutable per-build view.
  - `static TideExtremeSnapshot load(TideExtremeRepository repo, Collection<Long> locationIds, Instant from, Instant to)` makes
    one bulk query and groups the rows by location, ordered ascending.
  - `List<TideExtremeEntity> between(long locationId, LocalDateTime from, LocalDateTime to)` filters with **inclusive bounds on
    both ends**, exactly as the repository's `BETWEEN` does, and keeps ascending order.
  - It is never cached across builds; it lives for one `doRefreshBriefing` call. **No eviction rule applies**, because nothing
    outlives the build. A `tide_refresh` write landing mid-build is seen by the next build instead of by some of this build's
    slots, which is more consistent than today.
- **The window.** Use the earliest date in the build at `00:00 Europe/London` minus 3 days, to the latest date at `24:00` plus
  3 days, converted to UTC. That is wider than every `[solar ± 2d]` window any slot can ask for, with a day's margin. A test
  pins that every slot's window falls inside it.
- **Threading.** These methods gain **overloads** taking a `TideExtremeSnapshot`; the existing signatures stay for every other
  caller:
  - `BriefingSlotBuilder.buildSlot` and `calculateTideData`;
  - `TideFactDeriver.derive`;
  - `TideService.deriveDualWindowTideData`;
  - `TideService.nearestMidpointOffsetMinutes`;
  - `curveFacts`.

  The other callers, which must not change behaviour, are:
  - `ForecastDataAugmentor`, `CoastalTideFactsBuilder` and the sync engine;
  - `PromptTestService` and `ModelTestService`;
  - `TideRunBuilder`, `TideSizeIndex`, `SurgeRunDayBuilder` and `TopicDailyLogJob`.

  Each old method delegates to a shared private core that takes the rows, so both paths run the same logic. With a snapshot
  the core reads `snapshot.between(…)`; without one it queries exactly as today.
- **`BriefingService.doRefreshBriefing`** loads the snapshot once, before the slot loop, for the coastal locations in the roster
  (`LocationService.isCoastal`), and passes it to every `buildSlot`.
- **`getTideStats`** is unchanged: it is already memoised.
- **Measure.** `doRefreshBriefing` logs only a total. Add one DEBUG line around the slot loop (`slotLoopMs`, `tideQueries` if
  cheaply countable). A query-count test is the real proof (below).

### B.3 Tests

- **Equivalence (the core proof).** For a fixture of several coastal locations, every tide-type mix (including MID), and dates
  that cross a GMT/BST change, build every slot twice: once on the old path (repository per slot) and once through a snapshot.
  Assert the resulting `TideInfo` is **equal** for every slot. Use a fake in-memory `TideExtremeRepository`, or a mock answering
  from a list with real `BETWEEN` semantics, so both paths read the same data.
- **Query count.** With the repository mocked:
  - one `buildSlot` loop over N coastal slots calls the bulk method exactly once;
  - the per-location method zero times;
  - `getTideStats` at most once per location.
  If feasible, also add a Hibernate-statistics test in the shape of `controller/ForecastHistoryQueryCountTest`
  (`@DataJpaTest`, `generate_statistics=true`) over a small seeded roster: assert the build-loop query count does not grow with
  the number of dates.
- **`TideExtremeSnapshot` unit tests:**
  - inclusive bounds at both ends;
  - ordering;
  - unknown location gives an empty list;
  - an empty roster makes no query.
- **Existing tests.**
  - `TideFactDeriverTest`, `TideServiceTest` and the other callers' tests pass **unchanged**, because the old signatures and
    their exact repository calls are kept.
  - `BriefingSlotBuilderTest` stubs the per-location window in four places (~:1680, :1783, :1833, :1873) and runs under strict
    stubs. Its build-path cases move to the snapshot overload (a fixture change, not an assertion change); keep one case on the
    old overload.
  - `TideSurfaceAgreementTest` wires both builders over one mocked repository. Give it the snapshot for the slot side and keep
    its assertion.
- **Mutants:**
  - an exclusive upper bound in `between`;
  - the snapshot window shrunk to ±1 day;
  - `buildSlot` ignoring the snapshot and querying;
  - `curveFacts` re-querying;
  - descending order.

### B.4 Phase

**B1**, a single backend PR. No frontend, no migration, no behaviour change, proven by the equivalence test.

---

## C. Removing the inert seam and the callerless endpoint

### C.0 Decisions (owner)

- **D2: Close-to-home.** Delete the stack?
  - Recommendation: **delete**.
  - It has had no caller since v1 retirement D2.
  - "Near me tonight" for a widget or Web Push would be a new design. `plan-panel-data-contracts.md` §4 says it would not be a
    port.
  - A future client would more likely reuse `/api/briefing/digest` with a per-user reach join.
  - Keeping it costs about 2,100 lines, an extra assembly path, and the P5 asymmetry.
- **D3: `evaluationGate`.** Delete the seam? It was kept deliberately for "the next hard physical constraint" (recorded in
  `BriefingGatingPolicy`, `BriefingSlot`, `tide-window-plan.md`).
  - Recommendation: **delete the frontend readers and the slot field; keep a trimmed `BriefingGatingPolicy` only if D3 says
    keep**.
  - It has been inert since 2026-09-18.
  - The client half has no functional reader (`tideGated` is written and never read; `gated` is unread).
  - A future hard constraint would want a design informed by the tide gate's history, not this field revived.
- **D4: `SKIPPED_HARD_CONSTRAINT`.** Keep the enum constant as a historical category: its rows are pruned within 30 days, and
  the admin breakdown shows unknown keys raw anyway.
  - Recommendation: **keep the constant, with a javadoc that it is historical**.
  - Retiring it saves nothing and makes old rows render as raw keys.

If D2 or D3 is "keep", skip that phase.

### C.1 Phases

- **C1: frontend `evaluationGate` readers** (only if D3 = delete).
  - **What goes:**
    - `buildEvaluationGateIndex`;
    - `gate` on `buildSlotIndex` entries and `gated` on the tide index entry;
    - the `evaluationGateIndex` prop chain (`WindowFirstMapPane` → `MapView` → `MapCallout`, `LocationFourDaySheet`);
    - `MapCallout`'s gate row and its "Not scored" versus "yet" branch;
    - the location sheet's `row.gate`, gate row and no-prose fallback;
    - `MapView`'s `tideGated`;
    - the gate CSS (`.wf-loc-gate*`, `.wf-callout-gate*`, the dim rule).
  - **Wire-safe:** the key has always been absent since 2026-09-18.
  - **Wording:** the "Not scored" versus "Not scored yet" choice collapses to "Not scored yet". That is what every slot reads
    today, because no slot is gated.
  - **Tests:**
    - delete the gate-specific blocks (`locationSheet.test.js` ~1312–1450, `MapCallout.test.jsx` ~527–575 and ~1345–1420,
      `LocationFourDaySheet.test.jsx` ~865–985 and :476–478, `MapViewHeat.test.jsx` ~888–1010);
    - adjust `WindowFirstMapPane.test.jsx` ~355–370.
  - **No new behaviour:** prove it with the full suite and a grep that `evaluationGate`, `gated` and `tideGated` are gone from
    `src`.
- **C2: backend `evaluationGate`** (only if D3 = delete).
  - **What goes:**
    - the `BriefingSlot.evaluationGate` component, `withEvaluationGate`, and the field's parameter in the 17-arg constructor (the
      constructor becomes 16-arg, and its 9 test call sites change);
    - the `BriefingSlotBuilder` seam block (~:263–277);
    - `couldCarryRating` collapses to `claudeRating != null || !canopy`;
    - `BriefingGatingPolicy` and `BriefingGatingPolicyTest`: delete them if D3 removes the seam entirely, and inline their
      always-true predicates at `BriefingGlossService` (:200, :264), `ForceEvalHeadlineSelector` (:153) and
      `BriefingCandidateCollector` (:231–250, a dead branch);
    - the `RecordWitherPreservationTest` row;
    - `DailyBriefingResponseJsonTest`'s gate cases (~761–802).
  - **Replace the "legacy payload reads null" case** with one asserting a legacy payload **carrying** an `evaluationGate` key
    still deserialises. Do this by adding `@JsonIgnoreProperties(ignoreUnknown = true)` on `BriefingSlot` only, never
    mapper-wide.
    - The persisted `daily_briefing_cache` row is overwritten every cycle and has carried no such key since 2026-09-18, so the
      real exposure is nil.
    - But `AppConfig`'s Jackson 2 mapper keeps `FAIL_ON_UNKNOWN_PROPERTIES` at its default (true). An unknown key would make
      `loadPersistedBriefing` throw and leave the Plan tab empty until the next build.
    - The annotation makes that impossible at the cost of one line.
  - **Keep `DispositionCategory.SKIPPED_HARD_CONSTRAINT`** (D4), with its javadoc corrected to "historical".
  - **Also update:** the honesty-filter comments, the `CycleLocationOutcomeResolver`, `SupersedingDispositionService` and
    `BatchRetryService` comments, and the docs (CLAUDE.md :177, :387, :575, :581; `tide-window-plan.md`'s seam sentences;
    `window-tide-facts-plan.md` :210).
  - **Mutants:**
    - `couldCarryRating` still counting a gate;
    - `ignoreUnknown` removed;
    - a canopy slot counted as scoreable.
- **C3: Close-to-home** (only if D2 = delete).
  - **What goes:**
    - `CloseToHomeController` and its test;
    - `CloseToHomeService` and `CloseToHomeResponse` and the service test;
    - `AbstractControllerTest`'s mock bean (:245–246);
    - `BriefingService.getServedBriefing()` and its three tests (`BriefingServiceTest` ~3490–3517, ~3870–3893);
    - the `HttpCachingConfigTest` row for the path (:117–120). Keep another personal-data path pinned there.
  - **Fold `ServedBriefingAssembler.assembleWithoutPlan` into `assembleForPlan`,** its one remaining caller.
    - Reword the ordering comment (~:129–135) and the P5 `stripSlotTide` javadoc, which loses its exception.
    - Remove `ServedBriefingAssemblerTest`'s `cacheAndCloseToHomePathKeepSlotTide`.
    - Keep a test that the raw cache keeps slot tide.
  - ⚠️ **`photocast.close-to-home.radius-miles` stays.** `AskService` (:151) reads it. Leave the property name; renaming it is a
    separate config change touching every profile.
  - **Keep** `UserSettingsService.getHomeLocation`, `localRadiusMiles` and the `--color-close-to-home` CSS token: all are live.
  - **Docs:**
    - CLAUDE.md (:147, :177, :370–371, :393, :1022);
    - `plan-panel-data-contracts.md` §4;
    - `v1-retirement-plan.md` §8.3, which gets a resolution line;
    - `served-briefing-assembler-plan.md`;
    - `window-tide-facts-plan.md`.
  - **Changelog:** a `### Removed — …` entry.
  - **Mutants:**
    - the fold drops the honesty filter;
    - the fold drops the best-bet fallback;
    - the fold drops movement.

### C.2 Line counts

These are estimates from the research and will be measured in each PR.

| Phase | Approximate lines removed |
|---|---|
| C1 | ~600 (~150 source and CSS, ~450 tests) |
| C2 | ~900 |
| C3 | ~2,100 |

---

## D. Sequencing and parallelism

1. **B1, C1 and C2** start together: B1 is backend tide, C1 is frontend, C2 is backend slot and gating. C1 and C2 touch
   different layers. C2 and B1 both touch `BriefingSlotBuilder`, but different methods (the seam block at ~:263–277, and
   `calculateTideData` and `curveFacts`), so expect at most a trivial merge.
2. **C3** next, after C2 merges, because both edit `ServedBriefingAssemblerTest` and nearby docs.
3. **A1** last, built on C3's folded assembler.

## E. Open owner decisions

| | Question | Default |
|---|---|---|
| D1 | The wording table in A.2 | as written |
| D2 | Delete Close-to-home | delete |
| D3 | Delete the `evaluationGate` seam (client and slot field; trimmed or removed gating policy) | delete client and field, remove the policy |
| D4 | Keep `SKIPPED_HARD_CONSTRAINT` as a historical category | keep |
