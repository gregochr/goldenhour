# Cirrus levels at the far point: record 250 and 200 hPa cloud

**Status:** draft, 2026-10-31. Nothing built. One PR.
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
  days and locations), so 8 should cost the same weighted calls as 6. **Confirm in the PR** from one
  real run's response, as T7a did.
- A refusal of the widened request loses all eight levels for that run (as a refusal of the six
  does today); it cannot reach directional cloud, the prompt or triage.

**Response model.** `OpenMeteoForecastResponse.Hourly` gains `cloudCover250hPa` and
`cloudCover200hPa` (`List<Integer>`, `@JsonProperty("cloud_cover_250hPa")` / `"_200hPa"`).

**Reader.** `CloudPointCacheReader.PRESSURE_LEVEL_COUNT` becomes 8; `readPressureLevels` fills
indices 6 and 7 (250, 200) through `OpenMeteoResponseParser.integerAt`, keeping its order
(850 → 200), its failure isolation and its all-null return on any exception. Update its Javadoc
("six" → "eight", the level list).

**Record.** `DirectionalCloudData` gains `farSolarCloud250hPa` and `farSolarCloud200hPa` after
`farSolarCloud300hPa` (20 components).
- The 12-argument and 7-argument constructors keep their signatures and pass two more nulls.
- The two canonical call sites (`OpenMeteoService` ~l.694, `CloudPointCacheReader` ~l.155) pass
  `levels[6]` and `levels[7]`.
- Javadoc: the class's "eleven trailing far-point components" becomes thirteen; `@param` for both.
- `OpenMeteoService`'s DEBUG line (~l.704) prints all eight levels.

**Entity.** `DirectionalCloudDetails` gains `farSolarCloud250` / `farSolarCloud200`
(`@Column(name = "far_solar_cloud_250hpa")` / `"_200hpa"`), mapped in `from(...)`.

**Migration.** Next free number (V209 at the time of writing; read it from the tree), on V176's
pattern: one `ALTER TABLE forecast_evaluation` adding `far_solar_cloud_250hpa INTEGER` and
`far_solar_cloud_200hpa INTEGER`, nullable, no default, no backfill. A header comment cites this plan
and says why (§1). No `cloud_verification` change: the ERA5 archive returns null for pressure-level
cloud (checked 2026-10-13).

**Javadoc elsewhere.** Every "850 to 300 hPa" / "six levels" mention in `OpenMeteoClient`
(`PRESSURE_LEVEL_CLOUD_PARAMS`, `fetchPressureLevelCloudBatch`), `OpenMeteoService` (~l.318, ~l.601)
and `CloudVerificationService` (~l.46) becomes "850 to 200 hPa" / "eight". Grep
`grep -rn "300 hPa\|six pressure\|six levels\|six null" backend/src/main` and fix each.

---

## 3. Cost and risk

- **Open-Meteo quota:** no extra request; the same request carries two more variables (see the
  weighting check above).
- **Database:** two nullable integer columns on `forecast_evaluation`; ADD COLUMN with no default is
  metadata-only on Postgres.
- **Ratings:** none. `FarCloudCaptureInvarianceTest` must cover the new components (§4).
- **Rollback:** safe at any time. An older build ignores the two columns; the record's JSON
  (if any stored copy is read by an older build) carries two unknown properties, so confirm the
  mapper used for `DirectionalCloudData` ignores unknown properties, or that nothing persists it as
  JSON across versions. If neither holds, say so in the PR and note the deploy order.

---

## 4. Tests (Sonnet writes; the existing classes are the pattern)

- `OpenMeteoClientTest`: `PRESSURE_LEVEL_CLOUD_PARAMS` carries exactly the eight variables, in order,
  and never any of `CLOUD_ONLY_PARAMS`.
- `OpenMeteoForecastResponseTest`: the two new fields deserialise from `cloud_cover_250hPa` /
  `cloud_cover_200hPa`.
- `CloudPointCacheReaderTest`: eight levels read in order; a response missing the two new series
  yields null at 6 and 7 and the first six unchanged; the all-null failure path returns eight nulls.
- `OpenMeteoServiceTest`: the live reader passes 250 and 200 through to the record; a failing level
  call leaves every non-level field identical (extend the existing case).
- `DirectionalCloudDataJsonTest`: round trip of the 20-component record; old 18-, 12- and
  7-component JSON still deserialise with the new components null.
- `FarCloudCaptureInvarianceTest`: run it; if it enumerates components reflectively it covers the new
  ones with no edit, otherwise add them. Either way it must prove no prompt builder or triage rule
  reads them.
- `integration/FarSolarPressureLevelCloudMigrationTest` or a new
  `FarSolarCirrusLevelCloudMigrationTest` on the same pattern: the two columns exist, are nullable,
  and an entity with them set round-trips. **Proven in CI only** (no Docker locally).
- Entity mapping: `DirectionalCloudDetails.from` maps both (extend whichever test covers `from`).

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
  means fixture changes. Follow up only when P1 needs the new levels.
- Levels at the 113 km cone (an open question from T7, unchanged).
- 150 hPa and above: above the UK tropopause most of the year; not worth a column.
- Any use of the levels in a prompt, triage or rating.
- ERA5 verification of the levels (the archive serves none).

---

## 7. Build

Per `CLAUDE.md`'s workflow: a Sonnet subagent in its own worktree on `feature/far-cloud-cirrus-levels`
builds §2–§5 and runs the gates until green; the planning session reviews the diff read-only; one ready
PR against `main`; merge on green CI and Codex 👍 on the head commit.
