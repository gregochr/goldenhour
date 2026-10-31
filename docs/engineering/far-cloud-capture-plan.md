# Far cloud capture (far-cloud-lid-plan P2): a delegation plan

Status: plan, 2026-10-13, revised after an adversarial review the same day (§8). T7 built 2026-10-13 (V176) with the resilience isolation recorded in §7. Parent:
`docs/engineering/far-cloud-lid-plan.md` §6 P2 (and §4 S0, S1). Written to be handed card by card to
Sonnet subagents, with one orchestrating session that briefs, checks and merges.

## 1. Why

The P0 replay (2026-10-13, N = 20, three models) produced three results.

**No input variant reached the observed 2★.** No model, on any variant of the prompt, gave the
Angel of the North's 13 Oct sunset the 2★ the owner observed. The prompt carried nothing about the
bank to the west.

**Sonnet 5.5's 4★ rested entirely on the 91% high cloud.**
- With high cloud set from 91 to 0%, every model gave 3★.
- Raising low cloud from 7 to 29% changed nothing.

**The prompt could not tell a lid from a canvas.** Whether a far deck was one or the other is a
question it cannot answer, because at the 226 km far point we keep **low cloud only**. We discard
mid and high, which the same Open-Meteo call already returns.

This phase starts recording them, **as data only**: the prompt stays byte-identical, and so do
triage, the ratings and everything a reader sees.

**What the data is for.** P1 (parent §6) sizes history from a *reconstruction*: Open-Meteo's
Previous Runs or Historical Forecast APIs on the forecast side, ERA5 on the other. What this phase
adds to P1 is a **validity check** on the variables its hypotheses actually test. Today the
reconstruction can be checked against stored values only for the cone and the far low. From the
deploy onwards it can also be checked against the far mid and high, for every row written after it.
P1 should therefore include a window after the deploy.

The stored rows are a prospective record for P4's outcome arm. They are not P1's sizing path: P1
does not wait for them to accrue.

## 2. Scope

**In:**
- **S1: mid and high cloud at the existing 226 km far-solar point**, at the event's hour. The
  forecast reading goes into `forecast_evaluation`. The ERA5 reading, which `ARCHIVE_HOURLY` already
  fetches, goes into `cloud_verification`.
- **The far point's other hour: low, mid and high at the slot on the dark side of the event.** That
  is the hour after a sunset, or the hour before a sunrise.
  - The stored slot comes from `TimeSlotUtils.findBestIndex`: the latest hour at or before a sunset,
    or the earliest at or after a sunrise. So the after-glow (sunset) and the pre-glow (sunrise) are
    never inside it.
  - The other hour is already in the same response, so it costs no extra call. Without it, a later
    rule cannot interpolate to the event minute from stored rows (parent §8, timing).
- **S0, pressure-level cloud at the far point**, as its own PR straight after PR #A (T7, §7). It is
  forecast-only: the ERA5 archive returns null for these variables (§3). It feeds the parent plan's
  H5.

**Out, and must not change** (parent plan §7):
- the prompt text, for every builder and every model, including the woodland and bluebell builders;
- triage (`WeatherTriageEvaluator`) and which reading it uses;
- `CloudPointCacheReader`'s existing all-or-nothing behaviour for the far LOW read. It must be pinned
  by a test, not changed (§3, the readers);
- the 113 km cone rules, THIN STRIP, FAR CORRIDOR, the veto demotion and the far low reading's role;
- the basic tier, `ForecastEvaluationDto` and `ForecastDtoMapper`, every briefing payload, and the
  frontend;
- `deleteIncompleteVerifications`: the new columns must not be added to it (parent §6 P2.5), or the
  next backfill re-walks the 33k-row history for nothing;
- `CloudVerificationRepository`'s projection and `CloudVerificationPair` (P1 adds its own read);
- any file carrying `@Tag("prompt-regression")`, and any prompt-regression assertion.

**One wire-format exception, stated rather than hidden.** Two admin-only payloads will carry the new
keys with no DTO edit:
- `PromptTestResultDto.atmosphericDataJson`;
- `ModelTestResultDto.atmosphericDataJson`.

`PromptTestService` and `ModelTestService` serialise the whole `AtmosphericData` into that field.
`PromptTestView.jsx` reads it with `JSON.parse`, which ignores extra keys.

**One deliberate divergence from the parent plan.** P2.4 says "extend `CloudVerificationRepository`'s
projection". Nothing reads the new ERA5 fields until P1, so this phase **stores** them and leaves the
projection alone.

## 3. Facts the tasks rely on

Checked at `e160d8fa` (2026-10-13) by five read-only reviewers and the orchestrator.

**The record**
- `model/DirectionalCloudData` is a 7-component record:
  - solar low, mid and high, all `int`;
  - antisolar low, mid and high, all `int`;
  - `Integer farSolarLowCloudPercent`.
