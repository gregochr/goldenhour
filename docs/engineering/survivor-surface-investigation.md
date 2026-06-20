# Survivor-surface fix — Step 0 (read-only)

**Status:** Read-only mapping of the FIX surface. No code changed. Hard stop after this doc.
**Date:** 2026-06-20
**Predecessor:** `inversion-persistence-gap-investigation.md` (diagnosis — settled).
**Diagnosis (not re-litigated):** hot-topic detectors read `forecast_evaluation`, but nightly
that table holds the **triaged-out rejects**; the survivors Claude scores route to
`cached_evaluation`. Confirmed by the 2026-06-20 natural experiment (01:00 batch: 67 rows, 0
inversion, 0 rated; 06:00 sync: 186 rows, 54 inversion, 186 rated).

---

## TL;DR — two corrections to the brief's framing, then the plan

Tracing the actual fix surface changed the picture in two load-bearing ways. **Trust the code:**

1. **The survivor surface already exists and is half-built — it is `forecast_score`, NOT
   `cached_evaluation.results_json`.** There is an in-flight re-architecture (V107 `forecast_type`
   + V108 `forecast_score`, "Pass 1…4", feature-flagged dual-write `photocast.forecast-score.dual-write`)
   whose *stated purpose* is to replace "the column-per-forecast-type sprawl on `forecast_evaluation`
   **and** the field sprawl inside `cached_evaluation.results_json`." It is survivor-populated (Pass 2
   dual-write is live on the batch path), SQL-queryable, location-grained, has a hot-topic index, and
   **`BluebellHotTopicStrategy` already reads it** via the exact pattern the Group-B detectors should
   copy. V107 *explicitly anticipates* inversion: "AURORA and INVERSION are deliberately NOT seeded.
   They fold in via their own future work; a future type is one seed row + one enum constant + a
   writer." **This Step 0 IS that future work for inversion.** Routing signals into `results_json`
   instead would re-grow the very sprawl the re-arch is removing.

2. **The signals are NOT homogeneous, and only one of them is available at the survivor-write
   seam.** The brief assumes all three (inversion/dust/surge) "are on the evaluation output and/or the
   atmospheric data the batch already has, so carrying them is wiring, not new computation." Half
   true. At the survivor seam (`ForecastResultHandler.buildResult`), **only the Claude `eval` is in
   scope** — tide is *re-derived* there; the `AtmosphericData` (aerosol, surge, snow) is **not
   threaded** to the async batch result handler. So:
   - **INVERSION is an evaluation product** (`eval.inversionScore()/inversionPotential()`) — available
     at the seam, score-shaped, a clean `forecast_score` fit. **The cheap, blessed one.**
   - **DUST / SURGE (and SNOW — see below) are pre-evaluation atmospheric data** — *not* at the seam,
     *not* score-shaped, and would need threading/re-derivation plus a different carrier. **A larger,
     separate piece.**

3. **Scope is bigger than three detectors.** `SNOW_FRESH` and `SNOW_TOPS` *also* read
   `forecast_evaluation` off pre-evaluation data (`snow_depth_m`, `freezing_level_m`) — so they too
   fire off the reject population. The Class-B (atmospheric-data) repoint is **four** detectors, not
   two.

**Recommended outcome (not "JSON payload, no migration, repoint three"):**
- **Stage A — INVERSION onto `forecast_score`** (one seed row + enum constant + dual-write +
  Bluebell-style repoint). Small, unblocks the trigger, rides the blessed re-arch. *This is the
  whole of the cheap win.*
- **Stage B — design the Class-B carrier** for dust/surge/snow (atmospheric data, not at the seam,
  not score-shaped). A separate, larger design — threading + carrier-shape decision.

---

## Section 1 — what `cached_evaluation` stores, and the real carrier choice

### Storage shape: `results_json` is a serialised record list (no column to add for writing)

`cached_evaluation` (V91) is **one row per `regionName|date|targetType`**, columns:
`cache_key`, `region_name`, `evaluation_date`, `target_type`, **`results_json TEXT`**, `source`,
`evaluated_at`, `updated_at`
([`CachedEvaluationEntity`](../../backend/src/main/java/com/gregochr/goldenhour/entity/CachedEvaluationEntity.java),
[`V91`](../../backend/src/main/resources/db/migration/V91__create_cached_evaluation_table.sql)).

