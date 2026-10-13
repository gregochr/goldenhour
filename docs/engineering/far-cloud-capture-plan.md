# Far cloud capture (far-cloud-lid-plan P2): a delegation plan

Status: plan, 2026-10-13. Parent: `docs/engineering/far-cloud-lid-plan.md` §6 P2 (and §4 S0, S1).
Written to be handed task by task to Sonnet subagents, with one orchestrating session that briefs,
checks and merges.

## 1. Why, in one paragraph

The P0 replay (2026-10-13, N = 20, three models) found that no model, on any input variant, gave the
Angel of the North 13 Oct sunset its observed 2★: the prompt carried nothing about the bank to the
west. Sonnet 5.5's 4★ rested entirely on the 91% high cloud (high 91 → 0% took every model to 3★;
low 7 → 29% changed nothing). Whether a far deck was a lid or a canvas is a question the prompt
cannot answer, because at the 226 km far point we keep **low cloud only** and discard mid and high,
which the same Open-Meteo call already returns. This phase starts recording them, **data only**: the
prompt stays byte-identical, nothing a reader sees changes. It is what P1 (retrospective sizing) and
any later rule need to stand on, whichever model is configured.

## 2. Scope

**In:**
- **S1, mid and high cloud at the existing 226 km far-solar point.** The forecast reading goes into
  `forecast_evaluation`, and the ERA5 reading (already fetched by `ARCHIVE_HOURLY`) into
  `cloud_verification`.
- **S0, pressure-level cloud** at the far point. T0 found the forecast serves it and the archive does
  not, so it is captured as forecast-only (task T7, its own PR).

**Out, and must not change** (parent plan §7):
- the prompt text, for every model;
- the 113 km cone rules, THIN STRIP, FAR CORRIDOR, the veto demotion and the far low reading's role;
- the basic tier, every DTO and the API wire format, and the frontend;
- `deleteIncompleteVerifications`: the new columns must not be added to it (parent §6 P2.5), or the
  next backfill re-walks the 33k-row history for nothing;
- any prompt-regression assertion.

**Deliberate divergence from the parent plan:** P2.4 says "extend `CloudVerificationRepository`'s
projection". No reader of the new ERA5 fields exists until P1, and the projection is a JPQL
constructor expression whose record (`CloudVerificationPair`) every report bucket uses. So this
phase **stores** the ERA5 far mid and high and leaves the projection for P1, which will read them.

## 3. Facts the tasks rely on (checked 2026-10-13 at `5e712759`)

**The record.** `model/DirectionalCloudData` is a 7-component record:
- solar low/mid/high and antisolar low/mid/high, all `int`;
- `Integer farSolarLowCloudPercent`.

It has 28 `new DirectionalCloudData(` call sites, 11 test files among them, including the
prompt-regression sources.

**The two readers.** Both read the far point positionally, as index 4 of
`DirectionalSamplingGeometry.computeDirectionalCloudPoints`:
- `OpenMeteoService.fetchDirectionalCloudData` (~l.545–632, `responses.get(4)`): the far read is in
  its own try/catch, so a failed far read nulls only the far low.
- `CloudPointCacheReader` (~l.70–95, `points.get(4)`): it has **no** inner try. Any exception there
  (for example a null `getCloudCoverMid()` list) reaches the outer catch and **nulls the whole
  `DirectionalCloudData`**. A new mid/high read must not be able to do that.

**The far point already carries mid and high.** It is fetched with
`OpenMeteoClient.CLOUD_ONLY_PARAMS` = `cloud_cover_low,cloud_cover_mid,cloud_cover_high`. No
`models=` is sent, so Open-Meteo answers with `best_match`.

**Persistence.**
- `entity/DirectionalCloudDetails` is the embeddable on `forecast_evaluation`; its far field is
  `far_solar_low_cloud`, added in V53.
- Its single mapper is `DirectionalCloudDetails.from(DirectionalCloudData)`, called only by
  `ForecastService.buildEntity` (~l.839).
- No DTO and no frontend reads `farSolarLow`.

**Verification.**
- `CloudVerificationService.applyFarSolar` (~l.457–478) keeps only `farLowCloud`.
- `ARCHIVE_HOURLY` already requests mid and high for every point.
- The `deleteIncompleteVerifications` query is at `CloudVerificationRepository` ~l.60–68.

**Migrations.**
- The latest migration is **V174**, so the next is V175. Re-read the tree before writing it: a
  parallel PR may take the number.
