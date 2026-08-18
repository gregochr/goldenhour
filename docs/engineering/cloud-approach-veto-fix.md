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
| **D2** | The veto can fire on two single-cell readings and override a clear coned horizon, a full canvas and favourable aerosol. No corroboration required. | `PromptBuilder:209-220` — **DEMOTED (shipped, `[Unreleased]`)**: the absolute 1–2★ ceiling is now a bounded penalty (`fiery_sky` −20..−30, rating cap 3) and the evidence-nullification clauses and summary gag are gone. Unconditional — see the F2 note below. |
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

⚠️ **The demotion's price is two recorded washouts at 3★, and it is measured, not estimated.**
Under the sky-rating eval harness (paired against a pristine control, 2026-08-18, Sonnet, 8 runs
per fixture), `copt-hill-11mar-false-positive` and `copt-hill-15mar-overcast` both move from
{1,2} to a steady **3** — the cap, exactly. Their bands are **ground truth and stay unchanged**:
Chris was physically present on 15 March 2026 and it was a washout. Their harness rows are
therefore **red-but-explained**, not a re-baselining opportunity, and they must not be parked on
`gated = false` to quieten them.

15 Mar is worth understanding before anyone proposes a fix. Its *observer point* was overcast
(100% low / 99% mid) but its **solar horizon reads 39% low cloud**, and the prompt orders Claude
to score from the directional data — so the >60% blocked ceiling never applied to it, and the
veto was the only rule holding it down. Cap the veto at 3 and 3 is what a directionally-clearing
sky with a 65% mid canvas earns. Claude's own summary: *"The solar horizon clears to just 20% low
cloud at event time with a solid mid-cloud canvas above, but the [BUILDING] trend and current
upwind cover of 84% make that snapshot uncertain."*

⚠️ **The obvious next move — cap at 2 when the trend PEAK was high — cannot be sized, because the
peak is not persisted.** `CloudApproachDetails.from` writes `slots.getFirst()` (earliest),
`slots.getLast()` (event) and the `isBuilding()` boolean; `isBuilding()` computes the peak and
throws it away, and V51 is the only migration that touches these columns. The pair does not
recover it whenever the peak sits at an interior slot — 15 Mar is exactly that shape (52 → 100 →
100 → 20, persisted as 52 and 20, true peak 100). `persistBatchLog` stores `responseBody` only,
so the prompt's own trend series is not a fallback either. The verified Feb–Aug window therefore
cannot answer whether a high peak selects genuinely cloudier skies; only new sampling could, and
a new column would start accumulating from deploy rather than retro-filling. Adding one is cheap
and worth doing before this question is asked again — but it is a *future* enabler, not evidence,
and the pre-registered rule that gated the cap-2 proposal fired its no-data arm on exactly this
finding.

⚠️ **The demotion that shipped is not F2.** F2 keyed its exemption on the coned solar-horizon reading — the exact reading Copt Hill proved misleading — and that rejection stands untouched. What shipped applies *whenever both approach signals stand*, conditioned on nothing, and it caps rather than exempts: rating ≤3 with a −20..−30 `fiery_sky` penalty, where F2 would have lifted a clear-horizon case out of the rule altogether. Copt Hill still lands one band above its recorded outcome under the demotion, which is the price the change states rather than hides.

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

### F6 — Persist the solar-trend PEAK, so cap-2-on-high-peak can eventually be sized

**Raised 2026-08-18, by the demotion's own stop-point.** The demotion's accepted price is two
recorded washouts rating 3★ (§4 D2). The obvious refinement — cap at **2** when the combined
signal fires *and* the trend peaked high — was proposed, gated behind a pre-registered sizing cut,
and the cut could not run: **the peak is not persisted anywhere.**

`CloudApproachDetails.from` writes `slots.getFirst()` (earliest), `slots.getLast()` (event) and the
`isBuilding()` boolean. `isBuilding()` computes `peak - earliest >= 20` and then **discards the
peak**. V51 is the only migration that touches these columns, and `persistBatchLog` stores
`responseBody` only — so the prompt's own trend series is not a fallback either. The persisted pair
does not recover the peak whenever it sits at an interior slot, which is the shape that matters:
Copt Hill 15 Mar 2026 ran 52 → 100 → 100 → 20 and persisted as `(52, 20)`, true peak **100**.