`results_json` is written by
[`BriefingEvaluationService.persistToDb`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java:581):
```java
String json = objectMapper.writeValueAsString(resultList); // List<BriefingEvaluationResult>
```
So **writing** new fields = add them to the `BriefingEvaluationResult` record → they serialise into
`results_json` automatically. **No migration to write.** That is the kernel of truth behind the
brief's "JSON-payload, no migration" intuition.

### …but the carrier choice is dominated by the READ, not the write — and by grain

Three things make `results_json` the wrong carrier:

1. **Detectors query in SQL, not Java.** Every Group-B detector calls a JPQL `@Query` over
   `ForecastEvaluationEntity` columns (e.g. `findInversionDaysByPotential`:
   `WHERE e.inversionPotential = :potential`). You cannot portably
   `WHERE <field> = …` against a TEXT JSON blob across H2 (local) and Postgres (prod). Reading
   signals out of `results_json` means **load-all-rows-in-window → `objectMapper.readValue` →
   filter in Java** (the shape `loadFromDb` already uses at line 467). That's a rewrite of each
   detector's read mechanism, not a table-name swap.
2. **Grain mismatch.** `cached_evaluation` is **region-grained** (one row per region/date/event), with
   a **per-location list inside the JSON**. Detectors need **per-location** signals ("which location
   has STRONG inversion"). Real columns on `cached_evaluation` therefore can't hold them (they'd be
   region-level) — confirming the "new columns on cached_evaluation" option is unworkable.
3. **It fights the in-flight re-arch.** `results_json` field sprawl is explicitly one of the two
   things `forecast_score` is being built to *remove* (V108 header comment). Adding inversion/dust/
   surge fields there grows the sprawl the team is mid-way through deleting.

### The full set of structured signals dropped at `buildResult`

[`ForecastResultHandler.buildResult`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java:386)
emits a `BriefingEvaluationResult` carrying only:
`locationName, rating, fierySkyPotential, goldenHourPotential, summary, triageReason, triageMessage,
headline`. Relative to what `ForecastService.buildEntity` stamps on a `forecast_evaluation` row,
the survivor surface **drops**:

- `inversionScore`, `inversionPotential` (Claude eval output — *would be available here*)
- `dust`, `aerosolOpticalDepth`, `pm25` (aerosol)
- `surgeRiskLevel`, `surgeTotalMetres`, `surgePressureMetres`, `surgeWindMetres`,
  `surgeAdjustedRangeMetres`, `surgeAstronomicalRangeMetres`
- `snowfallCm`, `snowDepthMetres`, `freezingLevelMetres`
- directional cloud (`solar*`/`antisolar*`/`farSolar*`), cloud-approach (`solarTrend*`, `upwind*`)
- raw weather (`lowCloud`, `midCloud`, `highCloud`, `visibility`, `windSpeed`, `humidity`,
  `boundaryLayerHeight`, `weatherCode`, …)

**Of these, only the inversion pair is a Claude-eval product available at this seam.** Everything else
is pre-evaluation `AtmosphericData`, which is **not passed** to `buildResult` (signature is
`buildResult(location, eval, date, targetType, regionName, modelName, pipelineRunId)` — no
`AtmosphericData`). Tide is the tell: `buildResult` **re-derives** it
(`forecastDataAugmentor.deriveTideContext(location, date, targetType)`, line 399) rather than reading
it off threaded data — because the atmospheric context is gone by the time the async batch result
returns.

**Answer to Section 1:** writing to `results_json` needs no migration, but it's the wrong carrier
(SQL-unqueryable, wrong grain, anti-re-arch). The right carrier is **`forecast_score`** for the
eval-product signal (inversion); the atmospheric signals (dust/surge/snow) are not available at the
seam at all and need a separate plumbing decision (Section 5, Stage B).

---

## Section 2 — per-detector read-surface map