- The pattern to copy is `integration/ForecastEvaluationPromptMigrationTest` (V164, columns on
  `forecast_evaluation`). Migrations are proven by CI only.

**Prompt fidelity.** `service/evaluation/LowHauxleyPromptFidelityTest` already pins one stored
production prompt byte for byte.

**Rollback.** `PromptTestService` (~l.92) and `ModelTestService` (~l.94) read stored
`AtmosphericData` JSON with a `new ObjectMapper()` that does not ignore unknown properties.
- After this change, rows they write carry the new fields, so a build rolled back to before this
  change cannot replay them.
- Setting ignore-unknown in this change protects only future rollbacks, not one to an older build.

**S0: the forecast serves it, the archive does not** (T0, run by the owner 2026-10-13, at 54.9 N
3.5 W).
- **Forecast API, default `best_match`:** `cloud_cover_850hPa`, `_700hPa`, `_600hPa`, `_500hPa`,
  `_400hPa` and `_300hPa` all return numbers. They differ from the low/mid/high aggregates; for
  example at 12:00 the response had mid 100 with 700 hPa 81 and 600 hPa 54.
- **ERA5 archive, default model:** the same variables come back with units `"undefined"` and every
  value `null`.

So S0 can be captured but **cannot be verified against ERA5** from this archive request. P1 can
compare it only with ERA5's low/mid/high aggregates.

**`CLOUD_ONLY_PARAMS` is shared well beyond the directional points.** `fetchCloudOnlyBatch` is
called by:
- the directional fetch;
- `OpenMeteoService` ~l.367 and ~l.690;
- `NorthwardTransectSampler` (aurora);
- `OverheadCloudSampler`;
- `BriefingWeatherLoader`.

T7 must not widen all of them.

## 4. Orchestration

One orchestrating session (Opus) runs the tasks in this order:

```
T0 (owner, done)  ──────────────────────────────┐
T1 record + readers ──┬─> T2 persistence + V175 ─┼─> T3 ERA5 store ─> T6 integrate, review, PR
                      └─> T4 prompt invariance ──┘         (T5 rollback note rides T6)
                                                  └─> T7 S0 (forecast only, T0 answered; its own PR)
```

- **One branch**, `feature/far-cloud-capture`, from `main`. Each task commits on it; there is no
  push until T6.
- **Sequential by default.** T2 and T4 touch disjoint files and may run in parallel, each in its own
  worktree (`isolation: "worktree"`), with the orchestrator cherry-picking both. Do not run T3 before
  T2: T2 owns the single migration, and T3 uses its columns.
- **Each subagent gets:**
  - its task card, verbatim;
  - §3's facts;
  - `CLAUDE.md`;
  - `docs/engineering/test-improvement-standards.md`;
  - the environment notes below.

  It returns the diff summary, the exact commands run with their exit codes, and anything it could
  not verify.
- **The orchestrator verifies each task before starting the next.** Re-run the task's tests, read
  the diff and check the "must not" list. A subagent's "done" is a claim, not evidence.
- **Read-only reviewers.** Review agents never write to the working tree (`CLAUDE.md`, UI Work).
  Mutation probes run in a worktree.

**Environment notes for every backend task.** In a cloud session:
- **JitPack is blocked.** Install the vendored jar under the POM's coordinates:
  `./mvnw install:install-file -Dfile=.m2/repository/com/gregochr/solar-utils/2.1.0/solar-utils-2.1.0.jar -DgroupId=com.github.gregochr -DartifactId=solar-utils -Dversion=2.1.0 -Dpackaging=jar`
- **Only JDK 21 is installed.** Compile with `-Djava.version=21 -Dmaven.compiler.release=21`, with
  `src/test/java/com/gregochr/goldenhour/entity/EvaluationModelTest.java` moved aside for the run
  and restored afterwards; check the restore with `cmp`.
- **Gate on Maven's exit code** (`>/tmp/x.log 2>&1; echo "exit: $?"`), never on grep.
- **Always exclude the integration tests:** `-Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false`.
  There is no Docker, so the Testcontainers migration test runs in CI only.

**Model for subagents:** Sonnet, the default. Each card is written to be finished without design
choices. A subagent that hits a design choice stops and reports it rather than choosing.

## 5. Task cards

### T0 — check S0 availability (the owner; done 2026-10-13, answer in §3)

Two requests, no key needed:

