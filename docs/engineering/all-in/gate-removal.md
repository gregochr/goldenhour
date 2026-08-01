# DRAFT — gate-removal

> ⚠️ **Working material, not the build source.** Written against a mix of `a484d1c4`
> (not an ancestor of `main`) and `d421ef5f`; citations of `ForecastTaskCollector.java`
> past line 534 are off by +16. Adversarial verification refuted claims in this draft —
> see `gate-removal.corrections.md`. **Resolved positions live in `../all-in-build-plan.md`,
> which supersedes this file wherever they disagree.**

---

## §G — Gate removal and the collapse of candidate collection

Everything below was read from the tree at `a484d1c4`. Where a claim could not be checked
against a file it is marked **UNVERIFIED** in place.

---

### G.0 — Scope, restated against the brief

The design doc's §4.1 table (`docs/engineering/all-in-architecture-design.md:170–182`) keeps
four gates: weather triage at 80, travel day, hard constraint, and the T+0..T+3 horizon bound.
**Three of those four are now deleted.** Only the travel-day gate survives as policy; the
horizon bound survives only as a *structural* fact, and §G.4.4 shows it is no longer a policy
at all once the slate is built at the head of the cycle.

The unifying rule — *classify for display; never gate on the classification* — is not a slogan
here. Every gate in this section has a sibling piece of code that computes the same thing for
the screen. The sibling always survives. §G.1 locates every one of those boundaries at a line
number, because getting one wrong guts the Plan tab.

---

### G.1 — The display/gate boundary, located precisely

#### G.1.1 The Plan-tab GO/MARGINAL/STANDDOWN verdict

**Producer (survives, untouched):** `BriefingSlotBuilder.buildSlot`.

| Step | File:line |
|---|---|
| base weather verdict | `BriefingSlotBuilder.java:143` — `verdictEvaluator.determineVerdict(lowCloud, precip, visibility, humidity)` |
| mid-cloud demotion | `:146` |
| building-cloud demotion | `:148–151` |
| clear-all-layers demotion | `:153–160` |
| solar-horizon-cloud demotion | `:162–166` |
| **tide override to STANDDOWN** | `:171–177` |
| flags | `:179–186` |
| `standdownReason` label | `:189–191` (`verdictEvaluator.deriveStanddownReason(...)`) |
| verdict lands on the slot | `:205–207` (`new BriefingSlot(..., verdict, ..., standdownReason)`) |

`BriefingVerdictEvaluator` (543 lines) and `WoodlandVerdictEvaluator` are pure display
producers. **Neither is touched.** The tide-mismatch STANDDOWN at
`BriefingSlotBuilder.java:171–177` in particular stays: a low-tide-only beach at high tide must
still *read* STANDDOWN on the grid.

**Consumer that gates (dies):** `BriefingCandidateCollector.java:239–258`.

```java
if (!BriefingGatingPolicy.isEligibleForEvaluation(slot)) {        // :239
    ...
    dispositions.add(new CandidateDisposition(..., 
            DispositionCategory.SKIPPED_HARD_CONSTRAINT, ...));   // :251-255
    skippedVerdict++;
    continue;                                                     // :257
}
```

That block, and the whole of `BriefingGatingPolicy` (130 lines), is the gate. It reads a
display field (`slot.verdict()` at `BriefingGatingPolicy.java:81`, `slot.standdownReason()` at
`:84`, `:123`) and turns it into a skip. It goes.

The boundary is therefore exactly: **`BriefingSlot.verdict` is written by the slot builder and
read by the UI; the only line that reads it to *decide about a Claude call* is
`BriefingCandidateCollector.java:239`.** Deleting `BriefingGatingPolicy` breaks two other
call sites, both in publication, both fixed by inlining "true":

- `BriefingGlossService.java:203` — `if (BriefingGatingPolicy.hasAnyEligibleSlot(region))`
  → `if (region.slots() != null && !region.slots().isEmpty())`
- `BriefingGlossService.java:266–269` — `.filter(BriefingGatingPolicy::isEligibleForEvaluation)`
  → drop the filter; `evaluableSlots` becomes `region.slots()`

⚠️ **This is a real behaviour change and must be decided explicitly, not absorbed.** Today a
region whose slots are *all* tide-mismatched gets no gloss call. Ungated, it gets one. And at
`BriefingGlossService.java:271–275` the median cloud that feeds the gloss prompt is computed
over `goSlots.isEmpty() ? evaluableSlots : goSlots` — widening `evaluableSlots` to all slots
changes the median input for regions with zero GO slots. See G.13 open question 1.

Verified non-gating readers of `Verdict`, all of which survive untouched:
`BriefingHierarchyBuilder.java:136`, `BriefingHeadlineGenerator.java:65,140,188,190`,
`BriefingRollupBuilder.java:258–262`, `CloseToHomeService.java:531,544`,
`EvaluationViewService.java:458,487`, `BriefingGlossService.java:294–296`.

#### G.1.2 Weather triage — skip-only, no display reader at all

`WeatherTriageEvaluator` (65 lines) has exactly **one** production caller:
`ForecastService.java:367`. A whole-tree grep for the type name returns only
`ForecastService.java:72,98`, the class itself, and a Javadoc cross-reference in
`model/Verdict.java:6`. **It feeds no display.** The thresholds it encodes
(`CLOUD_THRESHOLD=80` at `WeatherTriageEvaluator.java:26`, `PRECIP_THRESHOLD=2.0` at `:27`,
`VISIBILITY_THRESHOLD=5000` at `:28`) are *duplicated* on the display side by
`BriefingVerdictEvaluator` — that is what `model/Verdict.java:6` means by "Aligned with
`WeatherTriageEvaluator` thresholds". So the knowledge survives in the display evaluator; only
the skip dies.

`TideAlignmentEvaluator` (111 lines) is likewise skip-only, called once at
`ForecastService.java:396`, and only when the `TIDE_ALIGNMENT` optimisation strategy is on
(`ForecastCommandExecutor.java:286–287`). It dies twice over — once as a gate, once with the
optimisation-strategy subsystem.

