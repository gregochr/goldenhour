# DRAFT — event-spine

> ⚠️ **Working material, not the build source.** Written against a mix of `a484d1c4`
> (not an ancestor of `main`) and `d421ef5f`; citations of `ForecastTaskCollector.java`
> past line 534 are off by +16. Adversarial verification refuted claims in this draft —
> see `event-spine.corrections.md`. **Resolved positions live in `../all-in-build-plan.md`,
> which supersedes this file wherever they disagree.**

---

# §4.4 (build depth) — The append-only evaluation spine and its projections

Everything below was read from the tree on branch `fix/release-ahead-guard` at `a484d1c4`. Where I
could not verify something I have written **UNVERIFIED** rather than asserting it. Latest migration
in the tree is `V138__durham_heritage_coast_locations.sql`, so the first new migration is **V139**.

---

## 4.4.0 The crux — what deleting triage does to the verification harness

This is the most important finding in this section, and it inverts the assumption in the brief.

**Today's chain, verified end to end:**

1. The batch collector triages every candidate through
   `forecastService.fetchWeatherAndTriage(...)` — `ForecastTaskCollector.java:374`.
2. `fetchWeatherAndTriage` persists a `forecast_evaluation` row **only when the slot is triaged
   away** — `ForecastService.java:376` (weather triage) and `ForecastService.java:406`
   (tide-alignment triage). A survivor returns a `ForecastPreEvalResult` and writes nothing there.
3. Survivors' scores land in `cached_evaluation` via
   `ForecastResultHandler.parseBatchResponse` → `mergeCacheKey` → `BriefingEvaluationService.mergeFromBatch`
   (`ForecastResultHandler.java:306-308`, `BriefingEvaluationService.java:235-255`).
4. `CloudVerificationRepository.findUnverified` selects `FROM ForecastEvaluationEntity e ... AND
   e.directionalCloud.solarLow IS NOT NULL` (`CloudVerificationRepository.java:37-48`).

The codebase already states this in its own migration prose:

> `V115__create_survivor_atmosphere.sql:4-7` — "…read pre-evaluation readings that, nightly, only
> ever land on the triaged-out REJECT rows of forecast_evaluation (survivors route to
> cached_evaluation)."

So the ERA5 harness's population is, by construction, **the triaged set** — slots the triage
evaluator rejected at `solarLow > 80` (`WeatherTriageEvaluator.java:26,42`), plus whatever the
synchronous admin path happens to write. That fully explains the veto-fired buckets' mean forecast
cloud of 81.6 / 84.5 quoted in §2.2: those means are not a measurement of the veto, they are a
restatement of the triage threshold.

**Now delete triage (correction 2).** `ForecastService.java:376` and `:406` become unreachable, and
they are the *only* `forecast_evaluation` inserts the nightly and intraday pipelines make. The
remaining `repository.save` sites are `:220` (sync `runForecasts`), `:456` (sync
`evaluateAndPersist`), `:514` (`persistCannedResult`, which dies with the SENTINEL_SAMPLING
optimisation strategy) and `:564` (wildlife `HOURLY`). None of those run on the batch path.

**The population problem does not solve itself. It inverts, and it fails silently.**

| | Before | After triage deletion, without `evaluation_event` |
|---|---|---|
| Harness population | triaged rejects only (`solarLow > 80`) | admin-triggered sync runs only |
| Rows/night entering the pool | ≈ the triaged count | **0** |
| Failure signal | none — the pool looks healthy | none — `countUnverified` returns 0 |

`CloudVerificationService.countRemaining()` (`CloudVerificationService.java:145-148`) delegates to
`repository.countUnverified`, which returns `0` when the pool is empty. The backfill status endpoint
therefore reports **"nothing left to verify"** — indistinguishable from "fully caught up". The
project's only cloud-side instrument goes dark and reports success while doing so.

And the residual population is *worse* than the one being removed: an admin-triggered run is
triggered because the operator found the slot interesting, which is a selection effect with no
threshold you can even write down.

**Binding sequencing consequence, and it overrides the design doc's Phase ordering:**
`evaluation_event` + the harness retarget is a **hard prerequisite** for deleting triage, not a
Phase-0 convenience. The correct order is:

1. V139 lands `evaluation_event`; dual-write starts; backfill runs; harness retargets; reconciliation
   passes (§4.4.6).
2. *Only then* delete triage.

If those are reversed there is a window — of unbounded length, because nothing alarms — in which
the redesign is unmeasurable, which is exactly the trap §2 of the design document documents.

**A second-order consequence worth stating and not acting on.** §2.5 declares D7 closed on a
−14.0pp result "both capped and uncapped". That statistic was computed over the triaged population,
i.e. over rows pre-selected on `solarLow > 80` — the same class of defect as every other retraction
in §2. I am **not** re-opening D7 and no work should be done on `MAX_UPWIND_DISTANCE_M`. I am
recording that the number should be re-run once on the `evaluation_event` population before it is
cited again, because a settled negative result computed on a selected population is not yet settled.
See open questions.

### Measuring what triage deletion costs (the brief asks for this, and the £32–48 figure no longer holds)

The design doc's `+£32–48/month` at-home delta was computed **with triage retained** (§4.1 marks it
"Keep, at 80"). Deleting triage adds one Claude call per triaged slot per cycle on top of that. That
delta is not in any published figure and must be measured, not guessed.

Three queries. Run all three; they cross-check each other.

```sql
-- (1) Triage share of the candidate set, last 30 days.
--     CAVEAT: forecast_run_disposition is pruned at 30 days
--     (ForecastDispositionService.RETENTION_DAYS = 30, ForecastDispositionService.java:41),
--     so 30 days is the entire available history. It also covers the BATCH path only.
SELECT d.disposition,
       count(*)                                                        AS n,
       round(100.0 * count(*) / sum(count(*)) OVER (), 1)              AS pct_of_candidates
FROM forecast_run_disposition d
WHERE d.created_at >= now() - INTERVAL '30 days'
GROUP BY d.disposition
ORDER BY n DESC;

-- (2) Triaged slots per cycle — the marginal Claude calls per cycle that deletion adds.
SELECT d.job_run_id,
       min(d.created_at)                                                AS cycle_at,
       count(*) FILTER (WHERE d.disposition = 'SKIPPED_TRIAGED')        AS triaged,
       count(*) FILTER (WHERE d.disposition = 'EVALUATED')              AS evaluated,
       count(*)                                                         AS candidates
FROM forecast_run_disposition d
WHERE d.created_at >= now() - INTERVAL '30 days'
GROUP BY d.job_run_id
ORDER BY cycle_at DESC;

-- (3) Longer window, no 30-day prune: forecast_evaluation keeps every triage row forever
--     (V128 header: "insert-only and never pruned"). This is the authoritative count.
SELECT date_trunc('day', fe.forecast_run_at)                            AS utc_day,
       count(*) FILTER (WHERE fe.triage_reason IS NOT NULL)             AS triaged_rows,
       count(*) FILTER (WHERE fe.triage_reason = 'HIGH_CLOUD')          AS high_cloud,
       count(*) FILTER (WHERE fe.triage_reason = 'TIDE_MISALIGNED')     AS tide_misaligned,
       count(*)                                                         AS rows_written
FROM forecast_evaluation fe
WHERE fe.forecast_run_at >= now() - INTERVAL '90 days'
  AND fe.target_type <> 'HOURLY'
GROUP BY 1
ORDER BY 1 DESC;
```

Convert to money with the true per-model rate rather than the blended $0.00206/call, because triaged
slots skew far-term (Haiku, batch-discounted):

```sql
SELECT a.evaluation_model,
       a.is_batch,
       count(*)                                    AS calls,
       round(avg(a.cost_micro_dollars))            AS avg_micro_dollars,
       round(sum(a.cost_micro_dollars) / 1e6, 2)   AS total_usd
FROM api_call_log a
WHERE a.called_at >= now() - INTERVAL '30 days'
  AND a.service = 'ANTHROPIC'
GROUP BY 1, 2
ORDER BY calls DESC;
```

Marginal monthly cost of deleting triage
`≈ (triaged per cycle from query 2) × 60 cycles/30d × avg_micro_dollars(HAIKU, is_batch=true) / 1e6`,
then scaled by the ~×1.6 at-home factor from §1. **State that number in the commit that deletes
triage.** The published `+£32–48/month` is now a lower bound, not an estimate.

---

## 4.4.1 `evaluation_event` — full DDL, Postgres 17

One row per *decision or result about one slot*. Never updated, never deleted. Outcomes and skips
live together, so no query can compute a numerator without meeting its denominator.

