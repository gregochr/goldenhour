# Hot Topics — Tide-Path Observer Refactor (Design)

**Status:** Design only — no code written. Stop for review.
**Scope:** Spring Tide + King Tide hot topics *only*. Aurora and Bluebell are
deferred follow-ons (Step 0 reports what they read, then sets them aside).
**Method:** Investigated against the real code. Where the brief or prior session
notes disagreed with the code, the code won and the contradiction is flagged
inline.

---

## TL;DR — the two facts most needed to start building

1. **The traversal decision is (ii): shared-derivation-separate-scopes — NOT
   (i) one shared traversal.** The tide hot-topic detection and the scoring
   `RatingCombiner` run over **physically different location sets at different
   times**: the briefing pipeline walks *all* enabled colour locations (no Claude,
   stand-down included), while the scoring visitors run *post-batch over survivors
   only* (`ForecastResultHandler` line 298–300). Unifying the two traversals would
   **break the stand-down property** (scoring never sees a triaged-out king tide).
   The code therefore supports (ii), and (i) is infeasible *for any traversal
   shared with scoring*. There is a narrower, achievable form of the
   per-element-observer aspiration — see point 2.

2. **The confirmed reuse map says this is rewiring, not rebuilding.** The
   recognition logic, the `HotTopic` model, the `ExpandedHotTopicDetail`
   region-grouped drill-down, the frontend `HotTopicStrip`/`TideExpandedCard`, and
   the `HotTopicSimulation*` harness are all **reusable as-is**. The *only*
   guaranteed structural change is extracting the duplicated tide-derivation logic
   (`BriefingSlotBuilder.calculateTideData` ⟷ `ForecastDataAugmentor.buildTideSnapshot`)
   into one shared utility both consume. That single step is the guaranteed win.

**Subtle but decisive finding:** the per-element tide facts
(`isKingTide`, `isSpringTide`, `lunarTideType`, `lunarPhase`, `moonAtPerigee`)
are **already computed once per slot at briefing-build time** and materialised on
`BriefingSlot.TideInfo`. The two tide *strategies* do **not** re-derive anything —
they are a pure **aggregation layer** that re-scans those pre-computed per-slot
findings. So the "one traversal, per-element observers" shape, scoped to the
briefing cache, *already substantially exists*; what is duplicated is the
**derivation**, shared with the scoring path. That is what (ii) kills.

---

## Step 0 — Verified reuse map

Classification legend: **survives** (no change) · **rehomes** (moves, logic
intact) · **replaced** (deleted, responsibility re-implemented) · **reshaped**
(stays but its body changes to delegate).

