# Snow trio — Step 0 reconnaissance

Read-only investigation. **No code was changed.** Deliverable only.

Scope: the snow trio (`SNOW_FRESH`, `SNOW_TOPS`, `SNOW_MIST`) needs new data
ingestion — `snowfall`, `snow_depth`, `freezing_level_height` are not fetched
anywhere today (confirmed at HEAD). That makes this two phases with a
dependency: (1) plumb the data through the live Open-Meteo fetch, then (2) the
three `@Component HotTopicStrategy` detectors. This Step 0 establishes that
Phase 1 can ride the existing chunk resilience without weakening it, recommends
where the data should land, and surfaces the three threshold questions for
Chris.

All line numbers are at HEAD (commit `d639e368`).

---

## Section 1 — the Open-Meteo fetch as it stands

### 1.1 Where calls are built and issued

| Component | File | Detail |
|---|---|---|
| Forecast client | `client/OpenMeteoForecastApi.java:14-34` | `@GetExchange("/v1/forecast")`, params `(lat, lon, hourly, wind_speed_unit, timezone)` |
| Air-quality client | `client/OpenMeteoAirQualityApi.java:14-32` | `@GetExchange("/v1/air-quality")` |
| Service wrapper | `service/OpenMeteoClient.java` | Resilience4j-decorated; owns the param constants, chunking, and batch methods |
| Extraction | `service/OpenMeteoService.java:718-771` (`extractAtmosphericData`) | response DTO → `AtmosphericData` |

The snow fields belong on the **forecast** endpoint (`/v1/forecast`), which is
already in use. No new endpoint and no air-quality change. All three
(`snowfall`, `snow_depth`, `freezing_level_height`) are standard Open-Meteo
hourly forecast variables on that same endpoint — verified against the
Open-Meteo forecast variable set; they require no special model or extra
request. Units to be aware of: `snowfall` is **cm** (preceding hour),
`snow_depth` is **metres**, `freezing_level_height` is **metres above sea
level**.

### 1.2 The chunked-prefetch + per-chunk resilience mechanism

This is the part Phase 1 must not weaken. Confirmed structure at HEAD
(`OpenMeteoClient.java`):

Tuning constants (lines 67–96):
- `BATCH_COORD_LIMIT = 20` — max coordinate pairs per request (avoids HTTP 414
  with ~50 locations).
- `INTER_CHUNK_DELAY_MS = 3_000` — pause between chunks (stays under the
  minutely quota; ~27 s across 9+ chunks, fine within the 2 h briefing cadence).
- `RATE_LIMIT_BACKOFF_MS = 61_000` — wait after a 429 before the next chunk.
- `CHUNK_MAX_RETRIES = 2` — transient-failure retries per chunk; 429 is **not**
  retried (uses the inter-chunk backoff instead).
- `CHUNK_RETRY_BASE_BACKOFF_MS = 2_000` — exponential: 2 s → 4 s.

Resilient batch path (the "break the flood cascade" / per-chunk isolation work):
- `fetchForecastBriefingBatch(coords)` — `OpenMeteoClient.java:287-342`. Splits
  >20 coords into chunks of 20, pre-fills the result list with `null`, fetches
  each chunk via `fetchBriefingChunkWithRetry()`, populates results positionally,
  and **leaves nulls where a chunk fails** (partial-success, no cascade).
- `fetchBriefingChunkWithRetry()` — `:357-399`. Retry loop with exponential
  backoff; re-throws 429 immediately for the caller to back off; re-throws
  non-transient (4xx/parse) immediately; retries transient (I/O, 5xx) up to the
  cap.
- `isTransientFailure(Exception)` — `:408-420`. `ResourceAccessException` +
  `HttpServerErrorException (5xx)` → retry; 429 / 4xx / parse → no retry.
- Air-quality mirror: `fetchAirQualityBatchResilient()` `:460-514` +
  `fetchAirQualityChunkWithRetry()` `:520-558`.