```sql
-- V139__create_evaluation_event.sql
--
-- The append-only evaluation spine. One row per decision or result about one
-- (location, target_date, target_type) slot. Replaces the split between
-- forecast_evaluation (which, on the batch path, only ever received TRIAGED rows —
-- ForecastService.java:376,:406) and cached_evaluation (which received the survivors'
-- scores, region-grained, as a JSON blob). That split is why every ERA5 statistic in
-- docs/engineering/ was computed over a population selected by the thing being measured.
--
-- RULES THIS TABLE ENFORCES STRUCTURALLY, NOT BY CONVENTION:
--   * Skips and outcomes are the same shape, distinguished by a column. A query that
--     wants survivors must say so.
--   * Cloud values are stored EXACTLY as Open-Meteo returned them. No averaging, no
--     clamping, no unit change. Transformation happens at read.
--   * Nothing is ever updated. A retry appends; the projections take the latest.
--   * Every timestamp is TIMESTAMPTZ. forecast_evaluation stores naive LocalDateTimes in
--     two different zones (see the backfill in V140 and the note in §4.4.5) and that has
--     already produced one live off-by-one-hour bug.

CREATE TABLE evaluation_event (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    ---------------------------------------------------------------- provenance
    -- The orchestrated cycle. NULL for the synchronous/admin path: ResultContext.forSync
    -- sets pipelineRunId = null (ResultContext.java, forSync factory).
    pipeline_run_id           BIGINT      REFERENCES pipeline_run (id),
    -- Cost/API provenance. Anchors this event to the job_run whose api_call_log rows
    -- carry the token detail. Nullable for the same reason as above.
    job_run_id                BIGINT,
    -- Join key to api_call_log.custom_id (V99). Format fc-/bb-/wd-{locId}-{date}-{TYPE}
    -- (CustomIdFactory.java:41-45,64-109). NULL on the sync path, which issues no custom id.
    custom_id                 VARCHAR(64),
    -- True when this event belongs to the cycle's single RETRY_FAILED batch. Mirrors
    -- forecast_batch.is_retry (V106) so the vocabulary is one word in both places.
    is_retry                  BOOLEAN     NOT NULL DEFAULT false,

    ---------------------------------------------------------------- slot identity
    -- NOT NULL, deliberately: see §4.4.1a on why structural exclusions are not events.
    location_id               BIGINT      NOT NULL REFERENCES locations (id),
    -- The coordinates the FORECAST used. locations.lat/lon are editable in the Admin UI,
    -- so re-deriving the sampling point at verification time would sample a place the
    -- forecast never looked at. forecast_evaluation carries location_lat/location_lon for
    -- exactly this reason (ForecastEvaluationEntity.java:51-56) and it is kept.
    location_lat              NUMERIC(9,6)  NOT NULL,
    location_lon              NUMERIC(9,6)  NOT NULL,
    target_date               DATE        NOT NULL,
    target_type               VARCHAR(10) NOT NULL,
    -- Which prompt evaluated (or would have evaluated) this slot.
    -- EvaluationTask.Forecast.PromptKind (EvaluationTask.java:103-117).
    -- NULL is legal only when no prompt was ever selected — see the CHECK below.
    prompt_kind               VARCHAR(16),

    ---------------------------------------------------------------- the decision
    -- SUBMITTED  the request went to Claude; no answer yet, or never came back
    -- EVALUATED  Claude answered and parsed
    -- FAILED     Claude answered unusably (API error / parse failure) — the retry pool
    -- SKIPPED    a policy decision not to spend. Post-redesign the ONLY value is TRAVEL_DAY
    -- TRIAGED    HISTORICAL ONLY. Written by the V140 backfill; no live writer emits it
    --            after the triage deletion. Pinned by EvaluationEventOutcomeTest.
    outcome                   VARCHAR(16) NOT NULL,
    -- DispositionCategory / TriageReason name, or TRAVEL_DAY.
    skip_reason               VARCHAR(40),
    -- The human string. TEXT, not VARCHAR(500): forecast_run_disposition.detail is
    -- VARCHAR(500) and ForecastDispositionService.truncate (:152-157) silently cuts at
    -- 500 chars. Nothing about a reason justifies losing its tail.
    skip_detail               TEXT,

    ---------------------------------------------------------------- timing
    -- When the weather snapshot behind this event was taken / the decision was made.
    forecast_run_at           TIMESTAMPTZ NOT NULL,
    -- When THIS ROW was created. clock_timestamp(), NOT now()/CURRENT_TIMESTAMP:
    -- now() is transaction-start, and submitBuckets writes ~1000 events in ONE
    -- transaction, so now() would give every one of them an identical timestamp and
    -- destroy the projection's ordering. See §4.4.7.
    produced_at               TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    -- The horizon AS COMPUTED AT DECISION TIME. Not derived from
    -- (target_date - forecast_run_at) at read: the pipeline computes days_ahead in
    -- Europe/London (BatchRetryService.java:295-296) while forecast_run_at is UTC
    -- (ForecastService.java:644), so the two disagree across midnight in BST.
    days_ahead                SMALLINT    NOT NULL,
    -- ConfidenceDeriver.fromHorizon(days_ahead).name(). Analytics only; gates nothing.
    confidence                VARCHAR(20),

    ---------------------------------------------------------------- the score
    evaluation_model          VARCHAR(10),
    rating                    SMALLINT,
    fiery_sky                 SMALLINT,
    golden_hour               SMALLINT,
    summary                   TEXT,
    headline                  VARCHAR(255),
    basic_fiery_sky           SMALLINT,
    basic_golden_hour         SMALLINT,
    basic_summary             TEXT,
    inversion_score           SMALLINT,
    inversion_potential       VARCHAR(10),
    -- Denormalised from CostCalculator.calculateCostMicroDollars(model, usage, isBatch)
    -- (CostCalculator.java:37). Denormalised on purpose: "spend by outcome bucket" is the
    -- question this whole redesign exists to answer, and it must be one table with no join.
    cost_micro_dollars        BIGINT,

    ---------------------------------------------------------------- solar geometry
    solar_event_time          TIMESTAMPTZ,
    azimuth_deg               SMALLINT,

    ---------------------------------------------------------------- observations, RAW
    -- Column-for-column the mapping in ForecastService.buildEntity (:638-683). Every one
    -- is stored exactly as fetched. The verification harness reads solar_low_cloud,
    -- mid_cloud, high_cloud, solar_trend_building, upwind_current_low_cloud,
    -- upwind_distance_km, wind_direction_deg and azimuth_deg
    -- (CloudVerificationRepository.java:93-101); the rest are here because the forecast
    -- made claims about them and an unexplainable past evaluation is not evidence.
    low_cloud                     SMALLINT,
    mid_cloud                     SMALLINT,
    high_cloud                    SMALLINT,
    solar_low_cloud               SMALLINT,
    solar_mid_cloud               SMALLINT,
    solar_high_cloud              SMALLINT,
    antisolar_low_cloud           SMALLINT,
    antisolar_mid_cloud           SMALLINT,
    antisolar_high_cloud          SMALLINT,
    far_solar_low_cloud           SMALLINT,
    solar_trend_event_low_cloud   SMALLINT,
    solar_trend_earliest_low_cloud SMALLINT,
    solar_trend_building          BOOLEAN,
    upwind_current_low_cloud      SMALLINT,
    upwind_event_low_cloud        SMALLINT,
    upwind_distance_km            SMALLINT,
    visibility_m                  INTEGER,
    wind_speed_ms                 NUMERIC(5,2),
    wind_direction_deg            SMALLINT,
    precipitation_mm              NUMERIC(5,2),
    precipitation_probability_pct SMALLINT,
    humidity_pct                  SMALLINT,
    weather_code                  SMALLINT,
    boundary_layer_height_m       INTEGER,
    shortwave_radiation_wm2       NUMERIC(7,2),
    pm2_5                         NUMERIC(7,2),
    dust                          NUMERIC(7,2),
    aerosol_optical_depth         NUMERIC(6,3),
    temperature_c                 DOUBLE PRECISION,
    dew_point_c                   DOUBLE PRECISION,
    apparent_temperature_c        DOUBLE PRECISION,
    -- Carried so survivor_atmosphere (V115/V123/V124) can be retired — see §4.4.9b.
    snow_depth_m                  DOUBLE PRECISION,
    freezing_level_m              DOUBLE PRECISION,

    ---------------------------------------------------------------- tide + surge
    tide_state                    VARCHAR(10),
    next_high_tide_at             TIMESTAMPTZ,
    next_high_tide_m              NUMERIC(5,2),
    next_low_tide_at              TIMESTAMPTZ,
    next_low_tide_m               NUMERIC(5,2),
    tide_aligned                  BOOLEAN,
    surge_total_m                 DOUBLE PRECISION,
    surge_pressure_m              DOUBLE PRECISION,
    surge_wind_m                  DOUBLE PRECISION,
    surge_risk_level              VARCHAR(10),
    surge_adjusted_range_m        DOUBLE PRECISION,
    surge_astronomical_range_m    DOUBLE PRECISION,
    surge_wind_speed_ms           DOUBLE PRECISION,
    surge_wind_direction_deg      DOUBLE PRECISION,

    ---------------------------------------------------------------- open-ended payload
    -- The ONLY genuinely open-ended thing here: prompt inputs that differ per prompt_kind
    -- (bluebell exposure and season phase; woodland canopy state). The set has changed
    -- three times in three releases (SKY -> BLUEBELL -> WOODLAND) and NOTHING queries it —
    -- it exists so a past evaluation can be explained. A column per prompt kind would be a
    -- migration per prompt kind, each one nullable for every other kind.
    -- DO NOT add a GIN index until a query exists that needs one.
    prompt_inputs             JSONB,

    ---------------------------------------------------------------- integrity
    CONSTRAINT ck_evaluation_event_outcome CHECK (
        outcome IN ('SUBMITTED','EVALUATED','FAILED','SKIPPED','TRIAGED')),
    CONSTRAINT ck_evaluation_event_target_type CHECK (
        target_type IN ('SUNRISE','SUNSET')),
    CONSTRAINT ck_evaluation_event_prompt_kind CHECK (
        prompt_kind IS NULL OR prompt_kind IN ('SKY','BLUEBELL','WOODLAND')),
    -- A scored event must know which prompt produced it, or the projection cannot keep a
    -- SKY score and a BLUEBELL score for the same slot apart.
    CONSTRAINT ck_evaluation_event_scored_has_prompt CHECK (
        outcome <> 'EVALUATED' OR prompt_kind IS NOT NULL),
    -- Skip reason present exactly when, and only when, the event is a non-evaluation.
    CONSTRAINT ck_evaluation_event_skip_reason CHECK (
        (outcome IN ('SKIPPED','TRIAGED')) = (skip_reason IS NOT NULL)),
    -- Scores only on EVALUATED. This is what makes "COUNT(rating)" and
    -- "COUNT(*) WHERE outcome='EVALUATED'" the same number, permanently.
    CONSTRAINT ck_evaluation_event_scores_only_when_evaluated CHECK (
        outcome = 'EVALUATED'
        OR (rating IS NULL AND fiery_sky IS NULL AND golden_hour IS NULL
            AND basic_fiery_sky IS NULL AND basic_golden_hour IS NULL)),
    CONSTRAINT ck_evaluation_event_rating_range CHECK (
        rating IS NULL OR rating BETWEEN 1 AND 5),
    CONSTRAINT ck_evaluation_event_score_range CHECK (
        (fiery_sky   IS NULL OR fiery_sky   BETWEEN 0 AND 100)
        AND (golden_hour IS NULL OR golden_hour BETWEEN 0 AND 100)),
    -- Cloud percentages are percentages. Cheap, and it catches a unit change at the point
    -- of record without transforming anything.
    CONSTRAINT ck_evaluation_event_cloud_pct CHECK (
        (low_cloud IS NULL OR low_cloud BETWEEN 0 AND 100)
        AND (mid_cloud IS NULL OR mid_cloud BETWEEN 0 AND 100)
        AND (high_cloud IS NULL OR high_cloud BETWEEN 0 AND 100)
        AND (solar_low_cloud IS NULL OR solar_low_cloud BETWEEN 0 AND 100)
        AND (far_solar_low_cloud IS NULL OR far_solar_low_cloud BETWEEN 0 AND 100)
        AND (upwind_current_low_cloud IS NULL OR upwind_current_low_cloud BETWEEN 0 AND 100))
);
```

