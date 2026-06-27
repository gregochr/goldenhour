# Sky-Rating Eval Harness — Step 0 Investigation

**Status:** Read-only investigation. No code changed. This document confirms the
*foundation* for a `pass^k` eval harness over PhotoCast's LLM sky-rating scorer
(the base `PromptBuilder` prompt). The harness *design* (band-not-point, both
halves needed, direction-bucketing) is sound and not re-litigated here.

**Scorer under test:** `ClaudeEvaluationStrategy.evaluate(AtmosphericData)` →
`SunsetEvaluation`, using `PromptBuilder.buildUserMessage(...)` + a structured
JSON-schema output. Same prompt for Haiku/Sonnet/Opus.

---

## Headline (Section 1): `PromptRegressionTest` is a *partial eval*, not a regression guard → **extend its approach, supersede its mechanics**

The single most important finding, because it decides build-new vs extend vs
complement: **despite the name "regression", `PromptRegressionTest` does not
compare to a stored baseline. It asserts against absolute bounds derived from
real-world observations** — which is the definition of an *eval*, not a
regression test.

Evidence — [`PromptRegressionTest.java:28-44`](backend/src/test/java/com/gregochr/goldenhour/service/evaluation/PromptRegressionTest.java):

> "Prompt regression tests that call the real Claude API with known atmospheric
> data and assert scores stay **within bounds established from real-world
> observations**."
>
> To add a case: "Go to a location for sunrise/sunset and **observe the actual
> conditions** … Add a new test method with the atmospheric data and your
> **observed score bounds**."

The assertions are bands, not points, and they encode ground truth:

```java
// PromptRegressionTest.java:118-125  (Copt Hill, blocked solar horizon — washout)
assertTrue(result.rating() <= 2, ...);
assertTrue(result.fierySkyPotential() <= 25, ...);
assertTrue(result.goldenHourPotential() <= 35, ...);
```

```java
// :169-176  (Angel of the North — spectacular)
assertTrue(result.rating() >= 4, ...);
assertTrue(result.fierySkyPotential() >= 60, ...);
```

So the harness we want is **already half-built in spirit.** What it shares with
our design, and what it lacks:

| Dimension | `PromptRegressionTest` today | Proposed harness | Gap |
|---|---|---|---|
| Assertion shape | Absolute **band** (`<= 2`, `>= 4`) | Absolute band (`∈ {4,5}`) | ✅ same philosophy |
| Both halves | ✅ Has strong (Angel), weak (Copt Hill washout), middling (St Mary's `==4`, horizon-strip `3..<5`) | strong + flat + middling | ✅ already spans range |
| Ground-truth source | Real observation → bounds | Real bands | ✅ same |
| **Runs per fixture** | **Once** (`strategy.evaluate(data)` — single call, `:114`) | **N times (`pass^k`)** | ❌ no variance handling |
| **Miss bucketing** | Plain `assertTrue` fail | Direction-bucketed (too-cautious / too-generous) | ❌ none |
| **Fixture fidelity** | Hand-authored via `TestAtmosphericData.builder()` — **partial** input (see §2) | Fully-augmented input | ⚠️ omits augmented fields |
| Gating | `@Tag("prompt-regression")`, `-Pprompt-regression` profile, real Claude | same | ✅ reuse verbatim |
| Ownership | User-owned ground truth (CLAUDE.md rule confirmed) | user-owned bands | ✅ |

**Verdict: it is a *fumbled/partial eval*, and the new harness should SUPERSEDE
its mechanics while inheriting its philosophy and (where faithful) its fixtures.**
Specifically:

- **Extend** the *approach*: absolute bands from real observations is exactly
  right — keep it.
- **Supersede** the *mechanics*: single-shot → `pass^k`; bare `assertTrue` →
  direction-bucketed misses; partial hand-authored input → fully-augmented
  fixtures.
- It is **not** a true regression guard, so there is no "regression vs eval
  division of labour" to preserve — the name is a misnomer. We are not building
  *alongside* a regression test; we are upgrading a partial eval into a real one.

⚠️ **CLAUDE.md / git rule applies and is confirmed:** `NEVER change assertions
in prompt regression tests … these encode ground-truth expectations against real
Claude output and must only be updated by the user.` So even though we supersede
the *mechanics*, the existing bound values are Chris's to move. The clean path is
a **new harness class** (new fixtures + `pass^k` loop) that does not touch
`PromptRegressionTest`'s assertions; whether the old class is then retired is
Chris's call (open question below).

---

## §2 — The scorer's INPUT: fully-augmented `AtmosphericData` (not raw weather)

**Method:** [`PromptBuilder.buildUserMessage(AtmosphericData data)`](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java) (`:349`;
an overload at `:339` adds storm-surge args). The parameter is the
`AtmosphericData` record — and it is **already augmented**, not raw Open-Meteo
numbers. Augmentation happens upstream in
[`ForecastDataAugmentor`](backend/src/main/java/com/gregochr/goldenhour/service/ForecastDataAugmentor.java)
(directional cloud at 113/226 km, cloud-approach trend + upwind sample, tide,
storm surge, inversion score, orientation, bluebell).

**`AtmosphericData` fields** (record at
[`AtmosphericData.java:35-55`](backend/src/main/java/com/gregochr/goldenhour/model/AtmosphericData.java)):

*Always set (a fixture cannot run without these):*
- `locationName : String`
- `solarEventTime : LocalDateTime`
- `targetType : TargetType` (SUNRISE / SUNSET)
- `cloud : CloudData` — `lowCloudPercent`, `midCloudPercent`, `highCloudPercent`
- `weather : WeatherData` — `visibilityMetres`, `windSpeedMs`, `windDirectionDegrees`,
  `precipitationMm`, `humidityPercent`, `weatherCode`, `shortwaveRadiationWm2`,
  `dewPointCelsius?`, `pressureHpa?`, `snowfallCm?`, `snowDepthMetres?`, `freezingLevelMetres?`
- `aerosol : AerosolData` — `pm25`, `dustUgm3`, `aerosolOpticalDepth`, `boundaryLayerHeightMetres`
- `comfort : ComfortData` — `temperatureCelsius?`, `apparentTemperatureCelsius?`, `precipitationProbability?`

*Augmented / conditional (these are what make a fixture "textbook-strong" rather
than just "clear numbers"):*
- `directionalCloud : DirectionalCloudData?` — solar/antisolar Low/Mid/High at
  113 km + `farSolarLowCloudPercent?` at 226 km (the strip-vs-blanket signal).
  **This is the field that decides a strong vs blocked sky** — the regression
  Copt Hill washout fixture sets *clear observer cloud* but `solar=67% low /
  100% high` here.
- `cloudApproach : CloudApproachData?` → `solarTrend : SolarCloudTrend?`
  (slots T-3h…T, with `isBuilding()`/`isClearing()`) + `upwindSample : UpwindCloudSample?`
  (`distanceKm`, `windFromBearing`, `currentLowCloudPercent`, `eventLowCloudPercent`)
- `mistTrend : MistTrend?` — slots T-3h…T+2h (`visibilityMetres`, `dewPointCelsius`, `temperatureCelsius`)
- `inversionScore : Double?` (0–10), for elevated water-overlooking sites
- `tide : TideSnapshot?`, `surge : StormSurgeBreakdown?`, `adjustedRangeMetres?`,
  `astronomicalRangeMetres?` — coastal tidal only
- `bluebellConditionScore : BluebellConditionScore?` — in-season bluebell sites only
- `locationOrientation : String?` ("sunrise-optimised"/"sunset-optimised")
- `stability : ForecastStability?` + `stabilityReason : String?` + `pressureTrend : PressureTrend?`

**Faithful-fixture field set (the deliverable for §2):** a credible
*textbook-strong* or *flat-grey* fixture must populate, at minimum, the *always*
block **plus** `directionalCloud` and `cloudApproach.solarTrend` — because those
are the blocks the prompt actually reasons over for colour (`[DIRECTIONAL CLOUD]`,
`[BUILDING]/[CLEARING]`, `[THIN STRIP]/[EXTENSIVE BLANKET]`). Add `mistTrend`,
`stability`/`pressureTrend`, and `inversionScore` to exercise the full prompt.
Coastal/bluebell sub-records only matter if the fixture is a seascape/bluebell
location.

**Key gap this exposes in the existing test:** `TestAtmosphericData.builder()`
(used by `PromptRegressionTest`) sets the *always* block + `directionalCloud`,
but most cases leave `cloudApproach`, `mistTrend`, `stability`, and
`pressureTrend` **null**. So today's regression fixtures are *partial* — they
don't drive the trend/stability blocks. A faithful eval fixture should carry
those, which is exactly why captured-real fixtures (§5) beat hand-authored ones.

---

## §3 — The scorer's OUTPUT: structured score-plus-prose; band against `rating`

**Type:** [`SunsetEvaluation`](backend/src/main/java/com/gregochr/goldenhour/model/SunsetEvaluation.java)
(record, `:28-39`). Fields:

| Field | Type | Notes |
|---|---|---|
| `rating` | `Integer` | **1–5 star — THE field the eval bands against** |
| `fierySkyPotential` | `Integer` | 0–100 dramatic colour |
| `goldenHourPotential` | `Integer` | 0–100 light quality |
| `summary` | `String` | 1-sentence prose rationale (required) |
| `basicFierySkyPotential` | `Integer?` | LITE-tier, no directional data |
| `basicGoldenHourPotential` | `Integer?` | LITE-tier |
| `basicSummary` | `String?` | LITE-tier prose |
| `inversionScore` | `Integer?` | 0–10 |
| `inversionPotential` | `String?` | NONE / MODERATE / STRONG |
| `headline` | `String?` | 4–9 word card header |

**Score extraction is TRIVIAL (structured, not prose-parsed).** The output is
constrained by an Anthropic structured-output JSON schema in
[`PromptBuilder.buildOutputConfig()`](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java) (`:559-599`):
required `rating` (integer enum 1–5), `fiery_sky`, `golden_hour`, `summary`;
optional `basic_*`, `inversion_*`, `headline`; `additionalProperties:false`.
Parsing in
[`ClaudeEvaluationStrategy.parseEvaluationWithMetadata()`](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ClaudeEvaluationStrategy.java) (`:251-287`)
reads the JSON node directly (`node.get("rating").asInt()`), with a bounded-regex
fallback only if strict JSON fails (e.g. unescaped quote in summary). **The
harness asserts on `result.rating()` — a parsed integer, no extraction logic
needed.**

**Which field to band against:** `rating` (1–5). It is returned *directly by
Claude*, **not** computed from the two 0–100 scores. (`RatingCombiner.withRating()`
passes it through a visitor but does not recompute it from sub-scores.) The
0–100 scores are useful as *secondary* band assertions (the existing regression
test asserts on all three), but `rating` is the primary axis the design's
`∈ {4,5}` bands target.

**Rationale (`summary`) — what a second assertion could check.** `summary` is a
single sentence; real/fixture examples: *"Pre-frontal fire — mid cloud catches
colour."*, *"Spectacular sea of clouds."*, *"Clear horizon."*, *"Heavy blanket
over the horizon."* `headline` (4–9 words, tone-matched to rating) is a second
prose surface. **What's assertable** (scoping only — not designing the
assertions): the prose reliably name the *driver* — cloud canvas ("mid cloud
catches colour", "blanket"), horizon state ("clear horizon", "blocked"),
inversion ("sea of clouds"), aerosol/colour, and temporal caveats ("if the front
holds off"). This is enough to catch the **score-right-reasoning-wrong** failure
mode the clearance work flagged: e.g. a strong fixture whose `summary` should
mention the *canvas*, or a flat clear-sky fixture whose `summary` should name the
*clear-sky liability* (no canvas → cap at 3). A keyword/affirmation check on
`summary` is feasible; the exact assertions are out of scope for Step 0.

---

## §4 — `PromptTestService` IS the replay engine to build on

[`PromptTestService.executeReplay()`](backend/src/main/java/com/gregochr/goldenhour/service/PromptTestService.java) (`:294-394`)
does exactly what the harness needs for input:

```java
// :326-327  deserialize frozen JSON → full AtmosphericData (no re-derivation)
atmosphericData = objectMapper.readValue(ref.getAtmosphericDataJson(), AtmosphericData.class);
// :342-343  run it through the BASE scorer
EvaluationDetail detail = evaluationService.evaluateWithDetails(atmosphericData, model, null);
```

The ObjectMapper registers `JavaTimeModule` (`:91-92`) so `LocalDateTime` fields
round-trip. The replay path calls **neither** `openMeteoService` **nor**
`augmentor` — confirmed by
[`PromptTestServiceTest.replayTest_usesStoredData`](backend/src/test/java/com/gregochr/goldenhour/service/PromptTestServiceTest.java) (`:391-430`,
`verify(...never())`). So **frozen-JSON replay carries the full augmented input
verbatim** — nothing is stripped or re-derived. *If the JSON was captured
post-augmentation, the replayed input is faithful.*

**The known blind spot is in FRESH FETCH, not replay** — confirmed and bounded.
`fetchAtmosphericData()` (`:562-588`) augments directional cloud + cloud-approach
+ tide, but **not aerosol or inversion** at fetch time (the comment at `:574-577`
documents that the cloud-approach augmentation had to be patched in). This only
matters if you *fresh-fetch* a fixture; it does **not** affect replaying a
captured JSON. So: capture-then-replay is faithful; fetch-fresh is partial.

**What the harness adds on top of `PromptTestService`:** the replay engine is
done. The harness is:
1. a **`pass^k` loop** — call `evaluateWithDetails(frozenData, model, null)` N
   times per fixture (the service is one-shot; the loop is new);
2. **band assertions** on `rating` (and optionally `fiery_sky`/`golden_hour`);
3. **direction-bucketing** of out-of-band misses (rounded-down = too cautious,
   up = too generous);
4. optionally a **`summary` assertion** (§3).

This is *not* a from-scratch replay build. Whether to drive it through the
existing async `PromptTestService`/DB path or to call
`evaluationService.evaluateWithDetails()` directly in a JUnit `pass^k` loop (like
`PromptRegressionTest` does, but N times) is an implementation choice for Step 1
— the latter is lighter and matches the existing gated-test pattern.

---

## §5 — Fixtures: captured-real is possible, but ONLY via the prompt-test path (not prod forecast history)

This is the most surprising finding (see below). The intuition was "pull a real
4–5★ day's augmented input straight from prod `forecast_evaluation`." **That does
not work** — and here is exactly why.

**Where the full augmented `AtmosphericData` JSON is stored** (grep for
`atmospheric_data_json` — only two tables have it):
- ✅ `prompt_test_result.atmospheric_data_json` (TEXT) — V44;
  [`PromptTestResultEntity.java:72`](backend/src/main/java/com/gregochr/goldenhour/entity/PromptTestResultEntity.java).
  Written by `populateAtmosphericData()` (`:656-682`) **after** augmentation.
- ✅ `model_test_result.atmospheric_data_json` (TEXT) — V39 ("Serialised
  AtmosphericData JSON for exact replay in determinism re-runs");
  [`ModelTestResultEntity.java:146`](backend/src/main/java/com/gregochr/goldenhour/entity/ModelTestResultEntity.java).
- ❌ **`forecast_evaluation` has NO such column.** It stores ~50 *denormalized
  scalars* (cloud %, directional cloud, cloud-approach trend booleans, tide, surge,
  inversion) but **never the serialized `AtmosphericData`**. `buildEntity()`
  unpacks the record into columns; the inverse is **lossy** (e.g. `SolarCloudTrend`
  collapses to `solar_trend_building` boolean + two cloud %; the full slot list is
  gone). You cannot reconstruct a faithful augmented input from prod forecast rows.
- ❌ `cached_evaluation.results_json` — **outputs only** (rating + summary), no input.
- ❌ `api_call_log.request_body` — the *prompt prose*, with the atmospheric data
  embedded inside the prompt string. Not a queryable structured input; unreliable
  to parse back.

**So the sourcing answer:**

- **Captured-real IS achievable** — but the capture happens through the
  **prompt-test harness**, not prod history. `POST /api/prompt-test/run` fetches +
  augments + evaluates the real enabled colour locations and **persists the
  augmented `atmospheric_data_json` + the resulting `rating`** to
  `prompt_test_result`. That row is a faithful, captured-real fixture seed.
  ⚠️ Caveat: a *fresh* prompt-test run inherits the §4 fetch blind spot (no
  aerosol/inversion augmentation at fetch time) — so a captured fixture may have
  null aerosol/inversion unless those were present. For a colour eval, directional
  cloud + cloud-approach (which *are* fetched) are the load-bearing fields, so this
  is acceptable, but worth knowing.

- **To find range-spanning days** (a real 4–5, a real 1–2, real 3s) with their
  inputs:

  ```sql
  -- fixture seeds: full augmented input joined to the score it produced
  SELECT id, location_name, target_date, target_type, rating,
         fiery_sky_potential, golden_hour_potential, atmospheric_data_json
  FROM prompt_test_result
  WHERE succeeded = true AND atmospheric_data_json IS NOT NULL
    AND rating IN (4,5)         -- swap to (1,2) for flat, 3 for middling
  ORDER BY created_at DESC;
  ```

  Deserialize `atmospheric_data_json` → `AtmosphericData`, freeze as a test
  resource. This requires that prompt-test runs covering days in each band exist
  (or are run) on the production DB — which the dev Mac **cannot reach** (prod is
  a separate Docker host; see memory). So the practical capture step is something
  Chris runs on prod, or we capture from a local run against representative
  locations/dates.

- **Hand-authored remains the fallback** — and is what `PromptRegressionTest`
  does today via `TestAtmosphericData.builder()`. Valid, but (a) tests whether the
  scorer agrees with *our* model of "strong", not a day it actually faced, and
  (b) tends to leave augmented fields null (§2), under-exercising the prompt.

**Recommended sourcing approach:** *captured-real via the prompt-test path*,
seeded from `prompt_test_result` rows that scored in each target band, with
hand-authored fixtures only to fill gaps the captured set misses (e.g. a clean
"clear-sky, no canvas, cap-at-3" case if no real day produced one). Because the
captured JSON replays faithfully (§4), these are the most credible seeds
available.

---

## What surprised me most

**That prod `forecast_evaluation` — the table with millions of real ratings — is
a dead end for fixture sourcing.** I expected the richest fixture source to be
"every real day we ever scored." But the augmented `AtmosphericData` is only ever
*denormalized* into columns there; the one lossless serialized copy lives in the
**test-harness** tables (`prompt_test_result`, `model_test_result`), not the
production forecast table. The capture infrastructure we need was built for
*model/prompt A-B testing*, and it happens to be exactly the replay engine for an
eval — but it means "real captured fixtures" come from *running the prompt-test
tool*, not from mining forecast history. The second surprise: the thing called
`PromptRegressionTest` is not a regression test at all — it's a single-shot,
partial-input eval. The name has been hiding the fact that ~70% of this harness
already exists.

---

## Open questions for Chris

1. **Fate of `PromptRegressionTest`.** It's a partial eval, not a regression
   guard. Do you want the new `pass^k` harness to (a) live alongside it (both
   gated under `-Pprompt-regression`), or (b) absorb its cases and retire it?
   Either way I will **not** touch its assertion values (per the git rule) without
   your say-so — this is asking permission, not proposing to change them.

2. **Fixture sourcing — capture vs hand-author.** Captured-real fixtures require
   `prompt_test_result` rows in each band on a DB I can't reach from the dev Mac
   (prod is remote). Options: (i) you run prompt-test on prod for known
   strong/flat/middling days and export the `atmospheric_data_json`; (ii) we run
   prompt-test locally against representative locations/dates and capture from the
   local H2; (iii) we hand-author at the augmented layer. Which do you want as the
   primary path? (My lean: (i) for the strong/flat anchors, (iii) to fill any
   missing band.)

3. **The fresh-fetch aerosol/inversion blind spot (§4).** Captured fixtures from a
   *fresh* prompt-test run can have null aerosol/inversion. For a colour eval this
   is probably fine (directional cloud + cloud-approach are the load-bearing
   fields and *are* captured), but confirm you're OK with fixtures that may not
   exercise the aerosol/inversion blocks — or we patch the capture path first
   (out of scope for this harness, a separate fix).

4. **Rationale-assertion scope (§3).** Do you want the harness to assert on
   `summary` prose at all in v1 (e.g. strong fixture's summary mentions the
   canvas; flat fixture's mentions the clear-sky liability), or start
   score-band-only and add prose assertions once the band harness is trusted? The
   prose is assertable; the question is appetite for prose-keyword brittleness.

5. **`pass^k` parameters.** Not a Step-0 question, but flagging for Step 1: N
   (runs per fixture) and which model(s) — the existing test defaults to Haiku via
   `REGRESSION_MODEL`. Real-Claude `pass^k` over a handful of fixtures × N runs ×
   3 bands has a token cost worth sizing before building.