```bash
curl -s "https://api.open-meteo.com/v1/forecast?latitude=54.9&longitude=-3.5&hourly=cloud_cover_low,cloud_cover_mid,cloud_cover_high,cloud_cover_850hPa,cloud_cover_700hPa,cloud_cover_600hPa,cloud_cover_500hPa,cloud_cover_400hPa,cloud_cover_300hPa&forecast_days=1" | head -c 1500
curl -s "https://archive-api.open-meteo.com/v1/archive?latitude=54.9&longitude=-3.5&start_date=2026-10-01&end_date=2026-10-01&hourly=cloud_cover_low,cloud_cover_700hPa,cloud_cover_500hPa,cloud_cover_300hPa" | head -c 1500
```

- **Yes:** each `cloud_cover_*hPa` array holds numbers (not all `null`) in both responses.
- **No:** an error, or arrays of `null`.

Paste both outputs to the orchestrator. T7 runs only on "yes" for the forecast. If the archive says
no, T7 captures the forecast side alone and records that it cannot be verified against ERA5.

### T1 — the record and both readers (Sonnet)

**Goal:** `DirectionalCloudData` carries far mid and far high, filled by both readers, with nothing
else changed.

**Do:**
1. Add two trailing components to `DirectionalCloudData`, `Integer farSolarMidCloudPercent` and
   `Integer farSolarHighCloudPercent`. Javadoc them as "recorded, not prompted (far-cloud-lid-plan
   P2)".
2. Add a public 7-argument constructor with the current parameter list that delegates with
   `null, null`. **No existing call site may change**: `git diff --stat` must touch no file outside
   the two readers, the record and new tests.
3. Name the far point's index. Add `DirectionalSamplingGeometry.FAR_SOLAR_POINT_INDEX = 4` and
   `ANTISOLAR_POINT_INDEX = 3`, with Javadoc tying them to `computeDirectionalCloudPoints`' order.
   Use them in both readers in place of the literals 3 and 4. Pin the order with a test against
   `computeDirectionalCloudPoints`.
4. In `OpenMeteoService.fetchDirectionalCloudData`, read far mid and high from the same far response
   at the same `farIdx`, inside the existing far try/catch.
   - Each layer is read through a null-safe helper that returns `null` for a null list, an index out
     of range or a null element.
   - A missing mid or high must not null the far low, and vice versa.
   - Add `M{}% H{}%` for the far point to the existing INFO line.
5. In `CloudPointCacheReader`, do the same.
   - **The far block gets its own try/catch**, as the live reader's has, so no far read can null the
     whole result.
   - The live reader's existing behaviour for every other point is unchanged.

**Tests (JUnit, Mockito, AssertJ):**
- For each reader:
  - far mid and high populated from the far response;
  - far mid and high null when the far response's mid or high list is null, or holds a null at the
    index;
  - the far low unaffected by a broken mid list.
- For the cache reader specifically: a broken far point leaves the cone and antisolar values intact
  and returns non-null. This is new behaviour; name it in the test.
- The 7-argument constructor yields null for both new components.

**Must not:**
- touch `PromptBuilder` or `CoastalPromptBuilder`;
- edit an existing test's assertions;
- edit anything under `src/test/java/**/regression/` or the `prompt-regression` sources.

**Done:** the touched classes' tests pass, Checkstyle passes, and each touched class is above 80%
line coverage (JaCoCo).

### T2 — persistence and the one migration (Sonnet; after T1)

**Goal:** the forecast-side far mid and high land in `forecast_evaluation`, and the migration also
adds the ERA5 columns T3 needs.

**Do:**
1. Write `V175__far_solar_mid_high_cloud.sql`, after checking that 175 is still free. All four
   columns are nullable `INTEGER`, no defaults, no backfill:
   ```sql
   ALTER TABLE forecast_evaluation ADD COLUMN far_solar_mid_cloud INTEGER;
   ALTER TABLE forecast_evaluation ADD COLUMN far_solar_high_cloud INTEGER;
   ALTER TABLE cloud_verification  ADD COLUMN far_mid_cloud INTEGER;
   ALTER TABLE cloud_verification  ADD COLUMN far_high_cloud INTEGER;
   ```
   (`CloudVerificationEntity` is `@Table(name = "cloud_verification")`, checked 2026-10-13.)
2. `DirectionalCloudDetails`:
   - add fields `farSolarMid` (`far_solar_mid_cloud`) and `farSolarHigh` (`far_solar_high_cloud`),
     with the class's existing annotations and Javadoc style;
   - map them in `from(...)`;
   - keep `orEmpty`/`EMPTY` semantics.
