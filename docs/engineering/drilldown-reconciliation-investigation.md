# Planner Drill-Down Reconciliation Investigation

**Date:** 2026-06-18
**Author:** Claude (read-only investigation; no code changed)
**Scope:** Reconcile what the Planner drill-down *displays* against what the
database *contains*, for **The Yorkshire Dales / 2026-06-20 / SUNSET**.

> **Process note:** this is a code trace, not an inference. Two prior confident
> explanations were falsified by direct query. Everything below names the exact
> file, method, and line that produces each behaviour. Where I could not verify
> something from the dev box (production logs, prod row counts) I say so
> explicitly rather than guessing.

---

## TL;DR — the verdict is (A): labelling / framing

The drill-down does **not** read its 54-row location list from
`cached_evaluation` or `forecast_evaluation`. It reads them from the **daily
briefing's in-memory slot hierarchy**, which is rebuilt live from Open-Meteo
weather on every briefing refresh — **one slot per enabled colour location in
the region**, persisted nowhere except the briefing cache (`AtomicReference` +
`daily_briefing_cache`). That is exactly why the two SQL queries came back
empty: the 51 unscored rows were never written to *either* evaluation table.
They are transient weather slots.

- **54 rows** = the Yorkshire Dales colour-location roster (every enabled
  colour location grouped into that region).
- **3 rows with stars + prose** = the `cached_evaluation` entries (3 Claude
  ratings) merged onto matching slots.
- **51 rows labelled "Worth it"** = the remaining slots, whose label is derived
  from their **weather triage verdict** (`Verdict.GO`), not from any Claude
  evaluation: `DisplayVerdict.resolve(null, GO) → WORTH_IT`.
- **"Clear at 54 of 54 locations"** = count of slots with weather verdict `GO`
  over total slots. A pure weather statistic. **Zero** to do with Claude.
- **"ratings … average 4.0, with all evaluated locations scoring highly"** =
  Claude-authored region gloss, fed `claudeAverageRating` computed over **only
  the 3 scored locations**. Honest arithmetic (4.0 over 3), but rendered
  immediately beside the weather-derived "54 of 54", so a reader reasonably
  infers "54 locations averaged 4.0," which is **not** what either number says.

So: **the AI is not decorative, and there is no coverage bug.** Scoring 3 of 54
at this horizon is the *intended* sparse-scoring behaviour of the cost-bounded
batch (Gate 4 stability gating + a force-eval cap of 6). The defect is
**presentational**: weather-"Worth it" and AI-"Worth it" are rendered
identically, and a region header that mixes a 54-wide weather count with a
3-wide AI average reads as a single, much stronger claim than the data supports.

---

## 1. The end-to-end trace: how 54 rows come from 3 cached ratings

### 1.1 The drill-down is the *briefing*, not the *evaluation merge*

The expanded panel in the screenshot ("Yorkshire Dales — Sat 20 Jun · Sunset")
is rendered by **`HeatmapDrillDown`** →
[`frontend/src/components/HeatmapGrid.jsx:430`](../../frontend/src/components/HeatmapGrid.jsx).
Its location list comes from **`region.slots`**
([`HeatmapGrid.jsx:539`](../../frontend/src/components/HeatmapGrid.jsx)) — i.e.
the slots already embedded in the briefing payload. It does **not** call the
evaluation-merge endpoint to build the list.

`region.slots` arrives from `GET /api/briefing` →
**`BriefingService.getCachedBriefingForApi()`**
([`BriefingService.java:239`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java)).

> Note: there is a *separate* canonical merge layer,
> `EvaluationViewService.forDateRange()` (served at
> `GET /api/briefing/evaluate/scores`), which the **Map tab** uses. It is not
> the source of the drill-down list. The drill-down only consults evaluation
> scores to *overlay stars onto slots it already has* (see §1.4). Conflating
> these two paths is, I suspect, where earlier reconstructions went wrong.

