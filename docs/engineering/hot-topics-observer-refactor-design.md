# Hot Topics → Unified Observer Traversal — Investigation & Design

**Status:** Design only. No code. Stop for review.
**Scope:** Research (Step 0–1) + Plan (Step 2–5) per CLAUDE.md Research→Plan→Implement.
**Date:** 2026-06-05

---

## 0. Headline (read this first)

Two facts decide everything; both differ from the brief's stated assumptions,
and **the code wins**:

1. **There is no Visitor scaffold in this codebase.** The brief assumes a
   "v2.13.x visitor scaffold" — `Visitor` (`appliesTo`/`evaluate`),
   `VisitorContext`, `RatingCombiner`, a `ForecastResultHandler` traversal seam —
   that the hot-topic observers would "plug into." **None of `Visitor`,
   `VisitorContext`, or `RatingCombiner` exist** (grep across
   `backend/src/main/java` returns nothing). `ForecastResultHandler` exists but
   it is a **batch-result parser**, not a per-element scoring traversal: it takes
   one Claude batch response, parses the rating/scores, and writes the cache
   (`ForecastResultHandler.parseBatchResponse`, lines 129–163). Scoring is an
   **asynchronous Claude Batch API** flow, not a synchronous in-process
   traversal. So "plug the observers into the existing scoring traversal" is not
   available — there is no such traversal to plug into.

2. **The wide, triage-independent, per-location traversal the brief is reaching
   for already exists — it is the daily-briefing build.** `BriefingService`
   `refreshBriefing` iterates **all enabled colour locations** (`findAllEnabled()`
   filtered by `isColourLocation`, BriefingService:237–239), and
   `BriefingSlotBuilder` builds **one slot per location per date per event**,
   deterministically, **NOT gated by the sky-scoring triage**. Each slot already
   carries per-location tide facts derived via
   `BriefingSlotBuilder.calculateTideData` (BriefingSlotBuilder:219–272). This is
   exactly the "visit every location-forecast, including the triaged-out ones"
   pass the design needs — and the four shipped strategies are already (mostly)
   reading from its cache (`BriefingService.getCachedDays()`).

**Consequently the target shape is option (ii): shared per-element fact
derivation, with deterministic hot-topic observers riding the *existing*
briefing traversal (the wide scope), and sky-scoring continuing on its own
async-batch scope (the narrow, survivors-only scope).** The two scopes share the
*fact derivation* (tide, inversion, surge, bluebell-seed) rather than a literal
loop. This is not a compromise forced by risk — it is what the architecture
already is. The refactor formalises it: lift recognition out of the strategies
into observers that run on the briefing pass, and unify the duplicated tide
derivation that feeds both the briefing slot and the scoring prompt.

The worked examples both fall out of this:
- **St Mary's, triaged out, king tide at noon** → already in the briefing cache
  with its tide info because the briefing pass is triage-independent. Today it
  *works by accident* of where the strategy reads; the refactor makes it
  *principled* (a deterministic observer on the per-location pass, no Claude
  gate). ✔
- **"King tides in Northumberland, drill to N locations"** → detected per
  coastal location on the briefing pass, rolled up to one regional pill by the
  reshaped aggregator. ✔

One concrete defect the refactor must fix (Step 2/4): **Bluebell is genuinely
Claude-backed, but its Claude call is welded to the sky-scoring call**, so a
bluebell location that is triaged out of sky scoring gets **no** `bluebell_score`
and therefore **no** bluebell hot topic. That violates fires-on-stand-down for a
Claude-backed observer and is the canonical motivation for "per-observer triage,
own call."

---

## 1. Verified reuse map (Step 0)

Legend: **SURVIVES** (reuse as-is) · **REHOMED** (logic kept, moved) ·
**REPLACED** (delete) · **RESHAPED** (kept role, changed responsibility).

### SURVIVES — reusable as-is (confirmed against code)