### 4.4.1a Why `location_id` is `NOT NULL`, and what stops being a row

`forecast_run_disposition.location_id` is nullable "because past-date and cached slots are recorded
without performing a location lookup" (`V101__forecast_run_disposition.sql:14-17`;
`ForecastRunDispositionEntity.java:51-57`). Three of today's `DispositionCategory` values —
`SKIPPED_PAST_DATE`, `SKIPPED_UNKNOWN_LOCATION`, and `SKIPPED_CACHED` — exist only because of that.

Under correction 2 the distinction becomes clean:

| Today's category | Post-redesign |
|---|---|
| `SKIPPED_TRAVEL_DAY` | **the only live `SKIPPED` event** (`skip_reason = 'TRAVEL_DAY'`) |
| `SKIPPED_PAST_DATE` | not an event — you cannot forecast yesterday. A *bound*, logged, not recorded |
| `SKIPPED_UNKNOWN_LOCATION` | not an event — misconfiguration. `LOG.warn`, no row (there is no `location_id` to hang one on) |
| `SKIPPED_CACHED`, `SKIPPED_STABILITY`, `SKIPPED_HARD_CONSTRAINT`, `SKIPPED_NO_REFRESH_NEEDED`, `FORCE_EVALUATED` | deleted with their gates |
| `SKIPPED_TRIAGED` | deleted with triage; survives as the historical `TRIAGED` outcome value |
| `SKIPPED_NO_PROMPT` | **kept**, as `SKIPPED` / `skip_reason='NO_PROMPT'`. It is not a gate — a canopy site out of season has no prompt that can say anything true (`DispositionCategory.java:48-57`) |
| `SKIPPED_ERROR` | **kept**, as `FAILED` / `skip_reason='ASSEMBLY_ERROR'` |
| `EVALUATED` | `SUBMITTED` then `EVALUATED` (two events — see §4.4.7) |

The rule: **a bound is a fact about the world and is logged; a policy is a choice and is recorded.**
Recording "we did not evaluate a date in the past" adds nothing to any denominator, and paying for it
with a nullable FK on the spine costs every query a null check forever.

### 4.4.1b `HOURLY` / wildlife is deliberately not here — and it blocks dropping `forecast_evaluation`

`ForecastService.runWildlifeHourly` writes `TargetType.HOURLY` rows at `ForecastService.java:564` —
one per full UTC hour between sunrise and sunset, with `EvaluationModel.WILDLIFE` and no Claude call.
They are weather observations for display, not decisions about spend, and admitting them would push
a high-volume, zero-decision row class through every `outcome` filter on the spine. The
`ck_evaluation_event_target_type` CHECK excludes them.

**Consequence, and it must be in the drop plan:** `forecast_evaluation` cannot be dropped while
wildlife hourly lives there, regardless of how the colour reconciliation goes. The minimal fix is one
migration that narrows those rows into their own table (~8 of the 60+ columns are populated for
them):

```sql
-- V14x (follow-on, not part of V139)
CREATE TABLE wildlife_comfort_hour AS
SELECT location_id, target_date, solar_event_time, temperature_celsius,
       apparent_temperature_celsius, wind_speed, precipitation,
       precipitation_probability_percent, weather_code, forecast_run_at
FROM forecast_evaluation
WHERE target_type = 'HOURLY';
```

Count it first: `SELECT count(*), min(target_date), max(target_date) FROM forecast_evaluation WHERE
target_type = 'HOURLY';` — UNVERIFIED, I have no production access.

---

## 4.4.2 Indexes, each tied to a named query

```sql
-- (1) The projection driver. DISTINCT ON resolves as an index scan + Unique with this
--     exact column order and direction; without it the planner sorts the whole slice.
--     Query: evaluation_current / evaluation_scored_current (§4.4.3).
CREATE INDEX idx_evaluation_event_slot_latest
    ON evaluation_event (location_id, target_date, target_type,
                         produced_at DESC, id DESC);

-- (2) The verification harness's candidate driver. PARTIAL, and its predicate is a
--     column-for-column copy of the NOT NULL guards in
--     CloudVerificationRepository.findUnverified (:40-47), so the index IS the candidate
--     set — Postgres never touches a row that could not be a candidate. The (target_date,
--     id) key matches that query's ORDER BY exactly.
CREATE INDEX idx_evaluation_event_verifiable
    ON evaluation_event (target_date, id)
    WHERE outcome = 'EVALUATED'
      AND solar_low_cloud IS NOT NULL
      AND azimuth_deg IS NOT NULL
      AND solar_event_time IS NOT NULL;

-- (3) Window scans over survivors: CloudVerificationRepository.findVerifiedPairs /
--     countVerifiedInWindow (targetDate BETWEEN), and ForecastCalibrationService's join
--     to actual_outcome on (location, date, target type).
CREATE INDEX idx_evaluation_event_scored_window
    ON evaluation_event (target_date, target_type, location_id)
    WHERE outcome = 'EVALUATED';

-- (4) "What did this cycle decide, and why?" — the ops disposition breakdown, replacing
--     idx_forecast_run_disposition_job_run (V101:33-34).
CREATE INDEX idx_evaluation_event_cycle
    ON evaluation_event (pipeline_run_id, outcome);

-- (5) Skip-share and the Phase-2 cost-gate monitoring, replacing
--     idx_forecast_run_disposition_disp_created (V101:37-38). PARTIAL because after the
--     redesign skips are a small minority of rows, and a partial index over a minority
--     class is a fraction of the size of the full-column index it replaces.
CREATE INDEX idx_evaluation_event_skips
    ON evaluation_event (skip_reason, produced_at DESC)
    WHERE outcome IN ('SKIPPED','TRIAGED','FAILED');

-- (6) EXPRESSION + PARTIAL: the cloud-approach veto's own firing predicate, so
--     "how often did the veto fire, across the WHOLE population" becomes an index-only
--     count for the first time. The predicate is literally
--     CloudVerificationPair.vetoFired() (CloudVerificationPair.java:83-87) with
--     UPWIND_TRIGGER_PERCENT = 60 (:69).
--     ⚠ The 60 is duplicated from Java. It is an OPTIMISATION ONLY: the query must still
--     state the predicate itself. If the Java constant moves, this index simply stops
--     being used — the answer stays correct, only slower. Do not read it as the source of
--     truth for the threshold.
CREATE INDEX idx_evaluation_event_veto_fired
    ON evaluation_event (target_date)
    WHERE outcome = 'EVALUATED'
      AND solar_trend_building
      AND upwind_current_low_cloud >= 60;

-- (7) The api_call_log join for token-level detail. PARTIAL: sync events have no custom id.
CREATE INDEX idx_evaluation_event_custom_id
    ON evaluation_event (custom_id)
    WHERE custom_id IS NOT NULL;
```

**No unique constraint on the slot key, on purpose.** A retry appends a second `SUBMITTED` and a
second `EVALUATED` inside the same `pipeline_run_id` (§4.4.7). Any uniqueness over
`(pipeline_run_id, location_id, target_date, target_type, prompt_kind)` would make the retry path
throw. `is_retry` distinguishes them; `produced_at`/`id` orders them.

---

## 4.4.3 The projections — `DISTINCT ON`, and two of them, not one

```sql
-- The DECISION projection: what most recently happened to this slot, whatever it was.
-- Backs the ops/disposition UI and any accounting that asks "what is the current state?".
-- Partitioned WITHOUT prompt_kind, because a skip has no prompt kind and must still be
-- able to be the latest thing that happened.
CREATE VIEW evaluation_current AS
SELECT DISTINCT ON (location_id, target_date, target_type) *
FROM evaluation_event
ORDER BY location_id, target_date, target_type, produced_at DESC, id DESC;

-- The DISPLAY projection: the latest SCORE per slot per prompt kind. Backs the Plan tab,
-- the Map tab and GET /api/forecast — everything EvaluationViewService merges today.
-- Partitioned WITH prompt_kind so a SKY score and a BLUEBELL score for the same slot
-- coexist, which the OPEN_FELL recombination requires
-- (BriefingEvaluationService.recombineBluebell, :339-352).
CREATE VIEW evaluation_scored_current AS
SELECT DISTINCT ON (location_id, target_date, target_type, prompt_kind) *
FROM evaluation_event
WHERE outcome = 'EVALUATED'
ORDER BY location_id, target_date, target_type, prompt_kind, produced_at DESC, id DESC;
```

