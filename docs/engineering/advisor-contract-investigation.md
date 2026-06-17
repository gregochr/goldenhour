# Advisor-Contract Pass — Step 0 Investigation

**Status:** Read-only investigation complete. No code or prompt changes made.
**Date:** 2026-06-17
**Scope:** Establish ground truth for the three judgement changes banked from the
truncation hotfix — (1) stay-home floor, (2) scarcity-aware ranking,
(3) aurora-region contract — before any prompt/code work.

All line numbers are against the **main working tree**
`backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BriefingBestBetAdvisor.java`
unless otherwise noted (the system prompt is a single inline text block, lines 120–362).

> **Evidence constraint.** Production runs in Docker on a separate host; the dev
> machine cannot query the prod DB or read prod logs (per project convention).
> The prod-frequency claims below therefore rest on (a) the evidence supplied in
> the pass brief — the 2026-06-16 response and runs #38/#39/#40 — and (b) what the
> code *guarantees* about how a stay-home pick is classified and persisted. Where a
> claim needs a live prod query to settle, it is flagged as an open question for
> Chris.

---

## Headline: the stay-home-floor verdict — **(a), trending: largely resolved by the truncation fix**

**The pass is two items, not three.** Item 1 needs no prompt surgery — at most a
guard test plus one optional one-line reinforcement. Here is the evidence chain.

### What the contract says today

Two relevant prompt clauses, both live:

- **Honesty / tone** (lines 245–247): *"If everything is STANDDOWN, say so
  honestly. Don't oversell marginal conditions. Be human — tell the photographer
  to stay home, charge their batteries, maybe edit last weekend's shots. A bit of
  humour is fine."*
- **Structural floor** (line 318): *"If everything is STANDDOWN, return a single
  pick with event and region as null."*

So the floor instruction is unambiguous and present.

### Why the omission was a truncation symptom, not a contract gap

The plumbing **explicitly** treats a `{event:null, region:null}` pick as a
first-class success — it is not filtered anywhere:

- Parser preserves the nulls — `parsePickNode` lines 1445–1448 map JSON `null` →
  Java `null` for both `event` and `region`.
- A non-empty `picks` array → `classifyAndParse` returns `SUCCESS_WITH_PICKS`
  (line 1410).
- Validation **whitelists it first** — `isPickValid` lines 700–702:
  `if (pick.event() == null && pick.region() == null) return true;`
- Coverage/headline gates exempt it — `applyCoverageAwareRanking` only engages at
  `picks.size() >= 2` (line 762); `isHeadlineEligible` lines 805–807 exempt
  null/null.
- Enrichment passes it through — `enrichWithEventData` line 588:
  `if (pick.event() == null) return pick;`
- `advise(...)` returns `SUCCESS_WITH_PICKS` (line 529) because `enriched` is
  non-empty.

So nothing downstream drops the floor pick. The only mechanism that *did* drop it
was truncation: the floor pick is a **single** pick, and when the 1024-token cap
(the pre-hotfix budget) cut the JSON mid-structure, `objectMapper.readTree` threw
and — before `salvagePicks` existed — the whole response became `FAILED` with zero
persisted picks. The fix raised the budget to **4096** (`DEFAULT_MAX_TOKENS`,
line 87; rationale lines 78–86; configurable via
`photocast.best-bet.max-tokens`, line 398) and added `salvagePicks`
(lines 1482–1518) to recover complete leading picks from a truncated tail. Both
directly address the omission mechanism.

### Corroborating evidence (from the brief)

- 2026-06-16 01:13 advisor response ended with a `{event:null, region:null,
  confidence:"high"}` pick and prose "...while opportunities exist right now." —
  the floor pick **did** fire on a flat-ish window.
- Runs #38/#39/#40 all `best_bet_status = SUCCESS_WITH_PICKS`, JSON closing
  cleanly — responses now complete inside the 4096 budget.

### The verdict, precisely

**(a) — largely resolved by the truncation fix.** Recommendation for the pass:
**do not rewrite the floor instruction.** Instead:

1. Add a **guard test** asserting a flat/all-STANDDOWN rollup produces a single
   null/null pick that survives `validateAndFilterPicks` as `SUCCESS_WITH_PICKS`
   (this protects the behaviour the truncation fix restored — there is no such
   test today).
2. Consider — but only if prod observation shows residual flakiness — a **one-line
   reinforcement** addressing the residual ambiguity below.

### The residual ambiguity (this is the one real risk, and it surprised me — see below)

There are **two** distinct "honest nothing" outcomes in the code, and the model
can pick either:

| Model output | Status | Path |
|---|---|---|
| `"picks": []` (empty array) | `SUCCESS_NO_PICKS` — "honest decline" | line 1401–1404 |
| `"picks": [{event:null, region:null, ...}]` | `SUCCESS_WITH_PICKS` | line 1410 |

The contract (line 318) wants the **second**. But the parser also accepts the
first as a legitimate honest decline (line 1402 log: *"empty 'picks' array
(honest decline)"*). So even with ample budget, a model that "honestly says
nothing" by emitting `picks: []` satisfies its own reading of lines 245–247 while
**missing** the line-318 structural floor — and that reads as `SUCCESS_NO_PICKS`,
not the intended stay-home `SUCCESS_WITH_PICKS`. This is the only way item 1 is
not a pure (a): the floor's *reliability* depends on the model choosing the
null/null pick over the empty array. Budget no longer forces the omission;
instruction ambiguity could still cause it.

**This is the cheap, high-value prompt tweak if one is wanted:** make line 318
explicit that an empty `picks` array is *not* an acceptable all-STANDDOWN
response — the stay-home null/null pick is mandatory. Confirm against prod
frequency first (open question Q1).

---

## Item 2 — Scarcity: input-assembly map + which signals are reachable

### The advisor's input today (the rollup)

The rollup is a **hand-built Jackson `ObjectNode` tree** (not a record/Map),
serialized to a JSON string. Built in `buildRollupJson()` (line 907); per-region
detail in `appendRegionNode()` (line 1039). Shape: `root → events[] → regions[]`.
Region-level aggregate only — **no per-location slot scores reach the advisor.**

**Root** (lines 916–980): `currentTime`, `forecastWindow{startDate, endDate,
dayCount, availableDates[]}`, `validEvents[]`, `validRegions[]`, `events[]`.

**Per-event** (lines 936–943): `event` (e.g. `2026-03-30_sunset`), `date`,
`dayName`, `eventTime`, `regions[]`. Capped at `MAX_VISIBLE_EVENTS = 6` (line 93).

**Per-region** (`appendRegionNode`, lines 1082–1131):
`name`, `verdict`, `goCount`, `marginalCount`, `standdownCount`,
`totalLocations`, `temperatureCelsius?`, `apparentTemperatureCelsius?`,
`windSpeedMs?`, `weatherCode?`, `tideAlignedCount`, `lunarKingTideCount`,
`lunarSpringTideCount`, `extraExtraHighCount`, `extraHighCount`, `hasKingTide`,
`kingTideLocations[]?`, `hasSpringTide`, `hasSurgeBoost`, `coastalLocationCount`,
`inlandLocationCount`.

**Claude scores** (`appendClaudeScores`, lines 1144–1152, only when cached
drill-down scores exist): `claudeRatedCount`, `claudeHighRatedCount`,
`claudeMediumRatedCount`, `claudeAverageRating`.

**Stability** (lines 1182–1187): `stability`, `stabilityReason`.

**Aurora event** (when active, lines 1204–1215): `event` (`<date>_aurora`),
`alertLevel`, `kp`, `darkSkyLocationCount`, `clearLocationCount`.

Notes that bear on the pass:
- `fiery_sky`/`golden_hour` (0–100) are **not** sent — only the derived 1–5★
  `claudeAverageRating` + counts. (`BriefingSlot` carries the 0–100 values,
  `BriefingSlot.java:41-42`, but they never reach the rollup.)
- **Drive times are not in the rollup** — the `driveMap` param to `advise` is
  explicitly unused (line 472). (Despite CLAUDE.md describing drive-time
  recommendations, they are populated server-side after parsing, not given to the
  model.)

### Are the three scarcity signals reachable?

**Bluebell — substrate built, NOT wired to the advisor.**
- Scores: `ForecastType.BLUEBELL` rows in `forecast_score`
  (`ForecastScoreEntity`), read today only by `BluebellHotTopicStrategy.detect()`
  (`service/BluebellHotTopicStrategy.java:91-104`) — the "Pass 3 C4 re-point" from
  `forecast_evaluation.bluebell_score` to the normalised rows.
- Season window: `SeasonProperties` (`config/SeasonProperties.java:35-42`,
  defaults `04-18`→`05-18`, `photocast.season.bluebell.*`) →
  `SeasonConfig.bluebellSeasonWindow()` → `SeasonalWindow.isActive(date)`.
- **Reachability:** A grep across `BriefingBestBetAdvisor` for
  `bluebell|forecastScore|season|bloom` returns **nothing**. `BriefingService`
  computes `seasonalFeatures = bluebellSeason.isActive(today) ? ["BLUEBELL"] : []`
  (`service/BriefingService.java:379-380`) but puts it on
  `DailyBriefingResponse`, **not** into `bestBetAdvisor.advise(days, jobRunId,
  Map.of())` (lines 362–363). So neither bluebell scores nor the season flag reach
  the advisor today.
- **"N days remaining" does not exist anywhere** — `SeasonalWindow` exposes only
  `isActive()`, `start`, `end`. Trivially derivable (`end − today`) but unwritten.

**Spring/king tide — reaches the advisor as in-window counts; "next in N days" not derivable.**
- Classifier is `LunarPhaseService.classifyTide(LocalDate)`
  (`service/LunarPhaseService.java:154-161`) → `KING/SPRING/REGULAR`. *(CLAUDE.md's
  `TideClassificationService` name is stale — no such class exists.)*
- It already reaches the advisor: `LunarPhaseService` → `BriefingSlot.TideInfo`
  (`model/BriefingSlot.java:144-153`) → aggregated in `appendRegionNode`
  (lines 1052–1080) into `lunarKingTideCount`, `lunarSpringTideCount`,
  `extraExtraHighCount`, `extraHighCount`, `tideAlignedCount`. The prompt
  instructs heavily on these (lines 233–243).
- **But** the advisor only sees classifications for events inside the 6-event
  window. There is no forward scan for the *next* spring/king tide beyond the
  horizon. `SpringTideHotTopicStrategy` scans cached days for the earliest
  spring-not-king date but produces a `HotTopic`, not advisor input, and computes
  no countdown. A "next spring tide in N days" perishability signal would need new
  forward-scan logic (loop `classifyTide` past the horizon).

**Aurora — already implicitly perishable (it only appears the night it's live).**
The aurora event node is appended only when `auroraStateCache.isActive()` and
alert-worthy (lines 958–964); it carries `darkSkyLocationCount` /
`clearLocationCount`. So "aurora tonight only" is already *implicit* in the data
(it's a one-night column). Whether the pass wants an **explicit** perishability
flag is a design choice for the pass, not a reachability gap.

### Where scarcity would attach (the shape question)

**Clean.** `appendRegionNode` (line 1039) is the single per-candidate (event×region)
assembly site; it already adds ~20 fields and has `region`, `date`, `targetType`,
and **`daysAhead`** in scope. A per-candidate scarcity annotation is one more
`regionNode.put(...)` (or a nested object) here. The per-candidate keying pattern
already exists: `coverageByKey` (line 914) keyed by `coverageKey(eventId,
regionName)` (line 453), carrying the `CandidateCoverage` record (line 443:
`claudeRatedCount`, `daysAhead`, `claudeAverageRating`) — the established way
derived per-candidate data flows into the deterministic ranking gate
(`applyCoverageAwareRanking`, lines 760–795). A scarcity field could be added to
the JSON (for Claude) and/or to `CandidateCoverage` (if it should also drive the
deterministic gate).

**The one tangle:** bluebell scarcity needs inputs the class doesn't currently
have — the `SeasonalWindow bluebellSeason` bean is not a dependency (constructor
line 392), and bluebell scores would need threading into `BriefingDay`/`BriefingSlot`
or querying via `ForecastScoreRepository`. Tide scarcity can largely reuse data in
scope (inject `LunarPhaseService` for a forward scan, or use the in-window counts
already present).

---

## Item 3 — Aurora-region: the improvisation mechanism + fix options

### The data shape: region is **absent**, not null/placeholder

The aurora event node (`appendAuroraEvent`, lines 1204–1215) has exactly five
keys — `event`, `alertLevel`, `kp`, `darkSkyLocationCount`, `clearLocationCount`.
**No `region`, no `regions[]`, not even a null.** Contrast the solar-event path
(lines 945–951), which builds a `regions[]` array with a `name` per region.
Crucially, `validRegions` is **not** augmented for aurora (only
`validEvents.add(auroraEventId)`, line 963). The backing `AuroraStateCache` is
region-blind (scalar counts + level + Kp); it holds `cachedScores`
(`AuroraStateCache.java:80`) but the advisor never reads `getCachedScores()`.

### The improvisation: purely the model filling a gap — abetted by prompt + validation

There is **no code that assigns an aurora region** (so none to "fail"). Three
things conspire to make the model invent one:

1. **Validation skips aurora region checks** — `isPickValid` lines 707–712: an
   `_aurora` event's `region` is **not** checked against `validRegions`, so any
   string (including an invented "Northumberland") passes.
2. **Coverage gate exempts aurora** — `isHeadlineEligible` lines 805–807.
3. **The prompt seeds it** — line 196 gives the exemplar *"Good aurora potential
   tonight — dark sky sites in Northumberland are well placed"*. With no region
   data, a Northumberland exemplar in the prompt, and no validation to reject it,
   the model improvises ("Northumberland is typically the premier dark-sky
   region", observed 2026-06-06; reasoned about again 2026-06-13).

`AuroraOrchestrator` contains zero references to "region" — the whole aurora
pipeline is region-agnostic by design.

### Fix-option inputs (assessment only — the pass designs the rule)

- **(a) Config default ("darkest-sky region").** Does **not** exist. The only
  dark-sky config is the Bortle alert *threshold* (`application-example.yml:118-120`),
  not a region name. No `aurora.region` / `darkest` default anywhere.
- **(b) Region-level dark-sky score.** Not on the region. `RegionEntity`
  (`RegionEntity.java:31-50`) has only `id`, `name`, `enabled`, `createdAt`. **But
  locations carry it:** `LocationEntity.bortleClass` (line 140), `skyBrightnessSqm`
  (line 151), and each location links to a `RegionEntity` (lines 103–106). So a
  region's dark-sky quality is *derivable by aggregation* — but that aggregation
  doesn't exist yet.
- **(c) Per-aurora-event region from triaged locations — the cleanest.**
  `AuroraStateCache.getCachedScores()` returns `List<AuroraForecastScore>`
  (`AuroraStateCache.java:211`); each `AuroraForecastScore` carries a full
  `LocationEntity` (`AuroraForecastScore.java:19-25`) → which has both `region` and
  `bortleClass`. The clear, triage-passing scored locations therefore already know
  their regions. The advisor could group cached scores by
  `location.getRegion().getName()` and surface the region with the most
  clear/darkest locations that night — **from data already in the cache, no new
  persistence.** This data is currently ignored by the advisor.

**Lean:** option (c) is the data-supported, no-new-persistence path; (b)'s
aggregation could refine the ranking within it. (a) would need a new config key.

### Separability from aurora-as-forecast-type — confirmed clean

The future aurora-as-forecast-type refactor is explicitly out of scope and does
**not** conflict:

- `V107__create_forecast_type.sql` header: *"AURORA and INVERSION are deliberately
  NOT seeded. They fold in via their own future work..."* — `ForecastType`
  (`ForecastType.java`) seeds only SKY/FIERY_SKY/GOLDEN_HOUR/TIDAL/BLUEBELL.
- `forecast_score` (V108) is additive-only (Pass 2 dual-write, Pass 4 read), keyed
  on `(forecast_type_id, location_id, evaluation_date, event_type)` — a
  persistence/serving concern for *location-component scores*, unrelated to aurora
  and unrelated to the advisor's in-memory rollup.
- An aurora-region fix touches only `buildRollupJson` / `appendAuroraEvent` (the
  live JSON sent to Claude) + optionally the validation set — it writes nothing to
  `forecast_score`. **Cleanly separable; neither pre-empts nor blocks the later
  refactor.**

---

## Cross-cutting checks

### Output contract (post-Change 4) — current form

Lines 319–323, immediately after the stay-home line:

> *"Reason concisely, then output ONLY the JSON object. Keep any deliberation to a
> few short lines — do NOT write extended 'key observations', repeated same-slot
> analysis, or back-and-forth 'actually, wait...' paragraphs. That verbosity has
> consumed the response budget and truncated the JSON before it could finish. Emit
> no code fences and no markdown, and write nothing after the closing brace."*

This is the live anti-verbosity layer. Any prompt change in the pass should build
*on top of* this block (e.g. append a clause), not rewrite it, to avoid
re-introducing the rambling Change 4 curbed. The advisor uses **manual JSON
parsing**, not structured output (`OutputConfig`/`JsonOutputFormat`) — request is
`.model().maxTokens().systemOfTextBlockParams().addUserMessage()` (lines 485–493) —
so the "output ONLY JSON, no fences" instruction is load-bearing, and
`stripCodeFences` + `extractJsonObject` + `salvagePicks` are the safety net.

### Before/after replay harness — does not exist; moderately easy to build

- **The advisor's input rollup is not persisted re-feedably.** The live call logs
  `requestBody = null` (lines 508–511) — only the raw *response* is stored in
  `api_call_log`. `briefing_model_test_run.rollup_json` (V63) stores a rollup, but
  it's *rebuilt live* from the current briefing for display, not a captured
  historical input.
- **Two adjacent harnesses, neither fits as-is:**
  - `BriefingModelTestService` + `BriefingBestBetAdvisor.compareModels`
    (lines 1284–1355) — runs the *same* rollup across 5 *models*, but swaps the
    model not the prompt (`SYSTEM_PROMPT` is a hardcoded constant, lines 120–362),
    and builds the rollup from the *current* briefing (can't replay a past cycle).
  - `PromptTestService` — a genuine store-input → replay-with-swapped-prompt
    harness, but for the **per-location colour forecast** path
    (`ForecastDataAugmentor`/`PromptBuilder`), with no knowledge of the advisor.
- **Net verdict:** No clean advisor before/after harness exists. Building one is
  moderate work because the seams are already there (`advise(days, …)` /
  `buildRollupJson(days, now)`). Two gaps: (1) **capture** — start passing
  `rollup.json()` as the `requestBody` at line 508 so each live cycle's exact input
  lands in `api_call_log.request_body`; (2) **prompt-swap** — `SYSTEM_PROMPT` is a
  constant, so add an overload that takes the prompt + a stored rollup string
  (bypassing `buildRollupJson`, which needs live `BriefingDay` objects a historical
  replay won't have). **Until that capture exists, validation of the pass's
  judgement changes leans on observing live cycles post-deploy** (the
  `pipeline_run_pick` + `best_bet_status` cross-run comparison is the intended
  live-observation mechanism).

### `best_bet_status` interaction — stay-home pick reads correctly, with one caveat

`BestBetStatus` = `SUCCESS_WITH_PICKS` / `SUCCESS_NO_PICKS` / `FAILED`
(`model/BestBetStatus.java:13-27`). A stay-home (null/null) pick is classified
`SUCCESS_WITH_PICKS` (traced in the headline section) — it is **not** read as
`SUCCESS_NO_PICKS` and does **not** trip the fallback (`BestBetFallbackService`
fires only on `FAILED`, `BriefingService.applyBestBetFallback` lines 257–271).
Persistence stores the row with null `event_date`/`region`
(`PipelineRunPickService.parseEventDate(null)` → null; schema allows it, V104).

**Caveat (implicit behaviour, not a bug):** a persisted stay-home row has
`event_date = NULL`, and the fallback query filters `WHERE p.eventDate >= :today`
(`PipelineRunPickRepository.java:48-52`) — `NULL >= today` is never true — so a
stay-home pick can never itself be resurrected as a *stale fallback* for a later
FAILED run. Arguably correct (a stale "stay home" is useless), but it's an implicit
consequence, not a deliberate decision. Worth a one-line note in the pass; no
action needed.

---

## What surprised me most

**The advisor has two different ways to "say nothing", and the contract only wants
one of them.** An empty `picks: []` array is logged as an "honest decline" and
classified `SUCCESS_NO_PICKS` (line 1401–1404), while the line-318 stay-home floor
wants a `picks: [{null, null}]` pick classified `SUCCESS_WITH_PICKS`. The
truncation fix removed the *budget* cause of the floor going missing — but it
didn't remove this *semantic* fork. So "is the floor firing?" isn't a yes/no about
truncation; it's about whether the model, on a flat week, chooses the null/null
pick over the empty array. That reframes item 1 from "fix the truncation symptom"
(done) to "if anything, disambiguate the two honest-nothing outcomes" — a one-line
prompt clarification, not a rewrite, and only if prod shows it's needed.

Runner-up: **drive times and the 0–100 scores are computed but never sent to the
advisor** — the model ranks on 1–5★ averages + counts + verdict only. Relevant
because the scarcity pass should not assume the advisor can see anything it isn't
explicitly given in the rollup.

---

## Open questions for Chris (before the pass)

1. **(Item 1, blocks the floor decision)** Across recent flat/all-STANDDOWN
   cycles in prod, how often does the advisor emit the **null/null stay-home pick**
   (`SUCCESS_WITH_PICKS`) versus an **empty array** (`SUCCESS_NO_PICKS`)? Query
   `pipeline_run.best_bet_status` + `pipeline_run_pick` for flat windows. If it's
   reliably the null/null pick → item 1 is pure test-only. If it sometimes returns
   `SUCCESS_NO_PICKS` on a genuinely flat week → add the one-line disambiguation to
   line 318. *(I can't query prod from the dev machine.)*

2. **(Item 2, scarcity scope)** Should scarcity ranking cover all three signals
   (bluebell, spring/king tide, aurora) in this pass, or start with tide (cheapest
   — data already in the rollup) and defer bluebell (needs a new injected
   dependency + threading bluebell scores into the advisor) and the
   "next-in-N-days" forward scans?

3. **(Item 2)** Does the scarcity annotation need to influence only the **model's**
   ranking (add a field to the rollup JSON), or also the **deterministic**
   `applyCoverageAwareRanking` gate (add a field to `CandidateCoverage`)? This
   decides where the field attaches.

4. **(Item 3)** Preferred aurora-region source: option (c) — group
   `auroraStateCache.getCachedScores()` by `location.getRegion()` and surface the
   best dark-sky region that night — looks cleanest and needs no new persistence.
   Confirm before the pass designs the assignment. Should a tie/empty-cache case
   fall back to a configured default region (which doesn't exist today and would
   need a new config key)?

5. **(Validation)** Is investing in the advisor replay harness (capture
   `requestBody` at line 508 + a prompt-swap overload) worth it for this pass, or
   should validation lean on post-deploy live-cycle observation? The harness is
   reusable for all future advisor prompt work, so there may be a case for building
   it once here.

---

## Summary

| Item | Verdict | Pass action |
|---|---|---|
| **1. Stay-home floor** | **(a) largely resolved** by truncation fix; residual is a two-honest-outcomes ambiguity, not budget | Guard test; *optional* one-line disambiguation pending Q1 |
| **2. Scarcity** | Inputs reachable; tide already in rollup, bluebell needs wiring, aurora implicitly perishable. Clean attach point at `appendRegionNode` | Design the within-band ranking rule; wire missing inputs |
| **3. Aurora region** | Region purely absent; model improvises (validation skips + prompt exemplar). Option (c) data already in cache | Assign region from triaged locations' regions; cleanly separable from forecast-type refactor |

**The pass is two substantive items (scarcity, aurora-region) plus a test/guard for
the stay-home floor.** The truncation hotfix did item 1's heavy lifting.