- It has no methods.
- There are 28 `new DirectionalCloudData(` call sites: two in main (the two readers) and 26 across
  11 test files.
- Five of those test call sites are in `service/evaluation/PromptRegressionTest.java`, which is
  `@Tag("prompt-regression")` and must not be edited.
- `AtmosphericData.with*` passes the record by reference.

**The two readers.** Both index the far point positionally, as point 4 of
`DirectionalSamplingGeometry.computeDirectionalCloudPoints`. The antisolar point is index 3. The
class has only `SOLAR_CONE_POINT_COUNT` (l.58), no index constants.

- **The live reader**, `OpenMeteoService.fetchDirectionalCloudData` (~l.545–632,
  `responses.get(4)`), reads the far point inside its own try/catch, so a failed far read nulls only
  the far low.
- **The cache reader**, `CloudPointCacheReader` (a `public final` static utility, ~l.70–99,
  `points.get(4)`), has **no** inner try:
  - a far low read that throws (null `hourly`, null low list, short array) reaches the outer catch
    and returns null for the whole `DirectionalCloudData`;
  - `ForecastDataAugmentor.augmentWithDirectionalCloud` (l.114–123) then leaves the data with **no**
    directional cloud;
  - so the prompt has no DIRECTIONAL CLOUD block, and triage reads the observer's low cloud instead
    (`WeatherTriageEvaluator` l.38–41).
- **That outcome is a rating input.** Changing it to "keep the rest" would change prompts and triage
  for those rows, so it stays exactly as it is in this phase.
- A missing far cell, as opposed to a malformed one, already leaves only the far low null (l.82).

**The null-safe reader**
- `OpenMeteoResponseParser.getIntegerValue(List<Integer>, int)` (private static, ~l.389) already
  returns null for a null list or a too-large index. It does not guard a negative index.

**The fetches**
- `OpenMeteoClient.CLOUD_ONLY_PARAMS` = `cloud_cover_low,cloud_cover_mid,cloud_cover_high` (l.60).
  No `models=` parameter is sent anywhere, so Open-Meteo answers with `best_match`.
- `CLOUD_ONLY_PARAMS` is used by `fetchCloudOnly` (single point, l.232) and `fetchCloudOnlyBatch`.
- `fetchCloudOnlyBatch` is called by:
  - `OpenMeteoService.prefetchCloudBatch` (~l.346–399, its call at l.367);
  - `fetchDirectionalCloudData` (l.574);
  - the cloud-approach fetch (l.690);
  - `NorthwardTransectSampler` (aurora);
  - `OverheadCloudSampler`;
  - `BriefingWeatherLoader`.
- **`prefetchCloudBatch` is the directional prefetch.** Both prefetchers call it:
  `BatchWeatherPrefetcher` (~l.115–147) and `ForecastCommandExecutor.prefetchCloudPoints`
  (~l.533–562).
- Each passes one list holding the five directional points AND the upwind point. It deduplicates by
  grid cell across every location into one call, so one cached cell can be one location's far point
  and another's cone or upwind point.
- The same cache feeds `CloudPointCacheReader.fetchCloudApproachDataFromCache` (cone trend and
  upwind).

**Persistence**
- `entity/DirectionalCloudDetails` is an `@Embeddable` on `forecast_evaluation`, with `@Builder` and
  no positional `new` callers. Its far field is `farSolarLow` (`far_solar_low_cloud`, V53).
- `DirectionalCloudDetails.from(...)` has one caller, `ForecastService.buildEntity` (l.839, private).
  That method reaches it on every path: batch via `persistPendingEvaluation` (l.626), triage rows,
  synchronous and retry.
- **Only triaged or submitted slots get a row**, so no row exists for:
  - Gate-4 skips;
  - region cache skips;
  - intraday no-refresh skips;
  - canopy-lane slots.
- So stored data is complete for T+0 and T+1, and biased toward settled regimes at T+2.
- `ForecastDtoMapper` (~l.481) maps explicit fields and never reads the far fields.

**Verification**
- `cloud_verification` (`@Table(name = "cloud_verification")`, `@Builder`) is written by the hourly
  `cloud_verification_backfill` job (V131, `0 20 * * * *`, ACTIVE) about `ARCHIVE_LAG_DAYS` (~6)
  after the target date.
- `CloudVerificationService.applyFarSolar` (private, ~l.467–478) keeps only `farLowCloud`, through
  the null-safe `layerAt` (~l.531).
- `ARCHIVE_HOURLY` = low, mid and high (l.58), for every point.
- `deleteIncompleteVerifications` is at `CloudVerificationRepository` l.64–68 and lists explicit
  columns. `CloudVerificationRepositoryTest` (`@DataJpaTest`) exercises it at l.106–144.

