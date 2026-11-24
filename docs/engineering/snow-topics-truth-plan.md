# Snow topics that tell the truth — plan

**Status:** revision 3 (2026-11-24): a six-lens adversarial review, then a second round (a live-data replay
of the §3.1 rules, and a refutation of revision 2's design). Owner decisions recorded in §6 (2026-11-24).
**Sequencing (owner, 2026-11-24):** built only **after the screen-spec series (`screen-spec-plan.md`, P1–P5) has
merged**, one phase at a time, never alongside another build (laptop hardware is the bottleneck).
**Roles:** Opus plans and reviews; Sonnet builds every phase (CLAUDE.md, "How every code change is made").

Covers all three snow hot topics, which stay three distinct things:

| Topic | Label(s) | The photograph |
|---|---|---|
| **SNOW_FRESH** | "Fresh snow" | snow underfoot where you stand |
| **SNOW_MIST** | "Fresh snow with mist" / "Snow, mist & frost" | snow underfoot with valley mist at first light |
| **SNOW_TOPS** | "Snow on the fells" | white fells in view from below ("shoot from low ground looking up"), above green valleys or on an all-white day |

---

## 1. What went wrong (production, 2026-11-24, for Friday 27 Nov sunrise)

The map showed **"Fresh snow · 230 places"**, ringing the Durham and Northumberland coast. Production
`slot_atmosphere` for 2026-11-27 SUNRISE (written 04:28 UTC) had **60** rows with `snow_depth_m ≥ 0.02`:
53 of 61 Lake District places, 4 of 94 Northumberland & Tyneside, 3 of 53 Dales, 0 of 30 North York Moors.
`freezing_level_m` was NULL on all 60.

| # | Defect | Evidence |
|---|---|---|
| D1 | **SNOW_FRESH / SNOW_MIST never set `locationNames`**, so `HotTopicEventEnricher.qualifyingLocations` (l.179-217, `default -> enabled`) rings every enabled place in the topic's regions. 60 snowy rows became 230 rings. | SNOW_TOPS is unaffected: `PerDateHotTopicBuilder` calls `withLocations`. |
| D2 | **`snow_depth` is a 9 km grid-cell value, not the place's.** In the UK `best_match` stack, `snow_depth` comes from ECMWF IFS HRES 9 km (UKMO supplies none). Open-Meteo elevation-corrects temperature only. Rydal Water (59 m, +2 °C, zero precipitation all week) got the 4 cm of a model cell whose terrain is **346 m**. | Open-Meteo source (`EcmwfVariable.isElevationCorrectable`, `VariableHourly.swift`), maintainer in issue #1827; probes with `elevation=1000` (temperature −6 °C, depth unchanged) and `elevation=nan` (346 m cell). |
| D3 | **Summit snowfall is under-reported on days 0–2.** UKV's 509 m cell at +2 °C calls it rain; Open-Meteo never converts rain to snow when the downscaled point is colder. Scafell Pike: 23 mm at −2 °C, snowfall 0. | `VariableHourly.swift`; `ukmo_uk_deterministic_2km&elevation=nan` probe. |
| D4 | **`freezing_level_height` is NULL from ~day 2** (only UKV 2 km supplies it), so SNOW_TOPS cannot fire on days 2–4 and its "snow line" fact is blank. | Open-Meteo UKMO docs; nulls from the UKV horizon. |
| D5 | **SNOW_TOPS fires on a low freezing level alone.** In dry air that means frozen bare ground, not white tops. | Met Office snow guide; Avalanche Canada / NWS on snow level vs freezing level. |
| D6 | **SNOW_FRESH/MIST collapse a multi-day spell into one topic dated to its earliest day** (`SnowFreshHotTopicStrategy` l.169), so only that sunrise is badged, facts mix days, and a travel day on day 1 drops the whole spell (`HotTopicAggregator.actionableOutsideTravel`). | Pinned by `SnowFreshHotTopicStrategyTest.detect_multipleDays_enumeratesAll`. |

Evidence kept: `docs/engineering/snow-evidence/2026-11-24-best-match-past7.json` (Open-Meteo `best_match`,
`past_days=7`, 336 hourly values for Rydal Water, Grasmere, Scafell Pike, Helvellyn, Windy Gyle, Harbottle
Hills, High Force, Durham, Tynemouth: precipitation, temperature, RH, snow depth, snowfall, freezing level).
It is the replay fixture for S2 and S3.

## 2. The physics we build on

**[F]** documented fact (sources §10); **[J]** judgement, a named constant with boundary tests.

- **[F] Rain or snow is decided by wet-bulb temperature**, 50 % transition at Tw ≈ 0.5–1.3 °C (NWS SLC 0.5 °C;
  Jennings 2018 ~1.0 °C). Dry-bulb thresholds swing with humidity; wet-bulb is stable.
- **[F] Falling is not settling.** Snow that falls at +2 °C onto ground still at 4–6 °C does not lie unless it
  is heavy. Settling needs the air near 0 °C (Met Office snow guidance).
- **[F] Open-Meteo's 2 m temperature is downscaled to the place's real height, in past and forecast hours
  alike; precipitation and the snow fields are not** (probe: `elevation=961` vs `100` shifts temperature by
  −6 °C and only changes which neighbouring cell's precipitation you get).
- **[F] Melt.** Shallow-snow degree-day factors are 3–5 mm/°C/day; overcast calm days sit at the low end.
  Fast melt is condensation onto the snow, which needs a dew point above 0 °C **and** wind — high RH alone is
  normal in a UK winter and is not enough.
- **[F] Density.** Fresh snow ≈ 7 cm per 10 mm water (ratio 0.7 cm/mm, ~140 kg/m³); a few days old it settles
  to ~250–350 kg/m³.
- **[F] White tops need snow to have fallen there.** A low freezing level in dry air gives frozen, not white,
  tops. Whether the tops are white is a fact about the **backdrop's** height, not the camera's.
- **[F] Coarse-model summit precipitation is under-forecast** (smoothed orography).

## 3. The design

### 3.1 A point snowpack, from the place's own weather

`PointSnowpack` (new, pure, in `util/`), walked hour by hour over a **fixed 168 h lookback ending at the slot
hour** (the same slot always sees the same window, whatever the run time). Inputs per hour: precipitation,
temperature_2m, relative_humidity_2m, wind_speed_10m, shortwave_radiation.

1. **Wet-bulb** `Tw` from T2m and RH (Stull 2011). Dew point `Td` from T2m and RH (Magnus).
2. **Snow fraction** `f`: 1 at `Tw ≤ 0.5 °C`, 0 at `Tw ≥ 1.5 °C`, linear between.
3. **Settling gate**: the hour's snow water `s = precip × f` is added only if `T2m ≤ 1.0 °C`, or if
   `1.0 < T2m ≤ 2.0 °C` **and** `s ≥ 2 mm`; never above 2.0 °C.
4. **Melt** when `T2m > 0`: `swe −= DDF/24 × T2m`, `DDF` = 3 mm/°C/day; 6 when `Td > 0 °C` and wind ≥ 5 m/s;
   at least 5 when shortwave > 200 W/m². Floor at 0. No refreeze bonus (errs towards melting).
5. **Outputs** at the slot hour: `snowWaterMm` (modelled water-equivalent), `snowfall24hCm` (snow added in the
   24 h before the slot × 0.7). **Null** when the lookback is not covered or more than 10 % of its hours lack
   an input. Null never fires a topic.

**Lying at a place** ⇔ `snowWaterMm ≥ 3.0` (≈ 2 cm fresh, the old threshold).
**White at a summit** ⇔ `snowWaterMm ≥ 5.0` (2 cm does not whiten heather and tussock).

**Replayed on live data (2026-11-24, these exact rules, 08:00 UTC slots 24–30 Nov):**

- **Places, any day:**
  - Durham, Tynemouth and Bamburgh: never.
  - Rydal, Grasmere, Buttermere and Keswick: 0 mm all week. Grid depth was 2 cm on the 27th, so D2 is fixed.
- **Places, on the 28th:**
  - Windy Gyle 5.0 mm, Harbottle Hills 8.7 and High Force 7.5. That matches UKV's own 2 cm.
  - Malham Tarn (374 m) 3.4 mm on the 24th and 10–11 mm on 28–29. Its grid gives only 0–1 cm, so check it against an observation after S2.
- **Summits:**
  - Helvellyn 35–49 mm, with 22.6 cm fresh on the 25th.
  - Scafell Pike 23–57, Cross Fell 8–46, The Cheviot 14–65.
  - Skiddaw 6–9 from the 26th, marginal at the 5 mm line.
  - Whernside 4–19. Urra Moor white only on 28–29.
  - Grid depth at every summit was 0–2 cm, so D3 is fixed.

Sensitivity:
- Lying at 2 mm or 4 mm changes one place-day each.
- A summit at 3 mm makes the Urra Moor heather white too often.
- A settling gate of 1.5 °C fires Windy Gyle and Harbottle Hills spuriously on the 25th, so keep 1.0 °C.

S2 turns the kept fixture into a test asserting these outcomes.

⚠️ Request wind in **m/s** (`wind_speed_unit=ms`). Open-Meteo defaults to km/h, which would silently make the 5 m/s test compare against km/h.

**Depth is never shown for a summit** (a 60–97 mm modelled summit pack is a plausible white top but an
implausible depth to quote). SNOW_FRESH shows fresh snow from `snowfall24hCm` only (ratio 0.7 is right for
snow under 24 h old).

### 3.2 Its own fetch, not the main one

The point snowpack needs ~7 days of history. Adding `past_days` to the main forecast fetch was reviewed and
**rejected**: `OpenMeteoResponseParser` l.75-83 reads the air-quality arrays with the forecast's index (dust,
PM2.5, AOD would silently go null), and `ForecastStabilityClassifier` l.97-179 reads by array position (every
stability signal would measure last week). `AstroConditionsRefreshJob` documents a no-`past_days` contract,
and `OpenMeteoClientInversionProfileTest` pins "main fetch has no `past_days`, no geopotential".

Instead, a **snow fetch**, modelled on `fetchInversionProfileBatch` (best-effort, chunked, resilient, its own
`pastDays`): the five inputs above, `past_days=7`, default 7 forecast days. Five variables over 14 days weigh
**1 call per point** under Open-Meteo's formula `max(1, V/10 × max(1, D/14))` (verified with the live
`X-RateLimit-Cost` header). ~260 places + ~15 summits ≈ 275 weighted calls per run.

