# Cirrus levels at the far point: record 250 and 200 hPa cloud

**Status:** reviewed 2026-10-31 (one read-only adversarial reviewer against the code; no blockers, three should-fix folded in). Nothing built. One PR.
**Builds on:** `far-cloud-capture-plan.md` §7 (T7, V176), which records pressure-level cloud at the
226 km far-solar point from 850 to 300 hPa. This plan extends that list upward by two levels, on the
same terms: **data only, never prompted, never read by triage, never a rating input.**
**Feeds:** `far-cloud-lid-plan.md` H5 (lid versus canvas by height).

---

## 1. Why

V176's top level is 300 hPa, about 9 km. UK cirrus commonly sits at 250–200 hPa (about 10–12 km),
so a far point under a cirrus deck can read 0 at every stored level while Open-Meteo's single-level
`cloud_cover_high` (8 km and above) reads high. A height analysis then cannot tell "cirrus above the
top level" from "no cloud".

**Production, 2026-10-31** (`forecast_evaluation`, every row with levels stored, 25,644 rows):

| | Rows |
|---|---|
| Far mid or high ≥ 30 | 13,111 |
| … with all six levels 0 | 1,718 (13%) |
| … of those, high only (far mid < 30) | **1,475 (86%)** |
| … of those, mid ≥ 30 | 243 |

So the blind rows are almost all high-only, which fits cirrus above 300 hPa.

**Will 250 and 200 hPa see it? Only about half the time.** The owner ran Open-Meteo's forecast API
(`best_match`) on 2026-10-31 at six UK points over 8 days (1,152 hours):

| | Hours |
|---|---|
| `cloud_cover_high` ≥ 30 | 384 |
| … with 500, 400 and 300 hPa all 0 | 144 |
| … where 250 or 200 hPa is above 0 | **78 (54%)** |

"Above 0" is a lenient test: a single spot sample at 54.6 N 3.0 W read high 100 with 200 hPa at only
4–13%, and at 12:00 the same day high 100 with 300, 250 and 200 hPa all 0. Open-Meteo derives
pressure-level cloud differently from the single-level layer bands, and it under-reports thin cirrus
even at the right height.

**The decision this plan asks for:** the change halves a blind spot at almost no cost (§3), but it
does not close it. About 46% of high-only hours stay invisible to every level. That limit goes into
`far-cloud-lid-plan.md` in the same PR (§5), so H5's reader does not treat "all levels 0" as "no
cloud above the line".

**Not the reason, stated so nobody infers it:** the Angel of the North sunset on 2026-10-31 that
started this enquiry (forecast 3★, every mid and high reading 0, broken altocumulus seen and lit)
is not explained or fixed by this plan. That run's single-level high was 0 too.

---

## 2. What changes

**Fetch.** `OpenMeteoClient.PRESSURE_LEVEL_CLOUD_PARAMS` gains `cloud_cover_250hPa,cloud_cover_200hPa`
at the end (eight variables). This is the existing separate, best-effort far-point request: shares
only the `open-meteo` rate limiter, no retry, no circuit breaker, stops at its first failed chunk,
90 s budget. Nothing about that changes, and no rating-affecting request is touched.

- Variable count goes from 6 to 8. Open-Meteo weights a request by variable count above 10 (and by
  days and locations; see `big-seas-plan.md` ~l.98 and `inversion-v2-plan.md` ~l.162), and this
  request sends no `forecast_days` or `past_days`, so 8 stays at weight 1 per location, as 6 does.
  **Confirm in the PR** from one real run's response, as T7a did.
- A refusal of the widened request loses all eight levels for that run (as a refusal of the six
  does today); it cannot reach directional cloud, the prompt or triage.

**Response model.** `OpenMeteoForecastResponse.Hourly` gains `cloudCover250hPa` and
`cloudCover200hPa` (`List<Integer>`, `@JsonProperty("cloud_cover_250hPa")` / `"_200hPa"`), placed
after `cloudCover300hPa` (~l.67).

**Reader.** `CloudPointCacheReader.PRESSURE_LEVEL_COUNT` becomes 8; `readPressureLevels` fills
indices 6 and 7 (250, 200) through `OpenMeteoResponseParser.integerAt`, keeping its order
(850 → 200), its failure isolation and its all-null return on any exception. Every level array is
already sized by the constant (`CloudPointCacheReader` ~l.51, ~l.69; `OpenMeteoService` ~l.746,
~l.775) and no loop runs over it. Update the Javadoc of both the constant (~l.32) and
`readPressureLevels` (~l.38–47, including "array of six values").