**Migrations**
- The latest is **V174**. No remote branch holds a V175, as of the review.
- The pattern for a test that must see a row from *before* the migration is
  `integration/SchedulerSolarOffsetColumnsMigrationTest`: its own container and
  `Flyway.configure().target(...)`.
- `IntegrationTestBase` runs every migration first, so a test built on it cannot plant an old row.
- In production, `validate-on-migrate: false` and `out-of-order: true` are set, and `ddl-auto` is
  `validate`. So an older build tolerates applied V175 columns.
- A nullable `ADD COLUMN` with no default is catalogue-only on Postgres. It still takes a brief
  ACCESS EXCLUSIVE lock.

**Stored JSON**
- `AtmosphericData` JSON is deserialised by three readers:
  - `PromptTestService` (mapper l.92, read l.321);
  - `ModelTestService` (mapper l.94, read l.409);
  - `eval/SkyRatingEvalFixtures` (l.175–195).
- The first two use `new ObjectMapper()` with unknown properties failing. The third already ignores
  unknown properties.
- `daily_briefing_cache`, `cached_evaluation` and `forecast_evaluation_prompt` do not carry the
  record.
- Jackson prefers a record's canonical constructor. Secondary constructors are expected to be safe,
  and T1 tests it.

**Prompts**
- `PromptBuilder` reads the record only through named accessors (l.518–541, `isThinStrip` l.854).
  `CoastalPromptBuilder` overrides only `getSystemPrompt()`.
- `buildUserMessage` has two overloads (l.450 with surge, l.474 without). `BatchRequestFactory`
  (~l.394–399) and `ClaudeEvaluationStrategy` (~l.96–98) use the surge one.
- `WoodlandPromptBuilder` and `BluebellPromptBuilder` also take `AtmosphericData`.

**S0** (T0, the owner, 2026-10-13, at 54.9 N 3.5 W)
- The forecast API (`best_match`) returns numbers for `cloud_cover_850hPa` to `_300hPa`, and they
  differ from the aggregates: at 12:00, mid 100 with 700 hPa 81 and 600 hPa 54.
- The ERA5 archive's default model returns units `"undefined"` and all `null`.
- Not tried: `models=era5`, and lead times beyond day 1.

## 4. Orchestration

```
T0 (owner, done)
T1 record + readers ──┬─> T2 persistence + V175 ──> T3 ERA5 store ──┐
                      └─> T4 invariance ───────────────────────────┴─> T5 + T6 integrate, review, PR #A
                                                                        (merge, then DEPLOY)
after PR #A merges ──> T7a fetch ──> T7b record, store, invariance ──> T7c integrate, PR #B
```

**Branch.** One branch per PR. PR #A uses `feature/far-cloud-capture`, from `main`. Commit after
each card. No push until T6, and then only when the owner asks.

**Parallelism.** After T1 is committed, the orchestrator names its SHA and creates two worktrees
from that SHA (`isolation: "worktree"`). T2 runs in one and T4 in the other.
- T4's diff must be exactly one new test file.
- Cherry-pick T2's commit, then T4's, and re-run the gate.
- Everything else is sequential.

**What every subagent's brief contains:**
- its card, verbatim;
- §2 and §3, verbatim;
- `CLAUDE.md`;
- `docs/engineering/test-improvement-standards.md`;
- the rules below.

**The rules every subagent follows:**
- Stage named paths only. Never `git add -A`, `git add .` or `commit -a`.
- Never edit `backend/pom.xml`, `.mvn/` or `backend/.m2/`.
- Never push, and never tag.
- Conventional commit message, ending with the session's attribution lines.
- Do not edit any file carrying `@Tag("prompt-regression")`, or any existing test's assertions.
- If the card leaves a design choice open, or a fact in §3 is wrong, stop and report. Do not choose.
- Report back:
  - the diff summary (`git status --porcelain` and `git diff --stat HEAD~1`);
  - every command run, with its exit code;
  - each touched class's line coverage from `target/site/jacoco/jacoco.csv`;
  - anything you could not verify.

**The gate.** One command, run in every card and in T6. Exit 0 is the pass:

```bash
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress \
  -Djava.version=21 -Dmaven.compiler.release=21 \
  -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"
```

- Never judge it by grepping the log.
- Drop the two JDK flags where JDK 25 is installed.
- The integration tests (Testcontainers) run in CI only. Say so; do not claim they passed.

**Cloud-container setup, done once per worktree:**
- Install solar-utils if `~/.m2/repository/com/github/gregochr/solar-utils/2.1.0` is absent:
  `./mvnw install:install-file -Dfile=.m2/repository/com/gregochr/solar-utils/2.1.0/solar-utils-2.1.0.jar -DgroupId=com.github.gregochr -DartifactId=solar-utils -Dversion=2.1.0 -Dpackaging=jar`.