The fetch also requests `cloud_cover_low` at the place's sunrise, for the SNOW_TOPS visibility gate in
§3.3. Six variables still weigh 1 call per point.

**Fetch discipline.** The fetch shares the client-wide cool-off in `OpenMeteoClient` (l.1138-1158).

- **Honouring a cool-off:**
  - When a cool-off is already running, it fetches nothing.
  - A refusal for the hourly or daily limit calls `noteRefusal`, as the main fetch does, because the limit is shared.
- **Minute-limit or concurrency 429:** this ends the snow run *without* starting the shared cool-off. Otherwise a snow fetch could knock the evening's main fetch out for 10 minutes. Test both paths.
- **Budget:** its own constant of 150 s. 14 chunks × 3 s delay plus request time won't fit the inversion fetch's 90 s.
- **Order:** summits are fetched first.
- **Logging:** log the weighted cost per run against both the per-minute and the daily limit.

**Job.** `SnowStateRefreshJob`, three runs a day, staggered after the main fetch. No Claude cost. To be legitimate here it needs:

- a `scheduler_job_config` seed row in its migration (the V161 pattern);
- `@PostConstruct registerJobTarget` (the `WildlifeComfortRefreshJob` l.115 pattern);
- a `SchedulerJobCatalogue` entry with a staggered-after edge to the main fetch;
- `ScheduledFiresLister`, if it is to show in the Day view;
- `jobRunService.startRun` with a new `RunType`, plus its `LocalH2EnumWidener.TARGETS` line;
- `logApiCall`, and `SchedulerFireOutcome.report`;
- `ServedBriefingEpoch.bump()` after commit (the `GridCellBackfillJob` l.162 pattern), so served topics and the Ask snapshot memo refresh.