### `DISTINCT ON` vs the window-function form

The window form is:

```sql
SELECT * FROM (
  SELECT e.*, ROW_NUMBER() OVER (
           PARTITION BY location_id, target_date, target_type, prompt_kind
           ORDER BY produced_at DESC, id DESC) AS rn
  FROM evaluation_event e) t
WHERE rn = 1;
```

`DISTINCT ON` wins on three counts and loses on one:

- **Plan shape.** `DISTINCT ON` compiles to `Unique` over an ordered index scan and stops at the
  first row of each group. `ROW_NUMBER` compiles to a `WindowAgg` that must read and number *every*
  row of every partition before the `rn = 1` filter can discard them. With ~10–20 events per slot the
  difference is a constant factor, not an order — but it is a factor on the Plan tab's hot path.
- **Column bleed.** The window form emits an `rn` column that every consumer must then not select.
  An `@Immutable` JPA entity mapped over it either maps a meaningless field or relies on
  `SELECT *` never being used. `DISTINCT ON` emits exactly the base table's columns, so the read
  entity is a field-for-field mirror.
- **Intent.** `DISTINCT ON (key)` says "one row per key". `ROW_NUMBER() ... WHERE rn = 1` says the
  same thing as arithmetic and has to be read twice.
- **Loses on:** portability. `DISTINCT ON` is Postgres-only; H2 (the unit-test slice,
  `src/test/resources/application.yml:2-3`) cannot parse it. Under correction 1 that is not a cost —
  Postgres is the only runtime database (`backend/pom.xml:250-253`, H2 `<scope>test</scope>`) — but it
  does mean **the projection's semantics are only testable in the Testcontainers slice**
  (`IntegrationTestBase`), never in a `@DataJpaTest`. See §4.4.8 for why that matters more than it
  sounds.

**Verdict: `DISTINCT ON`.** Choosing `ROW_NUMBER` here would be writing lowest-common-denominator
SQL to serve a database that does not run this application.

### View, materialized view, or maintained table?

**Plain view, and it holds indefinitely — conditionally.**

Volume, from the tree rather than a guess: `ForecastDispositionService.java:38-39` and
`V101__forecast_run_disposition.sql:41` both size the candidate set at **~2k rows/day** (60k over the
30-day retention). Every candidate produces one decision event; submitted candidates produce a second
terminal event. So `evaluation_event` grows at **≈ 3,500–4,000 rows/day ≈ 1.3M/year**, and
`forecast_evaluation` is never pruned (`V128__index_forecast_evaluation_latest_run.sql:7-9`), so
neither should this be.

A plain view over a 1.3M-row-per-year table would indeed degrade — **if anything read it without a
date range.** Nothing does. Every consumer of the projection asks about the plan window:
`BriefingService.java:348` calls `getScoresForEnrichmentBulk(start, end, types)`;
`ForecastController.java:184` calls `cachedOnlyViewsForDateRange(start, end, ...)`;
`BriefingEvaluationController.java:68` calls `forDateRange(...)`. With `target_date` in the leading
position of index (3) and in the projection driver (1), the working set is
`~100 locations × 5 dates × 2 events × ≤3 prompt kinds × ~10 events of history ≈ 30k rows` — a
bounded, small scan that does not grow with table age.

**The load-bearing condition, and it must be pinned:** every read of `evaluation_scored_current`
carries a `target_date` predicate. `EvaluationEventQueryDisciplineTest` (§4.4.8) asserts it in the
same pass that asserts the outcome filter.

**Revisit threshold — concrete, not "if it gets slow":**

1. a reader appears that needs the projection without a date range (a whole-history analytics
   screen), **or**
2. `EXPLAIN (ANALYZE, BUFFERS)` on the Plan-tab enrichment query exceeds **50 ms** or 20k shared
   buffer hits.

The upgrade is fifteen minutes, because the history is already there to rebuild from:

```sql
CREATE MATERIALIZED VIEW evaluation_scored_current AS ...same body...;
CREATE UNIQUE INDEX uq_evaluation_scored_current
    ON evaluation_scored_current (location_id, target_date, target_type, prompt_kind);
-- Refresh at the end of PUBLISH, the last stage of the cycle. CONCURRENTLY needs the
-- unique index above and never blocks readers.
REFRESH MATERIALIZED VIEW CONCURRENTLY evaluation_scored_current;
```

A maintained table (trigger-updated) is the wrong third option and should not be reached for: it
reintroduces exactly the update-in-place surface this design removes, and the two can then disagree.

### A deliberate retention change worth naming in the commit

`forecast_run_disposition` is pruned at 30 days (`ForecastDispositionService.java:41,144-150`;
`disposition_cleanup` seeded in `V101:43-48`). When dispositions become events, **that prune goes
away.** It has to: a 30-day prune on the selection record makes any analysis older than 30 days blind
to its own selection, which is the precise defect §2.7 exists to fix. Deleting the `disposition_cleanup`
scheduler row is part of this work, not an oversight.

---

## 4.4.4 Dual-write — every write site, and where the new write goes

### The complete inventory (verified by grep over `src/main`)

**`cached_evaluation`** — one writer class, `BriefingEvaluationService`, all funnelling through
`persistToDb` (`:627-664`):

| Entry point | Line | Called from |
|---|---|---|
| `writeFromBatch` | `:204-213` | `ForecastResultHandler.flushCacheKey` `:289-291` (**`@Deprecated`, destructive**) |
| `mergeFromBatch` | `:235-255` | `ForecastResultHandler.mergeCacheKey` `:306-308`; `handleSyncResult` `:455` |
| `mergeBluebellFromBatch` | `:287-303` | `ForecastResultHandler.mergeBluebellCacheKey` `:319-321` |
| `mergeWoodlandFromBatch` | `:319-332` | `ForecastResultHandler.mergeWoodlandCacheKey` `:334-336` |
| `clearCache` | `:469-488` | `DELETE /api/briefing/evaluate/cache` (ADMIN) |

**`forecast_evaluation`** — one writer class, `ForecastService`:

| Site | Line | What it writes |
|---|---|---|
| `runForecasts` | `:220` | full scored row, sync scheduled/admin path |
| weather triage | `:376` | TRIAGED row — **dies with triage** |
| tide-alignment triage | `:406` | TRIAGED row — **dies with triage** |
| `evaluateAndPersist` | `:456` | full scored row, sync/admin path |
| `persistCannedResult` | `:514` | canned TRIAGED row — **dies with SENTINEL_SAMPLING (V52)** |
| `runWildlifeHourly` | `:564` | `HOURLY` comfort rows — see §4.4.1b |

All six build their row through the single mapping in `buildEntity` (`:635-684`). That is the one
place the observation columns are populated, and it is what the new writer mirrors.

### Where the dual-write goes

**A. Decision events — one call, one place.**
`ScheduledBatchEvaluationService.submitBuckets` (`:360-441`) is the only method that holds both the
six task buckets (each `EvaluationTask.Forecast` carrying its `AtmosphericData` via `data()`,
`EvaluationTask.java:74-82`) *and* the full disposition list, *and* the `pipelineRunId` and the
cycle's anchor `jobRunId`. The collector has the data but **not** the `pipelineRunId` — verified: no
occurrence of `pipelineRunId` anywhere in `ForecastTaskCollector.java`; it enters at
`doSubmitForecastBatch` (`:336`) and is passed to `submitBuckets`.

Add exactly one line beside the existing disposition write at `:429`:

```java
    persistCycleDispositions(cycleJobRunId, tasks.dispositions());
    evaluationEventWriter.recordCycleDecisions(pipelineRunId, cycleJobRunId, tasks);   // NEW
```

with

```java
/**
 * Appends one decision event per candidate this cycle considered — {@code SUBMITTED} for every
 * task in every bucket, {@code SKIPPED}/{@code FAILED} for every non-evaluation the collector
 * recorded. Denominator and numerator are written in one transaction so they cannot diverge.
 *
 * @param pipelineRunId the orchestrated cycle, or {@code null} for a cron-direct invocation
 * @param jobRunId      the cycle's anchor job run, or {@code null} when no bucket was submitted
 * @param tasks         the collector's buckets and dispositions
 */
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void recordCycleDecisions(Long pipelineRunId, Long jobRunId, ScheduledBatchTasks tasks);
```

**B. Result events — four call sites, exception-isolated.**
`ForecastResultHandler.parseBatchResponse` (`:169-214`), `parseBluebellBatchResponse` (`:237-270`),
`parseWoodlandBatchResponse` (`:352-...`) and `handleSyncResult` (`:418-...`), each *after* the
existing cache write and inside a `try/catch` that logs and continues:

```java
/**
 * Appends the terminal event for one request, copying the observation columns forward from the
 * slot's latest {@code SUBMITTED} event. The batch boundary discards {@link AtmosphericData}
 * (see {@link SurvivorAtmosphereWriter}), so the copy is how a scored event stays self-describing.
 *
 * @param identity   location, date and target type parsed from the custom id
 * @param promptKind which prompt produced this response
 * @param outcome    {@code EVALUATED} or {@code FAILED}
 * @param evaluation the parsed result, or {@code null} when the outcome is {@code FAILED}
 * @param context    batch/sync observability context (job run, batch id, pipeline run)
 * @param usage      token usage, for the denormalised cost
 */
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void recordResult(ForecastResultHandler.ForecastIdentity identity,
        EvaluationTask.Forecast.PromptKind promptKind, Outcome outcome,
        SunsetEvaluation evaluation, ResultContext context, TokenUsage usage);
```

