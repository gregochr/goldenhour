# Region gloss reads the same sky as the ratings — plan

Status: planned 2026-11-15, reviewed (plumbing + forecast-logic lenses), revised. Two phases, one PR each.

## The problem

Plan tab, Mon 16 Nov sunset, Northumberland & Tyneside:

- **Region gloss** (Haiku, `BriefingGlossService`): "Clear skies limit colour potential severely. With
  essentially all layers clear — just 18% low cloud and no mid or high layers — there is minimal
  atmospheric canvas … PhotoCast rates 43 of the 55 evaluated locations highly, but … even strong ratings
  reflect a fundamentally cloudless sky."
- **Angel of the North** (full evaluation, 4★): "A clear solar horizon under a full high-cloud canvas
  should catch warm colour at sunset."

Each is faithful to its inputs. The inputs differ:

| | Gloss | Full evaluation |
|---|---|---|
| Cloud source | Observer grid point (`BriefingSlotBuilder.buildSlot`) | Directional cone at 113 km on the real solar azimuth ±15°, plus the antisolar side |
| Canvas | Observer mid/high only | Mid/high on **either** side is canvas (`PromptBuilder` ~L90–180) |
| Clear-sky trigger | `anyMatch` — **one** slot with all observer layers < 15% | per location; a truly cloudless sky is capped ≤ 3★ (`CLEAR SKY CAP`) |
| Prompt stance | `CRITICAL RULE` (capitals) forces cautionary prose; it beats the soft "average > 3.5 justifies optimism" | "a clear observer point with directional cloud canvas is NOT 'clear sky'" |

What the briefing already has: `BriefingWeatherLoader.fetchHorizonCloud` fetches low/mid/high
(`CLOUD_ONLY_PARAMS`) at 113 km on a **fixed** bearing — `SUNSET_BEARING = 270°`, `SUNRISE_BEARING = 90°`
(`BriefingWeatherLoader.java:41,44`) — for every colour location, all hours. Only the low value of the
event's own side is read (`extractHorizonLowCloud`, for the horizon demotion). So at sunset both the due-west
(sun-side) and due-east (antisolar) responses are already in memory; the sunset hour index works on both
(same build, same `fetchForecastChunk` shape, `timezone=UTC`, arrays start 00:00 UTC — checked).

The fixed bearing is ~40° off the real azimuth in November (sunset ≈ 225°) and again in summer (≈ 315°),
about 75–85 km from the evaluation's cone. Phase 1 is honest about that ("on the sunset side"); Phase 2
moves the point.

---

## Phase 1 — the gloss stops contradicting the ratings

### 1. Carry the horizon layers on the slot

`BriefingSlot.WeatherConditions` (`@JsonUnwrapped` on `BriefingSlot`, L118) gains five nullable components
after `highCloudPercent`, each `@JsonInclude(JsonInclude.Include.NON_NULL)`:

```java
Integer sunSideLowCloudPercent, Integer sunSideMidCloudPercent, Integer sunSideHighCloudPercent,
Integer antiSunSideMidCloudPercent, Integer antiSunSideHighCloudPercent
```

- Sun side = the event's own horizon response (`getForLocation(id, eventType)`); anti-sun side = the other
  event's response (`getForLocation(id, opposite)`), read at the **same** `idx`.
- Null when that response is missing, its hourly block or list is null, `idx >= size`, the element is null,
  or the slot is a canopy slot.
- Keep a public 10-argument constructor delegating with five nulls (Javadoc it — checkstyle). Only the sky
  path in `buildSlot` uses the 15-argument form. Update the record's `@param` list and its "at the observer
  point" Javadoc (`BriefingSlot.java:~592`).
- `buildSlot` gains the anti-sun response as a parameter (or receives the `HorizonCloudData` lookup — the
  builder's choice; keep `BriefingService`'s call site at L685 the only one changed). The horizon demotion
  keeps reading sun-side low exactly as today.