### 1.2 Where the 54 slots come from — the full roster

`BriefingService.refreshBriefing()`
([`BriefingService.java:297`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java)):

```java
List<LocationEntity> colourLocations = locationService.findAllEnabled().stream()
        .filter(this::isColourLocation)        // drops pure-WILDLIFE
        .toList();
...
List<BriefingSlot> allSlots = /* one slot per location × date × event */;
List<BriefingDay> days = hierarchyBuilder.buildDays(allSlots, colourLocations, dates);
```

`BriefingHierarchyBuilder.buildEventSummary()`
([`BriefingHierarchyBuilder.java:93`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingHierarchyBuilder.java))
groups those slots by region. **The Yorkshire Dales region therefore contains
one slot for every enabled colour location whose `region_id` resolves to the
Dales** — that is the ~54. The list is the *full regional roster*, built fresh
from weather, **not** a query against evaluated rows.

Each slot is constructed via the convenience constructor
([`BriefingSlot.java:63`](../../backend/src/main/java/com/gregochr/goldenhour/model/BriefingSlot.java)),
which sets `displayVerdict = DisplayVerdict.resolve(null, verdict)` — i.e. from
the **weather triage verdict alone**, no Claude.

### 1.3 The 54 slots are persisted nowhere durable

The assembled `DailyBriefingResponse` is held in
`BriefingService.cache` (`AtomicReference`,
[`BriefingService.java:94`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java))
and mirrored to the `daily_briefing_cache` table. The individual slots are
**not** rows in `forecast_evaluation` or `cached_evaluation`.

**This is the reconciliation.** Falsified explanation #1 queried
`forecast_evaluation` for `rating IS NULL AND triage_reason IS NULL` and got 0
rows — correct, because the 51 unscored "Worth it" locations were never written
to `forecast_evaluation` at all. They live only inside the briefing blob.

### 1.4 The 3 stars — cache overlay

`BriefingService.enrichWithCachedScores()`
([`BriefingService.java:470`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java))
walks every region's slots and overlays Claude scores:

```java
Map<String, BriefingEvaluationResult> cached =
        evaluationViewService.getScoresForEnrichment(region.regionName(), day.date(), es.targetType());
List<BriefingSlot> enrichedSlots = region.slots().stream()
        .map(slot -> enrichSlot(slot, cached))   // sets stars iff cached.rating != null
        .toList();
```

`getScoresForEnrichment()`
([`EvaluationViewService.java:213`](../../backend/src/main/java/com/gregochr/goldenhour/service/EvaluationViewService.java))
returns the `cached_evaluation` entries first, then back-fills from
`forecast_evaluation` for locations the cache misses. For Dales/06-20/SUNSET the
cache holds **3 rated entries**, so exactly 3 slots get
`withClaudeScores(...)` and the recomputed `displayVerdict`
([`BriefingSlot.java:97`](../../backend/src/main/java/com/gregochr/goldenhour/model/BriefingSlot.java)).
The other 51 slots pass through `enrichSlot` unchanged
([`BriefingService.java:520`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java)).

### 1.5 The named end-to-end pipeline

| Step | Component | Data source | Result |
|------|-----------|-------------|--------|
| 1 | `LocationService.findAllEnabled()` + `isColourLocation` filter | `locations` table | ~54 Dales colour locations |
| 2 | `fetchWeatherSequential` + triage → `BriefingSlot` (per location) | Open-Meteo (live) | 54 slots, each with a weather `Verdict` |
| 3 | `BriefingHierarchyBuilder.buildRegion` | the 54 slots | region with `summary` = "Clear at 54 of 54", `slots` = all 54 |
| 4 | `enrichWithCachedScores` | `cached_evaluation` (3 rows) | 3 slots gain stars + prose; 51 unchanged |
| 5 | `BriefingGlossService.generateGlosses` | weather + the 3 cached scores | region gloss ("average 4.0 …") |
| 6 | `getCachedBriefingForApi` → `BriefingHonestyFilter.apply` | — | no rewrite (scoredCount=3 > 0) |
| 7 | `HeatmapDrillDown` renders `region.slots` | the briefing blob | 54 rows: 3 starred, 51 "Worth it" |