- Under JDK 21 only, set aside `EvaluationModelTest`, which needs the Java 24 classfile API:
  1. **copy** `backend/src/test/java/com/gregochr/goldenhour/entity/EvaluationModelTest.java` to the
     scratchpad;
  2. delete it for the Maven run only;
  3. copy it back;
  4. confirm with `cmp` against the scratchpad copy.
- **Before every commit**,
  `git status --porcelain -- backend/pom.xml backend/src/test/java/com/gregochr/goldenhour/entity/EvaluationModelTest.java`
  must print nothing.

**The orchestrator verifies each card before starting the next.**
- Re-run the card's tests and the gate.
- Read the diff against the card's "must not" list.
- Check §2's must-not list.

A subagent's "done" is a claim, not evidence.

**Reviewers.** Review agents read only. A mutation probe runs only with `isolation: "worktree"`, and
only after the orchestrator has committed.

**Model.** Sonnet for T1–T4 and T7a/T7b. The orchestrator, on Opus, does T5, T6 and T7c.

## 5. Task cards for PR #A

### T1 — the record and both readers

**Goal:** `DirectionalCloudData` carries the far point's mid and high at the event hour, and its
low, mid and high at the dark-side hour. Both readers fill them. No existing behaviour changes.

**Do:**

1. **Append five components to `DirectionalCloudData`**, in this order, after
   `farSolarLowCloudPercent`, all `Integer`:
   - `farSolarMidCloudPercent`
   - `farSolarHighCloudPercent`
   - `farSolarLowCloudPercentDarkHour`
   - `farSolarMidCloudPercentDarkHour`
   - `farSolarHighCloudPercentDarkHour`

   Add a `@param` for each to the record's class Javadoc. Checkstyle's JavadocType checks a record's
   `@param` tags against its components. Each `@param` says "recorded, never prompted
   (far-cloud-lid-plan P2)". The dark-hour ones also say "the hourly slot on the dark side of the
   event: the one after the stored slot for a sunset, the one before it for a sunrise".
2. **Add a public 7-argument constructor** with today's parameter list, delegating with five nulls.
   Give it full Javadoc with seven `@param` tags. **No existing call site changes.**
3. **Add two named indices.** In `DirectionalSamplingGeometry`, add
   `public static final int ANTISOLAR_POINT_INDEX = 3` and `FAR_SOLAR_POINT_INDEX = 4`. Javadoc each,
   tying it to the order `computeDirectionalCloudPoints` returns. Use them in both readers in place of
   the literals 3 and 4.
4. **Add the null-safe reader.** In `OpenMeteoResponseParser`, add a new **public** static method
   `integerAt(List<Integer> values, int idx)`. It returns null for a null list, a negative index, an
   index at or past the size, or a null element. Javadoc it. Rewrite the private `getIntegerValue`
   to call it; its existing callers must behave identically.
5. **Name the dark-side index.** Add a public static method on `TimeSlotUtils`,
   `darkSideIndex(int bestIdx, TargetType targetType)`, returning `bestIdx + 1` for SUNSET and
   `bestIdx - 1` for SUNRISE. Javadoc it, and say the caller range-checks through `integerAt`.
6. **The live reader**, `OpenMeteoService.fetchDirectionalCloudData`:
   - inside the existing far try/catch, after the far low read, read far mid and high at `farIdx`
     and low, mid and high at `darkSideIndex(farIdx, targetType)`;
   - every new read goes through `integerAt`;
   - add the far `M{}% H{}%` and the dark-hour `L/M/H` to the existing INFO line;
   - update the method's Javadoc.
7. **The cache reader**, `CloudPointCacheReader`:
   - **leave the existing far LOW read and the outer catch exactly as they are**;
   - after the far low read, read the five new values through `integerAt`, which cannot throw;
   - **add no new try/catch**;
   - update the class's Javadoc: a malformed far point still nulls the whole result, unchanged, and
     the new values are null-safe;
   - also cover the case where `farF` is null (no cell): every far field stays null and the rest is
     returned, as today.

**Tests:**
- **Create `service/CloudPointCacheReaderTest`.** It is a public static API, so test it directly:
  - the five new values filled from a far response;
  - null mid or high lists, null elements, and a dark index outside the array: those values null,
    the rest unchanged;
  - **a pin of the old behaviour:** a far response whose LOW list is null returns null for the
    whole `DirectionalCloudData`. Name the test so it says this is deliberate.
- **In `OpenMeteoServiceTest`**, beside `fetchDirectionalCloudData_returnsCloudAtBothPoints`
  (~l.730): the five values filled; a broken far mid list leaves the far low set.