**Record.** `DirectionalCloudData` gains `farSolarCloud250hPa` and `farSolarCloud200hPa` after
`farSolarCloud300hPa` (20 components).
- **No 18-argument overload is added.** Only the 12-argument constructor's code changes (six
  trailing nulls become eight); its Javadoc "six pressure-level values" becomes "eight". The
  7-argument constructor delegates to the 12-argument one and needs no code change.
- The two canonical call sites in main (`OpenMeteoService` ~l.694–700, `CloudPointCacheReader`
  ~l.155–163) pass `levels[6]` and `levels[7]`. These are the only two; a missed one fails to
  compile.
- Javadoc: the class's "eleven trailing far-point components" (~l.15) becomes thirteen; `@param` for
  both.
- `OpenMeteoService`'s **INFO** line (~l.702–717) prints all eight levels. ⚠️ `durationMs` is its
  last argument: add two `{}` placeholders **and** the two arguments `farSolarCloud250hPa()`,
  `farSolarCloud200hPa()` before `durationMs`, or SLF4J silently prints the duration as a level.

**Entity.** `DirectionalCloudDetails` gains `farSolarCloud250` / `farSolarCloud200`
(`@Column(name = "far_solar_cloud_250hpa")` / `"_200hpa"`), placed after `farSolarCloud300`
(~l.99–101) with the same "(recorded as data only)" field comment, and mapped in `from(...)`.
Its class Javadoc (~l.16, "850 to 300 hPa") becomes "850 to 200 hPa".

**Migration.** Next free number (V209 at the time of writing; read it from the tree), on V176's
pattern: one `ALTER TABLE forecast_evaluation` adding `far_solar_cloud_250hpa INTEGER` and
`far_solar_cloud_200hpa INTEGER`, nullable, no default, no backfill. A header comment cites this plan
and says why (§1). No `cloud_verification` change: the ERA5 archive returns null for pressure-level
cloud (checked 2026-10-13).

**Javadoc elsewhere.** Every "850 to 300 hPa" / "six levels" mention in `OpenMeteoClient`
(`PRESSURE_LEVEL_CLOUD_PARAMS`, `fetchPressureLevelCloudBatch`), `OpenMeteoService` (~l.318, ~l.601)
(including "That call's failure leaves only those six" at ~l.601) and `CloudVerificationService`
(~l.46) becomes "850 to 200 hPa" / "eight". Grep
`grep -rn "300 hPa\|six pressure\|six levels\|six null\|six values\|those six" backend/src/main`
and fix each.

---

## 3. Cost and risk

- **Open-Meteo quota:** no extra request; the same request carries two more variables (see the
  weighting check above).
- **Database:** two nullable integer columns on `forecast_evaluation`; ADD COLUMN with no default is
  metadata-only on Postgres.
- **Ratings:** none. `FarCloudCaptureInvarianceTest` must cover the new components (§4).
- **Rollback:** safe at any time, no deploy order needed. Nothing persists or caches
  `DirectionalCloudData` as JSON: the only reader is the eval-fixture loader, which is lenient
  (`SkyRatingEvalFixtures` ~l.175, `FAIL_ON_UNKNOWN_PROPERTIES=false`), and the only cache is
  in-memory Caffeine. Production runs `ddl-auto: validate`, so an older image accepts a schema with
  two extra columns.

---

## 4. Tests (Sonnet writes; the existing classes are the pattern)

- `OpenMeteoClientTest.fetchPressureLevelCloudBatch_requestsExactlySixLevels` (~l.430–439):
  **rewrite, don't add beside it.** Rename to `…requestsExactlyEightLevels`, update the literal to the
  eight variables in order, assert `hasSize(8)`. (`OpenMeteoClientInversionProfileTest` ~l.154
  compares against the constant and needs no edit.)
- `OpenMeteoForecastResponseTest` (~l.121/134): extend the existing case so the two new fields
  deserialise from `cloud_cover_250hPa` / `cloud_cover_200hPa`.
- `CloudPointCacheReaderTest`: eight levels read in order; a response missing the two new series
  yields null at 6 and 7 and the first six unchanged; the all-null failure path returns eight nulls.
- `OpenMeteoServiceTest`: the live reader passes 250 and 200 through to the record; a failing level
  call leaves every non-level field identical (extend the existing case).