---

## 2. What sets the "Worth it" label per row

Two distinct producers, **rendered identically**:

**Per-row pill** — `LocationSlotList`
([`HeatmapGrid.jsx:318-328`](../../frontend/src/components/HeatmapGrid.jsx)):

```jsx
{score?.rating != null
  ? <span ...>{score.rating}★</span>     // scored slots → star badge
  : <VerdictPill verdict={slot.verdict} />} // unscored slots → label from weather verdict
```

For the 51 unscored slots, `VerdictPill` maps `verdict === 'GO' → 'Worth it'`
([`HeatmapGrid.jsx:112-137`](../../frontend/src/components/HeatmapGrid.jsx)).
The label is the **weather triage verdict**, with no Claude involvement.

**The crux case** — a location with no `cached_evaluation` rating *and* no
`forecast_evaluation` triage row: its briefing slot still carries a weather
`Verdict`. `DisplayVerdict.resolve(null, GO)` returns `WORTH_IT`
([`DisplayVerdict.java:40-58`](../../backend/src/main/java/com/gregochr/goldenhour/model/DisplayVerdict.java)).
The UI renders the same green "Worth it" pill it uses for a Claude-4★ slot.
**There is no visual distinction between "Claude says worth it" and "the sky is
clear here" — they share one pill.**

(For the 3 scored slots the rating ≥ 4 also yields `WORTH_IT`, so the panel is
visually uniform; only the star badge / prose distinguishes them.)

---

## 3. "54 of 54" and "average 4.0" — honest numbers, misleading adjacency

### 3.1 "Clear at 54 of 54 locations"

`BriefingVerdictEvaluator.buildRegionSummary()`
([`BriefingVerdictEvaluator.java:514`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java)):

```java
long goCount = slots.stream().filter(s -> s.verdict() == Verdict.GO).count();
int total = slots.size();
if (verdict == Verdict.GO)
    conditionText = "Clear at " + goCount + " of " + total + " locations";
```

- **numerator (54)** = slots whose **weather** verdict is `GO`.
- **denominator (54)** = total slots = full regional roster.

So "54 of 54" means *"all 54 roster locations have clear-sky weather."* It is a
**weather** metric. It is **not** "54 evaluated," "54 GO-verdict by Claude," or
"54 scored." The literal claim is true; the implication that 54 things were
*assessed* is not.

### 3.2 "ratings average 4.0, all evaluated locations scoring highly"

This is **Claude-authored region gloss** (`region.glossDetail`), not a string
literal — which is why grepping for the prose finds nothing. It is generated by
`BriefingGlossService`
([`BriefingGlossService.java:238-332`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BriefingGlossService.java)).
The prompt is fed `claudeAverageRating`, computed in `appendClaudeScores`:

```java
List<BriefingEvaluationResult> scored = cached.values().stream()
        .filter(r -> r.rating() != null).toList();   // the 3 rated only
double avgRating = scored.stream().mapToInt(...).average().orElse(0);
node.put("claudeAverageRating", Math.round(avgRating * 10.0) / 10.0); // 4.0 over 3
```

The system prompt instructs Claude to *calibrate language* to that average and
explicitly tells it the figures are "per-location PhotoCast evaluation scores."
So "average 4.0" is **honest arithmetic over the 3 scored locations**, and "all
evaluated locations scoring highly" is **literally true** (all 3 evaluated
scored ≥ 4).

### 3.3 The combined effect

Neither number is *wrong arithmetic*. The problem is **adjacency + ambiguous
referent**:

- "Clear at **54 of 54**" (weather, 54-wide) and "ratings average **4.0**, all
  evaluated locations scoring highly" (Claude, 3-wide) sit one line apart with
  no scope marker.