Dates for days 0–4 come from `ForecastHorizon` with the injected `Clock` (the `WildlifeComfortRefreshJob` l.140 precedent).

**Store.** New table `snow_state`, migration **V&lt;next&gt;**. The screen series takes the next number first, so read it from the tree at build time.

- **Columns:**
  - `point_key` text: `loc:<id>` or `summit:<key>`;
  - `evaluation_date`, `event_type`;
  - `snow_water_mm`, `snowfall_24h_cm`, `low_cloud_pct`;
  - `computed_at`.
- **Key:** a plain unique on (`point_key`, `evaluation_date`, `event_type`). No nullable foreign key, CHECK constraint or partial index, which local H2 under `ddl-auto: update` would not create.
- **Writes:** days 0–4, sunrise and sunset hours.
- **Pruning:** rows dated more than 7 days back are deleted. Rewind reads up to `ForecastHorizon.SERVE_PAST_DAYS`.
- **Why a separate table:** it is not coupled to `slot_atmosphere`'s writer or to its `SKIPPED_CACHED` staleness, and it keeps `past_days` away from the evaluation path.

**Partial runs never erase.** A point that was not fetched (a failed chunk or the budget running out) keeps its previous row. A null never overwrites a value (the `WildlifeComfortRefreshJob` l.184-189 precedent).