| Artifact | File | Verdict & note |
|---|---|---|
| `HotTopic` model | `model/HotTopic.java` | **SURVIVES.** Output type. `type,label,detail,date,priority,filterAction,regions(List),description,expandedDetail`. `regions` is already a `List` and `Comparable` is priority-asc then date-asc (lines 76–79). The refactor changes *production*, not the produced thing. |
| `ExpandedHotTopicDetail` | `model/ExpandedHotTopicDetail.java` | **SURVIVES.** `regionGroups` + type-specific `bluebellMetrics`/`tideMetrics`; `RegionGroup`/`LocationEntry` already model region→location drill-down. Polymorphic, type-agnostic. The roll-up the new aggregator produces fits this shape unchanged. |
| API DTO field | `model/DailyBriefingResponse.java:36` (`List<HotTopic> hotTopics`) | **SURVIVES.** Defensive copy in compact constructor. |
| Controller endpoint | `controller/BriefingController.java:52–59` (`GET /api/briefing`) | **SURVIVES.** Serves `DailyBriefingResponse` unchanged. |
| Frontend pill layer | `frontend/src/components/HotTopicStrip.jsx` | **SURVIVES.** Depends only on the `HotTopic` JSON shape. `HOT_TOPIC_STYLES` (lines 7–23) is a frontend-only type→{colour,emoji} map; INVERSION icon flip (line 581, CSS 180° rotate); Lite greying (`isLiteUser`, opacity 0.45). No backend internals leak. |
| Simulation harness | `service/HotTopicSimulationService.java`, `controller/HotTopicSimulationController.java` (`/api/admin/hot-topics/simulation`), `frontend/.../HotTopicSimulation.jsx`, `api/hotTopicSimulationApi.js` | **SURVIVES.** 15 simulation templates incl. unbuilt observers (STORM_SURGE, DUST, INVERSION, SUPERMOON, SNOW_FRESH/MIST/TOPS, NLC, METEOR, EQUINOX, CLEARANCE). Overrides real strategies via `HotTopicAggregator` `simulationService.isEnabled()` check. Doubles as regression net AND the spec for unbuilt observers. Do not touch. |
| Deterministic calculators | `TideService.deriveTideData`, `LunarPhaseService.classifyTide/getMoonPhase/isMoonAtPerigee`, `InversionScoreCalculator.calculate`, `BluebellConditionService.score`, `StormSurgeService.calculate` | **SURVIVES** as the single source the traversal consumes. All confirmed pure/deterministic, no external API in hot path (see §1a). |
| Visitor scaffold (`Visitor`/`VisitorContext`/`RatingCombiner`) | — | **DOES NOT EXIST.** ❗ Correction to the brief. There is nothing to plug into and nothing to reuse here. The "traversal seam" the observers attach to is the **briefing build** (`BriefingService.refreshBriefing` → `BriefingSlotBuilder.buildSlot`), not a scoring visitor. |

### REHOMED — recognition logic kept, independent traversal/fetch discarded

| Recognition rule | Currently in | Rehome to |
|---|---|---|
| King-tide test (`isKingTide`: `tide.isKingTide() || lunarTideType==KING_TIDE`) + multi-day window + best-alignment | `KingTideHotTopicStrategy` (158–218) | A `KingTideObserver` reading the per-location tide fact on the briefing pass. |
| Spring-not-king test (`isSpringNotKing`) + king-trumps-spring suppression | `SpringTideHotTopicStrategy` (138–167, 88–93) | A `SpringTideObserver`, with the king-suppression moved to aggregation (cross-element, see §3). |
| Statistical height signal — `p95HighMetres` / `springTideThreshold` from `TideStats`, used by the briefing slot's `isKingTide`/`isSpringTide` booleans | `BriefingSlotBuilder.calculateTideData` (239–261) via `TideService.getTideStats` | Stays in the **shared tide derivation** (see §2) so both the slot boolean and the observer read one value. The brief's note that "the hot topic uses the statistical signal but the visitor doesn't" is moot here: there is no visitor; the briefing slot already carries both the lunar and statistical signals. |
| Aurora Kp / alert-level threshold (`level >= MINOR` tonight; cached Kp ≥ 4 tomorrow) | `AuroraHotTopicStrategy` (86–128) | An aurora **global/region observer** reading `AuroraStateCache` + `NoaaSwpcClient` cache. This is *not* per-location-tide-style; it's a platform signal projected onto aurora-capable regions (`findByBortleClassIsNotNullAndEnabledTrue`). Fits the **global calendar/observer** family more than the per-element family (§4). |
| Bluebell season + score gate (`SeasonalWindow.BLUEBELL.isActive`, best score ≥ 6) | `BluebellHotTopicStrategy` (71–151) | A `BluebellObserver` (Claude-backed, §2/§4). Recognition reads the persisted per-location `bluebell_score`; the season+site gate becomes the observer's **own triage**. |