- Decorators: `@Retry`/`@CircuitBreaker(name = "open-meteo-briefing")` on the
  briefing-grade methods; `@Retry`/`@CircuitBreaker`/`@RateLimiter(name =
  "open-meteo")` on the standard methods.

There is also a non-resilient batch (`fetchForecastBatchInternal` `:592-641`,
used by `fetchForecastBatch` `:267-269`) that chunks but does **not** do
per-chunk retry isolation — a failed chunk is logged, no chunk-level recovery.
This matters for acceptance (see §4): which path a given cycle uses determines
the resilience the new fields inherit.

### 1.3 The variable set today — and the single point of insertion

`OpenMeteoClient.java:47-58`:

```java
static final String FORECAST_PARAMS =
        "cloud_cover_low,cloud_cover_mid,cloud_cover_high,visibility,"
        + "wind_speed_10m,wind_direction_10m,precipitation,weather_code,"
        + "relative_humidity_2m,surface_pressure,shortwave_radiation,boundary_layer_height,"
        + "temperature_2m,apparent_temperature,precipitation_probability,dew_point_2m,"
        + "pressure_msl,wind_gusts_10m";

static final String CLOUD_ONLY_PARAMS = "cloud_cover_low,cloud_cover_mid,cloud_cover_high";
static final String AIR_QUALITY_PARAMS = "pm2_5,dust,aerosol_optical_depth";
```

**Key finding — one constant feeds every forecast path.** `FORECAST_PARAMS` is
referenced at exactly the points that matter:
- `:184` single forecast fetch
- `:200` briefing single fetch
- `:268` standard batch (`fetchForecastBatchInternal`)
- `:364` **resilient briefing chunk** (`fetchBriefingChunkWithRetry` →
  `fetchForecastChunk`)

So appending `,snowfall,snow_depth,freezing_level_height` to `FORECAST_PARAMS`
threads the new fields through **both** the regular forecast pipeline and the
resilient chunked briefing pipeline automatically. The new fields ride the
existing chunk resilience for free — there is no separate request to wire, and
no way to accidentally bypass the isolation, because the chunk path reads the
same constant. (`CLOUD_ONLY_PARAMS` — the directional horizon sampler — is left
untouched; snow has nothing to do with the cone sample.)

One thing to watch: adding 3 variables lengthens the GET URL. `BATCH_COORD_LIMIT
= 20` was chosen against the *current* param string to stay under HTTP 414.
Three more short variable names per request is a modest increase, but it should
be sanity-checked against the 414 ceiling on a full 20-coord chunk (see §4 risk).

### 1.4 Parse path → where weather lands

- DTO: `model/OpenMeteoForecastResponse.java:16-88`. The `Hourly` nested class
  (`:32-87`) maps each variable via `@JsonProperty`. **No snow fields exist
  today** (grep confirmed). Three new `List<Double>` fields + `@JsonProperty`
  getters are needed here (`snowfall`, `snow_depth`, `freezing_level_height`).
- Extraction: `OpenMeteoService.extractAtmosphericData()` `:718-771` finds the
  best hourly index (`findBestIndex`) and builds `CloudData` / `WeatherData` /
  `AerosolData` / `ComfortData` → `AtmosphericData` (`model/AtmosphericData.java:35-55`).
- Briefing slot sampling: `BriefingSlotBuilder.java:99-183`. Picks the nearest
  hourly index to the solar event (`TimeSlotUtils.findBestIndex`, `:101`) and
  reads **single-hour** values into `BriefingSlot.WeatherConditions`
  (`model/BriefingSlot.java:118-128`).
- Persistence: `ForecastService.java` writes the sampled event-hour values to
  `forecast_evaluation` (e.g. `inversionPotential` at `:675-676`; entity columns
  `surge_risk_level`, `inversion_score`, `inversion_potential` at
  `ForecastEvaluationEntity.java:285-302`).