| Detector | Reads today | Signal class | Available at survivor seam? | Action |
|---|---|---|---|---|
| **INVERSION** | `forecast_evaluation.inversion_potential` (`findInversionDaysByPotential`) | **Eval product** (`eval.inversionPotential`) | **Yes** — `eval` at `buildResult` | **Repoint → `forecast_score`** (new INVERSION type). *Fully inert today — the trigger.* |
| **DUST** | `forecast_evaluation` AOD/`dust`/`pm2_5` (`findDustDays`) | Pre-eval atmospheric | **No** — `AtmosphericData` not threaded | Repoint **after** Class-B carrier exists. Mis-samples rejects today. |
| **SURGE** | `forecast_evaluation.surge_risk_level` (`findSurgeDaysByRiskLevel`, HIGH only) | Pre-eval atmospheric | **No** | Repoint after Class-B carrier. Mis-samples rejects (coastal). |
| **SNOW_FRESH** | `forecast_evaluation.snow_depth_m` (`findSnowFreshDays`) | Pre-eval atmospheric | **No** | **Newly found** — same reject-sampling bug. Repoint after Class-B carrier. |
| **SNOW_TOPS** | `forecast_evaluation.freezing_level_m` vs `l.elevation_m` (`findSnowTopsDays`) | Pre-eval atmospheric | **No** | **Newly found** — same bug. Repoint after Class-B carrier. |
| **BLUEBELL** | **`forecast_score`** (`findComponentsForLocations`, BLUEBELL type) | Eval product | n/a | **Already correct** — the Pass-4 precedent to copy. |
| **KING_TIDE** | `briefingService.getCachedDays()` (briefing cache) + `locationRepository` | Deterministic tide | n/a | Not a survivor-bug victim (tide is deterministic, not Claude-scored). *Note: injects `ForecastEvaluationRepository` but `detect()` doesn't appear to use it — likely vestigial; verify.* |
| **SPRING_TIDE** | `briefingService.getCachedDays()` + `locationRepository` | Deterministic tide | n/a | As KING_TIDE. |
| **AURORA** | `AuroraStateCache` + `NoaaSwpcClient` (live space weather) | External live state | n/a | Not affected. |
| **EQUINOX / METEOR / NLC / SUPERMOON** | `SolarService` / `LunarPhaseService` / `LocationRepository` | Astronomical ephemeris | n/a | Not affected (pure calendar/geometry). |

**Signal availability at the write seam (Section 2.4):** the brief's claim that the signals "are on
the evaluation output and/or the atmospheric data the batch already has" is **only true for
inversion**. Dust/surge/snow live on `AtmosphericData`, which is computed at *collection* time
(`ForecastTaskCollector.fetchWeatherAndTriage`) and **not carried** into the async batch result
handler. Carrying them is therefore *not* "wiring, not new computation" — it needs either threading
`AtmosphericData` (or the derived signals) from submission to result, writing them at submission time
where the data exists, or re-deriving them at the seam (re-fetch — what the tide path effectively
does). That is the central cost the brief under-counted.

---

## Section 3 — single-seam: read model recommendation

**A shared survivor read model already exists and is the intended one: `forecast_score`.** The
durable answer is not a new abstraction — it's to finish the re-architecture's read migration (its
"Pass 4") for the affected detectors, exactly as `BluebellHotTopicStrategy` already did.