Two routes back, both recorded so neither is re-derived from scratch:

- **(a) Persist it forward** — one nullable `solar_trend_peak_low_cloud INT` beside V51's fields,
  one line in `CloudApproachDetails.from`. Cheap and obvious. It cannot retro-fill, so the sizing
  cut would wait for a fresh window to accumulate.
- **(b) Reconstruct it historically** from Open-Meteo's historical-forecast / previous-runs archive
  for the fired-promptable population, which would size cap-2 in days rather than months. Raised by
  Chris 2026-08-18. **Separate task, separate plan — not part of the demotion.**

Until one of them lands, the pre-registered rule's no-data arm stands: **cap-3 as specified**, and
the two March fixtures carry `{1,3}` bands whose upper edge is the guard.

*Cost: (a) trivial; (b) a costed read-side task of its own. Risk: none — neither changes scoring.*

### F7 — Retry a parse-failed evaluation, and stop counting API noise as a prompt regression

**Raised 2026-08-18, from the demotion's eval arms. Confirmed as its own task — not built here.**

On the two eval fixtures where the combined signal fires, Claude sometimes emits the opening of a
chain of thought as the `summary` field (`...`, `test`, `thinking...`, `placeholder`, `Block ed`,
`Let me think through this carefully.`), and once ran away into unparseable JSON:
`{"golden_hour": 68, "summary": "...Let me work this out properly.}let me analyze:_}{}{}{}...`.

⚠️ **This is PRE-EXISTING, and the first report of it was wrong.** It was initially measured as
"0 in 120 control runs" and attributed to the demotion. That zero was **a too-narrow grep**
(`summary=\.\.\.$` and `summary=test$`, anchored — a control run reading `summary=... ` with a
trailing space and one reading `Block ed` both slipped through). Re-detected across every arm:
**control 5/184 runs (2.7%), post-change 14/265 (5.3%)**; restricted to the veto-firing fixtures,
**~10% control vs ~18% post-change**. Almost every occurrence in *both* arms is on those two
fixtures, so it is a property of **conflicted approach inputs**, not of the rewording. Whether the
elevation is real is **open and unfunded** — post-deploy tallies answer it for free.

Two halves, both wanted:

- **(a) Production.** A parse failure is a *failed evaluation*, not a cosmetic defect: it happens
  **after** a successful API call, so `AnthropicApiClient`'s `@Retryable` predicates (529 /
  content filter) do not cover it. Retry once on a parse failure, on the sync path and the batch
  path as far as each allows.
- **(b) Harness.** Retry or discard degenerate runs so `MIN_PASSES` measures the *prompt* rather
  than API noise. This is what turns `copt-hill-15mar`'s 7/8 green **honestly** — its one
  out-of-band 4★ came from a run whose summary was `Let me think through this step by step..`.

**The degenerate tally stays a primary reported metric in both**, so a real rate change cannot hide
behind the retries. This protects the pristine prompt too: the ~10% was always there.

⚠️ **Use the corrected detector, never the narrow one**: flag any summary under ~45 characters, plus
openers `Let me|Let us|I need|First,|Okay|Looking at|Step |Analyzing`, and count
`Failed to parse evaluation response` separately. Eyeball the matches — legitimate short summaries
exist.

*Cost: small. Risk: low — (a) touches retry policy only; (b) touches test infrastructure only.*

**Documented beside it: the Haiku cap flake.** The demotion's rating cap does not bind equally on
both tiers, and the eval harness cannot see it — that harness runs **Sonnet only**, deliberately
("the model PhotoCast actually scores near-term with"), while `PromptRegressionTest` defaults to
**HAIKU**, which is `BATCH_FAR_TERM` for T+2/T+3. Measured 2026-08-18, one fixture
(`copt-hill-11mar`), 8 runs per arm, same session:

| Haiku, `copt-hill-11mar` | ratings | above the cap |
|---|---|---|
| pristine prompt (old absolute veto) | 3,2,1,2,2,2,2,2 | 0/8 |
| demotion, cap stated in rules prose | 4,4,3,4,4,4,4,4 | **7/8** |
| demotion, cap also on the output FIELD | 3,3,3,3,3,3,3,4 | **1/8** |