**Freshness.** A reader ignores rows whose `computed_at` is more than 36 h old, measured with the `Clock` bean. A missing or stale row means *unknown*, never *no snow* (see §3.3).

**Detector plumbing.**
- `HotTopicInputs` gains a memoised `Supplier` of snow rows: one read per serve, shared by all three snow strategies.
- Each strategy's two-argument `detect` falls back to reading the rows itself.
- Snow rows are keyed by place, not by `SlotSignals`. SNOW_MIST joins them to that place's SUNRISE slot signals only to ask `isLikely`; a place with no slot row is not misty, so it is SNOW_FRESH.
- The per-day grouping is the snow strategies' own, not `PerDateHotTopicBuilder`, which takes `SlotSignals`.
- `slot_atmosphere.snow_depth_m` keeps being written (audit). `WoodlandPromptBuilder` l.220 still reads grid
  depth for the woodland prompt — out of scope, recorded in §9.

### 3.3 Which topic, per place and day

Snow topics are built from **SUNRISE** rows only (owner **Q3**):

- It is the shooting hour all three topics are about.
- The enricher already stamps all three SUNRISE.
- Badges go to the `date:SUNRISE` window.
- Snow that falls during a day shows up at the next sunrise.

Snow is an **all-day condition**, so the snow strategies drop the `freshness.isAhead` filter, as SNOW_TOPS already does. Otherwise today's SNOW_FRESH/MIST would vanish at dawn while the snow lies all day. Today's topic stays until midnight. Test it.

For each place and sunrise:

1. **Own snow lying** (§3.1):
   - valley mist band LIKELY (`MistHotTopicStrategy.isLikely`, unchanged) → **SNOW_MIST**;
   - otherwise → **SNOW_FRESH**.

   At **any height**: no elevation split. A snowy summit viewpoint is "snow underfoot". Null `elevation_m`
   no longer matters to any snow rule.
2. **SNOW_TOPS: a white summit is in view**, whatever the place's own ground. It is independent of rule 1, so on an all-white day a place can carry SNOW_FRESH (or SNOW_MIST) *and* SNOW_TOPS. Those are two different photographs: snow underfoot, and white fells in view. Both conditions must hold:
   - Its **nearest white summit** is within **12 km**.
   - Its `low_cloud_pct` at sunrise is **≤ 60 %**, because tops in cloud cannot be seen. A missing value means the gate is not met.

   **Variant per place** (owner, 2026-11-24: "an all-white day is still a variation of this"), from the place's own row:

   | The place's own row | Variant | Detail |
   |---|---|---|
   | Fresh, non-null, `snowWaterMm < 3` | **green below** | "Tops white above green valleys" |
   | Fresh, non-null, `snowWaterMm ≥ 3` | **white throughout** | "White from valley floor to summit" |
   | Missing, stale or null | **unknown below** | "Tops white" (never claims green) |

   A failed chunk therefore can neither call snowy ground green nor hide the white tops.

   The topic's `regions` are the **ringed places'** regions, which is what the region-scope filter and Ask's `inScope` expect, since summits have no region.

   **One topic per day, several variants.** A topic has one detail and one description, so a day's SNOW_TOPS takes the variant held by **most** of its ringed places. A tie goes to *green below*, then *white throughout*. Every ringed place is still listed. Add a test: a mixed day picks the majority variant.

   Expect a big count on a classic Lakes day, as most Lakes valleys sit within 12 km of Helvellyn, Skiddaw or Scafell Pike. It is a true count, and the 12 km nearest-summit rule keeps it truer than 20 km would. In the replay, every Lakes valley place got SNOW_TOPS all week, which is defensible: the summit packs held 35–57 mm all week.