#### G.1.3 Stability — the classifier lives, the gates die

Two gating readers of `ForecastStability`, both deleted:

- `ForecastTaskCollector.java:448` — `eligibilityPolicy.resolve(daysAhead, stability, ...)`
- `ForecastCommandExecutor.java:624` — `NightlyEligibilityPolicy.INSTANCE.permitsHorizon(...)`

The display/prompt readers survive: `ForecastCommandExecutor.enrichWithStability`
(`:650–676`, writes `AtmosphericData.withStability`, which `PromptBuilder` renders as the
`FORECAST RELIABILITY:` block), and `GridCellStabilityService.classifyGridCellsAndPublishSnapshot`.

⚠️ **A trap, and it is a one-line mistake to make.** `applyStabilityFilter`
(`ForecastCommandExecutor.java:615–645`) does *two* jobs: it calls
`gridCellStabilityService.classifyGridCellsAndPublishSnapshot(batch)` at `:616–617` **and**
filters at `:620–632`. Deleting the method wholesale deletes the classify call that
`enrichWithStability` at `:369` depends on, silently removing the reliability block from every
prompt on the synchronous path. Hoist the classify call to the call site; delete only the
filter and the `StabilityFilterResult` record (`:596–599`).

⚠️ **An undocumented asymmetry this deletion happens to fix.** At
`ForecastCommandExecutor.java:351–358`, a manually-triggered run bypasses the filter — and
therefore leaves `stabilityByCell = Map.of()` (`:351`), so `enrichWithStability` early-returns
at `:653–655` and **manual runs get no `FORECAST RELIABILITY` block today**. Removing the gate
and always classifying gives manual runs the block for the first time. This is a behaviour
improvement, not a regression; say so in the commit message so it is not read as a bug.

---

### G.2 — Gate inventory: every site, with its verdict