### REPLACED — delete

| Artifact | Why |
|---|---|
| `HotTopicStrategy` interface + `detect(from,to)` contract | Bakes in per-strategy independent traversal/scope — the artifact. Replaced by the observer contract (§4). |
| Region-level (and ad-hoc) detection scoping | Each strategy picks its own scope: King/Spring read briefing **slots** (already per-location, but the strategy *re-scans* the whole `getCachedDays()` tree, BriefingService:78–96); Bluebell reads the **scoring DB** (`findBluebellEvaluations`); Aurora reads **NOAA cache**. The "scan-the-world-myself" loop in each strategy is replaced by emitting a finding per element during the one briefing pass (or once per date for globals). |
| `buildTideResult` / `deriveTideContext` duplication | `BriefingSlotBuilder.calculateTideData` (231, 265–267) and `ForecastDataAugmentor.augmentWithTideData` (150, 159–161) both independently call `TideService.deriveTideData` + the `LunarPhaseService` trio. Collapsed to one shared derivation (§2). **Resolving this is a success criterion.** |

❗ Correction to the brief: it states the duplication is "hot-topic side
(`BriefingSlotBuilder.buildTideResult`) vs visitor side
(`ForecastDataAugmentor.deriveTideContext`)." The method is named
`calculateTideData` (not `buildTideResult`) and `augmentWithTideData` (not
`deriveTideContext`), and the second is **scoring-prompt side, not visitor side**
(there is no visitor). The duplication is real and as described; the framing
"hot-topic vs scoring" is more accurate than "hot-topic vs visitor." The hot-topic
strategies themselves don't derive tide — they *read* the already-derived
`TideInfo` off the briefing slot.

### RESHAPED — role kept, responsibility changed

| Artifact | Keeps | Loses | Gains |
|---|---|---|---|
| `HotTopicAggregator` | Assemble final ordered/deduped `List<HotTopic>`; simulation override (50–52); natural-order sort (53–56) | Polling N strategies (`detect(from,to)` flatMap) | Per-location finding → region-grouped pill roll-up with drill-down (§3); cross-element suppression (king-trumps-spring). |
| `VisitorContext` | — (does not exist) | — | ❗ Correction: there is nothing to widen. The per-element fact bundle the observers need **already exists**: it is the briefing slot's data + `TideInfo` (`BriefingSlot`), plus the `AtmosphericData`/`TideSnapshot` assembled in the scoring path. The design introduces a `LocationDayFacts` carrier (§2/§4) rather than widening a non-existent `VisitorContext`. |

### §1a — Deterministic calculator signatures (the single source)

All pure, no external API in hot path (DB reads are pre-fetched caches):

- `TideService.deriveTideData(Long locationId, LocalDateTime eventTime, long windowMinutes) → Optional<TideData>` (TideService:375–401) — reads pre-fetched `tide_extreme`.
- `TideService.calculateTideAligned(TideData, Set<TideType>) → boolean` (414–424).
- `TideService.getTideStats(Long locationId) → Optional<TideStats>` (434–503) — `p95HighMetres`, `springTideThreshold`, etc.
- `LunarPhaseService.classifyTide(LocalDate) → LunarTideType` {KING/SPRING/REGULAR} (154–161); `getMoonPhase` (82–93); `isMoonAtPerigee` (133–144). Pure astronomy.
- `InversionScoreCalculator.calculate(AtmosphericData) → Double` 0–10 (37–102).
- `BluebellConditionService.score(AtmosphericData, BluebellExposure) → BluebellConditionScore` (53–86) — **deterministic SEED**, see §1b.
- `StormSurgeService.calculate(pressureHpa, windMs, windDirDeg, CoastalParameters, lunarTideType) → StormSurgeBreakdown` (101–142).