3. Do **not** add the fields to any DTO, `ForecastDtoMapper` or the API.
4. Write `integration/FarSolarMidHighCloudMigrationTest`, copying `ForecastEvaluationPromptMigrationTest`'s
   shape. It asserts that the four columns exist, are nullable integers, and that an existing row
   reads null for them.
5. Add one `changelog.d/20261013-far-cloud-capture.md` entry, `### Added — …` (`changelog.d/README.md`
   lists the allowed categories). Say that nothing user-visible changes.

**Tests:**
- `DirectionalCloudDetails.from` maps both new fields and maps nulls to nulls.
- An H2 persistence test (default test profile, `create-drop`) saves a `ForecastEvaluationEntity`
  through `ForecastService.buildEntity`'s path, or the nearest existing persistence test, and reads
  both values back.

**Must not:** touch `CloudVerificationRepository.deleteIncompleteVerifications`.

**Done:**
- the unit and H2 tests pass;
- the migration test compiles; it runs in CI only, and the subagent says so rather than claiming it
  passed;
- Checkstyle passes.

### T3 — store the ERA5 far mid and high (Sonnet; after T2)

**Goal:** each new `cloud_verification` row records the archive's far mid and high, without
re-walking history.

**Do:**
1. Add `farMidCloud` (`far_mid_cloud`) and `farHighCloud` (`far_high_cloud`) to
   `CloudVerificationEntity`, next to `farLowCloud`, in its style.
2. In `CloudVerificationService.applyFarSolar`:
   - also set `farMidCloud(layerAt(hourly.getCloudCoverMid(), idx))` and the same for high;
   - update the method's Javadoc: low remains the only layer the reports read; mid and high are
     recorded for far-cloud-lid-plan P1.
3. **Leave `deleteIncompleteVerifications` exactly as it is.** Add a test that pins this:
   - a row with `farLowCloud` set and null mid/high is not cleared by the query;
   - or, if the existing repository tests do this by query text, assert the query string does not
     mention `farMid`/`farHigh`.
4. Leave `CloudVerificationRepository`'s projection and `CloudVerificationPair` unchanged (§2's
   divergence).

**Tests:**
- `applyFarSolar` records all three layers from one archive response;
- it records nulls for missing layers;
- it records nothing when the archive is null or empty (the existing guard);
- the pinning test from step 3.

**Done:** the tests pass, Checkstyle passes, the coverage rule holds, and
`git diff -- src/main/java/**/CloudVerificationRepository.java` is empty.

### T4 — the prompt does not move (Sonnet; after T1; parallel with T2)

**Goal:** prove the new fields cannot reach the prompt.

**Do:**
1. Write `service/evaluation/FarCloudCapturePromptInvarianceTest`. For **both** `PromptBuilder` and
   `CoastalPromptBuilder`, build one realistic `AtmosphericData` twice:
   - once with `farSolarMid = farSolarHigh = null`;
   - once with them set to deliberately loud values (95 and 90).

   Assert that `getSystemPrompt()` and `buildUserMessage(...)` are identical strings. Use the
   builders' real entry points as `LowHauxleyPromptFidelityTest` does, and cover a THIN STRIP case
   and a FAR CORRIDOR case as well as a plain one.
2. Run `LowHauxleyPromptFidelityTest` and the default-profile `PromptBuilder*` tests unchanged.

**Must not:** change any builder. If the test fails, the subagent stops and reports which field leaks
and where.

**Done:** the new test passes, and the fidelity test passes untouched.

### T5 — rollback note (the orchestrator, inside T6's PR)

- In the PR description, state the §3 rollback fact: after deploy, prompt-test and model-test rows
  written by the new build will not replay on a build from before it.
- Set `FAIL_ON_UNKNOWN_PROPERTIES` false on both `PromptTestService` and `ModelTestService` mappers,
  each with a one-line comment. This protects later rollbacks.
- Add one test per service that deserialises JSON carrying an unknown property.

This is small enough to do in T6 rather than hand off.

### T6 — integrate, review, open the PR (the orchestrator)

1. **Gate:**
   - the full backend verify with the integration exclusion, reading the exit code;
   - Checkstyle;
   - SpotBugs;
   - JaCoCo;
   - `git diff --stat` against `main`, checked against every "must not" above.
