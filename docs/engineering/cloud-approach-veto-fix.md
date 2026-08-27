# Cloud-approach veto: unsmoothed inputs drive an absolute rating ceiling

**Status:** F1 and F4 implemented 2026-07-25 (#294). **F2 attempted and REJECTED** — refuted by the
Copt Hill ground-truth fixture. **F3 REJECTED** — adversarially reviewed, would introduce two new
degeneracies. **D6 (silent half-veto) found and FIXED.** **D7 MEASURED and ANSWERED** — the cap is
not the broken half; the ceiling is (§9). D8 and F5 outstanding. See §4.

⚠️ **`actual_outcome` is empty — zero rows, ever.** Aesthetic validation therefore still does not
exist. But the reanalysis verification this paragraph used to wait on **has now landed and
completed** (§9: 29,016 evaluations over Feb–Aug 2026, backlog fully verified 2026-08-16), so the
*cloud* claims the rules turn on are measured. The "change nothing" rule is retired for changes §9
justifies; it stands for anything §9 does not reach.
**Raised:** 2026-07-25, from a production observation on the Map tab
**Area:** `service/CloudPointCacheReader`, `service/DirectionalSamplingGeometry`, `service/evaluation/PromptBuilder`

---

## 1. The observed failure

Two LANDSCAPE locations ~10 km apart, same region, same sunset, scored in the same batch:

| | rating | fiery_sky | summary (abridged) |
|---|---|---|---|
| Angel of the North | **1** | **15** | "a low-cloud bank is **building** toward the western solar horizon… **making the forecast unreliable** — the **clear** mid-high **canvas** and **favourable aerosol** cannot overcome the physical approach" |
| Copt Hill | **4** | **72** | "the low blocker **lifts precisely** as the sun reaches the mid-cloud canvas… a dramatic clearance" |

Both are in region *Tyne and Wear*; both were written to the same `cached_evaluation` row with the same
`evaluated_at` (`2026-07-23 01:17:12`) and the same model (HAIKU). A cloud bank cannot be arriving at
Birtley while clearing 10 km away at Houghton, on the same horizon, at the same minute.

### Ruled out

- **Staleness / vintage drift** — same cache row, same `evaluated_at`, same model.
- **Two scoring algorithms** — the batch path uses the same `PromptBuilder` and system prompt as the
  synchronous path (`ScheduledBatchEvaluationService:45`, "identical structure to the real-time path").
- **Triage** — a triaged slot persists `rating = null` and renders an em-dash stand-down marker
  (`markerUtils.js:79-92`), never a `1★`. Both markers showed integers.
- **Sampling variance** — the sky-rating eval harness runs each fixture 8× on frozen inputs
  (`RUNS_PER_FIXTURE=8`) and records *"variance very low — most fixtures identical across 8 runs"*.
  Temperature is genuinely unset (no `.temperature(` anywhere in `backend/src/main/java`), so calls
  run at the API default of 1.0 — but measured variance does not span two rating bands.

### The actual mechanism

`PromptBuilder.java:209-220` defines the only rule in the system that forces a rating **regardless of
all other evidence**:

```
"- Combined signal: when BOTH [BUILDING] trend AND upwind current ≥60% are present,
 the forecast is UNRELIABLE — cloud was physically moving toward the solar horizon
 at the time this data was captured. In this specific case: output rating 1 or 2,
 fiery_sky ≤20, golden_hour ≤20. These ceilings apply even if the solar horizon
 appears clear, canvas is present, or aerosol is favourable …"
```

Angel of the North's stored summary paraphrases this rule — including its worked example at
`:219-220` — and its output obeys both ceilings exactly (rating `1` ∈ {1,2}; fiery `15` ≤ 20).
Copt Hill shows no trace of it. Per `:202-203`, a location that misses the `[BUILDING]` bar receives
**no `CLOUD APPROACH RISK:` block at all** and is scored on its canvas.

So one location hit an absolute veto and its neighbour never saw the rule. That is a 3-star split
with no visible weather difference — and it is deterministic, not stochastic.

> **Evidential caveat.** The 25 Jul prompts are not recoverable (see §3, D3), so the trigger values for
> that specific pair are inferred from the summary-text fingerprint rather than read directly. The
> 26 Jul rows show both locations tripping `solar_trend_building = true` with `upwind_current` 79 % and
> 82 % — i.e. both sides of the veto sit close to their thresholds for these two locations, which is
> exactly the regime where a small input difference flips one and not the other.

---

## 2. Root cause

**The most absolute rule in the prompt is fed by the least spatially smoothed inputs in the codebase.**

`DirectionalSamplingGeometry:40-45` states the design intent plainly:

```java
/**
 * Half-angle of the sampling cone for the solar horizon direction (degrees).
 * Three points are sampled at azimuth-CONE, azimuth, azimuth+CONE and averaged,
 * smoothing out Open-Meteo grid-cell boundary effects (~11 km resolution).
 */
```

That rationale was applied to exactly one of four directional legs:

| Leg | Distance | Points | Smoothed? | Feeds |
|---|---|---|---|---|
| Solar cone | 113 km | 3 (az ±15°) | **yes** — `CloudPointCacheReader:59-73, 93-96` | blocked/clear bands |
| Trend | 113 km | **1** (centre only) — `:126-128` | **no** | `[BUILDING]` → **the veto** |
| Upwind | ≤ 200 km | **1** | **no** | `current ≥60%` → **the veto** |
| Far-solar | 226 km | **1** — `:86-90` | **no** | thin-strip vs blanket fork |
| Antisolar | 113 km | **1** — `:76-82` | **no** | canvas behind observer |

Two compounding problems:

**(a) The trend point is the un-coned centre of a cone that is already computed.**
`computeSolarHorizonPoint(lat, lon, az)` (`:87-89`) returns exactly `points.get(1)` from
`computeDirectionalCloudPoints` — same bearing, same 113 km. The two flanking samples are *already in
the cache*. The veto's first trigger is therefore reading a single grid cell when the average of three
sits alongside it, unused.

**(b) The upwind point's *position* is per-location.**
`computeUpwindPoint` (`:102-113`) offsets from the observer along **that location's own** `windFromDeg`
for `min(windSpeedMs × secondsToEvent, 200 km)`. Two neighbours in adjacent Open-Meteo cells with a
15° wind-direction difference place their upwind samples **~45 km apart** at a 170 km offset — in
genuinely different weather. Both 26 Jul rows show `upwind_distance_km = 200`, i.e. the cap.

This is why the "locations sharing a horizon should agree" intuition fails: the solar cone *is*
effectively shared between neighbours (a 10 km observer offset translates to a ~10 km horizon offset),
but the upwind leg is not shared at all — its bearing is a local variable.

---

## 3. Defects

| # | Defect | Site |
|---|---|---|
| **D1** | Trend `[BUILDING]` read from one un-coned cell while the identical bearing/distance is coned for the solar reading. Inconsistent, and the smoothing is free. | `CloudPointCacheReader:126-128` |
| **D2** | The veto can fire on two single-cell readings and override a clear coned horizon, a full canvas and favourable aerosol. No corroboration required. | `PromptBuilder:209-220` |
| **D3** | Not diagnosable after the fact: batch calls persist neither prompt nor response (`request_body`/`response_body` NULL for every batch row in `api_call_log`). | `ForecastResultHandler.persistBatchLog` |
| **D4** | Geometry/prompt mismatch. `PromptBuilder:204-206` tells Claude the upwind point is *"the distance current cloud would physically travel **to the solar horizon** by event time"*. `computeUpwindPoint` anchors at the **observer**, not at `computeSolarHorizonPoint`. The sampled parcel is not the one that arrives where the prompt claims. | `DirectionalSamplingGeometry:102-113` |
| **D5** | No spatial-consistency check exists anywhere. Physically implausible neighbour splits are invisible until a user screenshots one. | (absent) |

---

## 4. Proposed fixes

Ordered by value-per-risk. **F1 and F3 are behaviour-preserving in spirit and carry no API cost.**

### F1 — Cone the trend point (no extra API calls)

`CloudPointCacheReader.fetchCloudApproachDataFromCache:126-134`: read the trend at all three solar-cone
bearings and average, mirroring `:59-73`. The points are already in `CloudPointCache` because
`computeDirectionalCloudPoints` fetched them for the same location, distance and bearings.

Add `DirectionalSamplingGeometry.computeSolarConePoints(lat, lon, az)` returning the three points, and
have both `computeDirectionalCloudPoints` and the trend path consume it — the cone bearings are
currently duplicated in `CloudPointCacheReader:52-57` and `DirectionalSamplingGeometry:65-69`.

*Cost: zero additional requests. Risk: low — strictly reduces single-cell noise on the veto's trigger.*

### F2 — ~~Require corroboration before the veto applies~~ **REJECTED 2026-07-25**

**Attempted and reverted. The premise was wrong, and a ground-truth fixture proved it.**

F2 proposed exempting the absolute ceiling when the *coned* solar-horizon low cloud reads below
50%, on the reasoning that a 3-point average should not be vetoed by two single-point signals.
Implemented as a computed `[BUILDING — … BOUNDED penalty …]` label, it broke exactly one test:
`PromptBuilderCoptHillSimulationTest`. That fixture reconstructs the real Copt Hill 2026-03-11
sunset, and its docstring records the outcome:

> "The 13:45 forecast showed only **7% solar low cloud** … leading to an **optimistic 4★
> prediction**. In reality, a cloud bank was approaching from the SW and the sunset was **~2★**."

That is a **wasted trip** (predicted 4★, actual 2★), not a missed opportunity — and the 7% clear
coned horizon was *the misleading reading*. The approach signals were correct; the horizon was
not. F2 keys its exemption on precisely the signal that failed, so it would have lifted a
correctly-vetoed 1–2★ case toward 3★ against a recorded 2★.

**The deeper correction: F1 already addressed F2's actual rationale.** F2's argument was that "two
un-averaged single-cell readings can veto a clear horizon". After F1 the trend is coned, so it is
no longer single-cell. The one remaining unreliable trigger is the upwind sample — which is F3.
The ceiling was never the defect; input noise was. Do **F3**, not F2.

Kept below for the record only — do not implement without new outcome evidence.

### ~~F2 (original proposal)~~

`PromptBuilder:209-220`: keep the rule, but gate the **absolute ceiling** on the coned solar-horizon
low cloud also reading ≥ 50 %. When the coned horizon is clear, demote to a bounded penalty
(cap rating 3, −20 fiery_sky) rather than a forced 1–2.

Rationale: the veto exists to catch cloud the event-time snapshot misses. That is a real phenomenon and
worth keeping. But when the *smoothed, three-point* horizon reading disagrees with two *single-cell*
approach readings, the smoothed reading should not lose outright.

*Cost: prompt-text change. Risk: medium — see §5.*

### F3 — ~~Anchor the upwind point at the solar horizon~~ **REJECTED 2026-07-25**

**Adversarially reviewed across three lenses before implementation; all three said do not ship.**

The narrow physics holds: observer-anchoring samples the parcel that will be over the *observer*
at event time, so in aligned UK flow it reads the horizon field ~3.8 h early (verified with the
repo's own spherical math — for Copt Hill the re-anchored point lands 114 km away, in the Irish
Sea rather than the Howgill Fells). But re-anchoring introduces two degeneracies the current
geometry structurally cannot have, because `MIN_UPWIND_DISTANCE_M`/`MAX_UPWIND_DISTANCE_M` bound
the **leg**, never the distance from the observer:

- **Self-sampling** — anti-aligned flow with a ~113 km leg puts the "upwind" point ~2.7 km from
  the observer. The observer's own current low cloud would then be printed as the upwind sample
  and could supply the second leg of an absolute rating veto.
- **Far-solar collision** — exactly aligned flow with a ~113 km leg puts it at 226 km along the
  solar azimuth, bit-identical to `FAR_SOLAR_OFFSET_METRES`. Two nominally independent signals
  reading one grid cell.

Per your model, the two sample points answer different questions — the **113 km solar horizon**
is "is there a gap for the low sun to get through", the **observer** is "is there a canvas
overhead to light up". Under that reading the observer-anchored upwind sample is not wrong, it is
measuring **canvas arrival** while the prompt describes and the veto reasons about **gap closure**.
Two legitimate signals, conflated — which is a design decision to make with data, not a geometry
bug to patch.

**Do not implement without outcome or reanalysis evidence.**

### Newly found while reviewing F3

| # | Defect | Status |
|---|---|---|
| **D6** | **Silent half-veto.** The prefetchers placed the upwind point using wind at `findNearestIndex` while the reader re-derives it from wind at `findBestIndex` (via `extractAtmosphericData`). The two disagree for ~half of all events (a 21:37 sunset → 21:00 vs 22:00), and at the 200 km cap a ~3° bearing difference crosses a 0.1° cache cell — so the lookup missed, the upwind sample came back null, and the veto lost a trigger with no error and no log. | **FIXED** — `OpenMeteoResponseParser.resolveEventWind` is now the single decider; both prefetchers call it. |
| **D7** | **The 200 km cap voids the trajectory identity.** `MAX_UPWIND_DISTANCE_M` binds beyond ~6.9 h at 8 m/s. The nightly batch runs ~20 h ahead of a summer sunset and every T+1…T+7 slot is far past it, so for most evaluations `dist ≠ windSpeed × timeToEvent` and the sample is just "cloud 200 km upwind now" — while `PromptBuilder:204-206` asserts otherwise and the veto forces 1–2★ on it. Anchor-independent; F3 would not have helped. | **MEASURED 2026-08-16 — answered, see §9.** The cap is not the broken half: capped +36.2 vs uncapped +34.9 gap error, separation −0.9pp. The ceiling itself is the defect. |
| **D8** | **Grep-invisible sixth call site.** `OpenMeteoService:672-681` re-implements the upwind geometry inline via `GeoUtils.offsetPoint` and never calls `computeUpwindPoint`. It is the *live* path whenever `cloudCache` is null. Any future geometry change must include it or the cached and live paths will diverge. | Open — documented. |

### ~~F3 (original proposal)~~

`DirectionalSamplingGeometry.computeUpwindPoint`: offset from `computeSolarHorizonPoint(lat, lon, az)`
rather than from `(lat, lon)`, so the geometry matches what `PromptBuilder:204-206` already tells Claude.

This also *reduces* neighbour divergence: both locations' upwind points then hang off horizon points
that are ~10 km apart, instead of off observer points with independently-varying local wind bearings.

Alternative if the current geometry is intentional: change the prompt text instead. They must not
continue to disagree.

*Cost: zero additional requests (same point count). Risk: medium — changes which cell is sampled, so
approach-risk behaviour shifts. Needs the eval harness (§6).*

### F4 — Persist batch prompt/response

`ForecastResultHandler.persistBatchLog`: store `outcome.rawText()` on success, not only on the regex
fallback path. `api_call_log.response_body` is already `TEXT`, and `/api/metrics/api-calls?jobRunId=`
already surfaces it in the Job Run screen. Without this, the next occurrence is equally un-diagnosable.

*Cost: storage. Risk: none. Do this first — it is the cheapest item here and it is what blocked closing
this investigation.*

### F5 — Neighbour-consistency tripwire

In the batch result flush: when two enabled locations within ~20 km differ by ≥ 2 stars for the same
`date|targetType`, log at WARN with both locations' directional readings (solar coned, far, trend,
upwind current/at-event/distance).