Sonnet held the cap at 7/7 well-formed runs from the prose alone; Haiku ignored it almost entirely
until the constraint was restated where the `rating` field is declared — in the prose field list and
in the structured-output schema's `rating` description. ⚠️ **Keep both restatements.** The residual
~1-in-8 Haiku breach is accepted and tracked here rather than chased with further wording.

Two observations worth keeping, because they cost real calls to find:

- **Smaller models follow the example, not the rule.** On the pristine prompt Haiku echoed the old
  veto's own `Example:` sentence nearly verbatim in 5 of 8 runs ("Cloud bank building toward the
  solar horizon makes this unreliable — approach risk outweighs the clear horizon..."). The rule's
  numbers were not what it was reading. A prompt example is a much stronger lever on this tier than
  its adjacent prose, and it should be treated as load-bearing when either is edited.
- **The schema already forbids the degenerate summaries and is ignored anyway.** The `summary`
  field's description says "never a placeholder such as 'test', 'placeholder', or an ellipsis" —
  and those are the *exact* strings the degenerate runs produce. Field-adjacent constraints help
  (they fixed the cap on Haiku) but they are not sufficient, which is part of why F7's retry half
  is wanted.

### The demotion's acceptance bar, and its one amendment

Recorded because the amendment must read as a conscious re-rule on corrected data rather than as a
quiet reinterpretation of a bar that was missed.

The demotion's second wording iteration was gated on a bar pre-registered **before** its results
were seen, in two clauses:

1. Degenerate output on the veto-firing fixtures back to **~0**.
2. `copt-hill-11mar` at **8/8 inside {1,3} on well-formed output**.

**Clause 2 was MET** and stands as written: the declarative cap wording ("rating is the LOWER of
what the sky earns and 3") produced seven well-formed runs at exactly 3 and no 4★ at all, against
1-in-8 breaches under the earlier procedural phrasing.

**Clause 1 was VOIDED FOR MEASUREMENT ERROR — not missed.** Its "~0" target was pre-registered
against a control the same session had measured as zero, and that zero was the too-narrow grep
described in F7. The real control rate on those fixtures is ~10%, so "~0" was never reachable by any
wording. Chris re-ruled the clause explicitly, on the corrected data, as: *the post-change degenerate
rate on veto-firing fixtures must not be materially above the paired control in the same session.*
Round 5 meets that — **4 post-change against 3 control**, same session, same fixture set.

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
   `-Dtest="CloudPointCacheReader*,DirectionalSamplingGeometry*,PromptBuilder*,PromptGoldenMasterTest"`.
   ⚠️ **`PromptBuilder*` does not glob-match `PromptGoldenMasterTest`** — it is the only
   test that asserts the assembled system prompt byte-for-byte, and the demotion
   (2026-08-18) turned all five archetypes red while every targeted `PromptBuilder*` run
   stayed green. Any edit to `SYSTEM_PROMPT` needs its five `prompt-golden/*.txt` fixtures
   regenerated (`-Dprompt.golden.regenerate=true`) as a standalone commit — see
   `integration-test-strategy.md` §3, which names regenerating that golden as the exact
   move that keeps every gate green while the scoring changes underneath it.
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
    depression — a zero-width window.
  - **226 km** is exactly √(2R·4): the grazing point and corridor *centre* for a **4 km mid
    canvas** (corridor 113–339 km). Chosen as "2 × horizon" for strip-vs-blanket, it is by
    coincidence the dead-centre mid-canvas probe.
  - An **8 km cirrus canvas** is blocked over **206–432 km, centred at 319 km** (= √(2R·8)).
    ~~The 226 km point sits in that corridor~~ — it sits at the near *edge*: 1 km-top cloud at
    226 km steals only the final 0.08° of the canvas's 2.87° lit arc. Deeper low decks (tops
    2–3 km, which Open-Meteo's low layer includes) widen the corridor to ~160–515 km and can
    amputate the whole red phase — so the 226 km reading still carries high-canvas signal, but as
    a synoptic-scale proxy ~90 km short of centre, not a direct measurement. A rigorous cirrus
    probe would be a sixth archive point at ~319 km.

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