**Critical parse-grain finding:** every persistence/cache sink today stores a
**single sampled hour** (the value nearest the solar event). Nothing sums an
hourly array across the overnight window. That has direct consequences for
SNOW_FRESH (see §2.1 and §3.1).

---

## Section 2 — where the new snow fields should land

### 2.1 Grain the detectors need

| Field | Nature | What a detector needs |
|---|---|---|
| `snow_depth` | cumulative state (m) | depth **at the morning hour** → "is snow lying at sunrise?" Single-hour sample suffices. |
| `snowfall` | per-hour increment (cm) | **overnight accumulation** = sum of hourly snowfall ~00:00→sunrise. **Not a single-hour value** → needs aggregation, which no current sink does. |
| `freezing_level_height` | level that varies through day (m ASL) | value **at the event hour** vs a location's `elevation_m`. Single-hour sample suffices; location-area independent (same for nearby lat/lon). |

So two of the three (`snow_depth`, `freezing_level_height`) fit the existing
single-hour sampling cleanly. The third signal — *overnight* snowfall total —
does **not** fit the single-hour pattern and is the one real grain mismatch in
the whole feature. Options for it are surfaced in §3.1; note here that
`snow_depth` at sunrise may make the overnight-`snowfall` sum unnecessary (depth
> 0 already proves snow is lying), which would collapse the mismatch.

### 2.2 Landing options, assessed against the proven detectors

The shipped detectors split into two read patterns:
- **Briefing-cache readers** (`KingTideHotTopicStrategy.java:86-90` →
  `briefingService.getCachedDays()`).
- **`forecast_evaluation`-column readers** (Group B: `Inversion` `:59-64`,
  `StormSurge` `:60-65`, `Dust` `:85-90` — each via a `ForecastEvaluationRepository`
  query over persisted columns).

| Option | How snow would land | Fit | Verdict |
|---|---|---|---|
| **A. `forecast_evaluation` columns** | Add `snow_depth_cm`/`snowfall_overnight`/`freezing_level_m` columns; `ForecastService` writes the sampled values per evaluation; detectors query like Inversion/Surge/Dust | Matches Group B exactly; per-location rows already keyed by date+location; a new repository query method per detector is the established shape | **Recommended** |
| B. Briefing cache | Add snow fields to `BriefingSlot.WeatherConditions`; detectors read `getCachedDays()` like KingTide | Works for `snow_depth`/`freezing_level` (single hour); cache is per-solar-event already. But the cache is in-memory + a single JSON row (`daily_briefing_cache`), regenerated each cycle — fine for transient signals, weaker for a persisted audit trail; and `snowfall` overnight-sum still needs aggregation upstream | Viable, less consistent |
| C. New structure | A bespoke snow table/record | No precedent; more surface area | Rejected |

**Recommendation: Option A — persisted `forecast_evaluation` columns**, mirroring
the inversion/surge/dust detectors. Rationale: (1) it's the exact pattern the
three most-similar detectors already use, so Phase 2 is genuinely routine; (2)
`forecast_evaluation` rows are per-location-per-date, which is the grain
`snow_depth`/`snowfall` need (per-location) — `freezing_level` is area-level but
storing it per-row is harmless; (3) it gives a durable record for boundary
tests and post-hoc verification (matters given the firing season is months out,
§4). The new columns would be added by a `V100`-range migration (note: actual
highest migration is **V99**, not the CLAUDE.md-stated V68 — always `ls
db/migration | sort -V | tail` first).

Where each detector reads (Option A):
- `SNOW_FRESH` → `ForecastEvaluationRepository.findSnowDays(from, to, depthThreshold[, snowfallThreshold])`, same shape as `findDustDays`.
- `SNOW_TOPS` → query joining `forecast_evaluation.freezing_level_m` against
  `location.elevation_m` (the join already exists in the Group-B queries:
  `JOIN e.location l`), filtering `freezing_level_m <= elevation_m - margin`.
- `SNOW_MIST` → depends on the §3.3 decision (composes snow with the existing
  mist signal).

### 2.3 Elevation for SNOW_TOPS — confirmed available