| # | Gate | Decision site (file:line) | Verdict |
|---|---|---|---|
| 1 | Weather triage `solarLow>80 \| precip>2mm \| vis<5km` | `ForecastService.java:367–384`; consumed at `ForecastTaskCollector.java:434–444`, `:778–780`, `ForecastCommandExecutor.java:301–303` | **DELETE** |
| 2 | Tide-alignment triage (SEASCAPE) | `ForecastService.java:386–415` | **DELETE** |
| 3 | Verdict / hard constraint | `BriefingCandidateCollector.java:239–258` via `BriefingGatingPolicy.java:80–93` | **DELETE** |
| 4 | Freshness / `SKIPPED_CACHED` | `BriefingCandidateCollector.java:214–236` via `FreshnessResolver.java:58–66` + `BriefingEvaluationService.hasFreshEvaluation:186–192` | **DELETE** |
| 5 | Nightly stability (Gate 4) | `NightlyEligibilityPolicy.java:45–55`, applied at `ForecastTaskCollector.java:448`, `:787–791` | **DELETE** |
| 6 | Nightly stability, sync engine | `NightlyEligibilityPolicy.permitsHorizon:68–72`, applied at `ForecastCommandExecutor.java:624` | **DELETE** |
| 7 | Intraday settled-skip | `IntradayEligibilityPolicy.java:47–52` | **DELETE** |
| 8 | Optimisation strategies (7) | `OptimisationSkipEvaluator.shouldSkip:67–159`, applied at `ForecastCommandExecutor.java:260–262` | **DELETE** |
| 9 | `SENTINEL_SAMPLING` region early-stop | `ForecastCommandExecutor.runSentinelPhase:491–571` (canned rows at `:553–558`) | **DELETE** |
| 10 | `TIDE_ALIGNMENT` strategy flag | `ForecastCommandExecutor.java:286–287` → `ForecastService.java:389` | **DELETE** |
| 11 | Force-eval-through-the-gate | `ForceEvalHeadlineSelector.java:90–139`; applied `ForecastTaskCollector.java:363–368`, `:450–479` | **DELETE** (guards nothing once #5 goes — confirmed: `:455` is only reached from the `!decision.eligible()` branch at `:451`) |
| 12 | Gloss region filter | `BriefingGlossService.java:203` | **DELETE** — see G.13 Q1 |
| — | **Travel day** | `BriefingCandidateCollector.java:177–198` via `TravelDayService.isTravelDay:49` | **KEEP** — the one surviving policy gate |
| — | Past date | `BriefingCandidateCollector.java:151–172` | **KEEP** (structural) |
| — | Unknown location | `BriefingCandidateCollector.java:259–270` | **KEEP** (structural) |
| — | Cycle window filter | `CandidateCollectionStrategy.includes`, applied `BriefingCandidateCollector.java:206–208` | **KEEP** — this is *cycle scope*, not policy. Its own Javadoc (`:201–205`) says slots outside it "are not 'decided against', they simply aren't this cycle's responsibility", and it records **no disposition** — which is exactly the tell that it is not a gate. |
| — | Prefetch-degradation abort | `ForecastTaskCollector.java:306–322` | **KEEP** (upstream outage, not policy) |
| — | Bluebell/woodland routing | `ForecastTaskCollector.java:391–433`, `:496–528` | **KEEP** — subject routing |

---

### G.3 — What the candidate set becomes

**Definition after surgery**, and it is now sayable in one line:

> every enabled colour location (`BriefingService.java:417–418`) × every date in the slate
> window × {SUNRISE, SUNSET} (`BriefingHierarchyBuilder.java:57`), minus travel days, minus
> past dates, minus names that do not resolve to an enabled `LocationEntity`.

Note that the batch path never consulted `LocationEntity.solarEventType` — `shouldEvaluateSunrise` /
`shouldEvaluateSunset` are called only from `ForecastCommandExecutor.java:247,250`
(whole-tree grep). Both target types are therefore already in the set today.

#### G.3.1 `BriefingCandidateCollector` survives — at ~110 lines, down from 354

It survives because three things still have to happen per slot (travel, past-date, resolve),
and because a disposition must still be recorded for each. What dies is the freshness block
(`:214–236`), the verdict block (`:237–258`), and the entire stability-lookup apparatus
(`:135–142`, `:281`, `:292–333`) which existed *only* to pick a freshness threshold.

Replacement body — this is the whole method:

```java
public Result collectForecastCandidates(DailyBriefingResponse briefing,
        CandidateCollectionStrategy candidateStrategy) {
    List<CandidateDisposition> dispositions = new ArrayList<>();
    List<ForecastCandidate> candidates = new ArrayList<>();
    LocalDate today = LocalDate.now(clock.withZone(LONDON));

    // Hoisted out of the per-slot loop: the old findLocation() called
    // locationService.findAllEnabled() once PER SLOT (see G.3.2).
    Map<String, LocationEntity> enabled = locationService.findAllEnabled().stream()
            .collect(Collectors.toMap(LocationEntity::getName, l -> l, (a, b) -> a));

    for (BriefingDay day : briefing.days()) {
        LocalDate date = day.date();
        int daysAhead = (int) ChronoUnit.DAYS.between(today, date);
        if (date.isBefore(today)) {
            recordWholeDay(day, date, daysAhead, dispositions,
                    DispositionCategory.SKIPPED_PAST_DATE, "Date in past");
            continue;
        }
        if (travelDayService.isTravelDay(date)) {
            recordWholeDay(day, date, daysAhead, dispositions,
                    DispositionCategory.SKIPPED_TRAVEL_DAY, "Travel day — away");
            continue;
        }
        for (BriefingEventSummary eventSummary : day.eventSummaries()) {
            TargetType targetType = eventSummary.targetType();
            if (!candidateStrategy.includes(date, targetType)) {
                continue;   // not this cycle's responsibility — no disposition, by design
            }
            for (BriefingRegion region : eventSummary.regions()) {
                if (region.slots() == null) {
                    continue;
                }
                for (BriefingSlot slot : region.slots()) {
                    LocationEntity location = enabled.get(slot.locationName());
                    if (location == null) {
                        dispositions.add(new CandidateDisposition(null, slot.locationName(),
                                date, targetType, daysAhead,
                                DispositionCategory.SKIPPED_UNKNOWN_LOCATION,
                                "Location not found in enabled set"));
                        continue;
                    }
                    candidates.add(new ForecastCandidate(location, date, targetType));
                }
            }
        }
    }
    LOG.info("[COLLECT] {} candidates | pastDate={} travelDay={} unknownLoc={}",
            candidates.size(), count(dispositions, SKIPPED_PAST_DATE),
            count(dispositions, SKIPPED_TRAVEL_DAY), count(dispositions, SKIPPED_UNKNOWN_LOCATION));
    return new Result(candidates, dispositions);
}
```

`recordWholeDay` is the existing duplicated triple-nested loop from `:152–166` and `:178–192`,
extracted once — those two blocks are byte-identical apart from the category and detail string.

Constructor drops three dependencies (`briefingEvaluationService`, `freshnessResolver`,
`stabilitySnapshotProvider`) — `BriefingCandidateCollector.java:54–56, 69–81`.

`ForecastCandidate` (a 4-line record) **survives unchanged**. `daysAheadFor(LocalDate, Clock)`
(`:103–106`) survives — `ForecastTaskCollector.java:372` still calls it.

#### G.3.2 A free correctness/perf fix that falls out

`BriefingCandidateCollector.findLocation` (`:285–290`) calls
`locationService.findAllEnabled().stream()...` **once per slot**. At a few hundred slots per
cycle that is a few hundred full-table reads. The rewrite hoists it to one. Not a gate, but it
was hiding inside the gate loop.

---

### G.4 — `ForecastTaskCollector` post-surgery: 883 → ~640 lines

#### G.4.1 What is removed

| Block | Lines | Note |
|---|---|---|
| `forceEvalHeadlineSelector` field + construction | `:124`, `:190–191` | |
| `forceEvalCap` field + `@Value` param + Javadoc | `:111–118`, `:164–165`, `:181` | |
| force-eval key selection + log | `:360–368` | |
| eligibility resolve + force-eval branch + skip branch | `:445–479` | |
| triage skip branch | `:434–444` | |
| `resolveEligibility` legacy helper | `:665–701` | |
| `EligibilityAggregator` nested class | `:816–882` (67 lines) | the `[BATCH ELIG]` cross-tab exists to prove Gate 4 honoured its table; no table, no proof needed |
| region-filtered path: triage + eligibility | `:778–780`, `:784–791` | |
| `freshnessResolver`, `stabilitySnapshotProvider` fields/params | `:103–104`, `:158–159`, `:176–177` | still needed by nothing |
| `forced` plumbing in `includeDisposition` | `:655–663` | collapses to a 5-line constant |
| the three `collectScheduledBatches` overloads collapse to two | `:223–271` | `EligibilityPolicy` param disappears from all of them |

#### G.4.2 What survives, and why

- **weather prefetch + degradation guards** (`:298–327`) — upstream health, not policy
- **cloud prefetch** (`:329–337`)
- **`gridCellStabilityService.classifyGridCellsAndPublishSnapshot`** (`:339–341`) — now purely
  for display + prompt enrichment. Keep the `ephemeral` flag: it is the intraday/nightly
  snapshot-authority seam, not a gate.
- **bluebell / woodland routing fork** (`:391–433`, `:496–528`, `:553–563`) — **subject
  routing.** Note that `:434` currently reads
  `if (preEval.triaged() && !bluebellWoodInSeason && !woodlandTask)`; the two negations exist
  *solely* to exempt canopy sites from sky triage (see the 12-line comment at `:426–433`).
  With triage gone, that exemption is unnecessary — but the routing booleans
  `bluebellScored` (`:391`), `canopySite` (`:393`), `bluebellWoodInSeason` (`:411`),
  `woodlandTask` (`:413`) and the misconfiguration WARN (`:401–409`) all stay. **Do not delete
  the WARN with the triage branch it sits above.**
- **near/far × inland/coastal bucketing** (`:481`, `:530–551`) and `NEAR_TERM_MAX_DAYS` (`:93`)
- **survivor atmosphere write** (`:487–494`)
- **`SKIPPED_ERROR` catch** (`:576–585`)

#### G.4.3 Model selection: two lines, no policy object

`NightlyEligibilityPolicy` was doing exactly one thing anyone still needs — picking the tier.
Inline it at the one site that needs it and delete the four types
(`EligibilityPolicy`, `EligibilityDecision`, `NightlyEligibilityPolicy`,
`IntradayEligibilityPolicy` = 233 lines):

```java
boolean isNearTerm = daysAhead <= NEAR_TERM_MAX_DAYS;
EvaluationModel model = isNearTerm ? nearTermModel : farTermModel;
```

`isNearTerm` is already computed at `:481` for bucketing, so this adds **one** line.
`bluebellTaskFor` / `woodlandTaskFor` (`:631–653`) change signature from
`(candidate, EligibilityDecision decision, preEval)` to
`(candidate, EvaluationModel model, preEval)`.

`ScheduledBatchEvaluationService` drops the `EligibilityPolicy` parameter from four signatures
(`:171–177`, `:192–196`, `:208–214`, `:244–248`, `:336–342`) and its two `requireNonNull`
guards. `PipelineOrchestrator` drops it from `runNightlyCycle` (`:223–227`),
`runIntradayCycle` (`:249–253`), `runCycle` (`:271–284`), `runCycleSynchronously` (`:296–302`)
and `submitPhase` (`:362`). `CandidateCollectionStrategy` and its two impls **stay** — that is
cycle scope, and the intraday cycle still needs its 36-hour decision window.

#### G.4.4 ⚠️ The horizon bound becomes a property of the slate — decide it deliberately

`NightlyEligibilityPolicy.java:54` is the only thing that stops T+4 entering the batch today
(`default -> skip("T+" + daysAhead + " beyond horizon")`). Delete it and the horizon is
whatever the slate contains, i.e. `BriefingService.BRIEFING_WINDOW_DAYS = 5`
(`BriefingService.java:118`) — **T+0..T+4**.

`BRIEFING_WINDOW_DAYS` is 5 rather than 4 purely to compensate for the circularity: its own
27-line Javadoc (`:97–117`) records that the briefing is read a cycle later, its first date has
aged into yesterday, and that this made the T+3 SETTLED tier "unreachable in production" —
"zero candidates at `days_ahead = 3`, and 5,684 at `days_ahead = -1`" over 14 days.

Once §4.7's slate split lands, the roster is never stale and the compensator has no job. Then
**the window size is the only horizon control left in the system.** Set it to 4 (T+0..T+3,
preserving today's intended depth) in the same commit that deletes the policy. Setting it to 5
is a decision to evaluate T+4 — a ~25% candidate-count increase on top of everything in G.8 —
and it must be taken on purpose, not inherited from a workaround.

---

### G.5 — `ForecastService.fetchWeatherAndTriage` → `fetchWeather`

#### G.5.1 The two persistence sites, and a factual correction

The brief calls these "the canned rating=1 entity persistence". **They are not rating=1.** Both
sites build `new SunsetEvaluation(null, null, null, null)` — `ForecastService.java:371` and
`:401` — and the 4-arg convenience constructor (`SunsetEvaluation.java:48–52`) fills the
remaining six fields with null. `ForecastServiceTest.java:809` asserts
`saved.getRating()).isNull()`. **Rating on a triaged row is NULL.** The stale claim survives
only in a `@DisplayName` string at `ForecastServiceTest.java:786` ("canned rating=1") — fix
that string.

This matters for the evidence base: §2.4's argument that
`CloudVerificationRepository.findUnverified` sees only the triaged set turns on triaged rows
existing with **null ratings**, not with a floor of 1. A "1" would have been a scored
population.

#### G.5.2 The method after surgery

Delete `ForecastService.java:366–415` — both triage blocks — leaving `:417–419`'s unconditional
return as the only exit. Then:

- **Rename** `fetchWeatherAndTriage` → `fetchWeather` at both overloads (`:259–264`, `:280–284`)
  and at all seven call sites (`ForecastTaskCollector.java:374`, `:774`;
  `ForecastCommandExecutor.java:474`; `BatchRetryService`; three test fixtures). The name is
  now a lie and a lie in a method name is how the mechanism gets reinvented.
- **Drop** the `boolean tideAlignmentEnabled` parameter from both overloads — its only reader
  was `:389`.
- **Drop** fields/ctor params `weatherTriageEvaluator` (`:72`, `:98`, `:110`) and
  `tideAlignmentEvaluator` (`:73`, `:99`, `:111`).
- **Delete** `persistCannedResult` (`:506–523`) — its only caller is the sentinel phase
  (`ForecastCommandExecutor.java:553–558`), which also dies (G.6).

#### G.5.3 `ForecastPreEvalResult` — drop three components, 82 → ~55 lines

`triaged()`, `triageReason()` and `triageCategory()` (`ForecastPreEvalResult.java:35–37`) all
become permanently `false`/`null`. Delete all three. Downstream:

- `ForecastTaskCollector.java:434`, `:778` — branches deleted (G.4.1)
- `ForecastCommandExecutor.java:301–303` — the `survivors` filter and the `triagedCount` log at
  `:304` collapse; `triageResults` *is* `survivors`
- `ForecastCommandExecutor.java:669` — the 14-arg reconstruction in `enrichWithStability` drops
  three args
- `BatchRetryService.java:249` — a log line referencing `pre.triaged()`; drop the reference
- the convenience constructor (`ForecastPreEvalResult.java:74–81`) disappears, since the two
  constructors differed only by `triageCategory`

#### G.5.4 ⚠️ Interaction with the event-spine area — flag it loudly

`ForecastService.java:376` and `:406` (`repository.save(entity)`) are the **only
`forecast_evaluation` inserts on the batch path** — design doc §2.4, verified. Deleting them
means:

1. **The batch path writes nothing to `forecast_evaluation` at all.** Every batch result already
   goes to `cached_evaluation` via `WriteTarget.BRIEFING_CACHE`
   (`ForecastTaskCollector.java:533`). So the §4.4 `evaluation_event` write must be **in place
   before** these two lines are removed, or the batch path becomes invisible to the durable
   spine and to `CloudVerificationRepository.findUnverified`.
2. **`DispositionCategory.SKIPPED_TRIAGED` and the `outcome=TRIAGED` value in §4.4's schema both
   become unreachable for new rows.** `TRIAGED` survives *only* as a decoder for historical
   rows during the `forecast_evaluation` backfill.
3. **`TriageReason` (48 lines) and `TriageDetails` (the `@Embeddable`) survive as read-only
   types.** They are on the API contract at `ForecastEvaluationDto.java:164`,
   `ForecastListDto.java:62`, `BriefingEvaluationResult.java:28`,
   `LocationEvaluationView.java:45`, and drive a display STANDDOWN at
   `EvaluationViewService.java:458`. After this change **nothing writes them** — verify with a
   test that greps for setter calls, and delete `TriageReason.fromRule` (`:31–47`) along with
   `TriageRule`.
4. Ordering: **G.5 must land after §4.4's `evaluation_event` dual-write (Phase 0), not before.**

---

### G.6 — `ForecastCommandExecutor` (the synchronous engine): 826 → ~620

Optimisation strategies are gates, so the whole subsystem goes, and it takes the sentinel phase
with it (`SENTINEL_SAMPLING` is an `OptimisationStrategyType`).

| Block | Lines | Note |
|---|---|---|
| `optimisationStrategyService` / `optimisationSkipEvaluator` fields + ctor params | `:70–71`, `:107–108`, `:120–121` | |
| `enabledStrategies` resolution | `:163–…` | and its pass-through in the `executeThreePhasePipeline` signature `:229` |
| optimisation skip in the descriptor loop | `:260–266` | leaves `excludedSlots` + `shouldSkipEvent` only |
| `tideAlignmentEnabled` derivation + threading | `:286–287`, `:300`, `:464`, `:469`, `:475` | |
| triage survivor filter + early-stop | `:301–314` | the `EARLY_STOP` phase becomes unreachable on this path |
| sentinel phase invocation | `:316–334` | |
| `runSentinelPhase` | `:491–571` (81 lines) | |
| `SentinelPhaseResult` record | `:778–781` | |
| `applyStabilityFilter` **filter only** | `:620–644` | ⚠️ keep `:616–617`'s classify call — hoist it |
| `StabilityFilterResult` record | `:596–599` | |
| `SentinelSelector` (58 lines, separate file) | — | only caller is `runSentinelPhase:520` |

`enrichWithStability` (`:650–676`) **survives**, and after the hoist it runs for manual runs too
(G.1.3). `runFullEvalPhase` (`:581–588`), `prefetchWeather`, `prefetchCloudPoints`,
`shouldSkipEvent` (`:751–761` — a past-event structural bound) and the wildlife bypass all
survive.

`ModelsController` loses the `PUT /api/models/optimisation` endpoint (`:106–110`) and the
`"optimisationStrategies"` key from the `GET /api/models` payload (`:52`).
`OptimisationStrategyUpdateRequest` (20 lines) goes with it. The frontend
`ModelSelectionView.jsx` + `modelsApi.js` optimisation surface goes (§4.6's "Ops UI shrink"),
along with `JobRunsMetricsView.jsx`'s `activeStrategies` column —
`JobRunEntity.activeStrategies` (`:99–100`) and `JobRunDto` (`:29`, `:48`, `:75`) become dead
and should be dropped; the `job_run.active_strategies` column can be dropped in the same
migration as `optimisation_strategy`.

---

### G.7 — `DispositionCategory`: 12 values → 5

Read from `entity/DispositionCategory.java`:

| Value | Line | Emitter today | After |
|---|---|---|---|
| `EVALUATED` | `:22` | `ForecastTaskCollector.java:661` | **REACHABLE** |
| `SKIPPED_PAST_DATE` | `:71` | `BriefingCandidateCollector.java:162` | **REACHABLE** (structural) |
| `SKIPPED_TRAVEL_DAY` | `:79` | `BriefingCandidateCollector.java:188` | **REACHABLE** (the one policy gate) |
| `SKIPPED_UNKNOWN_LOCATION` | `:93` | `BriefingCandidateCollector.java:266` | **REACHABLE** (structural) |
| `SKIPPED_ERROR` | `:100` | `ForecastTaskCollector.java:583` | **REACHABLE** (assembly failure) |
| `FORCE_EVALUATED` | `:33` | `ForecastTaskCollector.java:661` | unreachable — force-eval dies |
| `SKIPPED_HARD_CONSTRAINT` | `:40` | `BriefingCandidateCollector.java:253` | unreachable |
| `SKIPPED_TRIAGED` | `:59` | `ForecastTaskCollector.java:441` | unreachable |
| `SKIPPED_CACHED` | `:65` | `BriefingCandidateCollector.java:229` | unreachable |
| `SKIPPED_STABILITY` | `:86` | `EligibilityDecision.java:53` | unreachable |
| `SKIPPED_NO_REFRESH_NEEDED` | `:107` | `IntradayEligibilityPolicy.java:50` | unreachable |
| `SKIPPED_NO_PROMPT` | `:57` | **none** | **already unreachable today** |

`SKIPPED_NO_PROMPT` has no emitter in `src/main` — a whole-tree grep finds it only at its own
declaration and in a comment at `V135__seed_woodland_forecast_type.sql:4` explaining that the
woodland lane replaced it. It is pre-existing dead code and should go regardless of this work.

#### Does the disposition concept still earn its place? **Yes — but the table does not.**

The instinct to delete it is wrong for a specific reason. §2.7's rule is that a persisted
decision must record *why*, in the same table as the outcomes, because every retraction in the
evidence base was a statistic over a population selected by the thing being measured. Five
survivors is not "one WHY" — they are **four different kinds of why**:

- `SKIPPED_TRAVEL_DAY` — a **policy** decision that removes whole days non-randomly. It is
  already the largest single skip class (52.8% of intraday skips, §1), and design-doc §1 shows
  it distorts a month's cost by a factor of ~1.6. A query over evaluated slots that does not
  know which days were travel days is exactly the selection-effect trap.
- `SKIPPED_PAST_DATE` / `SKIPPED_UNKNOWN_LOCATION` — **structural**, and both are tripwires:
  the first is how the T+3 bug was caught at all ("5,684 rows at `days_ahead = -1`",
  `BriefingService.java:108–112`); the second exists so misconfiguration is visible rather than
  swallowed (`DispositionCategory.java:90–92`).
- `SKIPPED_ERROR` — a **failure**, and the one that matters most once the policy gates are
  gone. A systematic Open-Meteo failure concentrated on, say, coastal locations would bias every
  downstream statistic in precisely the way triage did — silently, and only visible because the
  skip is recorded next to the outcomes.

**What is overweight is the machinery, not the concept.** Retire:

- `forecast_run_disposition` as a separate table → the five survivors become
  `evaluation_event.outcome` + `skip_category` (§4.4). This is the §2.4 merge, and it is what
  makes the numerator and denominator queryable together.
- `ReclassSummary` (53 lines) — it counts `SKIPPED_NO_REFRESH_NEEDED` and `FORCE_EVALUATED`
  (`:38–39`), both unreachable. The `Consumer<ReclassSummary> betweenCollectAndSubmit` hook
  (`ScheduledBatchEvaluationService.java:342`, `:348`) still has a job — it separates intraday's
  `STABILITY_RECLASSIFY` phase from `FORECAST_BATCH_SUBMIT` — so retype it
  `Consumer<CollectionSummary>` carrying `(considered, evaluated, skipped)`.
- `DispositionCategory.fromString` forward-compat (`:117–127`) can stay; it costs 11 lines and
  is genuinely load-bearing during the transition, when old rows carry the seven retired values.
- The `disposition_cleanup` scheduler job (`V101__forecast_run_disposition.sql:40–47`, 30-day
  prune) must **not** be inherited by `evaluation_event` — §4.4 is append-only history and
  history is the point. Delete the job with the table.

---

### G.8 — The cost number, and the estimate that no longer holds

#### G.8.1 The conflict, surfaced

Design-doc §2.3 (`all-in-architecture-design.md:98–104`) argued triage stays at 80, on two
grounds: (a) loosening it buys nothing visible, because newly-admitted slots all have solar low
> 60% and `SYSTEM_PROMPT` already forces those to rating 1–2; (b) each newly-admitted slot costs
one Claude call. That reasoning is sound and is **not** retracted by evidence — **the user has
overridden it as a design choice.** The honest framing for the commit message is "we are paying
for uniformity", not "triage was wrong".

Consequence (b) is real and must be visible:

> **The +£32–48/month all-in delta in §1 was computed with weather triage and the
> hard-constraint gate RETAINED. It no longer holds and must not be used as the Phase 2
> acceptance threshold.** Re-measure before Phase 2 ships.

#### G.8.2 What the code says the exposure is

Triage fires on the *first* match of three rules (`WeatherTriageEvaluator.java:36–63`):
solar-horizon low cloud > 80%, precipitation > 2 mm, visibility < 5 km. Over a UK winter these
are not rare. The hard-constraint gate is narrower — only `TIDE_MISMATCH`
(`BriefingGatingPolicy.java:62–63`) — but applies to every SEASCAPE slot on every cycle.

The only concrete figure anywhere in the tree is the worked example in
`V101__forecast_run_disposition.sql:6–8`:

> `242 candidates → 163 evaluated · 48 hard-constraint · 41 triaged · 2 cached · 1 past-date`

**UNVERIFIED as a 30-day statistic** — it is an illustrative comment, reproduced as a fixture in
`JobMetricsControllerDispositionTest.java:37–40`. Taken at face value it says triage is 16.9%
and hard-constraint 19.8% of a cycle's slots, and that the evaluated fraction is **67.4%**.
Removing gates 1–3 takes that to ~99.6% of non-travel, non-past candidates — a **×1.48** on the
evaluate count, on top of whatever the freshness (7.0–8.7%) and stability (1.7%) removals cost,
and on top of any T+4 admission from G.4.4. That is a materially larger step than the one
§1 priced.

#### G.8.3 The queries that replace the guess

Run these against production Postgres **before** Phase 2. Note the 30-day ceiling is not a
choice: `disposition_cleanup` prunes older rows (`V101__forecast_run_disposition.sql:40–47`).

```sql
-- (1) 30-day disposition mix — the headline. Triage + hard-constraint are the new spend.
SELECT d.disposition,
       count(*)                                                        AS slots,
       round(100.0 * count(*) / sum(count(*)) OVER (), 1)              AS pct
FROM forecast_run_disposition d
WHERE d.created_at >= now() - interval '30 days'
GROUP BY d.disposition
ORDER BY slots DESC;

-- (2) Per cycle, so a mean and a spread are both visible (not just a total).
SELECT j.run_type,
       j.started_at::date                                              AS cycle_date,
       count(*)                                                        AS considered,
       count(*) FILTER (WHERE d.disposition = 'SKIPPED_TRIAGED')       AS triaged,
       count(*) FILTER (WHERE d.disposition = 'SKIPPED_HARD_CONSTRAINT') AS hard_constraint,
       count(*) FILTER (WHERE d.disposition IN ('EVALUATED','FORCE_EVALUATED')) AS evaluated
FROM forecast_run_disposition d
JOIN job_run j ON j.id = d.job_run_id
WHERE d.created_at >= now() - interval '30 days'
GROUP BY 1, 2
ORDER BY 2 DESC, 1;

-- (3) Marginal cost of ONE newly-admitted slot: mean cost of a batch forecast call.
SELECT a.evaluation_model,
       count(*)                                                        AS calls,
       round(avg(a.cost_micro_dollars) / 1e6, 6)                       AS avg_usd_per_call
FROM api_call_log a
WHERE a.service = 'ANTHROPIC'
  AND a.is_batch = true
  AND a.succeeded = true
  AND a.called_at >= now() - interval '30 days'
GROUP BY 1;

-- (4) The projected delta. (1)/(2) give the slot counts, (3) the unit price.
--     new_monthly_usd = (triaged + hard_constraint) * avg_usd_per_call
--     ...and then divide by NON-TRAVEL days, per §1, or the comparison is meaningless.
SELECT count(DISTINCT d.evaluation_date) FILTER (
           WHERE d.disposition <> 'SKIPPED_TRAVEL_DAY')                AS non_travel_days
FROM forecast_run_disposition d
WHERE d.created_at >= now() - interval '30 days';
```

**Phase 2's acceptance gate becomes:** re-run (1)–(4) on the week after the gates drop, compare
against the pre-change run **per non-travel day**, and hold the result against a threshold
recomputed from (4) — never against +£32–48.

---

### G.9 — Tests: 14 classes deleted outright, 10 amended

#### Deleted outright (2,578 lines)

| Test class | Lines | Reason |
|---|---|---|
| `service/WeatherTriageEvaluatorTest.java` | 195 | subject deleted |
| `service/TideAlignmentEvaluatorTest.java` | 247 | subject deleted |
| `service/BriefingGatingPolicyTest.java` | 186 | subject deleted (incl. the label round-trip pin) |
| `service/FreshnessResolverTest.java` | 91 | subject deleted |
| `service/OptimisationSkipEvaluatorTest.java` | 368 | subject deleted |
| `service/OptimisationStrategyServiceTest.java` | 216 | subject deleted (incl. mutual-exclusion validation) |
| `service/SentinelSelectorTest.java` | 138 | subject deleted |
| `service/batch/NightlyEligibilityPolicyTest.java` | 58 | subject deleted |
| `service/batch/IntradayEligibilityPolicyTest.java` | 67 | subject deleted |
| `service/batch/ForecastTaskCollectorEligibilityPolicyTest.java` | 117 | policy param gone |
| `service/batch/ForecastTaskCollectorForceEvalTest.java` | 350 | force-eval gone |
| `service/batch/CollectForecastTasksCachedGateTest.java` | 350 | ⚠️ **except** its travel-day test — see below |
| `service/BriefingEvaluationServiceCacheFreshnessTest.java` | 135 | `hasFreshEvaluation` gone |
| `model/TriageResultTest.java` | 60 | `TriageResult`/`TriageRule` gone |

⚠️ `CollectForecastTasksCachedGateTest.java:293–307` contains the **travel-day** disposition
test — the one surviving gate's only unit coverage. **Move it** into a new
`BriefingCandidateCollectorTest` before deleting the file. Losing it would leave the single
surviving policy gate untested.

#### Amended

| Test class | Tests | What changes |
|---|---|---|
| `service/ForecastServiceTest.java` | 63 | ~18 triage/canned tests go (`:414`, `:514`, `:559`, `:586`, `:787`, `:820`, `:1091`, `:1149`, `:1461`, `:1488`, and the whole `TideAlignmentWindowTests` nested class at `:1581–1652`). ⚠️ Fix the stale `@DisplayName` at `:786` in the same commit that deletes it — the string is the source of the "rating=1" error. |
| `service/ForecastCommandExecutorTest.java` | 65 | sentinel-phase, optimisation-skip, tide-alignment and stability-filter tests go. **Add** a test pinning that `enrichWithStability` now receives a non-empty map on a manually-triggered run (G.1.3). |
| `service/batch/ForecastTaskCollectorTest.java` | 49 | `:1074` (SKIPPED_TRIAGED), `:1104` (SKIPPED_STABILITY), `:1110–1145` (SKIPPED_NO_REFRESH_NEEDED), `:1197` (SKIPPED_HARD_CONSTRAINT), `:1206–1220` (SKIPPED_CACHED), `:1357–1359` all go. `:1240` (PAST_DATE), `:1257` (UNKNOWN_LOCATION), `:1263–1276` (ERROR) **stay**. |
| `entity/DispositionCategoryTest.java` | 64 lines | `:56–62` asserts `SKIPPED_NO_REFRESH_NEEDED` is present-but-unpopulated. Replace with a **reachability pin**: assert the emitted set is exactly the five survivors. |
| `integration/OrchestratedDispositionWriteIntegrationTest.java` | 315 lines | `:131–185` (CACHED + HARD_CONSTRAINT), `:212` (TRIAGED), `:252–293` (NO_REFRESH_NEEDED) go. Keep the orchestrated-write shape. |
| `integration/DispositionWriteIntegrationTest.java` | 266 lines | `:151–207` six-row fixture rebuilt from the five surviving categories. |
| `controller/JobMetricsControllerDispositionTest.java` | — | `:37–40` fixture uses retired categories. |
| `service/batch/ScheduledBatchEvaluationServiceTest.java` | 20 | `:229`, `:277–280` fixtures; policy param drops from four call signatures. |
| `service/pipeline/PipelineOrchestratorTest.java` | 28 | policy param drops. |
| `controller/ModelsControllerTest.java` | 21 | optimisation endpoint tests go. |
| `service/batch/BatchRetryServiceTest.java` | 12 | `pre.triaged()` reference at `BatchRetryService.java:249`. |

**Never touched:** `src/test/java/**/regression/**` (binding constraint), and
`service/evaluation/SystemPromptCacheabilityTest.java` — nothing in this section changes
`SYSTEM_PROMPT`, and the 15,500-char floor (`PromptBuilder.java:65`) is untouched. The
`FORECAST RELIABILITY` guidance in the system prompt stays even though the block is now emitted
*more* often, not less.

**JaCoCo:** the ~110-line `BriefingCandidateCollector` and the ~55-line
`ForecastPreEvalResult` both need 80% line coverage as small classes. Cover the
`region.slots() == null` guard and the `enabled.get(...) == null` branch with real assertions —
do not delete them to hit the number.

---

### G.10 — Dead config, endpoints and data

**Config keys** — grepped across all five of `application.yml`, `application-dev.yml`,
`application-prod.yml`, `application-local.yml`, `application-example.yml`:

| Key | Where declared | Note |
|---|---|---|
| `photocast.freshness.settled-hours` | `application-example.yml:176` | |
| `photocast.freshness.transitional-hours` | `:177` | |
| `photocast.freshness.unsettled-hours` | `:178` | |
| `photocast.freshness.safety-floor-hours` | `:179` | |
| `photocast.batch.force-eval-cap` | **nowhere** | `@Value("${photocast.batch.force-eval-cap:6}")` at `ForecastTaskCollector.java:164` — declared only as an inline default. Verified absent from all five yml files. |

The meteorological rationale in `FreshnessProperties.java:24–50` (36 h ≈ blocking-high
persistence; 12 h ≈ half a synoptic cycle / 2× NWS 6 h cadence; 4 h ≈ outer edge of the
nowcasting regime) **must be moved into this document or into §4.5's staleness constant before
the file is deleted** — that is §0's rule and it is the only place those numbers are written
down.

`photocast.batch.min-prefetch-success-ratio` (`application-example.yml:150`) **stays** — it
guards upstream degradation, not policy.

**Endpoints:** `PUT /api/models/optimisation` deleted (`ModelsController.java:106–110`);
`GET /api/models` loses its `optimisationStrategies` key (`:52`). Update CLAUDE.md's endpoint
list, which currently advertises `PUT /api/models/active|optimisation`.

**Data:** `DROP TABLE optimisation_strategy` (created `V41`, seeded/amended by `V42`, `V49`,
`V52`, `V54`); `ALTER TABLE job_run DROP COLUMN active_strategies`. `forecast_run_disposition`
(`V101`) and its `disposition_cleanup` scheduler row are retired by §4.4, not by this section —
sequence them after `evaluation_event` reconciles. Write it Postgres-natively; there is no H2
runtime and no compatibility constraint (`pom.xml:250–254`, h2 is `<scope>test</scope>`).

---

### G.11 — Line count

**Whole-file production deletions (1,583 lines):**

`WeatherTriageEvaluator` 65 · `TideAlignmentEvaluator` 111 · `BriefingGatingPolicy` 130 ·
`FreshnessResolver` 67 · `FreshnessProperties` 51 · `OptimisationSkipEvaluator` 206 ·
`OptimisationStrategyService` 241 · `OptimisationStrategyEntity` 60 · `OptimisationStrategyType`
39 · `OptimisationStrategyRepository` 41 · `OptimisationStrategyUpdateRequest` 20 ·
`SentinelSelector` 58 · `TriageResult` 22 · `TriageRule` 19 · `ForceEvalHeadlineSelector` 167 ·
`NightlyEligibilityPolicy` 73 · `IntradayEligibilityPolicy` 54 · `EligibilityPolicy` 40 ·
`EligibilityDecision` 66 · `ReclassSummary` 53.

**In-place production shrinkage (~660 lines):**

`BriefingCandidateCollector` 354→110 (−244) · `ForecastTaskCollector` 883→~640 (−243) ·
`ForecastCommandExecutor` 826→~620 (−206)  … partially offset by ~+30 lines of new code
(`recordWholeDay`, the hoisted lookup map, the two-line model selector). Plus
`ForecastService` −95, `ForecastPreEvalResult` −27, `ScheduledBatchEvaluationService` −40,
`PipelineOrchestrator` −30, `BriefingEvaluationService` −30, `ModelsController` −20,
`BriefingGlossService` −6, `TriageReason` −17.

**Production total: ≈ 2,250 lines removed.**
**Test total: 2,578 whole-file + ≈ 1,600 in-place ≈ 4,180 lines.**
**Section total ≈ 6,400 lines, four Spring beans and one DB table.**

---

### G.12 — Interactions to hand to the other areas

1. **Event spine (§4.4).** G.5.4 — deleting the two `forecast_evaluation` inserts makes the
   batch path write nothing durable. `evaluation_event` dual-write must land **first**.
   `outcome=TRIAGED` and `skip_category` values `SKIPPED_TRIAGED / _CACHED / _STABILITY /
   _HARD_CONSTRAINT / _NO_REFRESH_NEEDED / FORCE_EVALUATED` become history-only decoders.
2. **Circularity (§4.7).** G.4.4 — once the slate is built at the head of the cycle,
   `BRIEFING_WINDOW_DAYS` is the **only** horizon control. That is now this section's problem
   too: the two changes must ship in a known order or the horizon silently becomes T+4.
3. **Stability display (§4.2).** G.1.3 — `applyStabilityFilter` must be split, not deleted, and
   manual runs gain a reliability block they never had.
4. **Staleness (§4.5).** The three `FreshnessProperties` TTLs collapse to one display
   threshold; the meteorological rationale must be re-homed before deletion.
5. **Ops UI (§4.6).** The disposition view's category filter shrinks to five values; the
   optimisation-strategy configuration screen and the `activeStrategies` job-run column go.

---

### G.13 — Open questions requiring a decision, not a default

1. **Does the gloss gate go?** `BriefingGlossService.java:203` gates a Claude call on the same
   `BriefingGatingPolicy` predicate as the evaluation gate, but at publication stage and at
   region grain. Deleting it is consistent with "no gates" and costs one extra Haiku gloss per
   all-hard-constrained region per cycle; keeping it means keeping a 130-line class for two call
   sites. **Recommendation: delete, and inline `!region.slots().isEmpty()`** — but the
   median-source change at `:271–275` is a real prompt-input change and needs a look at the
   rendered gloss before it ships.
2. **`BRIEFING_WINDOW_DAYS`: 4 or 5?** (G.4.4). Recommendation: 4, decided explicitly in the
   same commit as the policy deletion.
3. **Does the admin region-filtered path
   (`ForecastTaskCollector.collectRegionFilteredBatches:716–808`, 93 lines) still earn its
   place?** With no gates it differs from the scheduled path only by a region filter and a
   weaker prefetch guard. Folding it in would remove another ~90 lines; it has its own callers
   that were not audited here.
4. **Should `SKIPPED_ERROR` be an `outcome` rather than a `skip_category` in `evaluation_event`?**
   It is a failure, not a decision, and the §2.7 rule reads differently for the two.