### §1b — Bluebell origin (resolved; brief was correct, with nuance)

- `BluebellConditionService.score(...)` (deterministic) is called in
  `ForecastDataAugmentor` (line 414) to produce a `BluebellConditionScore` that
  is injected into the **prompt** (`PromptBuilder:493`). This is a *seed/input*.
- The **persisted `bluebell_score` is Claude's number**: parsed from the Claude
  response (`ClaudeEvaluationStrategy:189–195` JSON / 279–288 regex fallback),
  carried on `SunsetEvaluation.bluebellScore`, persisted by
  `ForecastService:676–679` **only when `data.bluebellConditionScore() != null`**
  (i.e. only when the deterministic seed block was present → season + bluebell
  site). `setBluebellScore` has no direct setter call site because it is set via
  the entity builder in `ForecastService`.
- `BluebellHotTopicStrategy` reads this persisted Claude number
  (`e.getBluebellScore()`).

**Therefore Bluebell is a Claude-backed observer.** Its "own triage" already
exists in spirit: season (`SeasonalWindow.BLUEBELL`) + bluebell site
(`bluebellExposure != null`) gate whether the bluebell block enters the prompt.
**But that gate is welded onto the sky-scoring call** — the bluebell question
rides the same batch request as the sky score. A bluebell site triaged out of
sky scoring is never asked the bluebell question → no `bluebell_score` → no hot
topic. This is the concrete defect that the per-observer-own-triage/own-call
design exists to fix (§2, §4).

---

## 2. Current traversal & triage map (Step 1) + THE decision (Step 2)

### Two independent pipelines (this is the crux)

**Pipeline A — Daily Briefing (deterministic, wide, per-location, NO Claude per slot):**
```
BriefingService.refreshBriefing()                              [BriefingService:232]
  └ colourLocations = findAllEnabled().filter(isColourLocation)  [237–239]   ← ALL enabled colour locations
    └ for location × date × event:
        BriefingSlotBuilder.buildSlot(...)                                    ← ONE slot per location
          └ calculateTideData → TideService.deriveTideData + Lunar trio       [219–272]  ← tide fact #1 (DUP)
          └ verdictEvaluator.determineVerdict(...) → GO/MARGINAL/STANDDOWN    ← verdict COMPUTED, not a gate
    └ BriefingHierarchyBuilder.groupByRegion(slots)                           ← day→event→region→slots
    └ cache.set(response)  +  persistBriefing(daily_briefing_cache id=1)
getCachedDays() → cache.get().days()                            [221–224]     ← what the strategies read
```
Population: **every enabled colour location, regardless of sky-triage.** The
STANDDOWN verdict is *data on the slot*, not a filter. St Mary's appears here on
a cloudy day with full tide info. Slot granularity: **one per location** (a
13-location region yields 13 slots, each with its own `TideInfo`).

**Pipeline B — Forecast scoring (Claude Batch, narrow, survivors-only, ASYNC):**
```
ForecastTaskCollector.collectScheduledBatches()                [238–297]
  └ for candidate:
      ForecastService.fetchWeatherAndTriage(...)               [276–418]      ← assembles FULL AtmosphericData
        └ ForecastDataAugmentor.augmentWithTideData → deriveData + Lunar trio [150,159–161] ← tide fact #2 (DUP)
        └ WeatherTriageEvaluator.evaluate(...)                 [363]          ← THE sky-triage gate
        └ TideAlignmentEvaluator.evaluate(...) (SEASCAPE)      [393]
      if preEval.triaged(): persist canned rating=1; continue  [244–249]      ← triaged-out DROP here
  └ survivors → EvaluationTask.Forecast → BatchSubmissionService.submit()     ← ASYNC Claude batch
        ... minutes later ...
  BatchResultProcessor → ForecastResultHandler.parseBatchResponse()  [129–163] ← parse + cache write ONLY
                                                                                  (no tide re-derivation)
```

