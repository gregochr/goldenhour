# The all-in architecture — build plan

Companion to `all-in-architecture-design.md`. That document holds the *reasoning*; this one holds
the **resolved positions**, re-verified against the tree at **`01005d6e`** (`main`). Where the two
disagree, this one wins.

**Citation sweep, 2026-08-01.** All 55 file:line citations across both documents were re-checked
against HEAD by six agents after ten PRs (#385–#394) landed mid-design: **116 claims checked, 91
passed, 13 stale, 12 wrong, 0 gone.** Every correction is applied below. Only two cited files
actually changed — `BriefingService.java` (1022→1040) and `CloseToHomeService.java` (624→639) — but
`BriefingService` is the one the whole slate/publish split hangs off. See §2.10.

Produced by six parallel deep-design passes over the code, each adversarially verified, then a
cross-area consistency pass. The verifiers refuted **12 fatal and 30 major claims** — including
three inherited from the design document itself. The raw area drafts and their per-area
corrections are parked under `docs/engineering/all-in/`; they are working material and contain
the refuted claims. **Build from this file, not from those.**

---

## 0. Run this before anything ships

Five of six design passes filed "how many slots are triaged per cycle?" as an open question and
each invented a different query. It is one query, and the whole cost case depends on it:

```sql
SELECT jr.started_at::date                                                   AS cycle_date,
       count(*)                                                              AS candidates,
       count(*) FILTER (WHERE d.disposition = 'SKIPPED_TRIAGED')             AS triaged,
       count(*) FILTER (WHERE d.disposition = 'SKIPPED_HARD_CONSTRAINT')     AS hard_constraint,
       count(*) FILTER (WHERE d.disposition = 'SKIPPED_TRAVEL_DAY')          AS travel,
       count(*) FILTER (WHERE d.disposition IN ('EVALUATED','FORCE_EVALUATED')) AS evaluated
FROM forecast_run_disposition d
JOIN job_run jr ON jr.id = d.job_run_id
WHERE jr.started_at >= now() - interval '30 days'
GROUP BY 1 ORDER BY 1 DESC;
```

Normalise by `job_run.started_at::date`. **Never** by `evaluation_date` — that is the forecast
*target* date; across overlapping 4–5-day windows it counts ~34 distinct dates and says nothing
about how many cycles ran, so a £/day figure derived from it is wrong by the ratio of target-dates
to cycle-days.

**The published +£32–48/month all-in delta assumed triage was retained.** It no longer holds.

---

## 1. The migration ledger

Three areas independently claimed V139; one claimed V137. `ls db/migration | sort -V | tail -1` →
`V138__durham_heritage_coast_locations.sql`. Two scripts sharing a version is a **Flyway boot
failure in production**, not a lint. Nobody owned a ledger, so here it is — allocated in phase
order, and this table is the only place a number may be claimed:

| Version | Contents | Phase |
|---|---|---|
| V139 | `evaluation_event` + projections | 0 |
| V140 | backfill `forecast_evaluation` → `evaluation_event` | 0 |
| V141 | `cloud_verification` FK remap | 0 |
| V142 | `briefing_slate` | 1 |
| V143 | `pipeline_phase` value rename (data migration) | 1 |
| V144 | drop `stability_snapshot`, `evaluation_delta_log`, `optimisation_strategy` | 3 |
| V145 | `forecast_run_disposition` retirement | 4 |

---

## 2. Resolved contradictions

Each of these had two or more areas answering differently. The resolution is binding.

### 2.1 `BriefingGatingPolicy` survives — as a publish-side display helper

It has four consumers, and only two are gates:

| Site | Kind | Fate |
|---|---|---|
| `BriefingCandidateCollector.java:239-240` | **gate** | delete the call |
| `ForceEvalHeadlineSelector.java:110` | **gate** | dies with the class |
| `BriefingGlossService.java:203, :268` | **display/publish** | **keep** |
| `BriefingHonestyFilter.java:19` (Javadoc) | reference | keep |

Deleting the class compile-breaks `BriefingGlossService`; force-fixing that by removing the guard
makes every all-hard-constrained region silently lose its Plan-tab headline and detail prose. Add
a class-Javadoc line recording *why* it survives, or it gets deleted again in six months.

### 2.2 Stability rides the slate; the provider goes last

`StabilitySnapshotProvider` has nine production call sites across eight classes (count verified at
HEAD). It is the **only** transport out of the classifier. Per-region stability moves onto the
**slate**, and its consumer reads it from there. That work lands in phase 1 and **gates** every
deletion of the provider. See the design doc's §4.2 correction.

> ⚠️ **Corrected 2026-08-01 — the destination is the *prompt*, not the Plan tab.** This section
> and the design doc both called `BriefingRollupBuilder.java:403` and
> `BriefingBestBetAdvisor.java:150` "display/publish" paths. Verified at HEAD, they are neither:
> `appendStabilityToRegion` writes `stability`/`stabilityReason` into a Jackson `ObjectNode`
> (`:427,:429`) that becomes the rollup JSON (`buildRollupJson`, `:111`), whose only consumer is
> `BriefingBestBetAdvisor.advise` (`:202`) — the Claude best-bet **user message**.
> `BriefingRegion` has no stability component and nothing on the Plan tab renders one.
> `BriefingBestBetAdvisor:150` is a DI pass-through, not a read.
>
> **Consequence for this phase:** "stability rides the slate" must deliver a **prompt input**, not
> a display attribute on `BriefingRegion` — and the failure mode of getting the order wrong is
> *worse* than assumed, because a best-bet prompt quietly missing an input produces no blank cell,
> no error, and no visible symptom at all.

**Open and blocking:** `grep -rn withStability src` → one producer,
`ForecastCommandExecutor.java:667`. The batch path has never emitted the `FORECAST RELIABILITY`
block. If the synchronous engine is deleted, the block has no producer and the case for keeping the
classifier collapses. Decide before phase 1 — either the batch path emits it (zero API cost) or
classifier and block both go, leaving `SYSTEM_PROMPT` untouched.

### 2.3 `DispositionCategory` survives, reduced to five values

Five areas gave it five fates. §2.7 of the design settles it: the disposition record **merges into
the outcomes table**. So the enum survives as `EVALUATED`, `SKIPPED_TRAVEL_DAY`,
`SKIPPED_PAST_DATE`, `SKIPPED_UNKNOWN_LOCATION`, `FAILED` — down from 12 — and becomes
`evaluation_event.skip_category`. `forecast_run_disposition`, `ForecastDispositionService` (158),
`ForecastDispositionCleanupService` (44), `ForecastRunDispositionEntity` (97),
`DispositionBreakdownResponse` (60) and `CandidateDisposition` (37) all go, **in phase 4**.

Note `SKIPPED_NO_REFRESH_NEEDED` is *live* production code, not dead as one pass claimed —
`IntradayEligibilityPolicy.java:50` writes it and four tests exercise it.

### 2.4 One `PipelinePhase` owner, one migration

Two areas rewrote the enum differently and **neither wrote the data migration**. Both
`pipeline_run.current_phase` and `pipeline_run_phase.phase` are `@Enumerated(EnumType.STRING)`, so
after any rename every historical row throws `IllegalArgumentException: No enum constant` — killing
the admin run-history view and `PipelineRunComparisonService`. V143 does the `UPDATE` on both
tables. `STABILITY_RECLASSIFY` is named at `PipelineOrchestrator.java:333-334`, so dropping it is
also a compile break.

### 2.5 The 50% coverage threshold annotates; it never gates

`BriefingService.java:513` computes `aboveThreshold`; the branch at `:555` (else `:568-592`) shows
that **below threshold the briefing still publishes** — a last-known-good stale copy is served via
`cache.set(staleResponse)` at `:576`, or the partial is cached at `:581-586`, and `completeRun`
runs at `:591` either way. Relative to the above-threshold path the else branch skips three things,
not one: `persistBriefing()` (`:563`), `lastKnownGood.set` (`:562`) **and**
`eventPublisher.publishEvent(new BriefingRefreshedEvent(this))` (`:564`). One pass promoted this into a `failRun()` before submit,
which in a change whose entire mandate is *remove policy gates on Claude spend* would **create a
new all-or-nothing one** on a path that today degrades gracefully. It is also the
classify-for-display rule inverted: coverage is a fact to annotate the publication with, not a
decision about whether to spend. Deleted from the design.

### 2.6 Do not split `RunType.BRIEFING`

One pass proposed `BRIEFING` + `BRIEFING_PUBLISH`. It breaks metric continuity on the admin cost
dashboard and invalidates that same pass's £-saving query the day it ships. `pipeline_run_phase`
already times PUBLISH separately.

### 2.7 `BRIEFING_WINDOW_DAYS` is **5**

Three areas assumed three different values, and one built a "T+3 is a structural bound" argument on
it being 4. `BriefingService.java:119` reads `= 5`; `:415-417` builds `IntStream.range(0, …)`, i.e. T+0..T+4. **T+4 is inside the window, so the T+3 ceiling is policy, not structure** — it is the
surviving half of `NightlyEligibilityPolicy.java:54`. `HorizonModelSelector.MAX_DAYS_AHEAD` must be
re-justified against Open-Meteo's actual forecast extent, which no pass measured.

### 2.8 Keep the H2 **test** slice

> ⚠️ **Corrected 2026-08-01 — "there is no H2 at runtime" is false.**
> `backend/src/main/resources/application-local.yml` is **tracked**, is a runtime profile, and is
> H2: `jdbc:h2:file:./data/goldenhour` (`:6`), `org.h2.Driver` (`:7`), `H2Dialect` (`:11`),
> `ddl-auto: update` (`:13`), `flyway.enabled: false` (`:20`), console on (`:21-23`). `pom.xml:247-249`
> says it outright — *"H2 — used in tests (@DataJpaTest) and local dev profile (H2 file DB). test
> scope makes it available for test execution and spring-boot:run."* `backend/data/goldenhour.mv.db`
> is on disk. It even **changed since `d421ef5f`** (+15 lines of `photocast.tide-run` config at
> `:192-209`, the same block appended to `-prod` and `-example` — a blanket edit, not evidence of
> use).
>
> The true statement is narrower and belongs to the user's practice, not the tree: **they do not
> use it**; local development is Postgres. The instruction below is therefore right and the
> framing was wrong. The **Postgres-only DDL decision survives on its own merits**, because
> `local` has `flyway.enabled: false` and so never runs a migration — but state it that way round.

The `local` profile is a leftover to delete, not a profile in use.
`src/test/resources/application.yml:1-13` is a separate thing: `jdbc:h2:mem:testdb`, `ddl-auto: create-drop`, `flyway.enabled: false`. Deleting it
converts every unit test into a Testcontainers test and makes CLAUDE.md's CI-reproduction command
(`-Dtest='!**/integration/**'`) meaningless. **Delete `application-local.yml`, the h2 console
config and `backend/data/*.db`. Keep the test-scope dependency**, and record the rule: any test
touching a Flyway-created view, `DISTINCT ON`, JSONB, a partial index or a `CHECK` constraint must
extend `IntegrationTestBase`.

### 2.9 What the gates actually hide — and who marks it down once they are gone

The safety question for the whole redesign: *is there a physical condition the gates keep away from
Claude that nothing downstream knows how to penalise?* Answer: **one, and it is fixable in the
system prompt.**

Seven of the eleven gates are **bookkeeping** — freshness/cached, stability, horizon depth, intraday
settled-skip, optimisation strategies, past date, unknown location. They filter on "we already have
an answer" or "not worth it yet", never on a sky condition. Nothing is hidden; nothing to teach.

Four hide an actual condition (`WeatherTriageEvaluator.java:26-28` and `TideAlignmentEvaluator`):

| Hidden condition | Gate | Who catches it after removal | Status |
|---|---|---|---|
| Solar low cloud > 80% | `HIGH_CLOUD` | Claude — `>60% = BLOCKED`, rating 1–2 ceiling that overrides any canvas (`PromptBuilder.java:105,122-124`), plus strip-vs-blanket nuance the gate never had | ✅ covered, and better |
| Misaligned tide | `TIDE_MISALIGNED` | **Not Claude.** `TideVisitor` scores it `SCORE_MISALIGNED = 1` deterministically and `RatingCombiner` averages it into the sky score | ✅ pure duplication |
| Visibility < 5,000 m | `LOW_VISIBILITY` | Claude — and see the inversion note in §5 | ⚠️ the gate is arguably inverted |
| Precipitation > 2 mm | `PRECIPITATION` | **Nothing, adequately.** | ❌ **fix before removing** |

**Tide needs no work and the reason is worth recording**, because a naive grep for `tide` in
`PromptBuilder.java` returns **zero** hits and reads like a hole. It is not: `CoastalPromptBuilder`
is selected whenever `data.tide() != null` (`ClaudeEvaluationStrategy.java:88`,
`BatchRequestFactory.java:208`), and its Javadoc records a deliberate v2.13.2 decision — *tide is no
longer a rating lever in the prompt*; Claude scores the sky alone and tide is re-added at the
combine seam. `TideVisitor.java:29` states the rule outright: **"a misaligned tide penalises, it
does not abstain"**, abstaining only on a genuine data gap.

**Precipitation is the one real gap, and it blocks phase 2.** Claude *receives* the number —
`Precip: %.2fmm` at `PromptBuilder.java:443,452` — but has no rule to act on it. The only
precipitation content in `SYSTEM_PROMPT` is "post-rain clearing often vivid" (`:85`), which points
the **wrong way**, and a style example `1-2★ "Heavy rain — stay in and edit"` (`:289`). Compare
cloud, which gets an explicit numeric ladder with hard ceilings. Removing the `PRECIPITATION` triage
rule as things stand leaves a 5 mm slot depending on Claude inferring a penalty from a
summary-wording example.

> **Precondition on phase 2:** add a numeric precipitation rule to `SYSTEM_PROMPT`, shaped like the
> cloud ladder — >2 mm at event time caps `fiery_sky` and rating; light showers with clearing behind
> remain a positive. Two constraints: it must go in the **system** prompt, not the user message, so
> it stays inside the cached prefix; and adding text only *increases* prompt length, which is the
> safe direction against the 15,500-char floor (`PromptBuilder.java:65`).

### 2.10 The `refreshBriefing()` boundary, re-measured — and it is not a single cut

Re-verified at HEAD (`BriefingService.java`, now **1040 lines**; it was 985 when the split was first
sketched and 1022 at `d421ef5f`, so every earlier line number here was wrong twice over):

| | Range | Landmarks |
|---|---|---|
| **Free half (slate)** | `409–488` | `dates` 415-417 · `colourLocations` 422-424 · `fetchWeatherSequential` 438-439 · `fetchHorizonCloud` 442 · `marineWaveRefreshService.refresh` 448 · slot-build loop 454-485 · `hierarchyBuilder.buildDays` **488** |
| **Paid half (publish)** | `490–593` | `enrichWithCachedScores` 491 · `generateGlosses` 495 · `generateHeadline` 498 · `bestBetAdvisor.advise` 504 · aurora 508-509 · `aboveThreshold` 513 · `hotTopicAggregator` 550 · `bluebellGloss` 551 · `if(aboveThreshold)` 555 · `cache.set` 561 · `lastKnownGood.set` 562 · `persistBriefing` 563 · else 568-592 · ends **593** |

The old `:409–483` stopped five lines short and excluded `buildDays` — the very step the claim
names. `:486–560` started four lines early and cut off before persist.

**The part that changes the design, not just the numbers: three *free* refreshes sit inside the
paid half.**

| Block | Lines | Cost |
|---|---|---|
| `nlcClarityService.refresh` | 521-525 | free (Open-Meteo) |
| `meteorClarityService.refresh` | 530-535 | free (Open-Meteo) |
| `surgeCurveService.refresh` | 544-548 | free — **new**, PR #389 |

So SLATE/PUBLISH is **not a single cut at one line**. Three blocks must *move* into the slate half,
and the newest one is the awkward one: `surgeCurveService.refresh` consumes **`locationWeathers`**,
a free-half local. Today that works because both halves are one method sharing a scope; after the
split it becomes a value the slate must **carry**, not merely a call that relocates. `BriefingSlate`
therefore needs `locationWeathers` (or whatever `surgeCurveService` actually reads from it) as a
component.

Two lessons worth stating plainly. First, the split is a **partition of statements, not a cut
point** — an implementer following "everything before line N" will strand three free calls in the
paid phase and pay for the weather twice. Second, this crosser **did not exist** when the split was
designed; it arrived in one of the ten PRs that landed mid-design. Re-measure the boundary
immediately before phase 1 starts, not from this table.

---

## 3. Sequencing that actually stays green

The naive order goes red in six places. Notably: deleting `OptimisationStrategyService` breaks
`ModelsController` **and** `AbstractControllerTest` — the base class of *every* controller test —
so the whole controller package stops compiling; and `EvaluationViewService` /
`BriefingEvaluationService` are `@MockitoBean` fields on that same base class.

```
Phase 0  V139 evaluation_event + dual-write; V140 backfill; V141 cloud_verification remap
         calibration zero-data fix + outcome recording UI          [both additive]
         + the disposition-breakdown replacement endpoint (see §4.2)
Phase 1  slate split (V142) + stability onto the slate (§2.2)
         PipelinePhase rename + V143 data migration
Phase 2  gate removal — one commit per gate, each green
Phase 3  subsystem deletion + V144 drops
Phase 4  switch reads to evaluation_current; delete EvaluationViewService,
         BriefingEvaluationService, cached_evaluation, forecast_evaluation
         delete the sync engine (only after §2.2's open question is closed)
Phase 5  UI shrink + the new disposition view
```

**Non-negotiable edges:** stability-onto-slate before any provider deletion · the
`FORECAST RELIABILITY` producer decision before the sync engine goes · phase 0's disposition write
before phase 2 changes the categories · **the precipitation rule (§2.9) lands before the
`PRECIPITATION` triage rule is removed** · `AbstractControllerTest` touched **once**, in phase 4,
not incrementally.

Within phase 2, order the gate commits so the one gate with a downstream gap goes **last**:
`TIDE_MISALIGNED` and `HIGH_CLOUD` are safe to drop immediately (§2.9); `LOW_VISIBILITY` is safe and
is a likely *quality improvement*; `PRECIPITATION` waits on the prompt rule.

---

## 4. Day-one gaps — designed by nobody

1. **The `/api/metrics/disposition-breakdown` replacement.** Phase 4 deletes
   `JobMetricsController.java:165` and its service; the UI work builds a component against it;
   `DispositionBreakdown.jsx` plus two frontend test files break. The `evaluation_event`-backed
   endpoint that §4.6's "grow the disposition view" requires **does not exist in any draft**.
   Design it in phase 0 alongside V139.
2. **`ddl-auto: validate` vs the view entities.** Corrected 2026-08-01: it is **not** "all three
   runtime profiles". Validating are `application-dev.yml:12` and `application-prod.yml:13`, plus
   `application.yml:12` — which is **untracked/gitignored**, so absent from a fresh clone and from
   CI — and `application-example.yml:12`, a template rather than a profile. **`application-local.yml`
   does not validate**: `ddl-auto: update` (`:13`) with `flyway.enabled: false` (`:20`), so the
   Flyway-view question does not arise there at all and a `local` run would let Hibernate
   DDL-generate against H2 instead. On the profiles that *do* validate, Hibernate's visibility of a
   Flyway-created view on Postgres 17 must be proven before V139 ships, and
   `produced_at DEFAULT clock_timestamp()` needs `@Column(insertable = false, updatable = false)`
   or the first spine write fails on NOT NULL. Both surface only *after* the migration is applied.
3. **The ArchUnit guard that keeps the selection-effect fix durable.** §4.4 requires "a test that
   fails on any repository query against `evaluation_event` lacking an outcome predicate". Nobody
   wrote it. It is one test, and it is the mechanism that stops §2.4 recurring.
4. **The travel-day gate ships as an unlabelled row.** `evaluation_scored_current` filters
   `outcome = 'EVALUATED'`, so no `SKIPPED` event reaches any display path — including the one gate
   deliberately kept. `DispositionBreakdown.jsx:12-23` has no `SKIPPED_TRAVEL_DAY` label and falls
   through the generic unknown-category branch. The single surviving policy decision, which removes
   whole days non-randomly, would be invisible.
5. **Two submit paths record no dispositions.** `doSubmitForecastBatchForRegions`
   (`ScheduledBatchEvaluationService.java:528-550`) and `ForceSubmitBatchService` (340 lines) both
   reach `evaluationService.submit(...)` without `persistCycleDispositions`. Under the new spine
   they add **numerator rows whose denominator was never recorded** — exactly the defect §2.7
   exists to eliminate. Deletion-biased answer: delete `collectRegionFilteredBatches` (93 lines) and
   `RegionFilteredBatchTasks`, route the admin region filter through the same collector.
6. **`RunPhase.TRIAGE`, `LocationTaskState.TRIAGED`, `RunProgress.getTriaged()`** stay live and
   permanently zero. One pass renamed `fetchWeatherAndTriage` on the grounds that "a lie in a method
   name is how the mechanism gets reinvented", then left a phase, a task state and an admin counter
   all called TRIAGE.
7. **`ModelSelectionView.jsx:466-490,525,555`** is user-facing admin prose describing the deleted
   eligibility gate, including the per-run call-volume estimate the operator uses to sanity-check
   cost. After the change it is false and systematically **under**-states volume.
8. **`PAST_WINDOW_DAYS = 2` vs the 7-day outcome window.** The goal is twenty recorded outcomes, but
   the map's `DateStrip` only reaches T-2, so five of the seven fetched days cannot be recorded
   against. This is the most likely reason `actual_outcome` is still empty — and it is a small fix.
9. **Frontend test inventory is incomplete in every draft.** `JobRunsMetricsViewBatch.test.jsx:118,359`
   assert the exact string `/triage and stability gates/`; `PipelineRunsView.test.jsx:10` path-mocks
   a component being renamed. `npm run test` goes red with no warning in any plan.

---

## 5. What removing triage breaks that no area owned

- **Batch latency.** `submitBuckets` fans into six Anthropic batches. Bluebell and woodland are
  already triage-exempt (`ForecastTaskCollector.java:433`), so only the four colour buckets grow —
  ~17–20% from triage plus ~19% from hard-constraints. Observed afternoon latency is **98–173 min**
  against a 4h `DEFAULT_SAFETY_TIMEOUT`, and the 14:00 UTC cycle must land before evening. **Proof
  obligation for phase 2:** one week of `pipeline_run_phase` `EVALUATE_WAIT` durations, compared
  like-for-like, before phase 3.
- **PUBLISH now runs on pre-batch weather.** Today `refreshBriefing()` — *including* its Open-Meteo
  fetch — runs at the tail. Under the split, PUBLISH reuses a slate up to 3h older, so the Plan
  tab's weather gets staler by the whole batch latency. **This is the price of breaking the
  circularity and it belongs in the design, not in production.**
- **The best-bet advisor's input distribution changes shape.** A triaged slot today has *no* rating
  and reads as absent; afterwards it has a rating of 1–2. A region showing "3 scored, spread 1"
  becomes "8 scored, spread 3" — which **downgrades its confidence one band** under the
  `ratingRange >= 2` rule, on *more* information. Re-check the confidence design against the
  post-triage distribution before phase 5.
- **Gloss inconsistency.** Every slot will carry a rating, but `BriefingGlossService.java:203` still
  suppresses gloss for an all-hard-constrained region — ratings with no prose.
- **`BriefingHonestyFilter` (198 lines) loses most of its purpose.** Its second stated
  zero-coverage cause is all-hard-constrained regions; under no-gates those reach Claude and get
  scored, so coverage rises toward 100% and `lightlyEvaluated` becomes near-dead.
- **Prompt cache: positive.** More requests per homogeneous bucket amortise the `SYSTEM_PROMPT`
  cache write *better*. Recorded so it is not re-litigated. `SYSTEM_PROMPT` stays ≥15,500 chars
  (`PromptBuilder.java:65`) in every phase.
- **The visibility gate is inverted against the prompt for a *subset* of the band — removing it
  should improve output on inversion mornings.** Triage discards anything below 5,000 m
  (`WeatherTriageEvaluator.java:28`). The prompt's mist guidance scores **UP** for *"thin ground
  mist (visibility 2-8 km) with clear sky above … Mist in valleys from an elevated viewpoint —
  potential cloud inversion"* (`PromptBuilder.java:164-166`). So a real conflict exists: the gate
  bins mornings the entire `InversionScoreCalculator` subsystem exists to find, and because triage
  persists a canned rating of 1, those valley-inversion dawns go on record at the worst possible
  score.

  > ⚠️ **Corrected 2026-08-01 — an earlier draft overstated this and the overstatement matters.**
  > It said the prompt scores DOWN *"only"* for dense fog below 1 km, and therefore that the whole
  > 2,000–5,000 m band is unambiguously "triage: discard / prompt: score UP". The NEGATIVE list
  > does not stop at `:169`. `:171` reads *"Thick haze (2-5 km) WITH mid/high cloud — flat
  > contrast, muddy light"* — a score-DOWN sitting **inside the very band the argument rests on**.
  > The prompt's actual rule is **conditional**: 2–8 km scores UP only with clear sky above
  > (`cloud_cover_low < 30%`, `:165`) and DOWN when mid/high cloud is present (`:171`). The
  > conflict is therefore real but narrower — it holds for the clear-sky-above subset, which is
  > exactly the inversion case, and not for the whole band. The conclusion survives; the sweeping
  > version of the claim does not.

  It still means the post-removal rating distribution will shift **up** at some locations, not only
  down — factor that into the confidence re-check above.
- **The triage *display* path is not the triage gate.** `EvaluationViewService.java:458` derives
  `Verdict.STANDDOWN` from `cachedResult.triageReason()` and feeds `DisplayVerdict.resolve`, the
  fallback colouring unscored Plan cells; `ForecastDtoMapper.java:240,612` render the raw `triageReason`
  string into the API payload — **not** the derived verdict: `DisplayVerdict` appears nowhere in
  that mapper, and the forecast DTO has no `displayVerdict` component (it rides
  `LocationEvaluationView`, `BriefingSlot` and `BriefingRegion` only). So the STANDDOWN derived at
  `EvaluationViewService:458` reaches the *Plan* surfaces, while the forecast payload carries only
  the reason string. Delete either and a heavy-cloud slot renders a Claude rating instead of
  "STANDDOWN — sun blocked", and the fallback ladder loses a rung. Keep the display, delete the
  gate.

**One sentence for the whole design:** *the classifier and the verdict are computed at SLATE time
and ride the slate into PUBLISH for display and prompt enrichment; no stage between SELECT and
RECORD may branch on either, except SELECT's travel-day test.*

---

## 6. Honest size

The six passes claimed 11,873 production lines removed. Measured as a **union** of whole-file
deletions at `d421ef5f`, it is **3,818** — `DispositionCategory` was claimed five times,
`WeatherTriageEvaluator` four, the four eligibility types three, and so on.

With in-file surgery added (`ForecastTaskCollector` 899→~280, `BriefingEvaluationService` 665→0,
`ForecastCommandExecutor` 826→~450, `ForecastService` 685→~450, and the rest):

| | Production lines removed |
|---|---|
| Sync engine retained | **≈6,600** |
| Sync engine deleted | **≈7,700** |

**And test churn is not deletion.** `BriefingServiceTest` (2,454, split three ways),
`ForecastTaskCollectorTest` (1,557), `EvaluationViewServiceTest` (1,291),
`BriefingEvaluationServiceTest` (907), `PipelineOrchestratorTest` (856), `AbstractControllerTest`
(267) — **~7,300 test lines rewritten rather than removed**, and that dominates the real cost of
the change.

Report it as **~6,600–7,700 production lines removed, ~7,300 test lines rewritten.** Not 11,873.

---

## 7. Verification, stated plainly

No phase of this work is validated by any existing harness. Prompt-regression compares Claude to
hand-written expectations; the sky-rating eval to fixtures; model-comparison to other models; ERA5
to a reanalysis that shares the forecast model's biases where cloud is hardest. All can stay green
while forecasts drift.

The calibration gate is the only non-self-referential instrument, and it currently reports a
**measured zero** where it has no data (`CalibrationBucket.java:58-61` — the rates are correctly
`null`, but `missedOpportunities` and `wastedTrips` are `int` and return `0`). Fixing that and
getting the first twenty outcomes recorded is phase 0, not a follow-up — and gap 8 above is
probably why the table is still empty.