`LocationEntity.java:170-172` — `@Column(name = "elevation_m") private Integer
elevationMetres;` (nullable Integer, V65). **Populated** for the relevant fells
by migration `V86__populate_elevation_and_water_data.sql`, e.g. Cat Bells 451 m,
Side Pike 362 m, Loughrigg 335 m, plus higher non-water fells (Cheviot 815 m,
Windy Gyll 619 m, Simonside 440 m). Readable at detector time via
`LocationRepository` / `location.getElevationMetres()`, and reachable inside a
repository query through the existing `JOIN e.location l`. **Caveat:** elevation
is populated by V86 in production only — `application-local.yml` seed locations
have no `elevation_m`, so SNOW_TOPS boundary work in local dev must seed
elevation manually or use the test fixtures.

### 2.4 Would any existing consumer want snow data?

The forecast prompt (`PromptBuilder`) and triage have no snow concept today, and
adding snow there is **explicitly out of scope** (hot-topic-only). No compelling
existing seam found that would justify pulling snow into the prompt — the colour
pipeline doesn't reason about ground state. Noted and parked. The only data
already adjacent is the `weather_code` field (already fetched), whose WMO codes
include snow categories (71–77, 85–86); that's a possible cross-check signal for
a detector but not a substitute for `snow_depth`.

---

## Section 3 — the three thresholds (for Chris; options, not decisions)

### 3.1 SNOW_FRESH — accumulation AND morning persistence

What the data supports:
- `snow_depth` at the sunrise hour directly answers "is snow lying when I'd
  shoot?" — a single-hour sample, already the pattern.
- "Fresh" (fell overnight, not old hard pack) needs more than depth. Two ways to
  express it:

**Option F1 — depth-only.** Fire when `snow_depth` at sunrise ≥ *Y*. Simplest;
no aggregation needed; rides the single-hour sample. Downside: can't distinguish
fresh powder from week-old lying snow.

**Option F2 — overnight snowfall + morning depth.** Fire when `Σ snowfall
(00:00→sunrise) ≥ X cm` **AND** `snow_depth(sunrise) ≥ Y`. Captures "fell
overnight and is still there." Downside: the overnight sum is the one value no
current sink computes — Phase 1 would need to add an aggregation (sum the hourly
`snowfall` slice) at parse/persist time. Doable, but it's net-new logic beyond
"sample the event hour."

**Option F3 — depth delta.** Fire when `snow_depth(sunrise) −
snow_depth(~24 h earlier) ≥ X` AND `snow_depth(sunrise) ≥ Y`. Approximates
freshness from two depth samples instead of a snowfall sum. Needs two persisted
samples or a prior-row lookback.

Decision for Chris: which definition of "fresh" — and therefore whether Phase 1
must compute an overnight `snowfall` aggregate (F2) or can live on `snow_depth`
alone (F1) / a depth delta (F3). Open numbers if F2: X cm overnight, Y cm/m
lying.

### 3.2 SNOW_TOPS — freezing level vs summit elevation

What the data supports: both values are available at detector time —
`freezing_level_height` (new, per-row) and `elevation_m` (existing, on the
joined location). The detector compares them directly.