**Summits** (owner **Q1**) are a fixed, reviewed list in code (`SnowSummits`), fetched with `elevation=<summit>`
so the point model runs at the backdrop's height. Starting list: Helvellyn, Skiddaw, Blencathra, Scafell Pike,
Coniston Old Man, High Street, Cross Fell, Whernside, Ingleborough, Pen-y-ghent, Great Whernside,
Buckden Pike, The Cheviot, Hedgehope Hill, Urra Moor. The builder verifies each summit's coordinates and height.
Radius: 12 km to the nearest white summit, great-circle.

What this retires (owner **Q1**): the freezing-level rule and its 100 m margin. The recorded "no
elevation floor" decision is not reversed: SNOW_TOPS no longer reads a place's elevation at all.

### 3.4 Exact places, one topic per day

All three topics are **per day**, each carrying that day's regions and **exact `locationNames`**:

- SNOW_FRESH and SNOW_MIST rings are the snowy places.
- SNOW_TOPS rings are every place with a white summit in view (§3.3 rule 2), green below or not.

The SNOW_MIST-first precedence and the (place, day) exclusion stay, per day.

`PerDateHotTopicBuilder` takes one fixed label, so SNOW_MIST's per-day label, detail, description and note
(frost or not, from that day's representative) come from a callback that **rebuilds** the topic and re-attaches
`withLocations`. Detail strings lose their spanning day phrase ("… today and tomorrow"); the builder's contract
forbids it.

The enricher's region backfill stays for tides, sky-wide types and the admin simulation (its SNOW_* templates
carry regions only, and simulations never reach users). Add a test that a SNOW_* topic carrying locations is
never widened.

### 3.5 Facts and copy

| Topic | Facts | Copy |
|---|---|---|
| SNOW_FRESH | `fresh ~N cm` when `snowfall24hCm ≥ 1`, else `lying` | Detail "Fresh snow overnight" when it fell in the last 24 h, else "Snow lying" (owner **Q6**; the label stays "Fresh snow") |
| SNOW_MIST | as SNOW_FRESH, plus the existing frost and mist facts | Frost wording unchanged |
| SNOW_TOPS | `<summit> white` (the summit most places are nearest; one name, because the first fact becomes the glance chip) | Per the day's variant (§3.3). **Green below:** today's description, "fell tops are capped with snow while valleys stay green". **White throughout:** a new description, "Snow from the valley floor to the summits: a white landscape with the fells standing out against the sky." **Unknown below:** "Fell tops are capped with snow." The sim copy ("above 600m") is updated to match. |

The `snow line` and `N m below the tops` facts go. Nothing derives a freezing level: the old S3 is dropped.
The pressure-level derivation measured MAE 109 m (227 m in the lowest layer), for a fact no rule needs.

Glance chips print the first fact (`HotTopicGlanceDecorator.FIRST_FACT_TYPES`), so they change from "~X m" /
"N cm" to the forms above. Update `HotTopicGlanceDecoratorTest` l.278, 357.

### 3.6 Ask, the daily log and Ready

- **Ask:** per-day topics would crowd other kinds out. `get_hot_topics` folds by kind and day, then cuts to 5, and `get_coming_up` does the same, so a 4-day spell of three snow kinds would push aurora, dust and surge out.
  - **Fix:** keep `onePerKindAndDay` and the dates intact, and add a **kind round-robin** before `.limit(limit)`: every kind's first day comes before any kind's second day.
  - **Rejected:** folding by kind across the window. That would leave one date per kind, and `AskAnswerValidator.validEvents` (l.440-455) and `AskReadyFreshness.liveEvent` (l.241-247) both match on type and date, so later snow days would be dropped from answers.
  - **Tests:** 4 snow days plus an aurora gives the aurora inside the default limit; the validator still accepts a day-3 snow event.
- **`TopicDailyLogJob`:** the log is a census of what happened ("snow lying somewhere in the region"), so
  `present` becomes "any place's own snow lying" at any height. `maxIntensity` switches from grid depth (m) to
  modelled snow water (mm) from S3's deploy date, recorded in `reference/conditions.md` (owner **Q4**). Update
  the javadoc at l.99-106 and `TopicDailyLogJobTest` l.495-530.
- **Ready:** `ReadyQuestion.SNOW_TOPS` ("Is there snow on the tops?") narrows to fire on SNOW_TOPS only (owner
  **Q5**). Stored answers whose type changes are withheld for one precompute cycle after S3 deploys
  (`AskReadyFreshness.liveEvent` matches type and date). This heals itself; say so in the PR.