**C. Sync path — one complete event, no copy-forward.**
`ForecastService.evaluateAndPersist` (`:456`) and `runForecasts` (`:220`) never cross the batch
boundary; `AtmosphericData` is in hand. They write a single `EVALUATED` event with observations and
score together, beside the existing `repository.save(entity)`.

**D. Triage rows — dual-write window only.**
`:376` and `:406` also append a `TRIAGED` event, so the harness has continuity across the cut-over.
Both sites are deleted whole when triage goes.

### Why this changes no behaviour

Every property is copied verbatim from `SurvivorAtmosphereWriter`, which has been doing exactly this
in production since V115:

- `@Transactional(propagation = Propagation.REQUIRES_NEW)` — `SurvivorAtmosphereWriter.java:74`. A
  write failure rolls back only this write.
- Callers wrap the call and log — `ForecastService.java:461-468`,
  `ForecastTaskCollector.java:487-494`. Both log at `ERROR` and continue.
- Feature flag `photocast.evaluation-event.write` (default `true`), mirroring
  `photocast.survivor-atmosphere.write` (`SurvivorAtmosphereWriter.java:58`) and
  `photocast.forecast-score.dual-write` (`ForecastScoreWriter.java:66`,
  `application-example.yml:180-181`). Flag off = no rows written, no redeploy, additive-table rollback.

The new table is additive; nothing reads it until Phase 4. `ForecastScoreDualWriteIntegrationTest`
is the naming and structural precedent — the new one is `EvaluationEventDualWriteIntegrationTest`.

---

## 4.4.5 Backfill from `forecast_evaluation` — and the hour that would have been lost

```sql
-- V140__backfill_evaluation_event.sql
--
-- One event per historical forecast_evaluation row. Scored rows become EVALUATED;
-- triaged rows become TRIAGED (the historical-only outcome). HOURLY is excluded —
-- wildlife comfort is not an evaluation (§4.4.1b).
--
-- ⚠ TIMEZONES. forecast_evaluation stores naive LocalDateTimes in TWO DIFFERENT ZONES,
--   and the codebase's own Javadoc is wrong about one of them:
--     * forecast_run_at  is written by the ONLY writer of that column,
--       ForecastService.java:644 -> LocalDateTime.now(ZoneOffset.UTC). It is UTC.
--       EvaluationViewService.java:530-543 claims it is Europe/London and converts it as
--       such. That Javadoc and that conversion are WRONG — see §4.4.9.
--     * solar_event_time is written from SolarService's *Utc() methods
--       (ForecastService.java:346-347, :676). It is UTC.
--     * tide times come from TideDetails.from(data.tide()) and are UTC likewise.
--   Everything below is therefore `AT TIME ZONE 'UTC'`. Converting forecast_run_at as
--   London — which the Javadoc would have you do — shifts 25,730 verified rows by an hour
--   through BST, i.e. through the entire photographic season.

INSERT INTO evaluation_event (
    pipeline_run_id, job_run_id, custom_id, is_retry,
    location_id, location_lat, location_lon, target_date, target_type, prompt_kind,
    outcome, skip_reason, skip_detail,
    forecast_run_at, produced_at, days_ahead, confidence,
    evaluation_model, rating, fiery_sky, golden_hour, summary, headline,
    basic_fiery_sky, basic_golden_hour, basic_summary,
    inversion_score, inversion_potential, cost_micro_dollars,
    solar_event_time, azimuth_deg,
    low_cloud, mid_cloud, high_cloud,
    solar_low_cloud, solar_mid_cloud, solar_high_cloud,
    antisolar_low_cloud, antisolar_mid_cloud, antisolar_high_cloud, far_solar_low_cloud,
    solar_trend_event_low_cloud, solar_trend_earliest_low_cloud, solar_trend_building,
    upwind_current_low_cloud, upwind_event_low_cloud, upwind_distance_km,
    visibility_m, wind_speed_ms, wind_direction_deg,
    precipitation_mm, precipitation_probability_pct, humidity_pct, weather_code,
    boundary_layer_height_m, shortwave_radiation_wm2,
    pm2_5, dust, aerosol_optical_depth,
    temperature_c, dew_point_c, apparent_temperature_c,
    tide_state, next_high_tide_at, next_high_tide_m, next_low_tide_at, next_low_tide_m,
    tide_aligned,
    surge_total_m, surge_pressure_m, surge_wind_m, surge_risk_level,
    surge_adjusted_range_m, surge_astronomical_range_m
)
SELECT
    NULL, NULL, NULL, false,
    fe.location_id, fe.location_lat, fe.location_lon, fe.target_date, fe.target_type,
    CASE WHEN fe.triage_reason IS NULL THEN 'SKY' ELSE NULL END,
    CASE WHEN fe.triage_reason IS NOT NULL THEN 'TRIAGED'
         WHEN fe.rating IS NOT NULL
           OR fe.fiery_sky_potential IS NOT NULL
           OR fe.golden_hour_potential IS NOT NULL THEN 'EVALUATED'
         ELSE 'FAILED' END,
    CASE WHEN fe.triage_reason IS NOT NULL THEN fe.triage_reason ELSE NULL END,
    fe.triage_message,
    fe.forecast_run_at AT TIME ZONE 'UTC',
    fe.forecast_run_at AT TIME ZONE 'UTC',      -- produced_at: the only honest proxy we have
    fe.days_ahead, fe.confidence,
    fe.evaluation_model, fe.rating, fe.fiery_sky_potential, fe.golden_hour_potential,
    fe.summary, fe.headline,
    fe.basic_fiery_sky_potential, fe.basic_golden_hour_potential, fe.basic_summary,
    fe.inversion_score, fe.inversion_potential, NULL,
    fe.solar_event_time AT TIME ZONE 'UTC', fe.azimuth_deg,
    fe.low_cloud, fe.mid_cloud, fe.high_cloud,
    fe.solar_low_cloud, fe.solar_mid_cloud, fe.solar_high_cloud,
    fe.antisolar_low_cloud, fe.antisolar_mid_cloud, fe.antisolar_high_cloud,
    fe.far_solar_low_cloud,
    fe.solar_trend_event_low_cloud, fe.solar_trend_earliest_low_cloud, fe.solar_trend_building,
    fe.upwind_current_low_cloud, fe.upwind_event_low_cloud, fe.upwind_distance_km,
    fe.visibility, fe.wind_speed, fe.wind_direction,
    fe.precipitation, fe.precipitation_probability_percent, fe.humidity, fe.weather_code,
    fe.boundary_layer_height, fe.shortwave_radiation,
    fe.pm2_5, fe.dust, fe.aerosol_optical_depth,
    fe.temperature_celsius, fe.dew_point_celsius, fe.apparent_temperature_celsius,
    fe.tide_state,
    fe.next_high_tide_time AT TIME ZONE 'UTC', fe.next_high_tide_height_m,
    fe.next_low_tide_time  AT TIME ZONE 'UTC', fe.next_low_tide_height_m,
    fe.tide_aligned,
    fe.surge_total_metres, fe.surge_pressure_metres, fe.surge_wind_metres,
    fe.surge_risk_level, fe.surge_adjusted_range_metres, fe.surge_astronomical_range_metres
FROM forecast_evaluation fe
WHERE fe.target_type <> 'HOURLY';
```

### What is lossy, and what is unrecoverable

| Field | Status | Why |
|---|---|---|
| `pipeline_run_id`, `job_run_id` | **unrecoverable** → `NULL` | `forecast_evaluation` carries neither column. Historical cycle attribution is gone. New rows have it. |
| `custom_id` | **unrecoverable** → `NULL` | Same. `api_call_log.custom_id` only arrived in V99 and cannot be joined back to a `forecast_evaluation` id. |
| `cost_micro_dollars` | **unrecoverable** → `NULL` | Cost was never per-evaluation on this table; it lives on `api_call_log` with no link to the evaluation row. Historical spend-by-outcome is unanswerable. |
| `produced_at` | **lossy** — set equal to `forecast_run_at` | The row-creation instant was never recorded. This is a proxy and is honest at ~seconds' resolution for the sync path; it is not a measurement. |
| `prompt_kind` | **inferred** → `'SKY'` for scored, `NULL` for triaged | `forecast_evaluation` was only ever written by the sky path; the bluebell/woodland paths write to `cached_evaluation` and `forecast_score`. V112 dropped the legacy bluebell columns from this table. |
| `is_retry` | **unrecoverable** → `false` | Not recorded historically. |
| `snow_depth_m`, `freezing_level_m` | **unrecoverable** → `NULL` | **V116 dropped these columns from `forecast_evaluation`.** They exist only on `survivor_atmosphere` from V115 forward. |
| **Everything scored on the batch path** | **absent entirely** | This is the point. `cached_evaluation` holds region-grained JSON with only `locationName/rating/fierySky/goldenHour/summary/headline` (`BriefingEvaluationResult.java:22-27`) and **no observations, no `days_ahead`, no azimuth, no solar event time**. Those rows can be backfilled for *display* continuity but can never join the verification harness. |

**The `cached_evaluation` companion backfill** — optional, display-only, and it must be labelled as
such so nobody mistakes it for evidence:

```sql
-- Display continuity only. These rows carry NO observations and are therefore
-- permanently invisible to idx_evaluation_event_verifiable (§4.4.2) — which is correct:
-- a row with no cloud reading cannot verify a cloud claim.
INSERT INTO evaluation_event (
    location_id, location_lat, location_lon, target_date, target_type, prompt_kind,
    outcome, forecast_run_at, produced_at, days_ahead,
    rating, fiery_sky, golden_hour, summary, headline)
SELECT l.id, l.lat, l.lon, ce.evaluation_date, ce.target_type, 'SKY',
       'EVALUATED', ce.updated_at, ce.updated_at,
       (ce.evaluation_date - (ce.updated_at AT TIME ZONE 'Europe/London')::date),
       (r->>'rating')::smallint, (r->>'fierySkyPotential')::smallint,
       (r->>'goldenHourPotential')::smallint, r->>'summary', r->>'headline'
FROM cached_evaluation ce
CROSS JOIN LATERAL jsonb_array_elements(ce.results_json::jsonb) AS r
JOIN locations l ON l.name = r->>'locationName'
WHERE (r->>'rating') IS NOT NULL;
```

Two notes on that statement. `results_json` is declared `TEXT` (`CachedEvaluationEntity.java:52`), so
the `::jsonb` cast is required — this is the one place JSONB parsing earns its keep, and
`jsonb_array_elements` in a `LATERAL` is the idiomatic Postgres shred. `updated_at` and not
`evaluated_at`: `persistToDb` only sets `evaluated_at` inside its `orElseGet` for a *new* row
(`BriefingEvaluationService.java:640-645`), so a slot re-evaluated for three days still carries its
day-one `evaluated_at` — the same trap already documented at `EvaluationViewService.java:613-619` and
`BriefingEvaluationService.java:546-549`.

---

## 4.4.6 Reconciliation — what must pass before `cached_evaluation` is dropped

Run each as a query returning zero rows (or a documented, explained non-zero). All six must pass in
the same session, after at least **seven consecutive dual-write cycles** with no `[EVAL EVENT]` error
in the logs.

```sql
-- R1. Every historical forecast_evaluation row has exactly one event.
SELECT count(*) FILTER (WHERE fe.target_type <> 'HOURLY') AS source_rows,
       (SELECT count(*) FROM evaluation_event
         WHERE pipeline_run_id IS NULL AND is_retry = false
           AND outcome IN ('EVALUATED','TRIAGED','FAILED')) AS backfilled_rows
FROM forecast_evaluation fe;   -- the two numbers must be equal

-- R2. No scored slot lost its score, and none gained one.
SELECT count(*) AS mismatched
FROM forecast_evaluation fe
JOIN evaluation_event ee
  ON ee.location_id = fe.location_id
 AND ee.target_date = fe.target_date
 AND ee.target_type = fe.target_type
 AND ee.forecast_run_at = fe.forecast_run_at AT TIME ZONE 'UTC'
WHERE fe.target_type <> 'HOURLY'
  AND (fe.rating IS DISTINCT FROM ee.rating
    OR fe.fiery_sky_potential IS DISTINCT FROM ee.fiery_sky
    OR fe.golden_hour_potential IS DISTINCT FROM ee.golden_hour
    OR fe.solar_low_cloud IS DISTINCT FROM ee.solar_low_cloud
    OR fe.triage_reason IS DISTINCT FROM ee.skip_reason);   -- must be 0

-- R3. THE ONE THAT MATTERS. During dual-write, every cached_evaluation entry has a matching
--     scored event. This is the batch path's score arriving in the new spine.
SELECT ce.cache_key, r->>'locationName' AS location, (r->>'rating')::int AS cached_rating
FROM cached_evaluation ce
CROSS JOIN LATERAL jsonb_array_elements(ce.results_json::jsonb) AS r
JOIN locations l ON l.name = r->>'locationName'
LEFT JOIN evaluation_scored_current sc
       ON sc.location_id = l.id
      AND sc.target_date = ce.evaluation_date
      AND sc.target_type = ce.target_type
      AND sc.prompt_kind = 'SKY'
WHERE (r->>'rating') IS NOT NULL
  AND ce.evaluation_date >= current_date
  AND sc.id IS NULL;                                        -- must return 0 rows

-- R4. Ratings agree where both exist. A disagreement is a merge-precedence bug, not noise.
SELECT ce.cache_key, r->>'locationName' AS location,
       (r->>'rating')::int AS cached_rating, sc.rating AS event_rating
FROM cached_evaluation ce
CROSS JOIN LATERAL jsonb_array_elements(ce.results_json::jsonb) AS r
JOIN locations l ON l.name = r->>'locationName'
JOIN evaluation_scored_current sc
  ON sc.location_id = l.id AND sc.target_date = ce.evaluation_date
 AND sc.target_type = ce.target_type AND sc.prompt_kind = 'SKY'
WHERE (r->>'rating')::int IS DISTINCT FROM sc.rating
  AND ce.evaluation_date >= current_date;                   -- must return 0 rows

-- R5. Candidate accounting closes. Per cycle, events = the disposition count the cycle
--     recorded, plus one terminal event per submitted request.
SELECT ee.pipeline_run_id,
       count(*) FILTER (WHERE ee.outcome = 'SUBMITTED')            AS submitted,
       count(*) FILTER (WHERE ee.outcome IN ('EVALUATED','FAILED')) AS terminal,
       count(*) FILTER (WHERE ee.outcome = 'SKIPPED')              AS skipped
FROM evaluation_event ee
WHERE ee.pipeline_run_id IS NOT NULL
GROUP BY 1 ORDER BY 1 DESC LIMIT 10;
-- submitted must equal (terminal + still-outstanding); skipped must equal the cycle's
-- forecast_run_disposition SKIPPED_* count for the same cycle, while both still exist.

-- R6. The harness sees the batch path for the first time. Before the cut-over this is 0
--     by construction; after it, it must be a large majority of the population.
SELECT count(*) FILTER (WHERE ee.pipeline_run_id IS NOT NULL) AS batch_verifiable,
       count(*) FILTER (WHERE ee.pipeline_run_id IS NULL)     AS sync_or_historical
FROM evaluation_event ee
WHERE ee.outcome = 'EVALUATED'
  AND ee.solar_low_cloud IS NOT NULL
  AND ee.azimuth_deg IS NOT NULL
  AND ee.solar_event_time IS NOT NULL;
```

**R3 is the drop gate.** `cached_evaluation` may only be dropped when R3 returns zero rows across
seven consecutive days and the Plan tab has been served from `evaluation_scored_current` for those
seven days. R6 is the *success* measure for the whole redesign, not a safety check.

---

## 4.4.7 Retry semantics, and the tie the design must not have

`BatchRetryService` selects failures **only from precursor (non-retry) batches**
(`BatchRetryService.java:122-127`, `findByPipelineRunIdAndRetryFalse`) and skips submission if a retry
batch already exists for the cycle (`:205-209`). At most one retry per cycle, structurally.

It reconstructs each failure by calling `forecastService.fetchWeatherAndTriage(...)` afresh
(`:244-246`), producing **new** `AtmosphericData`. So the retry writes its own `SUBMITTED` event with
`is_retry = true` and fresh observations — not a copy of the original. That is correct: the retry is a
different request with a different weather snapshot, minutes later.

The event sequence for a slot that failed and recovered:

| # | outcome | `is_retry` | `produced_at` |
|---|---|---|---|
| 1 | `SUBMITTED` | false | T0 |
| 2 | `FAILED` | false | T0 + ~10 min |
| 3 | `SUBMITTED` | true | T0 + ~12 min |
| 4 | `EVALUATED` | true | T0 + ~22 min |