| Artifact | Path | Classification | Code-confirmed justification |
|---|---|---|---|
| `HotTopic` record | `model/HotTopic.java` | **survives** | Fields are `type, label, detail, date, priority, filterAction, regions, description, expandedDetail` (lines 28–38). No per-location score field. `Comparable` orders by `priority` asc then `date` asc (lines 75–79). Frontend `HotTopicStrip` PropTypes depend on exactly this shape. No change. |
| `ExpandedHotTopicDetail` | `model/ExpandedHotTopicDetail.java` | **survives** | Already region-grouped: `RegionGroup → List<LocationEntry>` with `TideLocationMetrics(tidePreference)` (lines 37–60, 100–106) and pill-level `TideMetrics(tidalClassification, lunarPhase, sunriseAlignedCount, sunsetAlignedCount)` (lines 83–85). Supports faithful per-location drill-down today. No change. |
| `HotTopicStrategy` interface | `service/HotTopicStrategy.java` | **survives (this pass)** | `List<HotTopic> detect(from, to)` (line 26). Retained because aurora + bluebell still implement it. The two tide impls retire (end state for the tide path); full interface retirement is a *later* pass once all phenomena are ported. |
| `HotTopicAggregator` | `service/HotTopicAggregator.java` | **survives** | Auto-collects `List<HotTopicStrategy>`, flat-maps `detect()`, `.sorted()`, returns; simulation override at lines 50–52. If the tide detection keeps emitting via (refactored) `HotTopicStrategy` beans, the aggregator is untouched. |
| `SpringTideHotTopicStrategy` | `service/SpringTideHotTopicStrategy.java` | **reshaped → retired (end state)** | Detection logic (`findSpringTide`, `isSpringNotKing`, king-suppression guard) ports into the aggregation layer; the strategy's *derivation* dependency is already indirect (it reads `BriefingSlot.TideInfo`, it does not derive). Retired once recognition is ported. |
| `KingTideHotTopicStrategy` | `service/KingTideHotTopicStrategy.java` | **reshaped → retired (end state)** | Same. Also the *home* of shared static helpers `buildExpandedDetail`, `parseTideAlignmentCounts`, `findBestAlignment`, the `formatXxx` builders — these **rehome** into a tide-pill assembly collaborator rather than dying. |
| `BluebellHotTopicStrategy` | `service/BluebellHotTopicStrategy.java` | **survives (deferred)** | Out of scope. See "Bluebell read" below — **prior-brief contradiction flagged**. |
| `AuroraHotTopicStrategy` | `service/AuroraHotTopicStrategy.java` | **survives (deferred)** | Reads `AuroraStateCache.getCurrentLevel()` + `NoaaSwpcClient.getCachedKpForecast()` (lines 87, 169) — regional/global space-weather state, not per-location atoms. Out of scope. See "Aurora deferred" below. |
| `BriefingSlotBuilder.calculateTideData` → `TideResult` | `service/BriefingSlotBuilder.java:220–273` | **reshaped** | **Duplication site #1.** Body delegates to the new shared derivation utility, then maps to `TideResult`. *(Naming correction: the brief calls this `buildTideResult`; the real method is `calculateTideData` returning the `TideResult` record. Code wins.)* |
| `ForecastDataAugmentor.buildTideSnapshot` / `deriveTideContext` | `service/ForecastDataAugmentor.java:198–256` | **reshaped** | **Duplication site #2.** `buildTideSnapshot` becomes a thin adapter over the shared utility, mapping to `TideSnapshot`. `deriveTideContext` keeps its widened-window overlay. |
| `TideService.deriveTideData` / `calculateTideAligned` / `getTideStats` | `service/TideService.java:387, 414, 434` | **survives** | The shared atoms. Called identically by both sides today; the utility calls them once. No change. |
| `LunarPhaseService.classifyTide` (+ `getMoonPhase`, `isMoonAtPerigee`) | `service/LunarPhaseService.java:154` | **survives** | Pure deterministic-from-date atom. `classifyTide`: `REGULAR_TIDE` unless new/full moon, then `KING_TIDE` if perigee else `SPRING_TIDE` (lines 154–161). No change. |
| `BriefingService.refreshBriefing` / `getCachedBriefing` / `getCachedDays` | `service/BriefingService.java:181, 241, 252` | **survives** | The two hot-topic compute points and the read accessor. No flow change — see Step 1. |
| `HotTopicSimulationService` + `…Controller` | `service/…`, `controller/…` | **survives (do not touch)** | Admin demo override + the before/after net. `getSimulatedTopics` returns hardcoded `KING_TIDE_SIM_DETAIL` / `SPRING_TIDE_SIM_DETAIL` (lines 189–225). Endpoint base `/api/admin/hot-topics/simulation` (`…Controller:23`). |
| `BriefingSlot.TideInfo` | `model/BriefingSlot.java` | **survives** | Carries `tideState, tideAligned, nearestHighTideTime, nearestHighTideHeight, isKingTide, isSpringTide, lunarTideType, lunarPhase, moonAtPerigee`. The per-slot finding the aggregation layer reads. |
| Statistical king/spring fields | `TideStats.p95HighMetres()`, `TideStats.springTideThreshold()` | **survives** | The height test atoms. Used by *both* sides today (see Step 2 "statistical signal"). No change. |

### Four prior-notes claims — confirmed against code

1. **No `HotTopicDetector` class — CONFIRMED.** `find -iname "*HotTopic*"` returns
   only the model, the four strategies, the aggregator, the simulation
   service/controller. Detection is the per-strategy `detect()` method. There is no
   `HotTopicDetector`.

2. **King/spring fire on "statistical flag **OR** `lunarTideType`" — CONFIRMED,
   exact condition quoted.**
   - King: `KingTideHotTopicStrategy.isKingTide` (lines 176–180):
     `tide != null && (tide.isKingTide() || tide.lunarTideType() == LunarTideType.KING_TIDE)`.
   - Spring (excluding king): `SpringTideHotTopicStrategy.isSpringNotKing` (lines 156–167):
     first returns `false` if `tide.isKingTide() || lunarTideType == KING_TIDE`,
     then returns `tide.isSpringTide() || lunarTideType == LunarTideType.SPRING_TIDE`.
   - The `isKingTide`/`isSpringTide` booleans are the **statistical height** test
     (`height > p95` / `height > springTideThreshold`), computed in
     `BriefingSlotBuilder.calculateTideData` lines 246–261, *gated* on
     `tideState == HIGH` within `±90 min` of the solar event. The `lunarTideType`
     leg is the **lunar/perigee** test. Either leg fires the topic. ✔