- **Create `service/DirectionalSamplingGeometryTest`.** The point at `FAR_SOLAR_POINT_INDEX` is
  226 km (±1 km, `GeoUtils`) from the observer along the solar azimuth, and the point at
  `ANTISOLAR_POINT_INDEX` is 113 km along azimuth + 180.
- **`integerAt` and `darkSideIndex` edge cases:** create `service/OpenMeteoResponseParserIntegerAtTest`
  for `integerAt`, and extend `util/TimeSlotUtilsTest` for `darkSideIndex`.
- **A Jackson round trip**, in a new `model/DirectionalCloudDataJsonTest`, using a mapper configured
  like `PromptTestService`'s (`new ObjectMapper().registerModule(new JavaTimeModule())`):
  - a 12-component record survives serialise then deserialise with every value intact;
  - JSON holding only the 7 old keys deserialises with the five new values null.

**Must not:**
- touch any prompt builder;
- touch `WeatherTriageEvaluator`;
- change the far low read or any catch in either reader;
- edit an existing test's assertions;
- edit a `prompt-regression` file.

`git status --porcelain` may list only:
- `DirectionalCloudData`, `DirectionalSamplingGeometry`, `OpenMeteoResponseParser`, `TimeSlotUtils`,
  `OpenMeteoService` and `CloudPointCacheReader`;
- test files.

**Done:** the gate exits 0, and each touched main class is at or above 80% line coverage.

### T2 — persistence and the one migration

**Goal:** the five forecast-side values land in `forecast_evaluation`, and the same migration adds
the five ERA5 columns T3 needs.

**Do:**

1. **`V175__far_solar_cloud_capture.sql`**, after checking 175 is still free. All columns are nullable
   `INTEGER`, with no default and no backfill. Use one statement per table, so each takes one lock:
   ```sql
   ALTER TABLE forecast_evaluation
       ADD COLUMN far_solar_mid_cloud INTEGER,
       ADD COLUMN far_solar_high_cloud INTEGER,
       ADD COLUMN far_solar_low_cloud_dark INTEGER,
       ADD COLUMN far_solar_mid_cloud_dark INTEGER,
       ADD COLUMN far_solar_high_cloud_dark INTEGER;
   ALTER TABLE cloud_verification
       ADD COLUMN far_mid_cloud INTEGER,
       ADD COLUMN far_high_cloud INTEGER,
       ADD COLUMN far_low_cloud_dark INTEGER,
       ADD COLUMN far_mid_cloud_dark INTEGER,
       ADD COLUMN far_high_cloud_dark INTEGER;
   ```
2. **`DirectionalCloudDetails`.** Add the fields `farSolarMid`, `farSolarHigh`, `farSolarLowDark`,
   `farSolarMidDark` and `farSolarHighDark`, mapped to the five forecast columns, in the class's
   existing annotation and Javadoc style. Map them in `from(...)`. Keep `orEmpty`/`EMPTY`.
3. Do **not** add them to `ForecastEvaluationDto`, `ForecastDtoMapper` or any API.
4. **`integration/FarSolarCloudCaptureMigrationTest`**, on `SchedulerSolarOffsetColumnsMigrationTest`'s
   pattern (its own container, `Flyway.configure().target(...)`):
   - migrate to `"174"`;
   - insert one `locations` row, one `forecast_evaluation` row, and one `cloud_verification` row with
     its NOT NULL columns (`forecast_evaluation_id`, `horizon_sample_lat`/`_lon`, `verified_at`; read
     the V-files that created the table for the full list);
   - migrate to `"175"`;
   - assert that all ten columns are `integer`/`is_nullable = 'YES'` in `information_schema`, and
     that both planted rows read null in every one of them.
5. **`changelog.d/20261013-far-cloud-capture.md`**, headed `### Added — …`. It covers both the
   forecast and the ERA5 capture, and says that nothing user-visible changes.

**Tests:**
- **In `repository/ForecastEvaluationRepositoryTest`** (`@DataJpaTest`), add two cases. In each,
  save an entity, flush, clear and reload it by id:
  - one whose `.directionalCloud(DirectionalCloudDetails.from(record))` was built from a record with
    all five new values set; assert them;
  - one built from a 7-argument record; assert nulls.
- **In `entity/ForecastEvaluationDetailsTest`**, test the `from()` mapping of the five fields.

**Must not:** touch `CloudVerificationRepository` or `CloudVerificationPair`.

**Done:**
- the gate exits 0;
- the migration test compiles (CI runs it; say so);
- the coverage rule holds.

### T3 — store the ERA5 far values

**Goal:** each new `cloud_verification` row records the archive's far mid and high, plus the
dark-hour low, mid and high, without re-walking history.

**Do:**