## 4. Phases (one PR each, Sonnet builds, gates per CLAUDE.md)

### S1 — exact places, one topic per day (still on grid depth)

- `SnowFreshHotTopicStrategy`:
  - per day, from SUNRISE rows, with exact `locationNames`;
  - no dawn cut-off: the `freshness.isAhead` filter is dropped, so today's topic lasts until midnight;
  - SNOW_MIST copy rebuilt per day;
  - no day phrase in the detail.
- The Ask kind round-robin (§3.6).
- Tests:
  - rewrite `detect_multipleDays_enumeratesAll`, `detect_split_datesPerTopic`, `detect_samePlaceOtherDay_stillFresh`;
  - add a `locationNames` assertion to `detect_split_twoTopicsEachWithOwnRegions`;
  - new: one snowy place in a region rings one place; sunset-only snow does not ring a sunrise; frost and no-frost chosen per day;
  - `HotTopicAggregatorTest`: a travel day on day 1 no longer drops day 2;
  - `HotTopicEventEnricherTest`: a SNOW_FRESH and SNOW_MIST carrying locations are not widened;
  - the Ask aurora test.
- Fix the existing `stubAhead` `any(LocationEntity.class)` (Codex pins stubs): give each row a named location and use `eq`.
- Docs: `reference/conditions.md` l.14.

*Effect:* the region spread goes (230 → about 60). The lakeshore false positives remain until S3.

### S2 — the snow fetch and the point snowpack, recorded only

Detectors are unchanged in this phase.

- `PointSnowpack` in `util/`.
- `SnowSummits`.
- The snow fetch in `OpenMeteoClient`. Reuse the chunk path with `pastDays` and per-point `elevation`; the main `FORECAST_PARAMS` is untouched.
- `SnowStateRefreshJob`, with the full registration list in §3.2.
- The `snow_state` table, migration V&lt;next&gt; (read the number from the tree):
  - written for Postgres, with a Testcontainers migration test following V211's pattern;
  - its entity ships in the same PR, because production runs with `validate`;
  - a row added to `reference/database-migrations.md`.
- A `SnowStateReader` applying the 36 h freshness rule.
- Fetch-discipline tests:
  - a running cool-off means no fetch;
  - a minute-limit 429 ends the run without starting a cool-off;
  - a daily-limit refusal does start one;
  - a failed chunk keeps the previous rows;
  - wind is requested in m/s.

Tests:
- Every `PointSnowpack` boundary:
  - Tw 0.5 / 1.0 / 1.5;
  - settling gate at T 1.0 and 2.0, and `s` at 2 mm;
  - DDF switches (Td 0, wind 5, shortwave 200);
  - T = 0, so no melt;
  - floor at 0;
  - the 24 h edge;
  - the 10 % missing-input share, exactly at it and one hour over;
  - lookback not covered;
  - empty series.
- **The evidence-fixture replay:** turn `snow-evidence/2026-11-24-best-match-past7.json` into a test fixture and assert the §3.1 outcomes for all nine points. Record the actual values in this plan.
- The job tolerates a failed chunk.
- The reader drops stale rows.

**Before S3:** one production query, handed to Chris, comparing `snow_state` with `slot_atmosphere.snow_depth_m` for the next cold days.

### S3 — detectors switch

- The §3.3 rules: SNOW_FRESH/MIST from a place's own `snow_state`, SNOW_TOPS for every place whose nearest white summit is within 12 km and whose low cloud at sunrise is at most 60 %, with the three variants of §3.3. Tests:
  - an all-white place carries both SNOW_FRESH and SNOW_TOPS (white throughout);
  - a missing or stale own row gives "unknown below", never "green below";
  - low cloud above 60 % or missing gives no SNOW_TOPS;
  - a mixed day picks the majority variant;
  - today's topic survives dawn.
- §3.5 facts and copy, including the sim copy for SNOW_TOPS ("above 600m" → match the new rule).
- `TopicDailyLogJob` and Ready per §3.6.
- Rewrite `SnowTopsHotTopicStrategyTest` and `SnowTopsHotTopicStrategyNullLocationTest`.
- Retire the 0.02 m `isFreshSnow` boundary tests for the new ones.
- Docs:
  - `conditions.md`;
  - `hot-topics-implementation-inventory.md` l.54-56 (stale "Sim-only");
  - `snow-trio-investigation.md` (F1 and the margin are superseded; link here);
  - `architecture-decisions.md`;
  - `api-endpoints.md` l.332 if wording changes.