**Where triage sits:** a **pre-submission gate** in Pipeline B only
(`WeatherTriageEvaluator` at ForecastService:363, `TideAlignmentEvaluator` at
393). It filters which locations reach the Claude batch. **Pipeline A (briefing)
has no such gate** — it is the wide pass.

**The async constraint:** scoring completes in a later, separate callback
(`ForecastResultHandler`), which only parses Claude's text and writes cache — it
does **not** re-derive tide. So there is no synchronous post-batch per-element
structure to hang observers on either.

### The decision: **(ii) shared derivation, separate scopes** — recommended

Map the brief's three options onto the real code:

- **(i) One literal pre-triage traversal** where scoring observers self-skip and
  hot-topic observers don't. **Infeasible as stated.** Scoring is an async Claude
  batch, not a synchronous element loop; there is no single in-process traversal
  that both scores and flags. Forcing one would mean rebuilding scoring as a
  synchronous visitor — a huge, unrelated change, and wrong (batching is
  deliberate for cost/latency).
- **(ii) Shared derivation, separate scopes.** Each per-location fact (tide,
  inversion seed, surge) is derived **once** into a shared `LocationDayFacts`
  carrier; **Pipeline A (briefing, wide)** consumes it for the slot *and* runs
  deterministic hot-topic observers over **all** locations; **Pipeline B
  (scoring, narrow)** consumes the same derivation to build the prompt for
  survivors. Kills the duplication, **does not move the sky-triage gate**, and
  the wide pass already exists. **Recommended.**
- **(iii) Hybrid.** Strictly, the recommendation *is* a hybrid in one respect:
  **deterministic** observers ride Pipeline A; the **Claude-backed** observers
  (Bluebell today; future Dramatic Clearance) need their own triage + own call,
  which may be (a) piggybacked on Pipeline B's batch but gated by *their own*
  pre-check rather than sky-triage, or (b) a small separate batch. See below.

**Why (ii) satisfies the four success criteria:**

| Criterion | How |
|---|---|
| (a) tide duplication gone | One `TideFacts deriveTideFacts(locationId, eventTime, window, tideTypes)` (extracted from the two current call sites) feeds both the briefing slot's `TideInfo` and the scoring prompt's `TideSnapshot`. Both pipelines call it; neither re-derives. |
| (b) St Mary's flags while unscored | King-tide is a **deterministic observer on Pipeline A**, which visits St Mary's regardless of sky-triage. No Claude, no gate → fires on stand-down. (Today it already fires because the strategy reads `getCachedDays()`; the refactor keeps that property and makes it explicit.) |
| (c) region-grouped + drill-down preserved | Observers emit **per-location findings**; the reshaped aggregator rolls them up to regional pills using `ExpandedHotTopicDetail.RegionGroup` (§3). The existing `findCoastalLocations()` region grouping logic in `KingTideHotTopicStrategy.buildExpandedDetail` (229–268) is reused as the roll-up. |
| (d) accommodates a future Claude-backed observer w/ own triage | A Claude-backed observer declares its own pre-check (`shouldAsk(facts)`) and its own call, independent of `WeatherTriageEvaluator`. A sky stand-down does not suppress it because the gate it answers to is its own. Bluebell is the first instance to be decoupled (fixing §1b's defect). |

**Cost of (ii):** moderate, well-scoped.
- Extract `deriveTideFacts` and repoint both call sites — small, mechanical,
  golden-master-verifiable (the produced `TideInfo`/`TideSnapshot` must be
  byte-identical).
- Introduce a `LocationDayFacts` carrier + observer registry that the briefing
  pass invokes per slot. The briefing pass already loops per location, so this is
  an injection point, not a new traversal.
- Decouple Bluebell's Claude call from sky-scoring — the largest single piece,
  and genuinely new behaviour (a bluebell stand-down location would now be asked
  the bluebell question). Stage it last (§5) behind its own flag.

---

## 3. Aggregation / presentation policy (Step 3)

Per-location detection → region-grouped pills. Resolve the policies the
region-as-unit model never had to:

1. **One pill per region per phenomenon.** 13 Northumberland king-tide
   detections → **one** "King tide" pill whose `regions` and `expandedDetail`
   carry the 13. This matches today's output shape (KingTide already emits a
   single topic with `coastalRegions` + a `RegionGroup` per region,
   KingTideHotTopicStrategy:124–149). Roll-up rule: **group findings by
   `(type, date-window)`, emit one `HotTopic`.**