1. **`CloudVerificationEntity`.** Add `farMidCloud`, `farHighCloud`, `farLowCloudDark`,
   `farMidCloudDark` and `farHighCloudDark`, mapped to T2's columns and placed beside `farLowCloud`,
   in its style.
2. **`applyFarSolar`.** After the existing `farLowCloud` line:
   - set mid and high at `idx` through `layerAt`;
   - set low, mid and high at `TimeSlotUtils.darkSideIndex(idx, candidate.targetType())` through
     `layerAt`.

   `layerAt` (~l.531) already returns null for a null list or a negative or out-of-range index.

   Update the Javadoc: low remains the only layer any report reads, and the rest is recorded for
   far-cloud-lid-plan P1.

**Tests:**
- **In `CloudVerificationServiceTest`**, through the public verification entry point its existing
  far-low tests use, capturing the saved entity:
  - all six far values recorded from one archive response;
  - nulls for missing layers;
  - a null dark-side slot at the array's edge;
  - nothing recorded when the archive is null or empty (the existing guard).
- **In `CloudVerificationRepositoryTest`**, add
  `deleteIncompleteVerifications_keepsRowWithFarLowButNoNewFarFields`, mirroring the l.106 fixture.
  A complete row with every new field null is not deleted (the delete returns 0), and stays verified.

**Must not:**
- `git diff main...HEAD -- backend/src/main/java/com/gregochr/goldenhour/repository/CloudVerificationRepository.java backend/src/main/java/com/gregochr/goldenhour/model/CloudVerificationPair.java`
  must print nothing.

**Done:** the gate exits 0, and the coverage rule holds.

### T4 — the prompt and triage do not move

**Goal:** prove that the new fields cannot reach any prompt or triage. Prove also that the test
would notice if they did.

**Do:** create one test file, `service/evaluation/FarCloudCaptureInvarianceTest`. It must not carry
`@Tag("prompt-regression")`.

1. **Base data.** Start from `LowHauxleyFixture`'s data, the same object `LowHauxleyPromptFidelityTest`
   uses. Make three directional states with `withDirectionalCloud`. Each sets solar and far low as
   given, leaves the other cone and antisolar values as the fixture has them, and sets the five new
   values to null:
   - **plain:** solar low 10, far low 5;
   - **THIN STRIP:** solar low 70, far low 20;
   - **FAR CORRIDOR:** solar low 70, far low 65.
2. **Positive controls,** so the test cannot pass vacuously:
   - the THIN STRIP user message contains `[THIN STRIP`;
   - the FAR CORRIDOR user message contains `[FAR CORRIDOR ALSO CLOUDY`;
   - changing only `farSolarLowCloudPercent` changes the user message.

   The markers are appended at `PromptBuilder` ~l.537–541. The system prompt also mentions both
   names, so assert on the **user message** only. If these values do not produce the markers, stop
   and report.
3. **The loud variant.** For each state, build a loud variant with **every** `DirectionalCloudData`
   component outside the prompted set at 95. The prompted set is the seven original components.
   Find the other components by reflection over `DirectionalCloudData.class.getRecordComponents()`,
   so a future component is covered with no edit.
4. **Assert equal, base against loud.** For both `PromptBuilder` and `CoastalPromptBuilder`:
   - `getSystemPrompt()`;
   - `buildUserMessage(data)`;
   - `buildUserMessage(data, surge, …)`, the l.450 overload, called as `LowHauxleyPromptFidelityTest`
     calls it.

   Also for `WoodlandPromptBuilder` and `BluebellPromptBuilder`, through their public entry points.
   And `WeatherTriageEvaluator.evaluate(...)`'s result.
5. **Run `LowHauxleyPromptFidelityTest` unchanged.**

**Must not:** change any builder or evaluator. If an equality fails, stop and report which field
leaks and where.

**Done:** the new test passes, the fidelity test passes untouched, and the gate exits 0.

### T5 — replays tolerate new fields (orchestrator)

1. **`PromptTestService` and `ModelTestService` mappers.** Set
   `FAIL_ON_UNKNOWN_PROPERTIES = false`, and add a `DeserializationProblemHandler` that logs one
   WARN naming each ignored property. Then a renamed field shows up in the log instead of silently
   replaying a different prompt.
2. **One test per service:** JSON with an unknown key deserialises, and the WARN is logged.
3. **A second changelog file,** `changelog.d/20261013-replay-ignores-unknown-fields.md`, headed
   `### Changed — …` (one change per file).
4. **The rollback fact, for the PR body.**
   - After deploy, prompt-test and model-test rows written by the new build will not replay on a
     build from before it. The old build fails on the new keys, and the replay is recorded as
     failed: contained, but a failure.
   - From this build on, later rollbacks are protected.

### T6 — integrate, review, open PR #A (orchestrator)