- **One surface, SQL-queryable, right grain.** `forecast_score` is keyed
  `(forecast_type, location, evaluation_date, event_type)` — the detector grain — with an index
  `idx_forecast_score_type_date` whose comment literally says "the hot-topic / reconciliation shape."
  A detector reads `findComponentsForLocations(typeId, locationIds, from, to)` and thresholds in
  Java (Bluebell's pattern). No JSON parsing, no DB-specific JSON SQL.
- **Survivor-backed by construction.** Pass 2 dual-write fires from the batch survivor path
  (`ForecastResultHandler.buildResult → dualWriteForecastScore → ForecastScoreWriter.write`), so any
  type written there is populated for the *scored* population — never the rejects. (It is also
  written on the sync path, so admin runs stay consistent.)
- **Prevents the recurrence.** A future detector wired against a `forecast_type` can only ever see
  survivor rows — it structurally cannot repeat "wired against a column the nightly pipeline never
  fills for survivors."

**Minimal durable shape:** for **inversion**, add `forecast_type` `INVERSION` (id 6, scale_max 10
or a 0/1/2 potential scale — encoding decision below) + enum constant + a dual-write upsert from
`eval` + repoint the detector. For **dust/surge/snow**, `forecast_score`'s `(score INTEGER, summary)`
grain is a **poor shape fit** (aerosol is three readings; surge is a risk enum; snow is depth/level) —
these may warrant a sibling survivor projection rather than being crammed into a score column. That
is a Stage-B design question, deliberately deferred. **Do not over-fit dust/surge/snow into
`forecast_score` just to reuse the table.**

---

## Section 4 — threshold re-verification (the "shipped ≠ working" guard on the repoint)

The thresholds were calibrated against the **reject** population. After repointing to the survivor
population, "reads the right table" is necessary but not sufficient — the detector must also **fire
at a sensible frequency**.

Current thresholds:
- **INVERSION** — `STRONG` only (potential 9–10). (`InversionHotTopicStrategy.STRONG_POTENTIAL`.)
- **DUST** — proxy `AOD > 0.3` **or** `dust > 50 µg/m³`, with `PM2.5 < 35` (or absent).
- **SURGE** — `HIGH` only. (`StormSurgeHotTopicStrategy.HIGH_RISK`.)
- **SNOW_FRESH** — `snow_depth_m ≥ threshold`. **SNOW_TOPS** — `freezing_level ≤ elevation − margin`.

**Verification plan (per detector, post-repoint, against real survivor data):**
1. After the carrier write lands, query the survivor surface over a representative window and count
   **how many survivor locations/days trip each threshold** — e.g. distinct STRONG-inversion days,
   dust-proxy days, HIGH-surge days. Target: non-zero but not flooding (a pill every few days in
   season, not every day and not never).
2. Compare against the **reject-population** counts the detector produces today, to see the
   population shift explicitly (e.g. dust currently fires off rejects — does the survivor count
   differ materially?).
3. If a threshold over/under-fires against survivors, **recalibrate as part of the repoint** — a
   correct table with a mis-tuned threshold is still broken. (Note: cannot be run from this dev
   machine — prod DB is remote; this is a gate Chris runs against prod/staging after each stage.)

**MODERATE-today note (do not mistake for failure):** today's sync-evaluated data shows the best Lake
District inversion is **MODERATE**, with no STRONG. STRONG is the fire threshold, so **even a perfect
inversion repoint would not light the pill today** — and that is *correct*. "No STRONG today" is a
genuine-conditions outcome, independent of the surface bug; it is the *second*, orthogonal reason the
pill is dark. Verify the repoint by confirming it *reads MODERATE/STRONG correctly off survivors*
(e.g. via a forced STRONG fixture or a simulation run), not by waiting for a live pill.

---

## Section 5 — staged plan (verification gate per stage)

The two signal classes have different fix shapes, so they stage separately.

**Stage A — INVERSION onto `forecast_score` (the cheap, blessed win; unblocks the trigger).**
- Seed `forecast_type` INVERSION (one row + enum constant; `ForecastTypeSeedDriftTest` enforces the
  pairing). Decide the encoding: store the 0–10 score (`scale_max = 10`) and threshold `≥ 9` in the
  detector, **or** store the NONE/MODERATE/STRONG potential as the score + classification in
  `summary`. (Open question 2.)
- Add an upsert in the dual-write (`ForecastScoreWriter.write` / `buildResult`) from
  `eval.inversionScore()/inversionPotential()` — available at the seam.
- Repoint `InversionHotTopicStrategy` to `forecast_score` (Bluebell pattern:
  `findComponentsForLocations(INVERSION.id, …)` + Java threshold).
- **Gate:** force/simulate a STRONG survivor and confirm the detector fires; confirm MODERATE does
  not; confirm the dual-write flag (`photocast.forecast-score.dual-write`) is on in prod.

**Stage B — design the Class-B carrier for DUST / SURGE / SNOW_FRESH / SNOW_TOPS (separate, larger).**
- These are pre-eval atmospheric data **not present at the survivor seam** and **not score-shaped**.
  First decision is the carrier (sibling survivor projection vs. forecast_score-as-score vs. threading
  `AtmosphericData` to the result handler), then where to write (submission time, where the data
  exists, is the natural seam — not `buildResult`).
- Only after the carrier exists: repoint DUST, then SURGE, then SNOW_FRESH/SNOW_TOPS, each with its
  own threshold re-verification gate (Section 4).
- **Do not bundle Stage B into Stage A.** Inversion ships independently and immediately; the
  atmospheric-data plumbing is a deliberate, separately-reviewed change.

**Per-stage gate (all stages):** verify against real survivor data that (a) the detector reads the new
surface and (b) fires at a sensible frequency, before moving on.

---

## What surprised me most

The brief framed this as "carry signals into `results_json` (no migration) and repoint three
detectors" — but the codebase is **already mid-flight on exactly this fix, via a different and better
surface.** `forecast_score` (V107/V108) is a documented, gated re-architecture whose written purpose
is to kill both the `forecast_evaluation` column sprawl *and* the `results_json` field sprawl; it is
survivor-populated, SQL-queryable, correctly grained, indexed "for hot topics," and **Bluebell is
already reading it.** V107 even names inversion as a one-seed-row follow-on. So the cheap, correct
inversion fix isn't a JSON hack — it's *finishing a lap the team already started.* The second
surprise cuts the other way: the three "siblings" aren't siblings. Inversion is a Claude **verdict**
sitting right there at the survivor seam; dust/surge/snow are **atmospheric inputs** that the async
batch throws away before the result handler ever runs — so "just carry them too" is a much larger
plumbing job hiding behind a symmetric-sounding sentence. And chasing it surfaced two *more* victims
(SNOW_FRESH, SNOW_TOPS) the brief didn't list.

---

## Open questions for Chris

1. **Ride the `forecast_score` re-arch, or sidestep it?** Recommendation: ride it for inversion
   (it's the blessed Pass-4 path, Bluebell precedent). Confirm that's the intent and that
   `docs/engineering/forecast-score-schema-investigation.md` (referenced by V107 but **absent from the
   working tree** — gitignored or uncommitted?) still describes the live plan.
2. **Inversion encoding in `forecast_score`:** store the raw 0–10 score (`scale_max = 10`, detector
   thresholds `≥ 9`), or the NONE/MODERATE/STRONG classification (as score + `summary`)? The detector
   only needs STRONG, but the planner/serving path may want the score.
3. **Class-B carrier for dust/surge/snow:** a sibling survivor projection, forecast_score-as-score
   (poor shape fit), or thread `AtmosphericData` through to the result handler? And — write them at
   *submission* time (where the data exists) rather than result time?
4. **Scope confirmation:** is fixing **five** detectors (inversion + dust + surge + snow_fresh +
   snow_tops) in scope, or just inversion now and the atmospheric four as a tracked follow-up? (They
   share the reject-sampling root but split into Stage A vs Stage B.)
5. **Is the `forecast_score` dual-write flag on in production?** (`photocast.forecast-score.dual-write`,
   default true.) Stage A is inert if it's off — verify before relying on it.
6. **KING_TIDE / SPRING_TIDE** inject `ForecastEvaluationRepository` but `detect()` reads the briefing
   cache — confirm the injection is vestigial (dead dependency to remove) and they're genuinely
   unaffected.

---

## Appendix — key references

| Concern | File:line |
|---|---|
| Survivor surface re-arch (replaces `forecast_evaluation` + `results_json` sprawl) | `db/migration/V108__create_forecast_score.sql` (header); `V107__create_forecast_type.sql:28-32` (INVERSION/AURORA deferred) |
| Survivor surface is location-grained + hot-topic indexed | `V108…:11-17, 52-54` |
| Pass 2 dual-write (survivor-populated, flagged) | `service/evaluation/ForecastScoreWriter.java:write/writeComponents`; called `service/evaluation/ForecastResultHandler.java:406, 441` |
| Bluebell already reads `forecast_score` (the repoint pattern) | `service/BluebellHotTopicStrategy.java:102`; `repository/ForecastScoreRepository.java:76-81` |
| Survivor write seam carries only `eval` (no `AtmosphericData`); tide re-derived | `service/evaluation/ForecastResultHandler.java:386-412` (deriveTideContext 399, 433) |
| `cached_evaluation` = region-grained, `results_json` TEXT blob of records | `entity/CachedEvaluationEntity.java`; `V91__create_cached_evaluation_table.sql`; write `service/BriefingEvaluationService.java:581` |
| `BriefingEvaluationResult` fields (what survivors carry) | `model/BriefingEvaluationResult.java` |
| INVERSION detector + query (inert) | `service/InversionHotTopicStrategy.java`; `repository/ForecastEvaluationRepository.java:103-109` |
| DUST detector + query (rejects) | `service/DustHotTopicStrategy.java`; `…Repository.java:142-153` |
| SURGE detector (HIGH only, rejects) | `service/StormSurgeHotTopicStrategy.java:39,61`; `…Repository.java:122-128` |
| SNOW_FRESH / SNOW_TOPS (newly found, rejects) | `service/SnowFreshHotTopicStrategy.java:91`; `service/SnowTopsHotTopicStrategy.java:74`; `…Repository.java:166-194` |
| Tide detectors read briefing cache (not affected) | `service/KingTideHotTopicStrategy.java:87`; `service/SpringTideHotTopicStrategy.java:78` |