- Replace `extractHorizonLowCloud` with one null-safe `extractLayer(response, getter, idx)` helper.
- Wire/cache: the new keys appear flattened in `/api/briefing` and `daily_briefing_cache` only when non-null.
  The cache uses the **Jackson 2** mapper (`BriefingService.java:3,290,937`); the HTTP path uses Jackson 3.
  Prove both (tests below). `BriefingVersion` hashes the gloss headline, so the first build after deploy
  bumps the briefing version once — expected.

### 2. The gloss inputs (`BriefingGlossService.buildUserMessage`)

All of the following run over **sky slots only**: exclude `canopy()` slots everywhere below (medians, the
sampled share, the clear count). If a region has no sky slots, keep today's behaviour on all slots.

- **Per-slot effective layers.** A slot is *directional* when sun-side low/mid/high are all non-null. Then:
  `low = sunSideLow`, `mid = max(sunSideMid, antiSunSideMid ?? 0)`, `high = max(sunSideHigh,
  antiSunSideHigh ?? 0)` — i.e. horizon low is the blocker, the larger of the two sides is the canvas,
  matching how the evaluation scores. Otherwise the observer values for all three (never mix).
- **Median source.** GO sky slots, else all sky slots (as today). If **all** of the median source is
  directional, the medians come from the effective layers and `cloudSampledOnHorizons` = true; otherwise
  the observer medians and `false`. No mixed medians.