`evaluation_scored_current` filters `outcome = 'EVALUATED'` and orders `produced_at DESC, id DESC`, so
it selects (4). `evaluation_current` orders identically without the filter, so it also selects (4).
Had the retry itself failed, `evaluation_current` would show `FAILED` at (4') — a visible hole rather
than a silent one — and `evaluation_scored_current` would fall back to whatever the previous cycle
scored, which is the correct display behaviour.

### Ties on `produced_at` — and why `now()` would have created them by the thousand

Two mechanisms, both Postgres-specific, both load-bearing:

1. **`DEFAULT clock_timestamp()`, never `now()` / `CURRENT_TIMESTAMP`.** In Postgres, `now()` and
   `CURRENT_TIMESTAMP` return **transaction start time** and are identical for every row inserted in
   one transaction. `recordCycleDecisions` writes on the order of a thousand events in a single
   transaction. With `now()`, every event in a cycle would share one `produced_at` and the projection's
   primary sort key would be constant across the entire cycle — reducing ordering to the `id`
   tie-break alone. `clock_timestamp()` reads the wall clock per row and produces strictly increasing
   values within a transaction. This is not a micro-optimisation; it is the difference between a sort
   key and a constant.

2. **`id DESC` as the deterministic tie-break, with an honest scope.** `BIGINT GENERATED ALWAYS AS
   IDENTITY` allocates from a sequence at INSERT. The general guarantee people assume — "higher id
   means later commit" — is **false** under concurrency: a transaction holding a lower id can commit
   after one holding a higher id. The guarantee that actually holds here is narrower and sufficient:
   *for a single slot*, the events are produced strictly sequentially by one process (submit, then
   result; retry-submit, then retry-result), never concurrently. Within that key the id order is the
   causal order. Do not extend the claim beyond the key.

A third mechanism was considered and rejected: a monotonic `sequence_no` column populated from a
dedicated sequence. It adds a column and a sequence to guarantee something `id` already guarantees for
the only key that matters.

**Test:** `EvaluationEventProjectionIntegrationTest.retryEventSupersedesItsPrecursor` — inserts the
four-row sequence above inside one transaction and asserts `evaluation_scored_current` returns the
retry's rating. It must live in the Testcontainers slice (`IntegrationTestBase`): H2 cannot parse
`DISTINCT ON`, and — more subtly — H2's `CURRENT_TIMESTAMP` semantics differ, so a same-transaction
tie test on H2 would pass or fail for the wrong reason.

---

## 4.4.8 The anti-regression mechanism — three layers, one of which cannot be forgotten

The requirement: *fail the build if a query against `evaluation_event` omits an explicit outcome
filter.* Being honest about what is and is not enforceable:

**ArchUnit is the wrong tool here and should not be added.** ArchUnit reasons about types and
dependencies. The defect being prevented lives inside a `String` — the JPQL in a `@Query` annotation —
which ArchUnit does not parse. It would add a dependency and catch nothing.

### Layer 1 (structural, cannot be forgotten) — the table has no read mapping

There is exactly one JPA entity mapped to `evaluation_event`, and its repository is **write-only**:

```java
/**
 * Write-only repository for the append-only evaluation spine.
 *
 * <p>Extends {@link org.springframework.data.repository.Repository} rather than
 * {@code JpaRepository} deliberately: {@code Repository} is a marker with <b>no inherited
 * methods</b>, so {@code findAll()}, {@code findById()} and every other unfiltered read simply
 * does not exist on this type. Reads go through {@link EvaluationScoredCurrentRepository} or
 * {@link EvaluationCurrentRepository}, whose entities are mapped to the filtered SQL views —
 * there is no mapped type through which an unfiltered read of the spine can be expressed.
 *
 * <p>The single exception is {@link #copyForwardObservations}, whose native statement reads the
 * spine to build the next event. It carries its own outcome predicate and is pinned by
 * {@code EvaluationEventQueryDisciplineTest}.
 */
public interface EvaluationEventRepository extends Repository<EvaluationEventEntity, Long> {
    EvaluationEventEntity save(EvaluationEventEntity event);
    List<EvaluationEventEntity> saveAll(Iterable<EvaluationEventEntity> events);
}
```

Read entities are `@Immutable` and mapped to the views:

```java
@Entity @Immutable @Table(name = "evaluation_scored_current")
public class EvaluationScoredCurrentEntity { /* field-for-field mirror */ }
```

A developer reaching for "all evaluations" finds only `evaluation_scored_current` (already
`WHERE outcome = 'EVALUATED'`) or `evaluation_current` (latest-anything, whose name says so). The
unfiltered read is not something you have to remember not to write; it is something you cannot write
without first adding a new repository over the base table — a reviewable act.

**The honest limitation, stated because it will otherwise be discovered later:** the H2 unit slice
(`src/test/resources/application.yml:2-11`, `ddl-auto: create-drop`, `flyway.enabled: false`) will
create `evaluation_scored_current` as an empty **table**, not a view, because Hibernate emits DDL for
`@Immutable` entities like any other. Tests in that slice will therefore read nothing and prove
nothing about the projection. Every projection test must extend `IntegrationTestBase`. This is one
more reason the H2 slice should go (correction 1) — out of scope here, but it is a cost this design
pays.

### Layer 2 (the build gate) — a reflective query-discipline test, no new dependency

```java
/**
 * Fails the build if any repository query touches the evaluation spine without stating what
 * population it means.
 *
 * <p>Scans every interface in {@code com.gregochr.goldenhour.repository} for {@code @Query}
 * annotations (JPQL and native), plus every {@code @Query} on a nested writer, and asserts that
 * any statement naming {@code evaluation_event} or {@code EvaluationEventEntity} also contains an
 * {@code outcome} predicate, and — for statements naming a projection view — a {@code target_date}
 * predicate (the condition under which the plain view stays cheap, §4.4.3).
 *
 * <p>This is a string test over annotation values, which is exactly the right shape: the defect
 * being prevented lives in those strings. It cannot catch dynamic SQL, and there is none — no
 * class in {@code src/main} references {@code JdbcTemplate}, {@code EntityManager} or
 * {@code createNativeQuery} (verified by grep).
 */
class EvaluationEventQueryDisciplineTest {
    @Test void everySpineQueryStatesItsOutcomeFilter() { … }
    @Test void everyProjectionQueryStatesADateRange() { … }
}
```

The grep that licenses the "cannot catch dynamic SQL, and there is none" claim is
`grep -rln "JdbcTemplate\|EntityManager\|createNativeQuery" src/main/java` → **no matches**. That fact
is itself worth pinning, so a third test asserts it:

```java
    @Test void noProductionClassIssuesRawSql() { … }   // guards the guard
```

### Layer 3 (database) — the views are the contract, and a smoke test says so

`EvaluationEventSchemaIntegrationTest` (modelled on the existing `BatchSchemaIntegrationTest`)
asserts against the Testcontainers Postgres that:

- `evaluation_current` and `evaluation_scored_current` exist and are `VIEW`s in
  `information_schema.views`;
- `evaluation_scored_current` returns no row whose `outcome <> 'EVALUATED'`;
- the six CHECK constraints exist by name in `pg_constraint`;
- `idx_evaluation_event_verifiable`'s predicate is byte-identical to the guards in
  `CloudVerificationRepository.findUnverified` (asserted by comparing the index's `pg_get_indexdef`
  against a constant that the repository test also pins — so moving one without the other fails).

**What is genuinely not enforceable:** a human running ad-hoc SQL in `psql`. Nothing in this design
prevents that, and pretending otherwise would be the same overclaim the retractions punished. The
mitigation is that the *reports* are code, and the code cannot express the unfiltered read.

---

## 4.4.9 What `EvaluationViewService` becomes — and a live bug it is carrying

`EvaluationViewService` (629 lines) exists for exactly one reason, stated in its own class Javadoc
(`:29-35`): to merge `cached_evaluation` with `forecast_evaluation` at read time. With one spine and
two projections there is nothing to merge. **It is deleted in full.**

Its four public methods re-home as follows:

| Method | Line | Consumer | Becomes |
|---|---|---|---|
| `forDateRange` | `:142-149` | `BriefingEvaluationController:68` | `SELECT` over `evaluation_scored_current` by date range |
| `cachedOnlyViewsForDateRange` | `:169-173` | `ForecastController:184` | same query; the whole "cached-only, avoid the N+1" contortion (`:151-167`) evaporates because there is one source |
| `getScoresForEnrichment` | `:273-308` | (none in `src/main` — dead once `getScoresForEnrichmentBulk` is the only caller path) | delete |
| `getScoresForEnrichmentBulk` | `:327-406` | `BriefingService:348` | one indexed range query over `evaluation_scored_current`, grouped by region in Java |

Replacement: **`EvaluationReadService`, ~170 lines**, whose entire job is *shape* — turning
`EvaluationScoredCurrentEntity` rows into `LocationEvaluationView` / `BriefingEvaluationResult`. No
precedence rule, no freshness comparison, no in-memory cache, no rehydration.

`BriefingEvaluationService` (665 lines) goes with it. Everything in it is either the merge's other
half or a gate: the `ConcurrentHashMap` cache (`:75`), `persistToDb` (`:627-664`),
`rehydrateCacheOnStartup` (`:509-561`), `logEvaluationDeltas` (`:393-445`, feeding the
`evaluation_delta_log` table already on §4.3's drop list), `hasFreshEvaluation` (`:186-192`, the
`SKIPPED_CACHED` gate, deleted by correction 2), and `recordCacheHealthHeartbeat` (`:590-613`, a
tripwire for a table that will no longer exist). Its remaining readers —
`BriefingGlossService:178,342`, `BriefingRollupBuilder:212,241`, `PipelineRunPickService:200` — all
call `getCachedScores(regionName, date, targetType)` and are re-pointed at `EvaluationReadService`.

`CacheKeyFactory` (85 lines) goes too: a `"region|date|targetType"` key only exists because
`cached_evaluation` is region-grained. The spine is per-location.

### The bug `EvaluationViewService` is carrying, which the new schema removes by construction

`EvaluationViewService.java:527-535` states:

> "`forecast_run_at` is a naive `LocalDateTime`; it is recorded in `LONDON`, not UTC, so it must be
> zoned before it can be compared with an `Instant`. Comparing it raw would be silently out by an
> hour through BST…"

and `:543` acts on it: `return forecastRow.getForecastRunAt().atZone(LONDON).toInstant();`

**That is false, and I checked rather than believed it.** `forecast_run_at` has exactly one writer in
the entire codebase — `grep -rn "forecastRunAt(\|setForecastRunAt" src/main/java` returns a single
assignment — and it is `ForecastService.java:644`:

```java
.forecastRunAt(LocalDateTime.now(ZoneOffset.UTC))
```

The column has no DB default (`V1__create_forecast_evaluation.sql:9`:
`forecast_run_at TIMESTAMP NOT NULL`). It is UTC.

**Live effect.** Through BST — late March to late October, i.e. the whole photographic season —
`forecastRunInstant()` returns an instant **one hour earlier than the truth**. That value feeds
`cachedIsAtLeastAsFresh` (`:518-525`), which decides whether a cached rating may still speak for a
slot. Making the forecast row look an hour older makes the cache win the merge for an extra hour —
which is exactly the failure the method's own Javadoc (`:440-447`) says it was written to stop: a
stale 4★ cached rating served over a current row triaged on 87–99% solar low cloud. The fix is not to
correct the conversion; it is that `evaluation_event.forecast_run_at` is `TIMESTAMPTZ` and the whole
comparison disappears with the merge.

This also determines the backfill: `AT TIME ZONE 'UTC'`, not `'Europe/London'`. Taking the Javadoc at
its word would have shifted all 25,730 verified rows by an hour.

### 4.4.9a Harness retarget — the three changes

```java
// CloudVerificationRepository, retargeted. Note the EXPLICIT outcome filter, which
// EvaluationEventQueryDisciplineTest requires and which is the entire §2.4 fix in one line.
@Query("SELECT new com.gregochr.goldenhour.model.VerificationCandidate("
        + " e.id, e.locationLat, e.locationLon, e.azimuthDeg, e.solarEventTime, e.targetType)"
        + " FROM EvaluationEventEntity e"
        + " WHERE e.outcome = 'EVALUATED'"          // <-- explicit, never defaulted
        + " AND e.targetDate <= :cutoff"
        + " AND e.azimuthDeg IS NOT NULL"
        + " AND e.solarEventTime IS NOT NULL"
        + " AND e.solarLowCloud IS NOT NULL"
        + " AND NOT EXISTS (SELECT 1 FROM CloudVerificationEntity v"
        + "                  WHERE v.evaluationEventId = e.id)"
        + " ORDER BY e.targetDate ASC, e.id ASC")
List<VerificationCandidate> findUnverified(@Param("cutoff") LocalDate cutoff, Limit limit);
```

Note `e.locationLat` / `e.locationLon` replacing today's `e.location.lat` / `e.location.lon`
(`CloudVerificationRepository.java:38`): the event carries the coordinates the forecast used, so a
later edit to `locations.lat` no longer moves the sampling point of a past forecast. That is a
verification-correctness fix, not a refactor.

The FK rename needs its own migration, because `cloud_verification.forecast_evaluation_id` is
`NOT NULL UNIQUE` with `ON DELETE CASCADE` to `forecast_evaluation`
(`V129__add_cloud_verification.sql:22,43-44`):

```sql
-- V141__retarget_cloud_verification_to_evaluation_event.sql
ALTER TABLE cloud_verification RENAME COLUMN forecast_evaluation_id TO evaluation_event_id;
ALTER TABLE cloud_verification DROP CONSTRAINT fk_cloud_verification_evaluation;
-- Existing rows point at forecast_evaluation ids, which are NOT evaluation_event ids.
-- Remap through the backfill's natural key rather than truncating: V130 already reset this
-- table once for the coned-sampling change and re-fetching 25,730 rows costs a week against
-- the Open-Meteo daily ceiling.
UPDATE cloud_verification cv
   SET evaluation_event_id = ee.id
  FROM forecast_evaluation fe
  JOIN evaluation_event ee
    ON ee.location_id  = fe.location_id
   AND ee.target_date  = fe.target_date
   AND ee.target_type  = fe.target_type
   AND ee.forecast_run_at = fe.forecast_run_at AT TIME ZONE 'UTC'
 WHERE cv.evaluation_event_id = fe.id;
ALTER TABLE cloud_verification
    ADD CONSTRAINT fk_cloud_verification_event
    FOREIGN KEY (evaluation_event_id) REFERENCES evaluation_event (id) ON DELETE CASCADE;
```

⚠ **Run R1 and R2 (§4.4.6) before this migration, and re-run them after.** If the backfill's natural
key is not unique — two `forecast_evaluation` rows with identical
`(location, date, type, forecast_run_at)` — the `UPDATE ... FROM` picks one arbitrarily and orphans
the other. Check first:

```sql
SELECT location_id, target_date, target_type, forecast_run_at, count(*)
FROM forecast_evaluation WHERE target_type <> 'HOURLY'
GROUP BY 1,2,3,4 HAVING count(*) > 1;   -- must return 0 rows before V141 runs
```

`findVerifiedPairs` and `countVerifiedInWindow` (`CloudVerificationRepository.java:93-114`) change
only their `FROM` clause and gain the same explicit `outcome = 'EVALUATED'` predicate.
`CloudVerificationService` itself needs **no change** — it consumes `VerificationCandidate` and
`CloudVerificationPair`, whose shapes are unchanged.

### 4.4.9b `survivor_atmosphere` becomes redundant by construction

`survivor_atmosphere` (V115, +V123, +V124) exists solely because survivors have no
`forecast_evaluation` row — its migration header says so at `V115:4-7`. The spine gives every
survivor a row with the same readings, so the table's reason to exist ends. The columns are already
carried in the V139 DDL (`snow_depth_m`, `freezing_level_m`, `surge_*`, `humidity_pct`,
`temperature_c`, `aerosol_optical_depth`, `dust`, `pm2_5`) precisely so this retirement is mechanical:
re-point `SurvivorSignalReader` (`:77`) at `evaluation_scored_current`, then drop the table, entity
(118 lines), writer (122) and repository (51). **Follow-on, Phase 4b — not part of this section's
change.** I have carried the columns; I have not designed the cut-over.

---

## 4.4.10 Deletion ledger

Production classes deleted by this section (Phases 0–4b), with verified line counts:

| Class / file | Lines | Phase |
|---|---|---|
| `service/EvaluationViewService.java` | 629 | 4 |
| `service/BriefingEvaluationService.java` | 665 | 4 |
| `entity/CachedEvaluationEntity.java` | 66 | 4 |
| `repository/CachedEvaluationRepository.java` | 54 | 4 |
| `service/evaluation/CacheKeyFactory.java` | 85 | 4 |
| `entity/EvaluationDeltaLogEntity.java` + repository | 70 + 10 | 3 |
| `entity/ForecastRunDispositionEntity.java` | 97 | 4 |
| `service/batch/ForecastDispositionService.java` | 158 | 4 |
| `repository/ForecastRunDispositionRepository.java` | 65 | 4 |
| `model/DispositionBreakdownResponse.java` | 60 | 4 |
| `ForecastResultHandler` cache flush/merge methods (`:289-336`) | ~60 | 4 |
| `entity/SurvivorAtmosphereEntity.java` | 118 | 4b |
| `service/evaluation/SurvivorAtmosphereWriter.java` | 122 | 4b |
| `repository/SurvivorAtmosphereRepository.java` | 51 | 4b |
| **Gross production lines deleted** | **2,310** | |
| New production code (`EvaluationEventEntity` ~330, `EvaluationEventWriter` ~180, read entities + repositories ~200, `EvaluationReadService` ~170) | **−880** | |
| **Net production reduction** | **≈ 1,430** | |

Test lines retired or rewritten (reported separately, since the field asks for production lines):
`EvaluationViewServiceTest` 1,291 · `BriefingEvaluationServiceTest` 907 ·
`BriefingEvaluationServiceCacheFreshnessTest` 135 · `ForecastDispositionServiceTest` 266 = **2,599**.

Deferred to the very end, gated on §4.4.1b (wildlife re-home) and §4.4.6 (reconciliation):
`ForecastEvaluationEntity` 239, its six `@Embeddable` clusters (`TideDetails`,
`DirectionalCloudDetails`, `CloudApproachDetails`, `StormSurgeDetails`, `InversionDetails`,
`TriageDetails`), and `ForecastEvaluationRepository`.

Schema objects dropped: `cached_evaluation` (V91), `forecast_run_disposition` (V101),
`evaluation_delta_log` (V97), the `disposition_cleanup` scheduler row (V101:43-48),
`survivor_atmosphere` (V115). Config keys dropped:
`photocast.forecast-score.dual-write` becomes moot once `forecast_score` and the spine reconcile
(not designed here); `photocast.survivor-atmosphere.write` goes with V115.

### New tests, named

- `EvaluationEventDualWriteIntegrationTest` (Testcontainers) — a full nightly cycle writes one
  decision event per candidate and one terminal event per submitted request; `cached_evaluation` and
  `evaluation_event` agree. Precedent: `ForecastScoreDualWriteIntegrationTest`.
- `EvaluationEventProjectionIntegrationTest` — `retryEventSupersedesItsPrecursor`,
  `skipDoesNotSupersedeAPriorScore`, `skyAndBluebellCoexistForOneSlot`,
  `sameTransactionInsertsDoNotTie`.
- `EvaluationEventSchemaIntegrationTest` — views are views; CHECK constraints exist;
  `idx_evaluation_event_verifiable`'s predicate matches the harness query.
- `EvaluationEventQueryDisciplineTest` — the build gate (§4.4.8), three tests.
- `EvaluationEventCopyForwardCompletenessTest` — reflects over `EvaluationEventEntity`'s `@Column`
  names and asserts every observation column appears in the copy-forward statement or in the explicit
  score-side exclusion list. This is what stops a column added in 2027 from being silently dropped on
  every batch-scored event.
- `EvaluationEventOutcomeTest` — no live writer emits `TRIAGED` after the triage deletion.
- `CloudVerificationRepositoryTest` — existing `@DataJpaTest`, retargeted; must **move** to the
  Testcontainers slice because `DISTINCT ON` and the partial index do not exist on H2.

JaCoCo note: `EvaluationEventEntity` is a wide entity with Lombok accessors — Lombok-generated
methods are excluded from JaCoCo only if `lombok.config` sets `lombok.addLombokGeneratedAnnotation`.
**UNVERIFIED** — I did not check for a `lombok.config` in the tree. If it is absent, the 80%-per-class
rule will bite this class hard and the fix is the config flag, not deleting fields.

---

## 4.4.11 Order of operations

1. **V139** `evaluation_event` + both views + seven indexes. Additive, unread.
2. **Dual-write on** behind `photocast.evaluation-event.write` (default true). Four call sites plus
   `submitBuckets`. No behaviour change.
3. **V140** backfill from `forecast_evaluation` (+ optional display-only `cached_evaluation` shred).
4. **Duplicate-key check**, then **V141** retarget `cloud_verification`. Re-run R1/R2.
5. Run **R1–R6** for seven consecutive cycles.
6. **Only now**: delete triage (§4.4.0). Record the measured marginal cost in the commit message.
7. Switch reads to `evaluation_scored_current`; delete `EvaluationViewService` and
   `BriefingEvaluationService`; drop `cached_evaluation`.
8. Phase 4b: retire `survivor_atmosphere`. Then, after the wildlife re-home, `forecast_evaluation`.

Steps 5 and 6 are the ones that must not be reordered. Everything else is negotiable.