Decision for Chris: the **margin**. "Tops are white" isn't `freezing_level ==
elevation` — snow lies a bit below the freezing level and you want confidence,
so the rule is `freezing_level_height ≤ elevation_m − M`. The sim copy says
"Tops white above 600m" / "freezing level drops below the peaks." Open numbers:
- `M` (metres the freezing level must sit below the summit) — e.g. 0? 100? 200?
- A minimum `elevation_m` floor for a location to qualify (only call "fells"
  above some height — the sim text implies ~600 m, but many populated fells are
  300–470 m).

### 3.3 SNOW_MIST — third type, or +mist variant?

The mist signal already exists and is well-defined:
- `BriefingVerdictEvaluator.java:46` — `HUMIDITY_MARGINAL = 90`; humidity > 90%
  → "Mist risk" flag (`:303-304`) and MARGINAL verdict (`:167-177`).
- Horizon low-cloud demotion (`:259-277`) — `HORIZON_CLOUD_STANDDOWN = 70`,
  `HORIZON_CLOUD_MARGINAL = 40`, sampled at the 113 km geometric horizon
  (`BriefingService.java:86-87`).
- A richer `MistTrend` record (`model/MistTrend.java`) tracks visibility + dew
  point T-3h→T+2h, but is used by Claude evaluation, **not** wired into briefing
  verdicts.

So "mist" today = humidity > 90% (and/or low horizon cloud / low visibility) at
the event hour. SNOW_MIST = that signal **co-occurring with** the SNOW_FRESH
signal.

Two framings for Chris (sim catalogue already drafts copy for both a standalone
`SNOW_MIST` and `SNOW_FRESH`, `HotTopicSimulationService.java:295-318`):

**Option M1 — distinct third type.** A separate `SNOW_MIST` `@Component` that
fires only when both snow (per §3.1) and mist (humidity > 90% / horizon-cloud /
low-vis) hold. Cleaner card, higher priority. Risk: it can collide with
SNOW_FRESH firing the same day → needs dedupe/precedence (mist supersedes
fresh).

**Option M2 — "+mist" flag on SNOW_FRESH.** One snow detector that adds a mist
qualifier to its detail/label when the mist signal co-occurs. No collision, less
code; loses the distinct high-drama card the sim copy implies ("among the most
dramatic conditions possible").

Decision for Chris: distinct type (M1) vs variant flag (M2). If M1, also: which
mist threshold(s) gate it — humidity-only, or humidity OR horizon-cloud OR
low-visibility.

---

## Section 4 — phasing + risk

The two-phase shape is **validated**: the fields genuinely don't exist (Phase 1
is real ingestion work), and the detectors are a proven pattern (Phase 2 is
routine). The dependency is hard — detectors can't read data that isn't
persisted yet.

### Phase 1 — data plumbing (the riskier half)

Scope: append 3 vars to `FORECAST_PARAMS`; add 3 `List<Double>` fields to
`OpenMeteoForecastResponse.Hourly`; sample them in `BriefingSlotBuilder` /
`ForecastService` (+ overnight aggregate iff §3.1 = F2); add
`forecast_evaluation` columns via a new migration (`V100`+); persist.

- **Riskiest element:** it touches `FORECAST_PARAMS`, which every forecast path
  reads (single, batch, **and** the resilient briefing chunk). A malformed param
  string or an unparseable response field would degrade the *whole* weather
  fetch, not just snow. Mitigation: the new fields are additive and nullable;
  Jackson ignores unknown/maps absent arrays to null; the chunk isolation is
  inherited unchanged because the chunk path reads the same constant (no bypass).
- **Secondary risk — HTTP 414:** `BATCH_COORD_LIMIT = 20` was sized against the
  current param string. +3 variables lengthens every GET. Check a full 20-coord
  chunk URL length stays under the 414 ceiling; if marginal, lower
  `BATCH_COORD_LIMIT` (a safe, isolated change). Flag: if it *didn't* fit, that
  would reshape Phase 1 — but 3 short variable names is very unlikely to breach
  it.
- **Acceptance (empirical):** trigger a real cycle and query `forecast_evaluation`
  — the 3 new columns are populated (0/low values are fine, see seasonality) with
  **no regression** to existing weather columns and no change to chunk
  success/failure counts in the `[chunk]` logs. Confirm `freezing_level_height`
  reads a plausible non-zero metres-ASL value even in June (it's a real level
  year-round, unlike snow), which proves the plumbing independent of snow season.
- **Rollback:** revert the `FORECAST_PARAMS` append + DTO fields; the migration
  adds nullable columns (forward-compatible, no destructive rollback needed —
  leave the columns, they read null).

### Phase 2 — three detectors

Scope: 3 `@Component HotTopicStrategy` beans, auto-collected by
`HotTopicAggregator` (`:32-57`), 4-day window (`today`→`today+3`), inherited
simulation gating. Each reads Phase-1 data via a `ForecastEvaluationRepository`
query (SNOW_FRESH/SNOW_TOPS) and/or briefing cache (SNOW_MIST's mist signal).

- **Riskiest element:** threshold correctness (§3) — but these are judgement
  calls, boundary-testable in isolation, with zero pipeline blast radius.
- **Acceptance:** boundary unit tests per detector (just-fires / just-doesn't at
  each threshold) + the admin sim render. The sim catalogue **already has**
  `SNOW_FRESH` / `SNOW_MIST` / `SNOW_TOPS` templates
  (`HotTopicSimulationService.java:295-318`) with finished card copy, so the UI
  render is testable today without any live snow.
- **Rollback:** remove/disable the beans — they're additive and gated; deleting a
  detector removes its topics with no side effects.

### Seasonality note (flagged)

It's **June 2026**. No snow will fall in northern England for months, so:
- Phase 1's snow columns will read **0 / empty** on real cycles until winter.
  That's expected and is itself the acceptance signal for the plumbing —
  *the columns populate correctly with zero values*. `freezing_level_height` is
  the exception: it's a real level year-round (often a few thousand metres ASL in
  summer), so it's the field that proves the fetch+parse works **now**.
- Phase 2's detectors **won't fire live until a real winter event**. Confidence
  comes from (a) boundary tests against synthetic data and (b) the admin sim
  render (templates already exist). Live confirmation is months out — same
  posture as NLC and meteor (built ahead of season deliberately). This is fine
  and intended; just record that "green" at build time ≠ "seen firing live."

---

## What surprised me most

**`FORECAST_PARAMS` is a single chokepoint that the resilient chunk path reads
directly** (`OpenMeteoClient.java:364`). I expected the briefing/chunked path to
have its own variable list or request builder that I'd have to separately update
(and risk leaving the new fields *outside* the chunk isolation). Instead, one
constant feeds the single fetch, the standard batch, and the resilient briefing
chunk alike — so the snow fields ride the per-chunk retry/backoff isolation for
free, and there is literally no way to add them to the request while bypassing
the resilience. That collapses the headline pipeline risk this Step 0 was
commissioned to investigate: Phase 1's "touches the live fetch" danger is real
for *correctness* (414, parse) but **not** for *resilience* — the isolation is
inherited structurally, not by remembering to wire it.

A close second: every sink stores a **single sampled event-hour**, so the only
genuine grain mismatch in the whole feature is SNOW_FRESH's "overnight
accumulation" — and `snow_depth` at sunrise may make that sum unnecessary
entirely, turning a plumbing problem into a threshold-definition choice.

---

## Open questions for Chris (decisions, not mine to make)

1. **SNOW_FRESH definition** (§3.1): depth-only (F1), overnight-snowfall +
   morning-depth (F2), or depth-delta (F3)? This decides whether Phase 1 must
   compute an overnight `snowfall` aggregate or can live on the single-hour
   `snow_depth` sample. If F2: X cm overnight, Y lying.
2. **SNOW_TOPS margin** (§3.2): how far below summit must the freezing level sit
   (`M` metres) to call the tops white, and a minimum qualifying `elevation_m`
   floor (sim copy implies ~600 m; many populated fells are 300–470 m)?
3. **SNOW_MIST shape** (§3.3): distinct third detector (M1) or a "+mist" variant
   flag on SNOW_FRESH (M2)? If M1, which mist threshold gates it (humidity > 90%
   only, or also horizon-cloud / low-visibility)?

Secondary (engineering, lower stakes):
4. Confirm Option A (persisted `forecast_evaluation` columns) over the briefing
   cache for landing — recommended in §2.2, but Chris may prefer the cache if he
   wants snow kept out of the persisted forecast schema.
5. Migration number: next free is **V100** (highest is V99, not V68 as
   CLAUDE.md states).