- **`clearAllLayers`** = a strict majority of the **median source** has all three effective layers
  `< BriefingVerdictEvaluator.CLEAR_ALL_LAYERS_MAX` (15; note this is a separate constant from the
  evaluation prompt's ≤ 5% cap — reused deliberately). It no longer reads the slot's `"Clear all layers"`
  flag. Using the median source keeps it consistent with the medians sent alongside it.
- **Ratings gate (deterministic).** After `appendClaudeScores`, if `claudeAverageRating >= 3.5` and
  `lowCoverage` is not true, force `clearAllLayers` = false. Rationale: the evaluation caps a cloudless sky
  at ≤ 3★, so a well-covered region averaging ≥ 3.5 means the full analysis saw canvas. Name the 3.5 as a
  constant shared with the prompt sentence that already uses it.
- New key `cloudSampledOnHorizons` → add to `LEAKED_FIELD_NAME`.

### 3. Prompt (`SYSTEM_PROMPT`)

- After `DETAIL`: "When cloudSampledOnHorizons is true, cloudLow is low cloud about 110 km out on the
  sunset (or sunrise) side — what the light must pass through — and cloudMid/cloudHigh are the larger of
  the mid/high cloud on that side and the opposite side — the canvas the light can catch. Describe them as
  cloud on the horizons, not overhead."
- `PHOTOCAST SCORES`: append "Never say the ratings are mistaken or explain them away; if the cloud figures
  and the ratings seem to disagree, describe the cloud plainly and let the ratings stand."
- `CRITICAL RULE`: unchanged text; it now fires only on a majority-clear, ratings-consistent input.

No `GLOSS_VERSION` bump: prompt and user message are hashed into the reuse key; `previousGlosses` is
in-memory only and has one caller, so every gloss regenerates on the next build (gloss spend is about $4 a
month per `claude-cost-ledger.md`).

### Tests (read `docs/engineering/test-improvement-standards.md`; pin every stub argument, no bare `any()`)

`BriefingGlossServiceTest` — new:
1. All median-source slots directional ⇒ effective medians, `cloudSampledOnHorizons` true. Illustrative
   numbers (not the real screenshot data): observer 18/0/0, sun side 15/0/10, anti-sun high 90 ⇒
   `cloudHigh` 90, `clearAllLayers` false.
2. No horizon data ⇒ observer medians, flag false (today's behaviour).
3. One median-source slot non-directional ⇒ observer medians for all, flag false.
4. A slot with one null sun-side layer ⇒ treated as non-directional.
5. Antisolar-only canvas (sun side 5/0/0, anti-sun high 70) ⇒ not clear.
6. Majority: 1 of 5 clear ⇒ false; 3 of 5 ⇒ true; 2 of 4 ⇒ false.
7. Slot flagged `"Clear all layers"` whose effective layers are not clear ⇒ not counted (proves the flag
   is no longer read).
8. Canopy slot excluded from medians and from the clear count (a canopy GO with 90% low cloud in a region
   must not move `cloudLow`).
9. Ratings gate: majority clear + average 3.6 + coverage normal ⇒ `clearAllLayers` false; same with
   `lowCoverage` true ⇒ true; average 3.4 ⇒ true.
10. `LEAKED_FIELD_NAME` drops a detail containing `cloudSampledOnHorizons`.
11. Prompt contains the two new sentences (string-contains; this proves wiring, not model behaviour —
    say so in the test's comment).

Rewrites of existing tests that set `clearAllLayers` only through the flag:
`BriefingGlossServiceTest` ~L665–711 (three tests) and `BriefingGlossReuseTest.richRegion` (~L63). Give
their slots clear effective layers instead; keep what each test asserts.

`BriefingSlotBuilderTest`:
12. Both horizon responses present ⇒ all five values set; horizon demotion unchanged.
13. Each null branch of the extractor: null response, null hourly, each list null, `idx >= size`, null
    element (needed for 90% branch coverage).
14. Canopy slot ⇒ all five null.

Serialisation:
15. Slot JSON without the new keys deserialises with them null via the Jackson 2 cache mapper; round-trip
    with them set preserves them; a slot with them null serialises **without** the keys — on both the
    Jackson 2 mapper and the Jackson 3 HTTP mapper (`JsonDateFormatContractTest` is the model for the
    latter).

Prompt regression: none added in this PR (would spend money and need the owner). Note it as an option in
the PR body.

### Docs and changelog

- Update the gloss description in `docs/engineering/reference/` (grep `BriefingGlossService`).
- `changelog.d/20261115-gloss-horizon-cloud.md`: `### Fixed — Region summaries no longer call a sky clear
  when the ratings saw a cloud canvas`.

---

## Phase 2 — the briefing's horizon point follows the sun

`BriefingWeatherLoader` samples at the event's solar azimuth instead of 90°/270°.

- Compute the azimuth with the project's solar utilities for each location and event on the **first**
  briefing date (it drifts under 1°/day across the briefing horizon; a per-date point would multiply the
  fetch). Anti-sun = azimuth + 180°. That is now four points per location (sun and anti-sun for each event)
  instead of two — dedup by grid cell as today; check the Open-Meteo request count in the job metrics.
- **This moves a triage input**: `applyHorizonCloudDemotion` reads sun-side low cloud, so GO/MARGINAL can
  change. Before merging, compare one build's verdicts old vs new (admin Rewind or a test fixture) and
  report the delta in the PR.
- Update the Phase 1 prompt wording from "on the sunset side" to "toward the sun".
- Tests: bearing for a known date/location (e.g. Angel of the North, 16 Nov sunset ≈ 225° ± 3°); anti-sun
  is +180°; dedup still collapses shared cells.

---

## Out of scope (named so it is not lost)

- The per-slot `"Clear all layers"` flag and triage's `applyClearSkyDemotion` (GO → MARGINAL) still use
  observer cloud. On an overhead-clear, horizon-canvas day this also makes the `goCount`/`marginalCount`
  sent to the gloss pessimistic. It is a verdict change with its own blast radius (Plan matrix, Go counts,
  `rollUpVerdict`) and should be judged against outcome data.
- The briefing samples one point per side; the evaluation uses a 3-point cone. Fine for a regional median.
- The far (226 km) point stays out of the gloss — architecture-decisions.md forbids giving it escalatory
  force.

## Gates

Backend build ladder from `CLAUDE.md` (compile → touched test classes → checkstyle → full `verify` with the
integration exclusion). No frontend change. No migration.