1. **The gate,** after T5. Then check `git diff --stat main...HEAD`:
   - no deleted or renamed file under `src/test`;
   - no `pom.xml` change;
   - nothing touched on §2's must-not list.
2. **An adversarial review.** Four read-only lenses:
   - runtime (the readers, the cache-reader pin);
   - persistence and migration;
   - invariance (does T4 cover every builder?);
   - conventions and Javadoc.

   Each charge goes to a refuter that defaults to REFUTED. Fix what survives.
3. **`CLAUDE.md`.**
   - In the *Directional cloud sampling* bullet, add: "mid and high at 226 km, and low/mid/high at
     its dark-side hour, are recorded on `forecast_evaluation` (V175), with ERA5's matching values on
     `cloud_verification`. Not yet compared or reported (P1); never prompted."
   - Add the same clause to the Cloud-verification API paragraph ("Five archive points").
   - Add a V175 row to the migrations table.
4. **Parent plan:** mark §6 P2 S1 as built, with the PR number.
5. **When the owner asks, push and open the PR.** The body covers:
   - what is recorded;
   - what is deliberately not changed: the prompt, triage, the cache-reader behaviour, the
     projection, the backfill;
   - the admin JSON exception;
   - the rollback fact;
   - that the migration is proven by CI;
   - "deploy outside the nightly backup window" (the brief ACCESS EXCLUSIVE lock).

   Then watch CI to green.

## 6. After PR #A deploys

- **Recorded from the deploy on.** Rows written after it carry the new forecast values. ERA5 values
  follow about 6 days later, through the hourly backfill job, for target dates after the deploy.
- **Nothing is backfilled.** Rows before the deploy stay null, and so do rows written during any
  rollback.
- **P1 counts only rows where both sides are non-null.**
- **Stored rows cover T+0 and T+1 fully.** At T+2 they are biased toward settled regimes (§3), so T+2
  sizing needs the reconstruction.
- **Any rule,** P4 in the parent plan, is decided from P1's numbers. It is then proven by the P0
  harness (`PromptReplayHarnessTest`), replaying stored prompts before and after.

## 7. T7 — S0 pressure-level cloud (PR #B, straight after PR #A)

*See also `far-cloud-cirrus-levels-plan.md`: 250 and 200 hPa added later (V209), data only.*

**Decided by the owner, 2026-10-13: T7 runs straight after PR #A.** An earlier deferral (the same
evening) was withdrawn after two checks:

- **The Historical Forecast API** serves pressure-level cloud for past dates, but it stitches each
  run's first hours, so it rebuilds roughly T+0.
- **The Previous Runs API** rejects pressure-level variables
  (`cloud_cover_700hPa_previous_day1`: "invalid String value").

So no source recovers the T+1 levels production saw. Stored levels are the only fair way to
validate a height rule, and the parent plan's H5 (added the same day) depends on them.

**The decision.** What the review established:
- Both prefetchers and the live reader share one cloud request with the upwind and cone points.
- Widening that request to nine variables would put **every** forecast's directional cloud on a
  request that has never run in production. If Open-Meteo refused it, every prompt would lose its
  DIRECTIONAL CLOUD block and triage would switch source, fleet-wide.

So T7 makes a **separate, best-effort request for the far points only**, which cannot touch the
existing one. The cost is one extra Open-Meteo request per prefetch (the far cells, deduplicated),
plus one per live evaluation that has no cache. The owner accepted that cost on 2026-10-13.