2. **Multi-region phenomenon → one pill, `regions = [all]`.** King tides in
   Northumberland AND North Yorkshire → a single "King tide" `HotTopic` with
   `regions=["Northumberland","North Yorkshire"]` and one `RegionGroup` each in
   `expandedDetail.regionGroups`. **Rationale:** `regions` is already a `List`
   and the current King/Spring strategies already emit multi-region single
   topics; the pill strip stays compact; drill-down disambiguates. (Reserve
   per-region *separate* pills only if a phenomenon's recognition genuinely
   differs by region — none of the four do.)

3. **Drill-down shows ALL flagging locations, grouped by region, best-first
   within region.** Matches current behaviour: `buildExpandedDetail` lists every
   coastal location per region sorted by name with a "Best" badge on the top
   (KingTide:244–256; Bluebell:187–205 sorts by score desc, badges the best).
   Ordering rule: **regions alphabetical (current `sorted(comparingByKey)`,
   KingTide:241–243); locations within region by the phenomenon's salience**
   (tide: alignment/preference; bluebell: score desc) **then name.** Do not
   truncate to "best few" — the drill-down is the faithful roll-up the brief
   wants; the *pill detail line* already provides the best-bet summary
   ("N locations catch sunrise").

4. **Priority/dedup unchanged at the list level; suppression moves up.** Final
   ordering stays `HotTopic.compareTo` = priority asc, date asc
   (HotTopic:76–79) — keep it. Dedup is now *intra-phenomenon roll-up* (point 1)
   rather than cross-strategy. **Cross-phenomenon suppression** that currently
   lives inside a strategy — *king-tide-trumps-spring-tide*
   (SpringTideHotTopicStrategy:88–93 returns empty if any king tide in window) —
   **moves into the aggregator** as an explicit post-roll-up rule, because no
   single observer can see another phenomenon's findings. Aggregator rule:
   *if a KING_TIDE pill exists for an overlapping window, drop the SPRING_TIDE
   pill.* This is the one piece of cross-element logic the observer split forces
   upward, and it belongs in the aggregator's "assemble final list" job.

---

## 4. Observer contract & registration (Step 4)

