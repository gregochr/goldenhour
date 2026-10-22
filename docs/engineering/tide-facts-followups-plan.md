# Window tide facts: the three follow-ups

Status: **plan, not built.** Draft 4, 2026-10-12 (the A1 review's fold revision in §A.2); draft 3, 2026-10-12 (a Codex finding folded into §A); draft 2, 2026-10-11, after an adversarial review: three reviewers, one per item, each refuting
its own charges, all "ready with fixes". §F records what the review changed.

The window tide facts series (`window-tide-facts-plan.md`, P0–P5, released in v2.25.0) left three items for separate PRs:

| # | Item | Kind | Owner decision first |
|---|---|---|---|
| A | The honesty filter's "Too unsettled to forecast" wording on windows that are unscored by design | user-visible fix | the wording (D1) |
| B | The briefing build reads `tide_extreme` about 1,220–1,400 times | internal efficiency | none |
| C | Removing the inert `evaluationGate` seam and the callerless Close-to-home stack | deletion | whether, and how much (D2, D3) |

Each item is a self-contained brief for a Sonnet subagent. Every decision, hazard, test and mutant is stated here, so the
builder decides nothing.

**Common rules for every PR:**
- One PR per numbered phase, each mergeable on its own, with a `changelog.d/YYYYMMDD-<slug>.md` entry (`changelog.d/README.md`).
- CLAUDE.md's review cadence: build, tests, adversarial review by read-only reviewers, fix, re-verify, commit, then a PR. Merge
  on green CI plus a Codex 👍 on the head sha.
- **Backend gate.** There is no JDK 25 on this Mac, so run on JDK 23 with `JAVA_HOME=…/openjdk-23.0.2`,
  `MAVEN_OPTS=--enable-preview`, `-Djava.version=23 -Dmaven.compiler.enablePreview=true`, and exclude `EvaluationModelTest`:
  `./mvnw clean verify --batch-mode --no-transfer-progress -Dtest='!**/integration/**,!EvaluationModelTest' -DfailIfNoSpecifiedTests=false`
  plus `./mvnw checkstyle:check`. Gate on the exit code, never on a grep.
- **Frontend gate:** `npm run lint && npx vitest run && npm audit --audit-level=high && npm run build`.
- **Mutation checks** are restored by editing the file, never with `git checkout` or `git stash`.
- **Flaky timing tests under load.** A full run that fails one timing-sensitive test while other builds load the machine
  (`AnthropicApiClientAskWireTest` and `AskShellKey.test.jsx` have done this) is rerun alone before it counts as a failure.
- **Line numbers** below are from `main` at `0bd19aec`. Re-find them after any earlier phase in this plan has merged.

---

## A. Honest wording for windows that are unscored by design

### A.1 The defect

On the API path, `BriefingHonestyFilter.rewriteRegionByCoverage` calls `fullRewrite` for any region with `scoreable > 0`
(`BriefingSlot::couldCarryRating`) and `scoredLocationCount == 0`. `fullRewrite` empties the slots and stamps these values:

| Field | Value |
|---|---|
| `displayVerdict` | `STAND_DOWN` |
| `verdictLabel` | "Too unsettled to forecast" |
| `summary` | "No per-location forecast — conditions too unsettled to evaluate" |
| `glossDetail` | "Conditions across this region were classified as too unsettled to evaluate confidently at this horizon. …" |

That wording is true only when weather triage stood the region down (`SKIPPED_TRIAGED`). It is false in four cases:
- **Gate 4 never scores the window:** all of T+4, T+3 unless the cell is SETTLED, and T+2 UNSETTLED.
- **The day is marked as a travel day:** that day's batch is skipped.
- **The run failed.**

**What readers see today.**
- On an unscored T+3 the **Plan card** says **"Poor"**. The card reads the window verdict through `VERDICT_LABEL`, and
  `PlanWindowProjector.verdict()` takes the top region's verdict.
- The "too unsettled" detail appears in the **Plan popup prose** (`WindowProseSlot`), the **Map callout and location-sheet
  fallbacks** (`regionGloss`) and the **Map region panel**.
- The **Regional planner drill-down** (`HeatmapGrid`) shows the pill and the summary.
- **Ask** and the **digest** receive `STAND_DOWN`.
- The Map pill and the landing card already show nothing for such a window.

### A.2 Design

On the serve path, each region gets a **coverage reason**. The reason comes from what the pipeline actually did: the latest
non-`SKIPPED_CACHED` disposition per slot, plus the horizon. `fullRewrite` uses it to choose the wording and the verdict.

**Where the data comes from.**
- `EvaluationViewService.loadTriagedByBatch` (via `findLatestNonCachedDispositions`) already loads exactly this row per slot
  on every serve.
- It then reduces each row to "was it `SKIPPED_TRIAGED`?" and discards the category.
- Keeping the category costs no new query, provided the assembler reads both views from one load (A.3).
- **One small extra query is needed for one case** (a Codex review of #1112). `EVALUATED`/`FORCE_EVALUATED` record only
  that a slot was submitted. When the batch later fails, expires or errors that request, `BatchResultProcessor` marks
  the batch and rewrites no disposition, so the latest disposition stays `EVALUATED` with no rating behind it. To tell
  that apart from a result still in flight, the fold needs to know whether the slot's **cycle has ended** (A.3 step 1).

**`CoverageReason`** is a new enum, `model/CoverageReason.java`. A fold over a region's **scoreable** slots (the slots
`couldCarryRating` admits) produces it. `daysAhead` is the region's horizon from the UK civil date. Let `failed` be
`SUBMISSION_FAILED` + `SKIPPED_ERROR` + `EVALUATED`/`FORCE_EVALUATED` whose cycle has ended. The rules are tried in this
order (draft 4, after the A1 review: see §F):

| # | Reason | When |
|---|---|---|
| 1 | `AWAY` | every scoreable slot's latest disposition is `SKIPPED_TRAVEL_DAY` |
| 2 | `NOT_ATTEMPTED` | `daysAhead >= 4`, no scoreable slot is `EVALUATED`, `FORCE_EVALUATED` or `SUBMISSION_FAILED`, and `SKIPPED_ERROR` slots are fewer than half (`errors*2 < total`). This holds whatever the triage mix: from T+4 Gate 4 skips everything, and a minority of collection errors does not change that. |
| 3 | `FAILED` | `daysAhead <= 3` and `failed*2 >= total` (errors count as failed) |
| 4 | `NOT_ATTEMPTED` or `STOOD_DOWN` | `daysAhead <= 3`, every scoreable slot is `SKIPPED_STABILITY`, `SKIPPED_TRIAGED` or `SKIPPED_ERROR`, and errors are fewer than half. Decided by majority: `stability >= triaged` gives `NOT_ATTEMPTED` (a tie goes there, because claiming poor weather needs a majority), otherwise `STOOD_DOWN`. All-stability and all-triaged are the special cases. The collector triages before Gate 4, so a mix is the common case. |
| 5 | `UNKNOWN` | anything else |

`UNKNOWN` covers, among other things:
- `EVALUATED` whose cycle has not ended (a batch still polling), or whose cycle cannot be found;
- `SKIPPED_NO_REFRESH_NEEDED` from the intraday cycle;
- a slot with no disposition at T+0 to T+3;
- a disposition string that `DispositionCategory.fromString` cannot parse (at T+0 to T+3);
- two different categories tied on `created_at` (at T+0 to T+3);
- a location renamed since its disposition, a name-keyed miss (at T+0 to T+3);
- a failed lookup at T+0 to T+3 (at T+4 and later a failed lookup reads `NOT_ATTEMPTED`, which is true by horizon);
- T+4 with a `SUBMISSION_FAILED`, `EVALUATED` or `FORCE_EVALUATED` slot, or errors at half or more.

**`UNKNOWN` is the safe default at T+0 to T+3 and keeps today's behaviour exactly**, so the change can only make a
region more honest, never less.

**"The cycle has ended"** means:
- the slot's disposition `job_run_id` is some `pipeline_run.disposition_job_run_id` (V163);
- that pipeline run is not `RUNNING`;
- and no `FORECAST` batch with that `pipeline_run_id` has a status outside `ForecastBatchEntity.BatchStatus.TERMINAL`.

This is the same rule `LocationFailureService` uses to defer a cycle whose batches are still polling. A retry batch
carries the same `pipeline_run_id`, so a cycle whose retry is still polling has not ended.

A slot is only ever folded here because its region has zero coverage, so it has no rating. An `EVALUATED` slot in an
ended cycle therefore produced no usable result. That covers a failed or expired batch, an errored request inside a
completed batch, and an unparseable response alike.

A job run that maps to no pipeline run counts as **not ended**. That includes an ad hoc admin submission and a cycle from
before V163.

**Wording.** This is owner decision **D1**; these defaults stand unless changed.

| Reason | `displayVerdict` | `verdictLabel` | `summary` | `glossDetail` |
|---|---|---|---|---|
| `AWAY` | `AWAITING` | "Not forecast", the client's own `AWAY_STATE_LABEL` (`utils/windowFirstStrip.js:51`) | "Marked as a travel day — no forecast was made" | "This day is marked as a travel day, so no per-location forecast was made for it." |
| `NOT_ATTEMPTED` | `AWAITING` | "Not scored at this range" | "Not scored this far ahead — forecasts firm up nearer the day" | "Forecasts this far ahead are only made when the weather pattern is stable enough, and not at all from four days out, so there is no per-location forecast for this window yet." |
| `STOOD_DOWN` | `STAND_DOWN` | today's | today's | today's |
| `FAILED` | `AWAITING` | "Forecast unavailable" | "No per-location forecast — the forecast run didn't complete" | "The forecast run for this window didn't complete, so there is no per-location forecast. It may be tried again at the next run." |
| `UNKNOWN` | `STAND_DOWN` | today's | today's | today's |

> **Amended 2026-10-21:** `UNKNOWN` now reads `AWAITING`, "Not scored", "No per-location forecast — the record does not say why": a record that cannot say why is not a weather claim, and "Poor" over a hatched field read as a rating. Only `STOOD_DOWN` keeps the `STAND_DOWN` row above.

Travel days are global, not per reader: `TravelDayService` marks dates the operator is away. That is why the AWAY copy
says "marked as a travel day", never "you're away".

**How these regions now behave.**
- **Neutral verdict.** `AWAITING` already renders as a neutral "Not scored" on every client surface: the card word, the popup
  chip, `VerdictPill`, the map panel and `HeatmapGrid`'s `regionIsPoor`. The strip's tint is null for `AWAITING`
  (`WindowFirstHeatStrip`'s `verdictTint`), so such a card loses its red tint. That is intended.
- **Slots still emptied.** Every reason still empties the slots, so the filter's `blanked` predicate (`BriefingHonestyFilter`
  :154) and Best Bet withdrawal are unchanged.
- **Nulled fields unchanged.** `fullRewrite` still nulls `confidence`, `meanRating`, `bestRating`, `meanRatingDelta`,
  `sampleSufficient` and `forcedSample`. Do not change which fields it nulls:
  - `attachMovement` depends on the null `meanRating`;
  - `verdictEligible()` depends on the null `sampleSufficient` and `forcedSample`.

### A.3 Where it is built (A1, backend)

1. **`EvaluationViewService`** gains one public method that reads the disposition query **once**:
   `ServeTriage loadServeTriage(LocalDate start, LocalDate end, Set<TargetType> types)`, where
   `record ServeTriage(Map<String, Set<String>> triagedByKey, Map<String, LatestDisposition> latestBySlot)` and
   `record LatestDisposition(DispositionCategory category, boolean cycleEnded)`. `cycleEnded` is meaningful only for
   `EVALUATED`/`FORCE_EVALUATED`, and is false for every other category.
   - `triagedByKey` is exactly what `getTriagedByBatchLocationNamesBulk` returns today: keyed `region|date|type`, built with
     the same one `findAllEnabled()`.
   - `latestBySlot` is keyed by the `stabilitySkipKey` shape (`location|date|type`), so it needs no roster join.
   - Both views come from one `findLatestNonCachedDispositions` pass. Add `d.jobRunId` as a sixth selected column and
     change nothing else in that query: the `NOT EXISTS` anti-join and its `WHERE` stay byte for byte, and the CLAUDE.md
     ⚠️ about its form stands. The existing reader (`loadTriagedByBatch`) reads columns 0–4 by index and ignores the
     sixth.
   - **The cycle query.** When at least one latest row is `EVALUATED`/`FORCE_EVALUATED`, run ONE more query over their
     distinct job-run ids, and over no others.
     - It is a new `PipelineRunRepository` method returning the ended ids. Use the shape
       `SELECT p.dispositionJobRunId FROM PipelineRunEntity p WHERE p.dispositionJobRunId IN :ids AND p.status <> RUNNING
       AND NOT EXISTS (SELECT 1 FROM ForecastBatchEntity b WHERE b.pipelineRunId = p.id AND b.batchType = FORECAST AND
       b.status NOT IN :terminal)`, with `:terminal` taken from `BatchStatus.TERMINAL`. Re-find the real field names.
     - When there is no such row, make no query.
     - If the query fails, set every `cycleEnded` to false (fail soft, so the rule reads `UNKNOWN`) and log one WARN.
   - **Fail soft, like `loadTriagedByBatch`:** on a `RuntimeException` from the disposition query, return empty maps.
   - **Ties:** a tie of different categories at the same `created_at` maps to no entry, which the fold reads as `UNKNOWN`.
     This matches the existing tie fold.
   - **Unparseable categories:** a category `fromString` cannot parse also maps to no entry.
   - `getTriagedByBatchLocationNamesBulk` stays as a thin wrapper over `loadServeTriage(...).triagedByKey()` for its other
     caller, `BriefingService.bulkTriagedResolver`.
2. **`ServedBriefingAssembler.reEnrichVerdicts`** calls `loadServeTriage` once, in place of `getTriagedByBatchLocationNamesBulk`.
   - It uses `triagedByKey` exactly as now.
   - It passes `latestBySlot` and a `Clock` (the assembler already holds `clock`) to the filter.
3. **`BriefingHonestyFilter.apply(response, minCoverageRatio, Map<String, LatestDisposition> latestBySlot, Clock clock)`**
   is a new overload.
   - The existing overloads stay and **bypass the fold** (every region reads `UNKNOWN`), so the 32 existing filter tests and
     `PlanWindowProjectorTest:1523` are unchanged.
   - `daysAhead` comes from `ForecastHorizon.daysAhead(date, clock)`.
4. **`CoverageReasons.of(List<BriefingSlot> scoreableSlots, LocalDate date, TargetType type, int daysAhead, Map<String, LatestDisposition> latestBySlot)`**
   is a new package-private static, pure. The table in A.2 is its spec. It looks up each slot by `location|date|type`.
5. **`fullRewrite(r, reason)`** picks the wording row. It keeps the 15-arg constructor and the same nulled fields.
6. **`PlanWindowProjector.BY_RANK`** (:91–103) needs a new step. Blanked regions tie on rating and count, so today the final
   tie-break is the region **name**. Its own comment records that renaming a region once flipped the window's verdict.
   - With A, one blanked region can be `AWAITING` and another `STAND_DOWN`, so the window verdict would again depend on name
     order.
   - Add a step before the name: among blanked regions, `STAND_DOWN` ranks ahead of `AWAITING`. The rule is that a region
     with a real stand-down speaks for the window over one with no forecast.
7. **Stale text:**
   - the `[ZERO COVERAGE]` log in `BriefingRegionEvaluationRollup` (:133–140), which says the rewrite "should only fire on
     batch failures";
   - the garbled comment tail at `BriefingHonestyFilter` :262 (re-find it if C2 has merged and rewritten the lines);
   - the comments at `PlanWindowProjector` ~:249 and `BriefingWindow` ~:63, which say the filter always writes `STAND_DOWN`;
   - the client heat-strip comments (`heatField.js` ~:497–503, `WindowFirstHeatStrip` ~:1610–1622), which say the client
     "cannot tell a stability skip from … a failed batch". These change wording only, with no behaviour change, and replace
     the separate A2 phase.

**No new `BriefingRegion` component.** Nothing downstream reads the reason, and a component would add work on the wither
guard and on legacy payloads.

### A.4 Tests

- **`CoverageReasonsTest`:**
  - every row of the table;
  - the `UNKNOWN` sources listed in A.2;
  - a T+4 region that is triaged and stability-skipped gives `NOT_ATTEMPTED`;
  - T+4 with one `SKIPPED_ERROR` among 20 skips gives `NOT_ATTEMPTED`; with errors at half or more it gives `UNKNOWN`;
    with any `SUBMISSION_FAILED` it gives `UNKNOWN` (never `FAILED`: rule 3 stops at T+3);
  - a T+3 mix with stability the majority gives `NOT_ATTEMPTED`; with triage the majority gives `STOOD_DOWN`; a tie
    gives `NOT_ATTEMPTED`; one error among many follows the majority;
  - a T+3 region with one `SKIPPED_ERROR` among 19 stability skips gives `NOT_ATTEMPTED` (it was `UNKNOWN` before the
    majority rule);
  - errors at half or more with no submission at T+3 gives `FAILED`;
  - a T+2 region whose slots are all `EVALUATED` with `cycleEnded` true gives `FAILED`; the same with `cycleEnded` false
    gives `UNKNOWN`;
  - a region mixing `SUBMISSION_FAILED` and ended-`EVALUATED` slots to half or more gives `FAILED`;
  - canopy slots are ignored, including at the filter (a region mixing canopy and sky slots);
  - a partial travel day (some slots away) gives `UNKNOWN`.
- **`BriefingHonestyFilterTest`:**
  - the existing cases pass unchanged through the old overloads;
  - new per-reason cases cover the verdict and the three strings, that the slots are emptied, and that the region stays in
    `blanked`, so a Best Bet on it is still withdrawn;
  - a per-reason case asserts exactly the fields `fullRewrite` carries from `r` (`regionName`, `verdict`, `tideHighlights`,
    and the four weather fields), and that the six nulled fields stay null.
- **`EvaluationViewServiceTest`** runs a real service over a mocked repository:
  - `loadServeTriage` calls `findLatestNonCachedDispositions` and `findAllEnabled` once each;
  - it calls the cycle query once, with exactly the `EVALUATED`/`FORCE_EVALUATED` rows' distinct job-run ids, and never
    when there are none;
  - a cycle-query exception leaves every `cycleEnded` false;
  - `triagedByKey` equals the old method's output on the same rows;
  - ties and unknown strings map to no entry;
  - a repository exception gives empty maps.
- **The cycle query** is checked by a `@DataJpaTest` on H2, the shape `ForecastHistoryQueryCountTest` uses, needing no
  Docker. It returns only the ended ids:
  - a `RUNNING` pipeline run is not ended;
  - one with a `SUBMITTED` FORECAST batch is not ended;
  - one whose batches are all `FAILED`/`EXPIRED`/`COMPLETED` is ended;
  - an AURORA batch still polling does not block;
  - an id with no pipeline run is absent.
- **`ServedBriefingAssemblerTest`:**
  - `loadServeTriage` is called once per serve and `getTriagedByBatchLocationNamesBulk` never;
  - production-shaped end-to-end cases cover T+4 with `SKIPPED_STABILITY` (gives `AWAITING` and the `NOT_ATTEMPTED` copy),
    a travel day (gives `AWAY`), and a majority `SUBMISSION_FAILED` at T+2 (gives `FAILED`);
  - the window verdict follows the region verdict.
- **`PlanWindowProjectorTest`:** two blanked regions, one `STAND_DOWN` and one `AWAITING`. The window verdict is `STAND_DOWN`
  whichever name sorts first, so the test renames the regions to swap the order.
- **Frontend, unchanged code:** add one `windowFirstCards` or `WindowFirstHeatStrip` test that an `AWAITING` window card
  reads "Not scored" with no tint.
- **Mutants:**
  - the fold always returns `UNKNOWN`;
  - the majority comparison flipped; a tie sent to `STOOD_DOWN`; the error-minority guard removed at T+4; the same guard
    removed at T+3; the filter passing `r.slots()` instead of the scoreable slots;
  - `AWAY` checked after `NOT_ATTEMPTED`;
  - rule 2's horizon at `>= 3`;
  - `FAILED` on any single failure;
  - `fullRewrite` ignores the reason;
  - a not-weather reason keeps `STAND_DOWN`;
  - a non-`UNKNOWN` region drops out of `blanked`;
  - the `BY_RANK` step removed;
  - `loadServeTriage` queries the dispositions twice;
  - `cycleEnded` ignored (an ended `EVALUATED` region reads `UNKNOWN`);
  - the cycle query ignoring batch status;
  - the cycle query ignoring `RUNNING`.

### A.5 Not changed

- `dayPeak`: an all-`AWAITING` day still yields `STAND_DOWN`, because `BriefingDayPeak` is documented as never `AWAITING`,
  and `peak` is write-only.
- Close-to-home, if C3 has not removed it.
- The `TideFitBlock` heading, the phone Auto tide rule, and whether the `[ZERO COVERAGE]` metric exists (only its wording
  changes).
- A stale `SKIPPED_TRAVEL_DAY` can outlive a travel day that was removed, until the next cycle. This is accepted: the
  disposition records what the pipeline did. Ask's live `TravelDayService` read is a different question.

---

## B. One `tide_extreme` read per briefing build

### B.1 The cost today

For each coastal slot, `BriefingService.doRefreshBriefing` → `BriefingSlotBuilder.buildSlot` → `calculateTideData` reads the
same location's rows several times:

| Read | Path | When |
|---|---|---|
| Q1 | `TideFactDeriver.derive` → `TideService.deriveDualWindowTideData` → private `fetchExtremesAround`, `[solar−2d, solar+2d]` (`QUERY_WINDOW_DAYS`, `TideService:59`) | every coastal slot |
| Q2 | `BriefingSlotBuilder.curveFacts`, the identical query (`CURVE_QUERY_WINDOW_DAYS`, `:50`) | when Q1 found rows |
| Q3 | `TideService.nearestMidpointOffsetMinutes`, the same query | MID-preference slots |
| S | `getTideStats` | memoised per location per UTC day |

That comes to about 1,220–1,400 queries per build. Q3 also carries a recorded TOCTOU risk (`TideFactDeriver` ~:107–119): a
write between Q1 and Q3 can make `tideAligned` and its quality disagree.

The derived query is `BETWEEN`, which is **inclusive**, ordered ascending. `event_time` is a naive-UTC `LocalDateTime`.
`UNIQUE(location_id, event_time)` (V14) means no two rows tie.

### B.2 Design

**`TideExtremeSnapshot`** (new, `service/TideExtremeSnapshot.java`) is an immutable view that lives for one build.
- **Constant:** `public static final int READ_WINDOW_DAYS = 2`, the one source for `TideService.QUERY_WINDOW_DAYS` and
  `BriefingSlotBuilder.CURVE_QUERY_WINDOW_DAYS`, both of which now reference it.
- **Window:** `static Window windowFor(List<LocalDate> dates)`.
  - `from` is `dates.getFirst().minusDays(READ_WINDOW_DAYS + 1).atStartOfDay(LONDON)` converted to UTC, as a naive
    `LocalDateTime`.
  - `to` is `dates.getLast().plusDays(READ_WINDOW_DAYS + 2).atStartOfDay(LONDON)`, converted the same way. That is the last
    date + 3 days at 24:00.
  - It is the same London-to-UTC idiom as `WindowTideRollupBuilder:229–232`.
- **Load:** `static TideExtremeSnapshot load(TideExtremeRepository repo, Collection<Long> locationIds, Window window)` runs one
  `findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc`, then groups by location in ascending order. It records
  `coveredIds` and the window. With no ids it makes no query.
- **Read:** `List<TideExtremeEntity> between(TideExtremeRepository repo, long locationId, LocalDateTime from, LocalDateTime to)`.
  - It filters **inclusively at both ends**, in ascending order.
  - ⚠️ If `locationId` is not covered, or `[from, to]` is not inside the loaded window, it **falls back to the per-location
    repository query**. Never return a silent empty list: empty means "no tide" downstream and would quietly drop a coastal
    slot to `TideResult.NONE`. Use a fallback, not an exception, because an exception would abort the whole build.
- **Lifetime:** it is never cached across builds, so **no eviction rule applies**. The build is single-threaded under
  `refreshLock`, and a `tide_refresh` write that lands mid-build is seen by the next build.

**Threading.** The existing methods stay exactly as they are, called by every other caller with exactly their current
repository and `TideService` calls. Only overloads are added:
- `TideService.deriveDualWindowTideData(Long, LocalDateTime, long, long, TideExtremeSnapshot)` (public). It reads its rows
  through `snapshot.between(…)` and shares a private core with the 4-arg method, which keeps calling `fetchExtremesAround`.
- `TideService.nearestMidpointOffsetMinutes(Long, LocalDateTime, TideExtremeSnapshot)` (public), with the same shape.
- `TideFactDeriver.derive(…, TargetType, TideExtremeSnapshot snapshot)`.
  - With `snapshot == null` it calls the **old** `TideService` methods, so `TideFactDeriverTest`'s strict stubs keep working.
  - Otherwise it calls the snapshot overloads.
  - The snapshot is threaded through the private `computeAlignmentQuality` and `offsetMinutesForWant`.
- `BriefingSlotBuilder.buildSlot(lw, date, type, horizonForecast, TideExtremeSnapshot snapshot)`, five parameters. Do **not**
  add a 4-arg `(…, snapshot)` overload: it would be ambiguous with `(…, horizon)` for a literal `null`.
  - `calculateTideData` and `curveFacts` take the snapshot.
  - `curveFacts` reads through `snapshot.between(…)` when there is one.
- `BriefingSlotBuilder.snapshotFor(Collection<LocationEntity> locations, List<LocalDate> dates)` returns the snapshot for the
  coastal ones (`locationService.isCoastal`, the same test `calculateTideData` uses). The builder already holds the
  repository, so **`BriefingService` gains no dependency**, and `BriefingServiceTest`'s six real constructions are unchanged.
- `BriefingService.doRefreshBriefing` calls `slotBuilder.snapshotFor(colourLocations, dates)` once, after the weather
  prefetch and immediately before the slot loop, and passes it to every `buildSlot`.

Add one DEBUG line around the slot loop with `slotLoopMs` and the snapshot's row count.

### B.3 Tests

- **Equivalence (the core proof).**
  - Use the real `TideService`, `TideFactDeriver` and `BriefingSlotBuilder`. Back the repository mock with `thenAnswer`
    implementing `BETWEEN` for both the per-location and the bulk method, marked `lenient`.
  - Build every slot twice: once with `snapshot == null` (the old path) and once with a snapshot. Assert the `TideInfo` is
    equal for every slot.
  - The fixtures must make the mutants observable:
    - several coastal locations, with every tide-type mix including MID;
    - dates crossing a BST/GMT change;
    - a location whose **only** extremes sit exactly at `solar−2d` and `solar+2d` (an exclusive bound turns it into `NONE`);
    - rows in `[d−2d, d−1d)` and `(d+1d, d+2d]` for the first and last build dates (a ±1-day window changes the result).
- **Fallback.**
  - An uncovered location reads through the per-location query, and the result is equal to the old path.
  - A range outside the loaded window does the same.
- **Query count**, with the repository mocked:
  - a `doRefreshBriefing`-shaped loop over N coastal slots calls the bulk method exactly once, the per-location method zero
    times, and `getTideStats` at most once per location;
  - an empty roster makes no query.

  Optionally, add a Hibernate-statistics test in the shape of `controller/ForecastHistoryQueryCountTest`: `@DataJpaTest` on
  H2 with `generate_statistics=true`, needing no Docker. Build `TideService` and `BriefingSlotBuilder` manually, with mocks
  for the non-JPA collaborators, and assert the loop's statement count does not grow with the number of dates.
- **`TideExtremeSnapshot`** unit tests:
  - inclusive bounds;
  - ordering;
  - `windowFor` over a BST/GMT-crossing date list, pinned exactly;
  - no query for no ids.
- **Existing tests.**
  - `TideFactDeriverTest`, `TideServiceTest` and the other callers' tests pass **unchanged**.
  - `BriefingSlotBuilderTest`'s four window-pinned curve stubs (:1680, :1783, :1833, :1873) stay on the null-snapshot path.
    Add snapshot-path cases beside them.
  - `TideSurfaceAgreementTest` **stays on the old path**, because it is the cross-surface window pin (:480, :484–488). Add a
    second case reading `buildSlot(…, snapshot)` over the same extremes, asserting the same agreement.
- **Mutants:**
  - an exclusive upper bound in `between`;
  - `windowFor` at ±1 day;
  - `windowFor` from UTC midnight instead of London midnight (the BST case);
  - `between` returning empty instead of falling back;
  - `buildSlot` ignoring the snapshot;
  - `curveFacts` re-querying;
  - descending order.

**B1** is a single backend PR. No frontend change, no migration, and no behaviour change, as the equivalence test proves.

---

## C. Removing the inert seam and the callerless endpoint

### C.0 Decisions (owner)

**D2: Close-to-home.** Recommendation: **delete**.
- It has had no caller since v1 retirement D2.
- A "near me tonight" widget or Web Push would be a new design (`plan-panel-data-contracts.md` §4: not a port), probably over
  `/api/briefing/digest` with a per-user reach join.
- Keeping it costs about 2,100 lines plus a second assembly path.

**D3: `evaluationGate`.** There are three options.

| Option | What it removes | Phases |
|---|---|---|
| **(a) Delete client, field and policy** (recommended) | the client readers, the `BriefingSlot.evaluationGate` field and `BriefingGatingPolicy` | C1 and C2 |
| (b) Delete the client only | the client readers, which have no functional reader | C1 only |
| (c) Keep everything | nothing | neither |

The seam was kept on purpose for "the next hard physical constraint" (`BriefingGatingPolicy`, `BriefingSlot`,
`tide-window-plan.md`). It has been inert since 2026-09-18, and a future constraint deserves a design informed by the tide
gate's history.

**D4: `SKIPPED_HARD_CONSTRAINT`.** Recommendation: **keep the enum constant**.
- After C2 nothing writes it.
- Its rows are pruned within 30 days, and the admin breakdown already shows unknown keys raw.
- Correct its javadoc to "no longer written; historical rows only".

### C.1 C1: frontend `evaluationGate` readers (D3 a or b)

**What goes:**
- **`utils/locationSheet.js`:**
  - `buildEvaluationGateIndex`;
  - `evaluationGate` on `buildSlotIndex` entries and its doc (:283–303);
  - `gated` on the tide index entry (:346–347, :387, :427–428);
  - the row gate (:816–825), and `gate` in the row object (:877).
- **`components/WindowFirstMapPane.jsx`:** the import and index build (:18, :435–440), and the prop (:689).
- **`components/MapView.jsx`:**
  - the `evaluationGateIndex` destructure (:1430), pass-through (:6228), and doc and PropTypes (:7253–7257);
  - `tideGated` (:3978), which has no reader;
  - the comment at :3414.
- **`components/map/MapCallout.jsx`:**
  - the prop (:284);
  - the lookup (:570–583) and its dependency-array entry (:462);
  - the comment block (:428–440), the gate paragraph, and the comment at :955;
  - the `gated` typedef and PropType (:173, :1106), and the `evaluationGateIndex` PropType (:1124–1125).
- **`components/LocationFourDaySheet.jsx`:** `row.gate`, the gate row, the no-prose fallback, and the comments (:566–573,
  :669, :687–698, :713).
- **`index.css`:**
  - `.wf-loc-gate` in the dim list (:9974–9984);
  - the gate rules (:10174–10215);
  - the comments at :7111 and :7413.

**Wording.** Delete **only** the `gate ? 'Not scored' :` arm in `MapCallout` (:798) and the `row.gate ? 'Not scored' :` arm
in the sheet (:571). Keep the remaining `ratingKnown` / `ratingRetrying` / `scoresKnown` ternary byte for byte: it is what
every slot reads today, and `MapViewNightScoresLoading.test.jsx` pins it. Do not hardcode "Not scored yet".

**Tests:**
- Delete the gate blocks:
  - `locationSheet.test.js` ~1312–1450;
  - `MapCallout.test.jsx` ~527–575 and ~1345–1420;
  - `LocationFourDaySheet.test.jsx` ~865–985;
  - `MapViewHeat.test.jsx` ~888–1010.
- Adjust the remaining references:
  - `locationSheet.test.js`: :114, :117 (`toEqual` with `evaluationGate: null`), :189, :231, :285–295, :312, :361, :397–402,
    :430;
  - `MapCallout.test.jsx`: the :18 import;
  - `MapViewHeat.test.jsx`: the :897 and :910 helper parameter, the callers at :990 and :1010, and `LocationFourDaySheet.test.jsx`
    :476–478;
  - `WindowFirstMapPane.test.jsx` ~355–370.

**Acceptance.** `npx vitest run` is green, and this grep has no hits in `frontend/src`:
`grep -rnE "evaluationGate|EvaluationGate|tideGated|wf-(loc|callout)-gate|(map-callout|location-sheet)-gate" frontend/src`.
Do not grep for `gated` alone; it matches `reach-gated`, `.wf-tab-gated` and unrelated tests.

**Mutants:**
- the callout's ternary reduced to a constant;
- the sheet's `scoresKnown` arm removed.

### C.2 C2: backend `evaluationGate` (D3 a)

**`BriefingSlot`:**
- Remove the `evaluationGate` component, so the canonical constructor goes from 18 to 17 arguments. Update the legacy 17-arg
  constructor, which becomes 16, the convenience constructor (:307–312), the withers (:249, :268, :443) and the
  `withClaudeScores` overloads.
- Remove `withEvaluationGate`.
- Collapse `couldCarryRating` to `claudeRating != null || !canopy`. That is exactly equivalent today, because the gate is
  always null. Its readers are `BriefingHonestyFilter:270` and `BriefingRegionEvaluationRollup:359`.
- Fix the javadoc at :72–98, :112, :150–153, :196 and :212–216.
- Update every `new BriefingSlot(` call site the compiler flags, including `BriefingSlotBuilder:252`; do not trust a count.

**`BriefingSlotBuilder`:** remove the seam block (:263–277).

**`BriefingGatingPolicy`:** delete it, with `BriefingGatingPolicyTest`, and inline at each caller:
- `isEligibleForEvaluation` is always true, so it is dropped at `BriefingGlossService:264`, `ForceEvalHeadlineSelector:153`
  and the collector.
- `hasAnyEligibleSlot` is **not** always true: it is false for a region with no slots. `BriefingGlossService:200` becomes
  `!region.slots().isEmpty()`.
- The collector's dead branch (`BriefingCandidateCollector:231–250`) goes, with its `skippedVerdict` counter (:113, :248) and
  the `verdict={}` slot in its log (:270–272).
- Remove the imports (`BriefingGlossService:22`, `BriefingCandidateCollector:15`, `ForceEvalHeadlineSelector:10`) and the
  `{@link BriefingGatingPolicy}` references (`BriefingGlossService:35, :189`; `BriefingHonestyFilter:27`).

**Tests:**
- Remove the gate cases:
  - `BriefingSlotTest` (:228–316 and the gate-dependent part of :348–374);
  - `BriefingSlotBuilderTest` (:1452–1609, :1896–1904);
  - `BriefingHonestyFilterTest` (:204–233);
  - `BriefingServiceRosterTest` (:59 helper and the `GatedRegion` class);
  - `RecordWitherPreservationTest`'s `withEvaluationGate` row;
  - `DailyBriefingResponseJsonTest` (~761–802).
- **Replace the JSON case.** Add a test that a `DailyBriefingResponse` JSON whose slot carries `"evaluationGate":"x"` still
  deserialises through `AppConfig.objectMapper()`, which `loadPersistedBriefing` uses.
  - Because `BriefingSlot` is `@JsonUnwrapped`, Jackson 2 already ignores unknown slot-level keys. A reviewer verified this
    on jackson-databind 2.21.4.
  - So **do not add `@JsonIgnoreProperties`**. Run the test before and after removing the component to confirm it.
  - Jackson 3 on the API is unaffected.
- Reword the comment-only references:
  - `BriefingGlossServiceTest` :140–141, :192;
  - `ForecastTaskCollectorTest` :165, :1258, :1367;
  - `TideWordingTest` :83–84;
  - `ForecastResultHandlerTest` :1181;
  - `DispositionWriteIntegrationTest` :175.

**Keep `DispositionCategory.SKIPPED_HARD_CONSTRAINT`** (D4) and correct its javadoc.

**Docs:**
- CLAUDE.md :177, :387, :575, :581;
- `tide-window-plan.md`'s seam sentences;
- `window-tide-facts-plan.md` :210;
- the comments in `BriefingHonestyFilter` (:258–270), `CycleLocationOutcomeResolver:54`, `SupersedingDispositionService:64`
  and `BatchRetryService:49`.

**Mutants:**
- `couldCarryRating` ignoring `canopy`;
- `BriefingGlossService:200` made a bare `true`.

### C.3 C3: Close-to-home (D2 = delete)

**What goes:**
- `CloseToHomeController` and its test;
- `CloseToHomeService`, `CloseToHomeResponse` and the service test;
- `AbstractControllerTest`'s mock bean (:245–246);
- `BriefingService.getServedBriefing()` and its tests (`BriefingServiceTest` ~3490–3517, ~3870–3893);
- the `HttpCachingConfigTest` row for the path (:117–120), keeping another personal-data path pinned there.

**`assembleWithoutPlan` stays** as a package-private method, called by `assembleForPlan` and by
`ServedBriefingAssemblerTest.stripPreservesEverythingButSlotTide`, which uses it as the unstripped oracle (:196). Only the
public accessor goes.
- Reword its javadoc (:82–95), the ordering comment (:129–135) and the `stripSlotTide` javadoc (:178), which lose the
  Close-to-home exception.
- Remove `cacheAndCloseToHomePathKeepSlotTide` (:226–241), and keep a test that the raw cache keeps slot tide.

**`photocast.close-to-home.radius-miles` stays.** It is in no committed YAML; the default of 22 lives only in `AskService`'s
`@Value` (:151), its sole reader after this phase. The production host's config is hand-edited and may set it, so leave the
name. Reword the `AskService` javadoc (~:142, "the Close-to-home default"). CLAUDE.md :153 stays as it is.

**Keep** `UserSettingsService.getHomeLocation`, used by `ReachService:84` and `TodaysLightService:92`, along with
`localRadiusMiles` and the `--color-close-to-home` token.

**Comments to reword or remove:**
- `PlanWindowProjector` :114, :352, :509, :528;
- `AppUserEntity:134`;
- `AskTools:1079`;
- `BriefingRegion:101`;
- `TodaysLightService:30`;
- `BriefingService` :378–380, :395–405;
- `ServedBriefingAssembler` :87–88;
- `frontend/src/utils/firstClause.js:5`;
- `frontend/src/api/lightApi.js:9`;
- `frontend/src/api/settingsApi.js:29`;
- `frontend/src/components/UserSettingsModal.jsx:618`.

**Docs:**
- CLAUDE.md :147, :177, :370–371, :393, :1022;
- `plan-panel-data-contracts.md` §4;
- `v1-retirement-plan.md` §8.3, which gets a resolution line;
- `served-briefing-assembler-plan.md`;
- `window-tide-facts-plan.md`.

**Changelog:** `### Removed — …`.

**Mutants** (`getCachedBriefingForApi` must be unchanged):
- `assembleForPlan` skipping the honesty filter;
- skipping the best-bet fallback;
- skipping movement.

### C.4 Size

These are estimates; each PR measures the real figure.

| Phase | Lines removed |
|---|---|
| C1 | ~600 |
| C2 | ~900 |
| C3 | ~2,100 |

---

## D. Sequencing

1. **B1, C1 and C2 start together.**
   - C1 is the frontend; C2 and B1 are the backend.
   - C2's seam block sits **inside** `buildSlot`, the method B1 re-signatures, and both edit `BriefingSlotBuilderTest`, so
     expect a small real merge conflict. Whichever lands second merges `main` and resolves it.
2. **C3 after C2.**
   - Both edit CLAUDE.md :177 and `window-tide-facts-plan.md`.
   - They do not overlap in code.
3. **A1 last.**
   - It edits `reEnrichVerdicts` and `BriefingHonestyFilter`, which C3 and C2 both touch.
   - Its builder re-finds the honesty-filter lines after C2's rewrite.

If D2 or D3 removes a phase, the rest of the order stands.

## E. Owner decisions

| | Question | Default |
|---|---|---|
| D1 | The wording table in A.2 | as written |
| D2 | Delete Close-to-home | delete |
| D3 | `evaluationGate`: (a) client + field + policy, (b) client only, (c) keep | (a) |
| D4 | Keep `SKIPPED_HARD_CONSTRAINT` as a historical category | keep |

## F. What the review changed (draft 1 → draft 2)

**A:**
- T+4 is "not attempted" whatever the triage mix; without that, mixed regions would have hidden almost every case.
- `FAILED` now needs at least half the slots failing, and never applies at T+4.
- The AWAY copy says "travel day", not "you're away", and its label is the client's real "Not forecast".
- The "fields kept" test now asserts the six deliberately nulled fields stay null; draft 1 contradicted the code.
- A `BY_RANK` tie-break stops the window verdict depending on region names.
- One combined `loadServeTriage` reads the dispositions once, with fail-soft, tie and unparseable-value rules.
- After a Codex review of #1112, a post-submission failure counts as `FAILED`, not `UNKNOWN`: an `EVALUATED` slot whose
  cycle has ended with no usable result. This costs one bounded cycle query per serve.
- The query-count test moved to where the repository is visible.
- The old filter overloads bypass the fold.
- A2 was folded into A1 as comment work.

**B:**
- An uncovered location or range falls back to the live query; draft 1 silently returned empty.
- The types are `LocalDateTime`, and `windowFor` is exact.
- Exact signatures, with old methods untouched for strict stubs and no ambiguous 4-arg overload.
- The snapshot is built through `BriefingSlotBuilder.snapshotFor`, so `BriefingService` gains no dependency.
- `TideSurfaceAgreementTest` stays as the window pin.
- The equivalence fixtures make the mutants observable.
- One `READ_WINDOW_DAYS` constant.

**C:**
- The redundant `@JsonIgnoreProperties` is dropped; `@JsonUnwrapped` already tolerates unknown keys.
- Only the gate arm of the "Not scored" ternary goes.
- The file, test and grep lists are completed.
- `hasAnyEligibleSlot` is not always true.
- `assembleWithoutPlan` is kept as the test oracle.
- The radius property claim is corrected.
- D3 is defined as three options.
- The sequencing claims are corrected.

**A, after the A1 review (draft 4):**
- The collector triages BEFORE Gate 4 (`ForecastTaskCollector`), so an unscored T+2 or T+3 region is usually a mix of
  `SKIPPED_TRIAGED` and `SKIPPED_STABILITY`, not one or the other. Draft 3's fold sent every mix to `UNKNOWN`, which still
  read "Poor". Rule 4 now decides a mix by majority, with a tie going to `NOT_ATTEMPTED` because claiming poor weather
  needs a majority.
- A single `SKIPPED_ERROR` made a T+4 region `UNKNOWN`, and the `BY_RANK` step then let its `STAND_DOWN` win the whole
  window. Errors under half the slots no longer change the T+4 or T+3 answer (the error-minority guard); errors at half or
  more at T+3 count as `FAILED`, and at T+4 stay `UNKNOWN`.
- The `NOT_ATTEMPTED` gloss says "stable enough", because at T+2 Gate 4 also scores TRANSITIONAL cells.