2. **Adversarial review:** about four read-only lenses —
   - runtime behaviour (the cache-reader change in particular);
   - persistence and migration;
   - prompt invariance;
   - conventions and Javadoc.

   Each charge then goes to a refuter that defaults to REFUTED. Fix what survives.
3. **`CLAUDE.md`:** one clause in the *Directional cloud sampling* bullet: "mid and high at 226 km
   are recorded (`far_solar_mid_cloud`/`_high_cloud`, V175) and ERA5-verified, never prompted
   (far-cloud-lid-plan P2)". Add a V175 row to the migrations table.
4. **Parent plan:** mark §6 P2 S1 as built, with the PR number, and record T0's S0 answer in §4.
5. **Push and open the PR** when the owner asks for it (`CLAUDE.md` Git conventions). Body:
   - what is recorded;
   - what is deliberately not: the prompt, the projection, the backfill;
   - that the migration is proven by CI;
   - the rollback note.

   Then watch CI and fix what fails.

### T7 — S0 pressure-level cloud at the far point (Sonnet; its own PR after T6 merges)

T0 answered (§3): the forecast serves pressure-level cloud and the ERA5 archive does not. So this is
**capture-only, with no verification**.

**Goal:** record pressure-level cloud at the 226 km far point. The prompt stays byte-identical, and
no other cloud call changes.

**Do:**
1. Add six fields to `OpenMeteoForecastResponse.Hourly`: `cloudCover850hPa`, `…700hPa`, `…600hPa`,
   `…500hPa`, `…400hPa` and `…300hPa`. Each is a `List<Integer>` with
   `@JsonProperty("cloud_cover_850hPa")` and so on, in the class's existing style. The class already
   ignores unknown properties.
2. Add a new constant, `OpenMeteoClient.DIRECTIONAL_CLOUD_PARAMS` =
   `CLOUD_ONLY_PARAMS` + `,cloud_cover_850hPa,cloud_cover_700hPa,cloud_cover_600hPa,cloud_cover_500hPa,cloud_cover_400hPa,cloud_cover_300hPa`.
   That is nine variables, within the 10 per location that keep the call at one unit of
   Open-Meteo's weighted cost. Add a new `fetchDirectionalCloudBatch(coords)` that uses it.
   **`CLOUD_ONLY_PARAMS` and `fetchCloudOnlyBatch` stay exactly as they are.**
3. Switch to the new fetch only on the paths that build directional points:
   - `OpenMeteoService.fetchDirectionalCloudData`;
   - the two prefetchers `CloudPointCacheReader` reads from (`BatchWeatherPrefetcher` and
     `ForecastCommandExecutor.prefetchCloudPoints`; enumerate their fetch calls first).

   **Stop and report** if a prefetcher fetches directional and non-directional points in one call.
   Splitting that call is a design choice the orchestrator makes.
4. `DirectionalCloudData` gains six nullable trailing components (`farSolarCloud850hPa` …
   `farSolarCloud300hPa`), read from the far response by the same null-safe helper T1 added, and
   the 7-argument and T1's 9-argument constructors keep working.
5. Write one migration, the next free number, adding six nullable `INTEGER` columns to
   `forecast_evaluation`: `far_solar_cloud_850hpa` … `far_solar_cloud_300hpa`. Map them in
   `DirectionalCloudDetails`, and add a Testcontainers migration test as in T2.
6. No ERA5 change: the archive returns null for these variables. Record that in the class Javadoc,
   so nobody later adds them to `ARCHIVE_HOURLY` expecting values.

**Tests:**
- the new fetch requests exactly the nine variables;
- `fetchCloudOnlyBatch` still requests three, and each non-directional caller still uses it;
- both readers fill the six levels and tolerate missing lists;
- T4's invariance test, extended with the six fields set loud.

**Must not:**
- touch the 113 km cone or antisolar points' stored values. Their responses will now carry the
  levels too, but nothing reads them in this task; whether to store them is P1's question;
- change the prompt;
- add a DTO field;
- change any other sampler.

**Done:** as T1–T4. Also check an Open-Meteo response fixture with the nine variables
(`src/test/resources`) parses.

## 6. After this phase

- **Deploy.** Rows written from then on carry far mid and high. Readings from before the deploy stay
  null; no backfill.
- **P1** (parent §6) reads them alongside ERA5 once there are enough rows: its pre-registered 80
  firings at the rate 4★ sunsets occur.
- Any rule (parent P4) is decided from P1's numbers, and is then proven by the P0 harness replaying
  stored prompts before and after.