- A reader fuses them into *"all 54 locations averaged 4.0 stars,"* which is
  unsupported. The word "evaluated" is doing load-bearing work that the layout
  hides — only 3 of the 54 were evaluated.

**Conclusion for the header question:** honest individual arithmetic, misleading
combined framing. Not wrong arithmetic.

---

## 4. Why only 3 of 54 get Claude-scored (the "why only 3" answer)

The drill-down list is the full roster, but only the **batch** decides what
Claude scores, and it is deliberately frugal. Selection happens in
`ForecastTaskCollector.collectScheduledBatches()`
([`ForecastTaskCollector.java:224`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java)),
in this order:

1. **Triage gate** ([`:367`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java)) —
   each candidate is re-fetched and re-triaged via
   `forecastService.fetchWeatherAndTriage(...)`; if `preEval.triaged()` it is
   skipped. (This is a *separate* triage from the briefing slot verdict — see
   §5, surprise #2.)

2. **Gate 4 — horizon × stability** (`NightlyEligibilityPolicy.resolve`,
   [`NightlyEligibilityPolicy.java:42`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/NightlyEligibilityPolicy.java)):

   | daysAhead | eligible | model |
   |-----------|----------|-------|
   | T+0, T+1 | all stabilities | `BATCH_NEAR_TERM` |
   | **T+2** | **SETTLED or TRANSITIONAL only** | `BATCH_FAR_TERM` |
   | T+3 | SETTLED only | `BATCH_FAR_TERM` |
   | T+4+ | never | — |

   **2026-06-20 from 2026-06-18 is T+2.** Any Dales cell classified
   `UNSETTLED` is dropped here — *regardless of how clear (GO) its weather looks
   in the briefing.* Only `SETTLED`/`TRANSITIONAL` cells survive.

3. **Force-eval cap** (`selectForceEvalKeys`,
   [`ForecastTaskCollector.java:537`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java)):
   stability-gated headline contenders (T+2..T+3, GO + eligible) can be rescued,
   but only up to **`photocast.batch.force-eval-cap` = 6** *total per cycle,
   across all regions/events* — explicitly "keeps the extra Claude spend tiny
   (a handful … against ~£2.50/night)."

The net is that at T+2 a region's Claude coverage is bounded to *settled cells +
a tiny shared rescue quota*, while the briefing still displays the entire GO
roster. **3 scored of 54 displayed is the designed sparse-scoring outcome at
this horizon, not a coverage failure.**

> **Cannot verify from dev:** the exact per-location dispositions (how many of
> the 51 were `SKIPPED_TRIAGED` vs `SKIPPED_STABILITY` vs simply not force-eval
> picks) live in the production `[BATCH DIAG]` / `[BATCH ELIG]` logs
> ([`ForecastTaskCollector.java:344, 472, 496`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java)),
> which are not accessible from this machine (production runs on a separate
> host). The *mechanism* is certain; the *exact split* needs those logs.

---

## 5. What surprised me most

1. **The drill-down list and the score data have different lineages.** The list
   is live weather (briefing blob, persisted nowhere durable); the stars are
   `cached_evaluation`. They only meet in `enrichWithCachedScores`. Both
   falsified queries assumed the list lived in an evaluation table — it never
   did. Once you see the slot roster is built from `findAllEnabled()` and held
   in an `AtomicReference`, the "0 rows" results stop being mysterious and
   become *expected*.

2. **There are two independent triages with different criteria, and they
   disagree by construction.** The briefing slot verdict (`BriefingVerdictEvaluator`,
   observer-point weather → `GO`) and the batch's gate (`fetchWeatherAndTriage`
   + Gate 4 *stability*, which is about forecast volatility, not cloud) answer
   different questions. A T+2 cell can be **GO** (clear sky now) in the briefing
   yet **UNSETTLED → not eligible** in the batch. That divergence is the real
   engine behind "54 clear but only 3 scored," and it is invisible in the UI.