Three observer families (the brief's three kinds), two registration shapes.

### 4.1 Per-element deterministic observer

```
interface LocationDayObserver {
    boolean appliesTo(LocationDayFacts facts);     // cheap predicate (coastal? bluebell site? has inversion fields?)
    Optional<HotTopicFinding> observe(LocationDayFacts facts);  // emit a per-location finding, or empty
}
```
- `LocationDayFacts` = the shared per-element carrier: `location`, `date`,
  `event/targetType`, the derived `TideFacts`, `AtmosphericData`/inversion seed,
  surge inputs, `bluebell_score` if present. Built **once per slot** on the
  briefing pass (the carrier replaces the non-existent `VisitorContext`).
- `HotTopicFinding` = a pre-aggregation record:
  `(type, date, locationName, regionName, salience, perLocationMetrics)`. The
  aggregator turns a `List<HotTopicFinding>` into `List<HotTopic>` (§3).
- **No triage** — these run on every element because they are arithmetic
  (king/spring tide, inversion-threshold, storm-surge, NLC-window). This is what
  preserves fires-on-stand-down universally.

### 4.2 Per-element Claude-backed observer

```
interface ClaudeLocationDayObserver extends LocationDayObserver {
    boolean shouldAsk(LocationDayFacts facts);     // the observer's OWN triage — distinct from sky-triage
    ClaudeQuestion question(LocationDayFacts facts);// what to ask
    Optional<HotTopicFinding> interpret(LocationDayFacts facts, ClaudeAnswer answer);
}
```
- `shouldAsk` is the per-observer triage. For **Bluebell** it is
  `SeasonalWindow.BLUEBELL.isActive(date) && exposure != null` — exactly today's
  prompt-inclusion gate (§1b), but now **decoupled from `WeatherTriageEvaluator`**.
- **Own call, not welded to sky-scoring.** The bluebell question must be askable
  for a location the sky-triage dropped. Two viable mechanisms (decide at build
  time, behind a flag):
  - **(a) Separate gated batch:** collect all `shouldAsk` survivors across
    locations and submit a small dedicated batch (cheap; mirrors the aurora
    interpreter's single-call-for-all-viable-locations pattern noted in
    CLAUDE.md). Cleanest for fires-on-stand-down.
  - **(b) Union into the existing batch:** add `shouldAsk` survivors to Pipeline
    B's batch *in addition to* sky survivors (a location can be in the batch for
    bluebell even if sky-triaged-out, with a prompt variant that only asks the
    bluebell question). Lower infra cost, more prompt-routing complexity.
  - Recommend **(a)** — it keeps the sky and bluebell calls independent, which is
    the whole point, and the simulation harness can stand in for it until built.
- Future **Dramatic Clearance** / **Photogenic Inversion** slot in here with
  their own `shouldAsk` (a trend+timing pre-check on cloud retreat) and own call.

### 4.3 Global calendar/observer (not per-element)

```
interface CalendarObserver {
    List<HotTopicFinding> observe(LocalDate from, LocalDate to);  // computed once per window, deterministic
}
```
- For facts true of the whole platform on a date: meteor shower, equinox,
  supermoon, seasonal light milestone, NLC season banner.
- **Aurora belongs here, not in 4.1.** It reads `AuroraStateCache` +
  `NoaaSwpcClient` (a platform signal), then projects onto aurora-capable regions
  (`findByBortleClassIsNotNullAndEnabledTrue`). It is not derived from per-location
  weather facts. Keep it a calendar/global observer that emits region-targeted
  findings. (It *may* later consult per-location cloud clearness for the
  drill-down count — `resolveClearCount` already does — but its *recognition* is
  global.)

### 4.4 One interface or several?

**Two sibling per-element interfaces (4.1 deterministic, 4.2 Claude-backed)
sharing the `LocationDayObserver`/`LocationDayFacts` base, plus a separate
`CalendarObserver` (4.3).** Rationale: the deterministic/Claude split is exactly
the "does it make a call → does it need its own triage" axis the brief makes
load-bearing; modelling it as a subtype keeps `shouldAsk`/`question`/`interpret`
off the observers that don't need them. A single fat interface would force
every observer to stub the Claude methods.

### 4.5 Registration

Keep the **Spring auto-collected bean list** shape the current strategies use
(`HotTopicAggregator` injects `List<HotTopicStrategy>`). The aggregator instead
injects `List<LocationDayObserver>` and `List<CalendarObserver>`. The briefing
pass invokes the per-element observers per slot; the aggregator invokes the
calendar observers once per window and performs the roll-up + cross-phenomenon
suppression. Auto-collection means adding an observer is one `@Component`.

---

## 5. Migration sequence (Step 5) — plan, do not execute

Single user, simulation harness as the net, golden-master discipline: the
produced `List<HotTopic>` (the pills) must match before/after at each step. Each
step independently shippable.

**Step A — Unify tide derivation (kill the duplication). Ship.**
- Extract `deriveTideFacts(...)` from `BriefingSlotBuilder.calculateTideData`
  (231,265–267) and `ForecastDataAugmentor.augmentWithTideData` (150,159–161)
  into one method/service; repoint both.