Not a correctness mechanism — a detector. Turns "user spots it on the map" into a grep. Note that
20 km is a *horizon-sharing* radius, not a region: regions vary from ~540 km² (Tyne and Wear) to
~5,000 km² (Northumberland), so region membership is the wrong unit for this check.

*Cost: trivial. Risk: none.*

---

## 5. What must NOT be changed unilaterally

- **Prompt regression test assertions** (`src/test/java/.../regression/`) encode ground-truth
  expectations against real Claude output. Per `CLAUDE.md`, only the user updates these. F2 and F3 will
  move some of them; surface the diffs and **stop** rather than editing assertions.
- `PromptBuilderCoptHillSimulationTest` (*"reconstructing the Copt Hill 2026-03-11 sunset failure
  case"*) and `PromptRegressionTest:255-279` already capture a prior false-positive of this exact shape
  at one of these two locations. Read them before touching F2 — the veto's current strength may be a
  deliberate response to that incident, in which case F2 needs the user's call, not mine.

---

## 6. Verification

1. `./mvnw compile -q` then targeted classes:
   `-Dtest="CloudPointCacheReader*,DirectionalSamplingGeometry*,PromptBuilder*"`.
2. `./mvnw checkstyle:check -q` before any full verify.
3. **Sky-rating eval harness** — the gated pass^k band+bucketing eval. Re-baseline and compare
   against the existing all-green Sonnet baseline; a prompt or geometry change that moves band
   assignments must be justified, not absorbed. Note what this can and cannot tell you: it measures
   band assignment against *fixtures*, so it detects that behaviour moved, not whether it moved
   toward reality.
4. **Calibration gate — `GET /api/admin/calibration?from=&to=` (built 2026-07-25).** The only
   measure in the project that compares forecasts to *recorded outcomes*. Run it over a fixed
   window before and after F2/F3 and diff the buckets. **`missedOpportunities`** (predicted ≤ 2,
   actual ≥ 4) is the specific number F2 has to improve: an absolute rating ceiling can only ever
   produce that failure mode, never a wasted trip. If relaxing the veto does not reduce it, the
   change is not justified — and if `wastedTrips` rises by more than `missedOpportunities` falls,
   it made things worse. Check per-horizon buckets too, since the veto's triggers vary with
   `time_to_event` and so bite differently at T+0 than at T+3.
4. Frontend untouched by F1–F4; F5 adds no UI.

## 7. Suggested sequencing

**F4** (unblocks all future diagnosis) → **F1** (free, strictly reduces noise) → **F5** (detector) →
then **F3** and **F2** together behind the eval harness, with the regression-test diffs put in front of
the user before anything is re-baselined.

Optionally set temperature explicitly to 0 on the scoring calls at the same time. It is one line and
removes a needless degree of freedom — but the harness data says it is *not* the cause of this failure,
so it should not be sold as the fix.

## 8. Measurement pass — cone structure and the far corridor (2026-08-12)

The reanalysis verification (§6's successor, `GET /api/admin/cloud-verification`) originally
scored the two claims a forecast *makes*: the gap and the canvas. It now also measures what the
forecast's own persistence throws away, so two standing questions about the sampling geometry can
be answered from data rather than argued from √(2Rh):

- **Cone aggregation (V142 `horizon_low_min`/`horizon_low_max`).** `OpenMeteoService` averages the
  three cone bearings before anything downstream sees them, and the prompt thresholds (>60 blocked,
  40–60 partial, <20 clear) run on that mean — so a uniform 60% deck and a 90/0/90 wall-with-gap
  score identically, though they are very different sunsets. `byConeStructure` buckets every
  verified pair by the *analysed* spread (uniform <20pp / mixed 20–39 / gapped ≥40) and carries the
  usual error stats, so "does gap error blow up exactly where the mean hides structure?" is now a
  report read, not a hypothesis.
- **Canvas-height blocking distance (V142 `far_low_cloud`).** *Physics corrected 2026-08-13 by
  first-principles re-derivation — the two struck claims below were wrong.* In the parabolic
  approximation, a ray through a canvas at height *h* over the observer, with the sun *δ* below the
  horizon, sits at height `g(x) = h − δx + x²/2R` above the surface at distance *x* sunward. Last
  direct light on the canvas is `δ_end = √(2h/R)`, grazing at `x = √(2Rh)`; low cloud with tops *z*
  blocks it over the corridor `√(2Rh) ± √(2Rz)`. Numbers (geometric; refraction k ≈ 1.15–1.2
  stretches all distances ~7–10%):
  - **113 km** (= √(2R·1)) is the far edge of the corridor where 1 km-top low cloud blocks *direct
    low sun reaching the observer* — and only that. ~~Correct for a mid-level canvas~~: low cloud at
    113 km starts shadowing a 4 km canvas at 2.03° depression, which *is* that canvas's last-light
    depression — a zero-width window. The "far edge" is top-height-conditional: tops of 2–3 km
    (which Open-Meteo's low layer includes) move the observer's own gap corridor out to 160–196 km,
    so the 113 km sample probes the *1 km-idealised* edge, not a bound on where low cloud can block
    the low sun (cross-vendor review C1, 2026-08-27).
  - **226 km** is exactly √(2R·4): the grazing point and corridor *centre* for a **4 km mid
    canvas** (corridor 113–339 km). Chosen as "2 × horizon" for strip-vs-blanket, it is by
    coincidence the dead-centre mid-canvas probe.
  - An **8 km cirrus canvas** is blocked over **206–432 km, centred at 319 km** (= √(2R·8)).
    ~~The 226 km point sits in that corridor~~ — it sits at the near *edge*: 1 km-top cloud at
    226 km steals only the final 0.08° of the canvas's 2.87° lit arc. ⚠️ That figure is specific
    to the 226 km point and must not be generalised: 1 km cloud placed *optimally* (~299 km, the
    tangent point at blocking onset) blocks for the final **0.185°** — 2.3× more, roughly half of
    a ~0.4° red phase (cross-vendor review C5, 2026-08-27). Deeper low decks widen the corridor
    per top height — **160–479 km for 2 km tops, 124–515 km for 3 km** (an earlier "~160–515"
    spliced the 2 km near edge onto the 3 km far edge; the corridors nest, they do not blend) —
    and can amputate the whole red phase (2 km tops block the final 0.385°, 3 km the final
    0.601°). So the 226 km reading still carries high-canvas signal, but as a synoptic-scale
    proxy ~90 km short of centre, not a direct measurement. A rigorous cirrus probe would be a
    sixth archive point at ~319 km — **geometric, not "refraction-corrected" to 342–349 km**:
    near-horizon refraction is profile-dependent and nonlinear (~0.57° at the horizon under a
    standard atmosphere, larger than every blocking window above), so a single √k stretch is
    uncertainty, not a correction.

  The far-solar point was never verified and never persisted by this table before V142.
  `byCorridor` buckets by near-minus-far divergence at ±30pp (the same threshold the production
  strip-vs-blanket rule uses) with `&highCanvas` sub-buckets. `farClearer&highCanvas` counts the
  over-pessimism candidates (gate reads blocked, underlighting corridor open — the hard-ceiling
  rule wrongly kills these); `farCloudier&highCanvas` counts the **false-optimism case no current
  rule covers** (gate reads clear, corridor blanketed, "ideal scenario" fires). The forecast's own
  226 km claim gets its first accuracy figure (`meanFarError`). Given the corrected geometry, the
  existing data answers the **mid-canvas** question exactly as-is — a `&midCanvas` sub-bucket is a
  free re-cut — while the high-canvas buckets should be read as proxy evidence pending a ~319 km
  sample.

Both new statistics are reanalysis-internal comparisons (spread across bearings; near vs far), so
the ~25pp horizon baseline offset that invalidated absolute-threshold readings cancels by
construction. High-canvas dominance is likewise a within-reanalysis layer comparison
(`observedCanvasHigh > observedCanvasMid`, strict so an empty sky never counts).

**Mechanics.** The backfill samples five archive points per evaluation (cone ×3, observer,
far-solar — ~25% more requests; the hourly tick budget comment in
`CloudVerificationBackfillRunner` was updated). The self-healing rule was broadened from "rows with
no observations" to "rows missing any observation the current sampling records"
(`deleteIncompleteVerifications`), which deliberately returns every pre-upgrade row to the
candidate pool on the first post-deploy run: the old rows kept only the cone mean and cannot answer
either question. Expect the report to be sparse for roughly a day while the hourly ticks re-walk
the backlog, exactly like the original backfill.

**How to read it against the fix list above:** if `gapped(>=40)` is rare, the mean is an adequate
aggregate and no forecast-side change is warranted; if it is common and gap error concentrates
there, min/centre/max should be surfaced to the scorer (a `DirectionalCloudData` + prompt change).
If `farCloudier&highCanvas` is non-trivial, the "ideal scenario" rule needs a far-corridor guard —
which, unlike relaxing the veto, *cannot* be gated on `missedOpportunities` (it would create
wasted trips instead), so it should be sized here first. Neither change should land before this
report has a re-verified window behind it.

## 9. Measured results (2026-08-16 → 2026-08-17, window complete)

⚠️ **Read the closing subsection ("The triage cut") first.** The veto and blocked figures in the
earlier subsections are whole-window, and the final recut re-attributed the headlines: most of
those populations were stood down by triage before any prompt was built, and the dramatic
anti-selection finding did not survive the correction.

Window 2026-02-01 → 2026-08-06, **29,016 evaluations, backlog fully verified** (`remaining: 0`,
no `lastError`). Three pulls were read along the way — 7,165 rows (2026-08-13), 17,146
(2026-08-14), 29,016 (2026-08-16) — and the third was the first that did not overturn the second,
which is the convergence test these numbers had to pass before anything below counts as evidence.

⚠️ **Partial windows reversed two conclusions.** At 7k rows the uncapped veto looked *healthy*
(+15.4 gap error, better than population) and cone-gap error looked concentrated in gapped skies;
both readings were dead by 17k. Anyone tempted to act on a partially re-verified window should
read those two sentences again.

Mean gap errors below include the known ~25pp forecast-vs-ERA5 baseline offset; every conclusion
therefore rests on **within-report contrasts** (fired vs not-fired, capped vs uncapped, bucket vs
bucket), where the offset cancels.

### The veto (D2) — fires on skies that were *clearer* than average

- Fired on 3,658 of 29,016 (12.6%), forcing rating 1–2 on each.
- Observed horizon low cloud: **49.1% where it fired vs 56.8% where it did not**
  (`vetoSeparation` −7.7pp). It does not merely fail to discriminate — it anti-selects.
- Gap error +35.6 fired vs +24.6 not fired: it fires precisely where the forecast overpredicted
  horizon cloud the most.
- **D7 answered:** capped +36.2 vs uncapped +34.9, `capSeparation` −0.9pp. The 200 km cap is not
  the broken half — the trajectory-identity critique was real but immaterial. The ceiling is the
  defect. By wind–sun angle the veto errs at every bearing (aligned +29.8 / oblique +37.3 /
  opposed +35.9), so no angle carve-out rescues it.
- **Justified change:** demote the absolute ceiling (`PromptBuilder:209-220`) to a bounded
  penalty. Not removal — Copt Hill 2026-03-11 remains a real wasted trip the signals caught — and
  **not** F2's exemption-on-clear-horizon, which stays rejected: the demotion is unconditional,
  not keyed to the reading Copt Hill proved misleading. Prompt-regression assertions will move
  (user-owned); the sky-rating eval harness must be re-baselined.

### Cone structure — SHELVED

Gapped skies are common (34%) but error does not concentrate there (abs 32.3 vs uniform 29.2;
mixed is worst at 38.6). By §8's own rule, surfacing min/centre/max is not warranted.

### The corridor — over-pessimism measured directly; the guard still unsized

- **`farClearer&midCanvas`: 3,201 skies (11%) — the rule-physics failure, measured.** Near gate
  genuinely blocked (observed 72.8, forecast 87.1 — the forecast was roughly *right*), corridor
  centre clear (drop 57.6), mid canvas overhead. The ">60% = blocked" rule kills a sky whose
  canvas is underlit through the clear corridor centre. Same story via proxy for high canvases
  (3,043 skies, gap error just +5.3).
- **Second mechanism in the same bucket:** `meanFarError` +37 there — the forecast's own 226 km
  reading misses the drop ERA5 sees, so the *existing* strip-vs-blanket softener under-fires
  because its input is biased, not because the rule is absent.
- **`farCloudier&midCanvas` (2,903) does NOT size the ideal-scenario guard.** Its mean member has
  `forecastGapLow` 87 — the forecast called the gate blocked, so "ideal scenario" never fired.
  The guard's target population (forecast-clear gate over a blanketed corridor) needs a
  forecast-conditioned cut (`forecastGapLow < 20` within `farCloudier`) before it can be sized.
  Free, read-side, not yet built.

### The strip split (2026-08-17) — the blanket label is the mechanism

The forecast-conditioned cuts (#522 follow-up) and the thin-strip split landed and were pulled the
same day; together they close the measurement program.

- **The ideal-scenario guard is dead**: `farCloudier&fcstClear(<20)` = 428 evenings (1.5% of all),
  `farCloudier&fcstIdeal` = 109 (0.4%). The false-"go" case barely exists — the guard is not
  built, by its own pre-registered decision rule.
- **The over-pessimism population is 6,281 (21.7% of all evaluations)** — forecast gate deep in
  the blocked band (mean 92%), observed corridor beyond genuinely clear.
- **The split is decisive and bimodal.** `stripSeen` 2,915 (46%): the forecast far reading showed
  the drop, `meanFarError` **+2.9** — when it sees the clearing it is nearly exact, and the THIN
  STRIP override plausibly softened. `stripMissed` **3,366 (54%, 11.6% of ALL evaluations)**:
  `meanFarError` **+67.7** — the forecast claimed ~89% over a ~21% corridor, a forecast-visible
  drop of ~3pp against a real ~58pp, so the data block printed
  `[EXTENSIVE BLANKET — full penalty applies]` and *confirmed* a blanket that was not there.
- **Both cheap fixes are dead**: no threshold retune reaches a 3pp forecast drop, and no constant
  bias correction fits a bimodal error (+2.9 / +67.7 is a mode split, not an offset).
- **The design conclusion**: the far-solar forecast reading may *soften* (as strip corroboration
  it is accurate) but must not *confirm* a blanket. Sizing the change needs one more number — the
  blanket label's precision (how often a forecast blanket call is right at all) — measured by the
  blanket-precision cut before `blanket-confirmation-plan.md` is executed.

### The triage cut (final, 2026-08-17) — the headline figures re-attributed

The fifth recut (#529, `byTriageCut`) re-read the veto and blocked families over slots at or
under `WeatherTriageEvaluator`'s 80% stand-down threshold — the only slots a prompt could have
been built for. It ends the measurement program, and it overturns this section's own headline:

- **The veto's anti-selection was a triage artifact.** Only **545 of 3,658 fired slots (15%)**
  were promptable; over those, the separation is **+3.4pp** (observed 36.3 fired vs 32.9
  not-fired) — vetoed skies marginally *cloudier*, not clearer. The −7.7pp whole-window figure
  lived entirely in the >80% population, where neither fired nor not-fired slots were ever
  prompted. What survives for the demotion: the signal barely discriminates, and it forces 1–2★
  on skies averaging **36% observed horizon cloud** — 545 times in six months (~2% of
  evaluations). An absolute ceiling on that signal remains indefensible *as absolute*; the
  dramatic framing does not.
- **The blocked headlines collapse the same way**: `fcstBlocked&underTriageCut` = **326** of
  6,281 (5%); `stripMissed&underTriageCut` = **192** (0.66% of all evaluations). The blanket
  *prompt* change is a small-footprint fix.
- **The blanket label's under-cut precision fires the pre-registered rule**: 285 of 532
  promptable blanket calls — **53.6%** — sat over an observed-open corridor. Double the 25%
  threshold, so `blanket-confirmation-plan.md` ships as drafted, subject to its stop-points. The
  baseline-offset caveat is weakened here: promptable non-vetoed slots show gap error −0.2 —
  essentially unbiased — so the ~26pp whole-window offset concentrates in heavy-cloud forecasts
  rather than applying uniformly.
- **The centre of gravity moves to triage.** 97% of blanket calls sit above the cut, and there
  **4,228 of 16,210 (26%)** had an observed-open corridor — thousands of slots stood down as "—"
  with no look at the corridor, where a scoreable sky existed. The triage-corridor question is
  now the largest user-visible opportunity in the program, and it is a `WeatherTriageEvaluator`
  design question, not a prompt one.

### Standing caveats

ERA5 is reanalysis, not observation: a disagreement means "the forecast differs from a
better-informed model". Nothing here says a sunset was beautiful — `actual_outcome` is still
empty, and the rating-scale consequences of any prompt change still need the eval harness plus
user-owned regression review. The ~319 km cirrus probe remains an open, costed option; the
`&highCanvas` buckets behave directionally like `&midCanvas`, which weakens the urgency.

## 10. Cross-vendor physics review (2026-08-27)

The geometry in §8 was put to an adversarial cross-vendor review (OpenAI Codex, physics-only
brief, no repo access, no CLAUDE.md). Full report:
`docs/engineering/adversarial-solar-cloud-physics-review.md`. Every quantitative claim in it was
independently re-derived here before adjudication — all reproduce exactly (blocking windows,
per-top corridors, refracted edges, the seasonal azimuth sweep). Verdicts:

- **Accepted, §8 amended.** (1) The "steals only the final 0.08°" figure is specific to the
  226 km point; optimally-placed 1 km cloud blocks the final 0.185° of an 8 km canvas's lit arc.
  (2) The "~160–515 km" deep-deck range spliced the 2 km near edge onto the 3 km far edge; the
  real corridors are 160–479 (2 km) and 124–515 km (3 km). (3) The 113 km "far edge" is
  conditional on the 1 km top idealisation — 2–3 km tops move the observer's gap corridor to
  160–196 km. (4) A ~319 km sixth point stays geometric; "342–349 km with refraction" is false
  precision, since near-horizon refraction (~0.57°, profile-dependent) dwarfs every blocking
  window and a single √k stretch is uncertainty, not a correction.
- **Contested with measured evidence.** The review's C8 charge — the cone mean erases gap
  topology (90/0/90 → 60) — was this program's own pre-registered question, and §9 answered it
  over 33k rows: gapped skies are common (34%) but error does **not** concentrate there (abs 32.3
  vs 29.2 uniform; mixed worst at 38.6), so keeping the mean is the measured choice, not an
  oversight. Min/centre/max are already persisted on the verification side (V142). Likewise its
  bimodality reading ("edge displacement, not corridor validation") matches §9's own framing, and
  its C7 advection refutation restates D7, which §9 already measured as immaterial
  (`capSeparation` −0.9pp) with the ceiling — now demoted — as the real defect.
- **Aligned with standing decisions**, independently reached: keep 113/226 as labelled
  non-binary features; far reading softens, never escalates; no threshold retune without outcome
  validation; AOD/visibility as the useful colour covariates (the aerosol proxy already does
  this, unknown to the reviewer).
- **New named open questions**, evidence-gated, not commitments. (1) *Swept-azimuth cone*: from
  sun altitude +6° to −6° the setting azimuth sweeps 14.4° (50°N equinox) to 35.1° (59°N
  solstices, asymmetric 21.5/13.6) — a static ±15° cone centred on the event instant under-covers
  the swept horizon at high latitude. Whether that costs accuracy is a recut question (does gap
  error grow with latitude/season?) before it is a design one. (2) *Terrain line-of-sight*: UK
  westward sightlines can cross >1 km terrain inside the 113 km corridor; a DEM mask per
  location/bearing would be site-scoped and more defensible than moving any national constant.
  (3) The review's grazing-path extinction scale (airmass ~38 at the horizon; direct-beam
  transmission ~0.15 at AOD 0.05) is a reminder that every corridor here is geometry about
  *reachability*, not a claim of photographic sufficiency — consistent with soften-never-confirm.