3. **`BriefingHonestyFilter` exists precisely for this class of problem — but it
   only fires at `scoredLocationCount == 0`.** It rewrites a region to "Too
   unsettled to forecast / No per-location forecast" *only when zero* locations
   were scored
   ([`BriefingHonestyFilter.java:116`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingHonestyFilter.java)).
   At `scoredLocationCount = 3` it does nothing, so the "54 of 54 / average 4.0"
   framing sails straight through. The guardrail is binary (0 vs >0) where the
   honest signal is a *ratio* (3 of 54).

---

## 6. Open questions for Chris

1. **Is the 54-vs-3 framing acceptable, or should the header carry the scope?**
   e.g. "Clear weather at 54 of 54; 3 evaluated, averaging 4.0★." The numbers
   are honest individually — the question is whether the layout should stop a
   reader fusing them.

2. **Should weather-"Worth it" and Claude-"Worth it" pills look the same?**
   Right now an unscored clear-sky slot and a Claude-4★ slot wear the identical
   green pill. Would a distinct treatment for "not yet evaluated" (e.g. an
   "Awaiting"/outline pill, or a "clear weather, not scored" tag) better reflect
   the data? (`DisplayVerdict.AWAITING` already exists but is only used when the
   weather verdict is also absent.)

3. **Should `BriefingHonestyFilter` trigger on a low *ratio*, not just zero?**
   A region showing 54 GO rows with 3 scored is arguably as misleading as one
   with 0 scored. Is there a coverage ratio below which the region should be
   framed as "lightly evaluated"?

4. **Is `force-eval-cap = 6` (shared across all regions/events per cycle) the
   intended ceiling?** At T+2 with several clear regions competing for 6 rescue
   slots, most clear-but-unsettled cells get no Claude coverage at all. Confirm
   this is the deliberate cost/coverage trade-off and not an under-tuned cap.

5. **Should the gloss prompt be told the *coverage ratio*, not just the
   average?** It currently receives `claudeRatedCount` and `claudeAverageRating`
   but is steered to "calibrate language" optimistically when the average is
   high — with no signal that the average covers 3 of 54. Feeding it
   `totalLocations` vs `claudeRatedCount` could let it hedge ("the few
   evaluated spots score highly") instead of implying region-wide quality.

---

## Appendix — key files

| File | Role |
|------|------|
| [`HeatmapGrid.jsx`](../../frontend/src/components/HeatmapGrid.jsx) | Drill-down UI; renders `region.slots`, per-row pill/star, region header |
| [`BriefingService.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java) | Builds the briefing; `refreshBriefing` (roster), `enrichWithCachedScores`, `getCachedBriefingForApi` |
| [`BriefingHierarchyBuilder.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingHierarchyBuilder.java) | Groups slots into region rollups |
| [`BriefingVerdictEvaluator.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java) | `buildRegionSummary` → "Clear at N of M" |
| [`DisplayVerdict.java`](../../backend/src/main/java/com/gregochr/goldenhour/model/DisplayVerdict.java) | `resolve(rating, verdict)` → WORTH_IT/MAYBE/STAND_DOWN/AWAITING |
| [`BriefingGlossService.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BriefingGlossService.java) | Claude region prose; `appendClaudeScores` (avg over scored only) |
| [`BriefingHonestyFilter.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingHonestyFilter.java) | Read-time rewrite, but only when `scoredLocationCount == 0` |
| [`EvaluationViewService.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/EvaluationViewService.java) | Canonical cache↔forecast merge; `getScoresForEnrichment` feeds the star overlay |
| [`ForecastTaskCollector.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) | Batch selection: triage gate, Gate 4, force-eval cap |
| [`NightlyEligibilityPolicy.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/NightlyEligibilityPolicy.java) | Gate 4 horizon×stability table |
</content>
</invoke>