3. **`HotTopic` has no per-location score field — CONFIRMED.** Fields listed above;
   it is region/priority/`filterAction`-shaped. Per-location data lives only in the
   optional `expandedDetail.regionGroups[].locations[]`, and there the tide metric
   is `tidePreference` (a label), not a score.

4. **King-tide presence suppresses the spring-tide topic — CONFIRMED, located and
   quoted.** In `SpringTideHotTopicStrategy.detect` (lines 88–93):
   ```java
   boolean kingTideInWindow = sorted.stream()
           .anyMatch(d -> KingTideHotTopicStrategy.findKingTide(d) != null);
   if (kingTideInWindow) {
       return List.of();
   }
   ```
   The suppression lives **on the spring side**, keyed off the king side's
   `findKingTide`. It is a *window-wide* suppression (any king tide on any day in
   the window kills the whole spring pill), not per-slot.

### Bluebell read — **prior-brief contradiction flagged (code wins)**

The brief states the bluebell scoring prompt "does not exist yet." **The code
contradicts this.** `BluebellHotTopicStrategy` reads `forecast_evaluation.bluebell_score`
via `evaluationRepository.findBluebellEvaluations(...)` (lines 82–83), and that
column **is written**: `PromptBuilder` appends a `BLUEBELL CONDITIONS:` block and
instructs Claude to return `bluebell_score` + `bluebell_summary` for bluebell-season
bluebell sites (lines 505–523), and `ClaudeEvaluationStrategy` parses
`bluebell_score` from the response (lines 240–241). So a bluebell scoring path
**does exist** and populates the column the strategy reads.

Two caveats worth recording (not this pass's concern, but flagged so the deferred
bluebell pass starts from truth):
- **Scale mismatch.** The prompt instruction says `bluebell_score (integer, 0-100)`
  (`PromptBuilder:523`) but every consumer treats it as **0–10**
  (`BluebellHotTopicStrategy` thresholds 6/5, `deriveQualityLabel` ≥9/≥7, frontend
  "`{score}/10`"). The scoring path exists but its scale contract is inconsistent.
- The brief's framing ("whatever it reads, the intended scoring path does not
  exist") is therefore wrong on the existence point. Bluebell is still **set aside
  as a deferred deterministic-per-element observer**, but on the basis that it is
  *out of scope*, not that it is *unbuilt*.

Both caveats are captured as explicit follow-ons in a sibling note —
[bluebell-scoring-deferred-findings.md](bluebell-scoring-deferred-findings.md) —
so they are not lost in this tide-refactor doc: (a) the 0–100/0–10 scale
inconsistency as a season-gated backlog defect, and (b) the
folded-in-vs-dedicated-prompt design discrepancy to reconcile before bluebell
hot-topic work.

### Aurora — deferred, and why (one paragraph)

`AuroraHotTopicStrategy` reads cached **regional/global space-weather state** —
`AuroraStateCache.getCurrentLevel()`/`getLastTriggerKp()` and
`NoaaSwpcClient.getCachedKpForecast()` (lines 87, 92, 169) — plus dark-sky region
membership and a cloud-triaged clear-count from `BriefingAuroraSummaryBuilder`. It
does **not** consume per-location tide-style atoms; its element is "tonight" / "tomorrow
night," not "location-forecast." Folding aurora into a per-element observer would
require modelling a *regional/global-state observer kind* that the tide path does
not need. That is a **separate follow-on question** and must not shape this design.
Deferred; the Step 4 contract is checked only for *not precluding* such an observer
later.

---

## Step 1 — Current traversal & triage map

**1. What each tide strategy iterates over today.** Both strategies call
`briefingService.getCachedDays()` → `List<BriefingDay>`, filter to the
`[fromDate, toDate]` window, then walk
`BriefingDay.eventSummaries() → BriefingEventSummary.regions() → BriefingRegion.slots()`
plus `event.unregioned()`, reading `BriefingSlot.tide()` (a `TideInfo`) per slot
(`KingTideHotTopicStrategy.findKingTide` lines 158–174;
`SpringTideHotTopicStrategy.findSpringTide` lines 138–154). **The scan unit is the
region-grouped cached briefing slot, not "all locations" directly.** The briefing
cache is the intermediary structure.

