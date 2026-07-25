# Cloud-approach veto: unsmoothed inputs drive an absolute rating ceiling

**Status:** F1 and F4 implemented 2026-07-25 (#294). **F2 attempted and REJECTED** — refuted by the
Copt Hill ground-truth fixture. **F3 REJECTED** — adversarially reviewed, would introduce two new
degeneracies. **D6 (silent half-veto) found and FIXED.** D7, D8 and F5 outstanding. See §4.

⚠️ **`actual_outcome` is empty — zero rows, ever.** Every rule in this document is therefore
unvalidated, including a veto that forces rating 1–2 on ~15% of evaluations. Until outcomes exist
or reanalysis verification lands, the correct move on any scoring rule is to change **nothing** and
improve **observability**.
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
| **D7** | **The 200 km cap voids the trajectory identity.** `MAX_UPWIND_DISTANCE_M` binds beyond ~6.9 h at 8 m/s. The nightly batch runs ~20 h ahead of a summer sunset and every T+1…T+7 slot is far past it, so for most evaluations `dist ≠ windSpeed × timeToEvent` and the sample is just "cloud 200 km upwind now" — while `PromptBuilder:204-206` asserts otherwise and the veto forces 1–2★ on it. Anchor-independent; F3 would not have helped. | Open — measure before changing. |
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