**The cards:**
- **T7a (Sonnet), the fetch.** No reader changes.
  - Add `OpenMeteoClient.PRESSURE_LEVEL_CLOUD_PARAMS`, the six levels 850–300 hPa (T0's list), and
    `fetchPressureLevelCloudBatch(coords)`.
  - Add six `List<Integer>` fields to `OpenMeteoForecastResponse.Hourly`, with
    `@JsonProperty("cloud_cover_850hPa")` and so on.
  - In `prefetchCloudBatch`, the prefetchers pass the far points separately: a new parameter, both
    callers updated. Fetch their levels in a second call, failure-isolated (any exception means no
    levels), into a second map on `CloudPointCache`.
  - In `fetchDirectionalCloudData`, make the same second call for the one far point, inside its own
    try/catch.
  - **Tests:**
    - `CLOUD_ONLY_PARAMS`, `fetchCloudOnly` and `fetchCloudOnlyBatch` are unchanged, and every
      non-directional caller still uses them;
    - a failing level call leaves the existing seven values and the five T1 values identical (the
      prompt-affecting fields);
    - the request carries exactly six variables.
  - Record one real run's response size and Open-Meteo's weighted count in the PR.
- **T7b (Sonnet), store and prove.**
  - `DirectionalCloudData` gains six components after the dark-hour ones, 850 to 300:
    `farSolarCloud850hPa` … `farSolarCloud300hPa`.
  - Add a public 12-argument constructor (T1's canonical list) delegating with six nulls. Keep the
    7-argument one, now delegating to the 12-argument one. Javadoc both.
  - Readers fill the levels from the second map or call, through `integerAt`.
  - Next free migration: six nullable `INTEGER` columns, `far_solar_cloud_850hpa` …
    `far_solar_cloud_300hpa`, one `ALTER TABLE`. Embeddable fields `farSolarCloud850` …
    `farSolarCloud300`.
  - `integration/FarSolarPressureLevelCloudMigrationTest`, on T2's pattern.
  - T4's reflection-driven test covers the new components with no edit. Run it.
  - The Jackson round trip from T1, extended: old 7- and 12-component JSON both deserialise.
  - Nothing goes to ERA5, because the archive returns null. Say so in `CloudVerificationService`'s
    Javadoc, so nobody adds the levels to `ARCHIVE_HOURLY`.
- **T7c (orchestrator), PR #B.**
  - Gate, review and `CLAUDE.md` (the clause and the migrations row).
  - `changelog.d/YYYYMMDD-far-cloud-pressure-levels.md`, headed `### Added — …`.
  - The rollback note: rolling back to PR #A is safe only if PR #A's build (with T5) is
    **deployed**, not merely merged, before PR #B deploys.
- **Optional first, 2 min:** repeat T0's archive request with `&models=era5`, to confirm "not
  verifiable" before writing it into Javadoc.

Open, recorded rather than decided:
- whether to store the 113 km cone's levels. Parent §4 says 850–700 hPa there matters for a low
  bank;
- whether 900 hPa beats 850 hPa.

Both are P1 questions.

**As built (T7c).** The pressure-level request is isolated from the rating-affecting fetches: it shares only the `open-meteo` rate limiter (the quota is shared on purpose), and carries no retry and no circuit breaker, so it can never open the breaker the rating-affecting fetches use. It stops at its first failed chunk (one WARN, no 61 s rate-limit backoff, remaining cells null) and at a 90 s budget (`PRESSURE_LEVEL_FETCH_BUDGET`, remaining cells null, one INFO line). When the cloud prefetch itself fails, no level request is made and the old empty cache is returned. `CloudPointCacheReader` reads the levels even when the far cloud cell is missing, matching the live reader. A logging failure never discards fetched levels, and a call that yields no cell is logged as failed.

## 8. Review record (2026-10-13)

Five read-only reviewers (factual accuracy, runtime and rating safety, persistence and rollback,
delegability, fitness for P1), then the orchestrator's checks against the code.

**Accepted and folded in:**
- **T1's "give the cache reader's far block its own try/catch" would have changed prompts and
  triage** for malformed far responses. Removed; the old behaviour is now pinned by a test.
- **§3 misnamed the prefetch call** (l.367 *is* the directional prefetch), and T7's
  stop-and-report was certain to fire. T7 is now designed: a separate, failure-isolated request,
  behind an owner decision.
- **A widened shared request could fail fleet-wide.** This is why T7 uses a separate request.
- **The single stored hour misses the after-glow.** Added the dark-side hour (five columns per
  table, no extra request). The owner kept it on 2026-10-13.
- **§6 misdescribed how P1 uses the data:** P1 reconstructs history, and the stored data is a
  validity check. Fixed, along with the T+2 coverage bias.
- **T4 could pass vacuously.** Added positive controls, both `buildUserMessage` overloads, the
  woodland and bluebell builders, triage, and reflection over the components.
- **The migration test could not see a pre-migration row.** It now uses the Flyway-target pattern.
- **Delegability gaps:**
  - T1's diff rule contradicted its step 3;
  - the null-safe helper had no home;
  - test placement, the gate command and the worktree base were unstated;
  - the environment steps could commit the deleted test file;
  - the protected test classes were misnamed;
  - the record Javadoc rules were missing;
  - T7 lacked PR-level steps.
- **Wire-format exception:** the admin `atmosphericDataJson` payloads gain the new keys. Stated.
- **The `CLAUDE.md` clause overclaimed** "ERA5-verified". Reworded.
- **T5:** a WARN on ignored properties, its own changelog file, and the deploy-before-T7 condition.
- **The migration lock:** one `ALTER TABLE` per table, and deploy outside the backup window.

**Refuted:**
- "ERA5 verification runs only when an admin triggers it." The hourly
  `cloud_verification_backfill` job (V131, ACTIVE) runs it.
- "`CLAUDE.md`'s migrations table stops at V173." It has a V174 row.

**Not checked by anyone:**
- Open-Meteo's weighted-cost accounting beyond its documentation;
- pressure-level availability beyond day 1, and with `models=era5`;
- Jackson's constructor choice at runtime (T1's round-trip test settles it);
- the production deploy and backup timing.