That cache is built in `BriefingService.refreshBriefing` over **all enabled colour
locations** — `locationService.findAllEnabled().stream().filter(this::isColourLocation)`
(lines 257–259) — with **no verdict filter** and **no Claude call**. Each slot
carries its own `Verdict` (GO/MARGINAL/STANDDOWN), but *every* colour location is in
the cache regardless of verdict. Hot topics are computed at two points, both via
`hotTopicAggregator.getHotTopics(today, today.plusDays(3))`:
- **`refreshBriefing`** (line 331): computed from the freshly-built days and stored
  in the cached `DailyBriefingResponse`.
- **`getCachedBriefing`** (line 193): a live **read-overlay** that recomputes topics
  on read so simulation toggles take effect without a full refresh (lines 190–205).

**2. Triage sits before everything, and the tide strategies are unaffected by it —
verified.** The `HotTopicStrategy` Javadoc states strategies "run AFTER the triage
cycle completes… read-only consumers… MUST NOT make any external API calls" (lines
9–16). The tide strategies make **no Claude call** — they read `getCachedDays()` and,
for the subtitle only, one repository query. Detection reads `slot.tide()` and
**never inspects `slot.verdict()`**. Therefore a king tide fires on a sky stand-down
day: the slot is present (all colour locations are cached), its `TideInfo` carries
the king flag, and `findKingTide` returns it regardless of the STANDDOWN verdict. ✔
*(One cross-source nuance: the alignment-count **subtitle** —
`countTideAlignedByTargetType` — reads `forecast_evaluation` (scoring survivors), so
a triaged-out king-tide location contributes to the **pill firing** but not to the
**aligned count**; the king path handles a zero count with "no tide alignments — but
exceptional coastal foreground," `KingTideHotTopicStrategy:134`.)*

**3. Orthogonality to scoring — confirmed, and `RatingCombiner` is left alone.** A
tide hot topic carries no rating (`HotTopic` has no score field) and is detected from
`BriefingSlot.TideInfo` flags. The 1–5 rating is produced on a *separate* path: at the
post-batch combine seam `ForecastResultHandler` derives a `TideContext`
(`forecastDataAugmentor.deriveTideContext`, line 298) and `RatingCombiner.combine`
averages `SkyVisitor` + `TideVisitor` (line 300). The hot-topic layer neither reads
nor writes `forecast_evaluation.rating`; the rating path neither reads nor writes hot
topics. A tide pill firing does not depend on, and does not alter, any location's
rating. **`RatingCombiner` is untouched by this design** — the "capped bonus" question
from prior notes is a scoring concern and is irrelevant here.

---

## Step 2 — The traversal decision *(the headline)*

### Decision: **(ii) shared-derivation-separate-scopes.** (i) is rejected as
infeasible for any traversal shared with scoring; the per-element-observer
aspiration is achievable only in a *narrower* form scoped to the briefing cache,
which the code shows is **already substantially realised**.

### Code evidence — two physically distinct traversals

| | Briefing/hot-topic traversal | Scoring traversal |
|---|---|---|
| Entry point | `BriefingService.refreshBriefing` (`@Scheduled` 04/14/22) | `ForecastResultHandler` combine seam, post-batch |
| Location set | **All** enabled colour locations, no verdict filter (`:257–259`) | **Batch survivors only** (triaged-out never reach the handler) |
| Claude cost | Zero | The batch Claude call already happened upstream |
| Tide derivation | `BriefingSlotBuilder.calculateTideData` → `TideInfo` on each slot | `ForecastDataAugmentor.buildTideSnapshot` → `TideSnapshot` per survivor (`:298`) |
| Consumes | Tide hot topics (via cache) | `RatingCombiner.combine` (`:300`) |

These are different sets, computed at different times, by different schedulers. The
scoring traversal **cannot** host tide hot-topic detection, because it never sees a
triaged-out location — and the canonical requirement is that a **king tide at St
Mary's must surface on a sky stand-down day**, i.e. precisely a location scoring
would have dropped. A single shared traversal between the two is therefore not just
costly but *semantically wrong*: it would silently drop exactly the locations the
hot topic exists to surface. **(i) is infeasible. Fall to (ii).**

### Why (ii), specifically — and what "observer" means here

The proven duplication is the **derivation**, not the traversal.
`BriefingSlotBuilder.calculateTideData` (lines 220–273) and
`ForecastDataAugmentor.buildTideSnapshot` (lines 219–256) each independently call:
`tideService.deriveTideData(...)` → `tideService.calculateTideAligned(...)` →
`lunarPhaseService.classifyTide / getMoonPhase / isMoonAtPerigee` →
`tideService.getTideStats(...)` for the statistical height comparison against
`p95HighMetres` / `springTideThreshold`. Same atoms, two assemblies. **(ii)
extracts one shared `TideDerivation` utility both consume**; each side keeps its own
output mapping (`TideResult` vs `TideSnapshot`) and its own gating.

Crucially, **the per-element tide finding is already computed once per slot** at
briefing-build time and stored on `TideInfo`; the two strategies are a pure
*aggregation* layer over those findings (Step 1.1). So the "one traversal, per-element
observers" shape — scoped to the briefing cache — *already exists in substance*. The
only genuine duplication left to kill is the derivation shared with scoring. That is
why the honest recommendation is (ii): the duplication kill is real and guaranteed;
the grander traversal unification is either already done (within the briefing) or
infeasible (with scoring).

### Satisfying the three required properties

- **Stand-down property** — satisfied by the **briefing** traversal already walking
  all colour locations. (ii) does not touch which locations the briefing builds, so a
  STANDDOWN king-tide slot remains in the cache and the pill still fires. (i)-with-scoring
  would have broken this; (ii) preserves it by construction.
- **Post-batch timing reality** — (ii) keeps the two run-sets independent: the
  briefing runs zero-Claude over all locations on its own schedule; scoring runs
  post-batch over survivors. The shared utility is a *pure function* (DB + deterministic
  lunar math), safe to call from either context at either time. No timing coupling is
  introduced.
- **Statistical-vs-lunar signal** — the hot-topic side uses a statistical height test
  (`height > p95` / `height > springThreshold`, gated `HIGH` within `±90 min`) that the
  scoring `TideVisitor` does **not** use (the visitor reads `tideAligned` /
  `widenedAligned` / `lunarTideType` only). The scoring `buildTideSnapshot` *also*
  derives a statistical classification (`TideStatisticalSize.EXTRA_HIGH/EXTRA_EXTRA_HIGH`
  via the *same* `p95`/`springThreshold` thresholds, lines 281–302) but **ungated**.
  The shared utility must therefore expose **both raw signals** — the lunar
  classification *and* the statistical height comparison — and let each consumer apply
  its own gating (the briefing side gates on `HIGH`+`±90 min`; scoring leaves it
  ungated). The height signal is preserved, not lost.

### Two worked examples traced through (ii)

**Example 1 — St Mary's, king tide, sky stand-down → king pill still fires.**
1. `refreshBriefing` builds a slot for St Mary's (an enabled colour location).
2. `BriefingSlotBuilder.calculateTideData` (now delegating to `TideDerivation`)
   sets `lunarTideType = KING_TIDE` (perigee + full/new moon) and/or `isKingTide =
   true` (`height > p95`, `HIGH` within `±90 min`); the slot's sky `Verdict` is
   `STANDDOWN`.
3. `KingTideHotTopicStrategy.findKingTide` scans `slot.tide()`, sees the king flag,
   **ignores the STANDDOWN verdict**, emits the `KING_TIDE` topic (priority 1).
4. The subtitle's aligned count comes from `forecast_evaluation`; if St Mary's was
   triaged out of scoring, its count is 0, and the detail reads "no tide alignments —
   but exceptional coastal foreground." **Pill fires.** ✔ Unchanged from today; (ii)
   keeps the derivation identical, just de-duplicated.

**Example 2 — region with a spring-but-not-king tide → spring pill fires; suppressed
if a king co-occurs.**
1. A region's slots carry `lunarTideType = SPRING_TIDE` (or `isSpringTide` via
   threshold) and **no slot anywhere in the window** is king.