## 5. What is deliberately unchanged

- SNOW_MIST's mist rule (valley mist band LIKELY on a sunrise).
- Priorities (MIST 1, FRESH 2, TOPS 3).
- Snow never touches triage, prompts or ratings.
- The admin simulation.

## 6. Owner decisions (answered 2026-11-24)

Chris accepted every recommendation below, with Q1 amended in conversation.

- **Q1 — SNOW_TOPS means "a white summit in view".**
  - The place's nearest white summit is within 12 km, and low cloud at the place's sunrise is at most 60 %.
  - It fires whatever the place's own ground, in three variants: green below, white throughout, unknown below (§3.3).
  - The owner's amendment: "an all-white day is still a variation of this".
  - It retires the freezing-level rule and its 100 m margin.
  - A snowy place is SNOW_FRESH or SNOW_MIST at any height, and can carry SNOW_TOPS as well.
  - The summit list stands as in §3.3, Urra Moor included.
- **Q2 — the thresholds stand as in §3.1:**
  - wet-bulb ramp from 0.5 to 1.5 °C;
  - settling gate at 1 °C and 2 °C;
  - DDF 3 / 5 / 6;
  - lying at 3 mm, a white summit at 5 mm.

  All are constants with boundary tests, and can be tuned later.
- **Q3 — snow topics are built from sunrise rows only,** as all-day conditions with no dawn cut-off.
- **Q4 — the daily log:** `maxIntensity` switches from depth in metres to snow water in millimetres from S3's deploy date. The switch date is recorded in `reference/conditions.md`.
- **Q5 — the Ready question** "Is there snow on the tops?" is offered only when SNOW_TOPS is live.
- **Q6 — wording:** the detail line reads "Snow lying" when nothing fell in the last 24 h, and "Fresh snow overnight" otherwise. The label stays "Fresh snow".
- **Q7 — stacking:** SNOW_MIST and Valley mist can both show on a snowy, misty morning. Left as is.

## 7. Verification

- **Unit and fixture replay** (S2).
- **Production comparison** between S2 and S3.
- **After S3**, on the next cold spell:
  - the pill counts only places with their own snow;
  - SNOW_TOPS rings places within 12 km of white summits, with the right variant;
  - Ask's lists still carry other kinds.

## 8. Risks

- **Place precipitation is still a model value.** It lacks orographic enhancement at summits, so light summit snow can be under-called. The 5 mm summit threshold is the lever.
- **Snow older than 168 h falls out of the window.** On summits in a long cold spell, the pack drops when its oldest snowfall leaves the window, and nothing seeds it from earlier days. Accepted for now. If it bites, carry the previous `snow_state` value forward as the seed.
- **No physical wind scour, sublimation or aspect.** The shortwave term covers sunny melt; the rest is accepted.
- **Open-Meteo's UK stack may change** (PR #2236 adds ICON-EU). The point model reads only temperature, RH, precipitation, wind and shortwave, so it does not depend on which model supplies the snow fields.
- **Per-minute rate limit.** Schedule the snow job clear of the main fetch, and log its weighted cost.

## 9. Out of scope

- The mist-over-snow nuance: cold, dry snow is a vapour sink, and rime against hoar wording.
- A "snow on trees" flag.
- `WoodlandPromptBuilder`'s grid-depth line.
- Any change to triage, prompts or ratings.

## 10. Sources

**Research and review, 2026-11-24:**
- Open-Meteo snow semantics (source, issues and probes);
- snow physics;
- the code's blast radius;
- six review lenses: physics, pipeline, consumers, gates, product, and an Open-Meteo fact-check.

**Open-Meteo:**
- open-meteo.com/en/docs, /en/docs/ukmo-api, /en/terms, /en/pricing;
- GitHub open-meteo PR #1733, #2236, issues #1677, #1827.

**Meteorology:**
- Met Office snow and mountain-forecast guides;
- Avalanche Canada, "snow level";
- NWS SLC TA1901;
- Jennings et al. 2018 (Nature Communications);
- Stull 2011 (JAMC);
- The Cryosphere 17, 211 (2023);
- Hock 2003.