- Verify: briefing slots' `TideInfo` and scoring `TideSnapshot` unchanged; King
  & Spring pills identical (simulation + live briefing `getCachedDays()`
  snapshot diff). No observer work yet. **Success criterion (a) met here.**

**Step B — Introduce `LocationDayFacts` + per-element observer registry
alongside the strategies (transitional).**
- Build `LocationDayFacts` per slot on the briefing pass; add the
  `LocationDayObserver` registry, initially **empty/no-op**, with output routed
  through the existing aggregator. No behaviour change.
- Verify: pills byte-identical (observers emit nothing yet).

**Step C — Port King + Spring recognition into deterministic observers; run in
shadow.**
- `KingTideObserver` + `SpringTideObserver` emit per-location findings; the
  aggregator rolls up to pills and applies king-trumps-spring (§3.4).
- Run **alongside** the existing `KingTideHotTopicStrategy`/`SpringTideHotTopicStrategy`,
  comparing outputs (log/diff), not yet replacing them.
- Verify: rolled-up pills equal the strategy pills across the simulation set and
  live windows incl. the St Mary's stand-down case and a multi-region window.

**Step D — Cut over King + Spring; retire those two strategies.**
- Aggregator stops polling the two tide strategies; deletes them.
- Verify: same pills; St Mary's still flags while unscored (criterion (b));
  region drill-down faithful (criterion (c)).

**Step E — Port Aurora to a `CalendarObserver`; cut over; retire its strategy.**
- Pure rehome (reads same caches). Verify tonight/tomorrow pills unchanged.

**Step F — Decouple Bluebell into a `ClaudeLocationDayObserver` with own triage +
own call. Ship behind a flag.**
- `shouldAsk = season && bluebell site`; own (separate, gated) batch per §4.2(a).
- New behaviour: a sky-triaged-out bluebell site can now flag. Guard behind a
  config flag; verify against simulation first, then a live bluebell-season
  window; confirm a stand-down bluebell site now produces a pill it previously
  could not (the §1b fix).
- Retire `BluebellHotTopicStrategy` once parity + the new case are confirmed.

**Step G — Retire `HotTopicStrategy` interface.** With all four ported, delete
the interface and the strategy-polling path in the aggregator. The aggregator now
consumes findings + calendar observers only.

**Verification gate at every step:** golden-master compare the produced
`List<HotTopic>` (and `expandedDetail`) from `getHotTopics` — via the simulation
harness (deterministic) and a captured live briefing window — not merely green
unit tests. The frontend, DTO, controller, model, and simulation harness are
untouched throughout (§1 SURVIVES), so any pill diff is a real regression.

---

## Appendix — corrections to the brief's assumptions (code wins)

1. **No `Visitor`/`VisitorContext`/`RatingCombiner` exist.** The "visitor scaffold
   to plug into" is absent; the per-element seam is the **briefing build**.
2. **`ForecastResultHandler` is a batch-result parser**, not a scoring traversal
   (parseBatchResponse:129–163); scoring is an async Claude batch.
3. **The duplication is `calculateTideData` (briefing) vs `augmentWithTideData`
   (scoring-prompt)** — not `buildTideResult` vs `deriveTideContext`, and not
   "hot-topic vs visitor."
4. **Hot-topic strategies do not share one scope** — King/Spring read the
   briefing cache (`getCachedDays`), Bluebell reads the scoring DB
   (`findBluebellEvaluations`), Aurora reads the NOAA cache. The "same operation
   over different slices" diagnosis is confirmed and is *worse* than the brief
   implies (three slices, not one).
5. **The wide triage-independent per-location pass already exists** (briefing),
   so St Mary's already works structurally; the refactor makes it principled, not
   newly possible.
6. **Bluebell is Claude-backed but its call is welded to sky-scoring**, so it
   currently *cannot* fire on a sky stand-down — the concrete defect motivating
   per-observer triage + own call.
7. **Aurora is a global signal, not a per-element one** — it belongs in the
   calendar/global observer family, not the per-location traversal.