2. `SpringTideHotTopicStrategy.detect`: the king-suppression guard
   (`kingTideInWindow == false`) passes; `findSpringTide` returns the slot's
   `TideInfo`; a `SPRING_TIDE` topic (priority 2) is emitted with coastal regions and
   `buildExpandedDetail`.
3. Had any day in the window contained a king tide, the guard `if (kingTideInWindow)
   return List.of()` would suppress the spring pill entirely. ✔

---

## Step 3 — Aggregation & presentation policy

**1. Per-location findings → region-grouped pills, with *better* drill-down.**
Presentation stays region-grouped (`HotTopic.regions` + `ExpandedHotTopicDetail.regionGroups`),
and the model/frontend already support a faithful per-location roll-up — **no change
to either**. Today's `buildExpandedDetail` (`KingTideHotTopicStrategy:229–268`) is
actually a *region-level guess*: it groups **all** `findCoastalLocations()` by region,
not the locations that actually had a king/spring tide. Because the per-slot finding
already exists on `TideInfo`, the aggregation layer can instead roll up the
**real per-location detections** (the coastal slots whose `TideInfo` fired) into
`regionGroups`. `TideExpandedCard` (`HotTopicStrip.jsx:397–463`) renders
`regionGroups[].locations[]` with `tideLocationMetrics.tidePreference` verbatim, so
the drill-down gets strictly more honest with **zero frontend change**. *(This is a
presentation improvement enabled by the refactor, not required by it — flag for the
build pass, don't gold-plate.)*

**2. King-suppresses-spring becomes an aggregation rule (confirm + place).** It
already *is* an aggregation rule, not a detection one: the per-slot king/spring flags
are detected independently at briefing-build; the *suppression* is applied when the
spring topic is assembled (`SpringTideHotTopicStrategy.detect:88–93`). In the target
shape it stays exactly there — in the spring aggregation step, keyed off a window-wide
"any king tide present" check. Detection (per-slot) and suppression (per-window
aggregation) remain cleanly separated.

**3. Ordering/dedup.** The natural order is `priority` asc then `date` asc
(`HotTopic.compareTo:75–79`), applied once in `HotTopicAggregator.getHotTopics`
via `.sorted()` (`:53–56`). King is priority 1, spring priority 2, so king precedes
spring whenever both survive (and they never co-occur, given suppression). This holds
unchanged: the aggregation layer still emits at most one king + at most one spring
topic, and the aggregator's single `.sorted()` is the only ordering site. No new
dedup needed — each tide phenomenon already emits at most one topic per refresh.

---

## Step 4 — Observer contract & registration (design only)

**1. Deterministic per-element observer contract.** Analogous to the scoring
`Visitor` (`appliesTo` + `evaluate : OptionalInt`), a flagging observer answers "does
this element exhibit my phenomenon?" and emits a **finding**, not a score:

```java
interface HotTopicObserver {
    /** Cheap location-shape gate, mirrors Visitor.appliesTo. */
    boolean appliesTo(LocationEntity location);

    /**
     * Inspect one already-derived per-element snapshot; return a finding if the
     * phenomenon is present, else empty. Pure, no I/O, no Claude.
     */
    Optional<HotTopicFinding> observe(LocationEntity location, HotTopicElement element);
}
```

where `HotTopicElement` wraps the per-element atoms the briefing already
materialises (date, event, `TideInfo`, region) and `HotTopicFinding` is a small
record (phenomenon type + the per-location facts that roll up into a `RegionGroup`).
A separate **aggregator** turns `List<HotTopicFinding>` into the region-grouped
`HotTopic` pills, applies king-suppresses-spring, and orders. *Detection emits
per-element findings; aggregation owns suppression/ordering/pill assembly.*

**2. One interface for both scoring and flagging, or two siblings? → Recommend two
sibling interfaces sharing the per-element walk, not one.** A scoring `Visitor`
returns `OptionalInt` and is combined by averaging; a flagging observer returns a
structured *finding* and is combined by region roll-up + suppression. Their *output
algebras differ* (mean vs grouped roll-up-with-veto), so collapsing them into one
interface would force an awkward union return type and a combiner that does both.
Keep `Visitor` (scoring) and `HotTopicObserver` (flagging) as siblings; if a future
single traversal is ever built, it can invoke both sibling sets over the same element
— but, per Step 2, the tide flagging walk and the scoring walk run over *different
sets*, so even the walk is not actually shared for tide.

**3. Forward-compatibility note (do not design).** The contract above must not
*preclude* (a) a future **Claude-backed observer** that carries its own per-observer
triage — `observe` returning `Optional` already allows an observer to internally
decide to abstain, and nothing forces it to be Claude-free at the *interface* level
(only the current tide observers are pure); nor (b) a future **regional/global-state
observer** (aurora) — an observer whose "element" is a region or "tonight" rather than
a location-forecast can implement a sibling shape without disturbing the per-location
`HotTopicObserver`. We confirm the tide contract doesn't paint these into a corner; we
do **not** design them here.

**4. Registration. → Recommend keeping the auto-collected Spring-bean shape.** The
current `List<HotTopicStrategy>` constructor injection into `HotTopicAggregator`
(`:32–36`) is clean and is what the simulation override already wraps. Observers
should register the same way — `List<HotTopicObserver>` auto-collected — so the
aggregator's collect/sort/simulate flow is preserved. During migration the tide
detection can remain behind the existing `HotTopicStrategy` beans (which internally
delegate to the observer walk), so the aggregator never changes.

---

## Step 5 — Migration sequence (plan only; do **not** execute)

Each step is independently shippable and gated by an **empirical** before/after
check — the produced `List<HotTopic>` / rendered pills must match (or change only as
intended), verified via the simulation harness **and** a live briefing check, not
merely green unit tests.

**Step A — Extract the shared `TideDerivation` utility (the guaranteed win).**
- *Changes:* a new pure utility exposing both the lunar classification and the
  statistical height comparison; `BriefingSlotBuilder.calculateTideData` and
  `ForecastDataAugmentor.buildTideSnapshot` both delegate to it, each keeping its own
  gating + output mapping (`TideResult` / `TideSnapshot`).
- *Why independently shippable:* it is a pure de-duplication. The hot topics must
  produce **the same pills** and scoring must be **unchanged**.
- *Empirical gate:* (1) golden-master the `List<HotTopic>` from `getHotTopics` on a
  fixed cached briefing before/after — byte-identical. (2) Enable the simulation
  harness (`/api/admin/hot-topics/simulation`) — `KING_TIDE`/`SPRING_TIDE` sim pills
  are unaffected (they bypass derivation), confirming the harness is a stable net.
  (3) Spot-check a live briefing refresh: tide pills + the per-survivor `rating`
  values unchanged. Scoring regression covered by existing `TideVisitor` /
  `RatingCombiner` tests *plus* a golden-master on persisted `rating`.

**Step B *(only if Step 2 had chosen (i) — it did not; included for completeness,
expected to be skipped)*.** Introduce a per-element `HotTopicObserver` walk over the
briefing cache, port the king/spring recognition into observers, verify identical
`List<HotTopic>` via the golden-master, then retire the two tide strategies. Because
the per-element finding already lives on `TideInfo`, this step is *optional polish*
(collapsing King's and Spring's two independent walks of `getCachedDays()` into one
walk emitting findings), not a correctness requirement. Recommend deferring it unless
the duplicated walk becomes a maintenance burden — the Step-A win stands alone.

**Step C — Leave `HotTopicStrategy` in place.** Aurora and (deferred) bluebell still
implement it. The interface is **not** retired in this pass. Full retirement is a
later pass once every phenomenon is ported.

**Per-step verification summary (golden-master gates):**
- A pinned `DailyBriefingResponse` fixture → run `getHotTopics` → assert the
  serialized `List<HotTopic>` is unchanged across the refactor.
- The simulation harness as a UI-level net (toggle `KING_TIDE`/`SPRING_TIDE`, confirm
  identical rendered pills).
- A live briefing-refresh diff on a real cached day (pills + ratings), not just unit
  tests.

---

## Constraints honoured

- **No code written.** Investigation + design only; stopping for review.
- `HotTopic`, the frontend, and the simulation harness are **confirmed reusable and
  untouched**.
- Aurora gets one "deferred and why" paragraph; bluebell gets a Step-0 read (with a
  flagged contradiction) then is set aside.
- **No `RatingCombiner` / scoring change proposed** — orthogonality confirmed in
  Step 1.3.
- The reuse map was verified against code; contradictions (the `calculateTideData`
  naming, the existence of the bluebell scoring path) are flagged inline, code
  winning.
- The end state for the tide path is the Step-2 shape (ii) with the two tide
  strategies' derivation de-duplicated; no permanent second parallel system is
  proposed.
- No push, no tag, no commit. (Per workflow: confirm with the user before even a
  local commit of this doc.)