- **Three tests call the 18-argument constructor and stop compiling:** `DirectionalCloudDataJsonTest`
  ~l.62, `ForecastEvaluationDetailsTest` ~l.95, `ForecastEvaluationRepositoryTest` ~l.128. Move each
  to 20 arguments and assert 250 and 200 too.
- `DirectionalCloudDataJsonTest`: round trip of the 20-component record. "Old 18-component JSON"
  is built by serialising a 20-argument record and stripping the `farSolarCloud250hPa` and
  `farSolarCloud200hPa` keys (as the existing test strips keys, ~l.78–80); it, and old 12- and
  7-component JSON, deserialise with the new components null.
- `ForecastEvaluationRepositoryTest` (`@DataJpaTest`, H2, runs locally): extend
  `farPointPressureLevels_persistAndReload` (~l.122) and `…twelveArgumentRecord_reloadsNulls`
  (~l.145) so 250 and 200 persist, reload, and stay null from the 12-argument record. This is the
  entity round trip.
- `ForecastEvaluationDetailsTest`: extend `from_mapsPressureLevelFields` (~l.91) and
  `from_twelveArgumentRecord_leavesPressureLevelsNull` (~l.108).
- `FarCloudCaptureInvarianceTest` is reflective (canonical constructor from
  `getRecordComponents()`), so it covers the new components with no new case. Raise the floor at
  ~l.79 to `>= 13` (message: "T1 five, T7 six levels, cirrus two") and change "six" to "eight" in
  its class Javadoc (~l.24). No other edit.
- **New** `integration/FarSolarCirrusLevelCloudMigrationTest`, copied from
  `FarSolarPressureLevelCloudMigrationTest`'s raw-JDBC pattern with Flyway targets `208` then `209`:
  the two columns exist as nullable `INTEGER`, and a row planted at V208 reads null. Do **not** add
  the new columns to the V176 test's array (V176 didn't add them). **Proven in CI only** (no Docker
  locally).
- Update the stale "six" `@DisplayName`s: `CloudPointCacheReaderTest` ~l.132, ~l.158;
  `OpenMeteoServiceTest` ~l.847; `ForecastEvaluationDetailsTest` ~l.91, ~l.108;
  `ForecastEvaluationRepositoryTest` ~l.122, ~l.145.
- No prompt-regression file is touched (they use the 7-argument constructor).

Local gates: the backend build ladder in `CLAUDE.md`, ending with the full
`clean verify -Dtest='!**/integration/**'` (JaCoCo per-class 80%, SpotBugs). No frontend change.

---

## 5. Docs in the same PR

- `docs/engineering/reference/architecture-decisions.md`, the directional cloud bullet: "850, 700,
  600, 500, 400 and 300 hPa" → add 250 and 200, with the new migration and column names, and one
  sentence: pressure-level cloud sees only about half of single-level high-only cirrus even with 250
  and 200 hPa (owner sample, 2026-10-31), so "all levels 0" is not "no cloud".
- `docs/engineering/reference/database-migrations.md`: a row for the new migration.
- `docs/engineering/far-cloud-lid-plan.md` H5: a dated note under the H5 paragraph stating §1's limit.
  **H5 itself is pre-registered and is not edited**: its levels (600/500/400) and threshold stay.
- `docs/engineering/far-cloud-capture-plan.md` §7: a one-line pointer to this plan.
- `changelog.d/YYYYMMDD-far-cloud-cirrus-levels.md`, headed `### Added — …`.

---

## 6. Out of scope

- **`scripts/far-cloud-p1/`** (`export_forecasts.sql`, `premise.py`'s fixed `COLUMNS` list, the
  fixture CSV, `openmeteo.py`'s `PRESSURE_VARS`). They pin the current six; adding columns there
  means fixture changes. Follow up only when P1 needs the new levels. `size.py` and
  `tests/test_size.py` read only H5's 600/500/400 hPa and are unaffected.
- Levels at the 113 km cone (an open question from T7, unchanged).
- 150 hPa and above: above the UK tropopause most of the year; not worth a column.
- Any use of the levels in a prompt, triage or rating.
- ERA5 verification of the levels (the archive serves none).

---

## 7. Build

Per `CLAUDE.md`'s workflow: a Sonnet subagent in its own worktree on `feature/far-cloud-cirrus-levels`
builds §2–§5 and runs the gates until green; the planning session reviews the diff read-only; one ready
PR against `main`; merge on green CI and Codex 👍 on the head commit.
