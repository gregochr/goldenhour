# DRAFT — slate-split

> ⚠️ **Working material, not the build source.** Written against a mix of `a484d1c4`
> (not an ancestor of `main`) and `d421ef5f`; citations of `ForecastTaskCollector.java`
> past line 534 are off by +16. Adversarial verification refuted claims in this draft —
> see `slate-split.corrections.md`. **Resolved positions live in `../all-in-build-plan.md`,
> which supersedes this file wherever they disagree.**

---

## §4.7 (build depth) — Breaking the briefing circularity

Everything below was read from the tree at `a484d1c4`. Line numbers are `file:line`. Anything I
could not check from source is marked **UNVERIFIED** with the query that would settle it.

---

### 4.7.0 The bug, restated from source

Two reads of a published artefact feed work that then rewrites it.

**Circularity 1 — selection reads publication.**
`ForecastTaskCollector.collectScheduledBatches` opens with
`DailyBriefingResponse briefing = briefingService.getCachedBriefing();`
(`ForecastTaskCollector.java:272`; the region-filtered admin twin at `:717`). That response is
`cache.get()` (`BriefingService.java:248`). The cycle tail calls `briefingService.refreshBriefing()`
(`PipelineOrchestrator.java:427`), whose success branch does `cache.set(response)`
(`BriefingService.java:543`). So the roster a cycle evaluates is the previous cycle's published
output. `BriefingService.java:100–116` documents the measured consequence: a four-date window
arrives at the nightly cycle one date short, T+3 was unreachable, and `days_ahead = -1` was one
quarter of every nightly candidate set.

**Circularity 2 — publication reads publication.** Not in the brief; found by reading.
Inside `refreshBriefing()`, hot topics are computed at `BriefingService.java:532`
(`hotTopicAggregator.getHotTopics(today, today.plusDays(3))`) — **before** `cache.set` at `:543`.
`KingTideHotTopicStrategy.java:97` and `SpringTideHotTopicStrategy.java:92` both call
`briefingService.getCachedDays()`, which returns `cache.get().days()` (`BriefingService.java:392–395`).
So every tide hot topic in a freshly built briefing is derived from the **previous** briefing's
slate. Same root cause, same fix.

The bean graph already records the tangle: `KingTideHotTopicStrategy.java:73` and
`SpringTideHotTopicStrategy.java:67` take `@Lazy BriefingService` purely to break the resulting
Spring cycle.

---

### 4.7.1 The exact free/paid boundary in `refreshBriefing()`

`refreshBriefing()` is `BriefingService.java:404–575`. The boundary is **between `:483` and `:486`**,
not where §4.7 of the summary design put it — three *free* Open-Meteo refreshes currently sit on the
wrong side of it (`:516`, `:523`) and one *free* DB read sits on the paid side (`:486`).

| Lines | Work | Cost | Half |
|---|---|---|---|
| 405–407 | log, `briefingStart`, `jobRunService.startRun(RunType.BRIEFING, false, null)` | — | slate |
| 409–412 | `today` (Europe/London), `dates` = `BRIEFING_WINDOW_DAYS` | — | slate |
| 417–419 | `colourLocations` via `isColourLocation` (`:633–646`) | 1 query | slate |
| 421–425 | empty-roster early return — `completeRun(jobRun,0,0)`, **cache untouched** | — | slate |
| 427–428 | `succeeded` / `failed` counters | — | slate |
| 433–434 | `fetchWeatherSequential` (`:787–848`) — one batched Open-Meteo call, grid-cell dedup, `captureGridCoordinates` writes `location.grid_lat/lng` (`:857–879`) | free API | slate |
| 437 | `fetchHorizonCloud` (`:893–949`) — one batched cloud-only call | free API | slate |
| 442–446 | `marineWaveRefreshService.refresh(colourLocations, dates, jobRun.getId())` | free API | slate |
| 449–480 | slot build loop; `succeeded++` / `failed++`; woodland sunset skip (`:468`) | — | slate |
| 483 | `hierarchyBuilder.buildDays(allSlots, colourLocations, dates)` (`BriefingHierarchyBuilder.java:45`) | — | slate |
| **486** | `enrichWithCachedScores(days)` → `EvaluationViewService::getScoresForEnrichment` | free **DB** | **publish** (must run *after* EVALUATE) |
| 489–491 | `glossService.generateGlosses(days, jobRun.getId())`, gated on `succeeded > 0` | **Claude** | publish |
| 493 | `headlineGenerator.generateHeadline(days)` — `BriefingHeadlineGenerator` takes only a `Clock`; no Claude | free | publish |
| 498–502 | `bestBetAdvisor.advise(days, jobRun.getId(), Map.of())`, gated on `succeeded > 0`, else `BestBetResult.noPicks()` | **Claude** | publish |
| 503–504 | aurora tonight/tomorrow summaries | cached | publish |
| 506–508 | `partialFailure = failed > 0`; `total`; `aboveThreshold = total == 0 \|\| (succeeded * 100 / total) >= 50` | — | **crosses** |
| 510–511 | `totalMs`, `circuitState()` (`:577–586`) | — | publish |
| 516–520 | `nlcClarityService.refresh(dates)` — `AtomicReference` cache, replace-in-full (`NlcClarityService.java:76`) | free API | **belongs in slate** |
| 523–530 | `meteorClarityService.refresh(dates)` — same shape (`MeteorClarityService.java:60`) | free API | **belongs in slate** |
| 532 | `hotTopicAggregator.getHotTopics(today, today+3)` — reads the *old* cache (circularity 2) | free | publish, **from the slate** |
| 533 | `bluebellGlossService.enrichGlosses(hotTopics)` | **Claude** | publish |
| 534–535 | `seasonalFeatures` | — | publish |
| 537–549 | success branch: build response, `cache.set`, `lastKnownGood.set`, `persistBriefing` (`:594–605`), `publishEvent(BriefingRefreshedEvent)`, `completeRun(jobRun, succeeded, failed, dates)` | — | publish |
| 550–574 | below-threshold branch: serve `lastKnownGood` marked `stale=true`, else partial | — | publish |

**Everything that crosses the boundary**, exhaustively:

| Crosses | Why publish needs it | Disposition |
|---|---|---|
| `days` (`:483`) | the thing being enriched, glossed and rolled up | `BriefingSlate.days` |
| `succeeded`, `failed` | gate gloss (`:489`) and best-bet (`:498`); feed `partialFailure` (`:506`), `failedLocationCount` (`:540`), `aboveThreshold` (`:508`), `completeRun` (`:547`) | `BriefingSlate.locationsSucceeded/locationsFailed` + derived accessors |
| `dates` | `completeRun(jobRun, succeeded, failed, dates)` (`:547`) | `BriefingSlate.dates` |
| `today` | `today.plusDays(3)` for hot topics (`:532`), `bluebellSeason.isActive(today)` (`:534`) | `BriefingSlate.referenceDate` |
| `jobRun` | `logApiCall(jobRun.getId(), …)` for gloss/best-bet/bluebell, and `completeRun` | **does not cross** — publish opens its own job run (below) |
| `briefingStart` | one log line (`:510`) | **does not cross** — publish times itself |
| `colourLocations` | last used at `:483`; never read after | **does not cross** |
| `lastKnownGood`, the stale path | below-threshold fallback | **deleted** (§4.7.8) |
| `aboveThreshold` | decides which branch publishes | becomes `BriefingSlate.aboveThreshold()`, evaluated by the **orchestrator** before EVALUATE |

`jobRun` deserves a decision, not a default. One `RunType.BRIEFING` row spanning SLATE→PUBLISH stays
open across `FORECAST_BATCH_WAIT` — observed at 98–173 min for afternoon batches
(`PipelineOrchestrator.java:99–103`) — so its `duration_ms` and cost would attribute the whole batch
wait to "briefing". **Two job runs:** SLATE keeps `RunType.BRIEFING` (whose javadoc at
`RunType.java:29` already reads "zero-Claude-cost weather pre-flight check" — that is literally the
slate), and publish gets a new `RunType.BRIEFING_PUBLISH`. `job_run.run_type` is `VARCHAR(20)`
(`V29__rename_job_name_to_run_type.sql:5`) and `BRIEFING_PUBLISH` is 16 chars, so **no migration**.
The enum switch at `RunType.java:78–84` is exhaustive and gains one arm. `ModelSelectionService`
needs no row: gloss and best-bet still resolve their models from `BRIEFING_GLOSS` /
`BRIEFING_BEST_BET` (`BriefingGlossService.java:168`, `BriefingBestBetAdvisor.java:161`), unchanged.

---

### 4.7.2 `BriefingSlate`

```java
package com.gregochr.goldenhour.model;

/**
 * The free half of a briefing: the roster of location × date × event slots a cycle will
 * select from, built from weather alone.
 *
 * <p>Upstream of everything. Selection reads it; publication reads it; nothing writes back
 * into it. It carries the two counters publication needs because they are facts about the
 * weather fetch, and the weather fetch happens here.
 *
 * @param builtAt            when the slate was assembled (UTC)
 * @param referenceDate      the Europe/London civil date the window starts on
 * @param dates              the window, {@code referenceDate} first
 * @param days               the day → event → region → slot hierarchy, unenriched
 * @param locationsSucceeded colour locations whose weather fetch returned a forecast
 * @param locationsFailed    colour locations whose weather fetch returned null
 */
public record BriefingSlate(
        Instant builtAt,
        LocalDate referenceDate,
        List<LocalDate> dates,
        List<BriefingDay> days,
        int locationsSucceeded,
        int locationsFailed) {

    /** Null-safe, defensive-copy compact constructor. */
    public BriefingSlate {
        Objects.requireNonNull(builtAt, "builtAt");
        Objects.requireNonNull(referenceDate, "referenceDate");
        dates = dates == null ? List.of() : List.copyOf(dates);
        days = days == null ? List.of() : List.copyOf(days);
    }

    /** @return an empty slate for a cycle with no colour locations at all. */
    public static BriefingSlate empty(Instant builtAt, LocalDate referenceDate) {
        return new BriefingSlate(builtAt, referenceDate, List.of(), List.of(), 0, 0);
    }

    /** @return true when there is no roster to select from or publish. */
    public boolean isEmpty() {
        return days.isEmpty();
    }

    /** @return locations attempted this build. */
    public int locationsTotal() {
        return locationsSucceeded + locationsFailed;
    }

    /**
     * @return true when at least half the roster returned weather. Integer division and the
     *         {@code total == 0} short-circuit are preserved verbatim from
     *         {@code BriefingService.java:508} — a zero-location build is "above threshold"
     *         so it is handled by {@link #isEmpty()}, not by the failure path.
     */
    public boolean aboveThreshold() {
        return locationsTotal() == 0 || (locationsSucceeded * 100 / locationsTotal()) >= 50;
    }

    /** @return true when some but not all location fetches failed. */
    public boolean partialFailure() {
        return locationsFailed > 0;
    }

    /** @return one-line phase detail for {@code pipeline_run_phase.detail}. */
    public String summary() {
        return locationsSucceeded + "/" + locationsTotal() + " locations, "
                + dates.size() + " dates, " + days.size() + " day(s)";
    }
}
```

JaCoCo enforces 80 % line coverage per class, and this record is small enough that the derived
methods are the class. `BriefingSlateTest` must assert each of them with real values —
`aboveThreshold()` at 0/0, 1/2, 1/3, 2/3; `isEmpty()` both ways; the compact constructor's null
handling for `dates` and `days` — rather than relying on incidental coverage from
`BriefingPublishTest`.

---

### 4.7.3 New signatures, and every caller

`refreshBriefing()` has exactly **three** production callers. Verified by
`grep -rn "refreshBriefing" --include="*.java" src/main`, which returns six hits of which three are
javadoc (`PipelinePhase.java:23`, `:54`, `PipelineOrchestrator.java:55`, `:511`, `:527`) and one is
the declaration (`BriefingService.java:404`).

```java
// BriefingService — replaces refreshBriefing()

/**
 * Builds the cycle's slate: weather, horizon cloud, marine sea-state, NLC/meteor clarity,
 * slot build and the day hierarchy. No Claude call, no write to the served cache.
 *
 * <p>Publishes nothing. The only externally visible writes are idempotent: grid-cell
 * capture on {@code location}, the {@code marine_wave} upsert keyed on
 * (location, date, event), and the two in-memory clarity caches.
 *
 * @return the slate, or {@link BriefingSlate#empty} when no colour location is a candidate
 */
public BriefingSlate buildSlate();

/**
 * Publishes a briefing built from {@code slate}: enrich with evaluation scores, gloss,
 * headline, best-bet, aurora, hot topics; write the served cache and persist it.
 *
 * <p>Terminal. Returns the response it published so callers never need to read the cache
 * back to learn what this cycle produced.
 *
 * @param slate the cycle's slate, reloaded from {@code briefing_slate}
 * @return the published response, or the existing cached response when {@code slate} is empty
 */
public DailyBriefingResponse publish(BriefingSlate slate);

/**
 * Ad-hoc republish for the admin endpoint: builds an unpersisted slate and publishes it
 * immediately. Submits no batch and creates no pipeline run — it re-renders the plan
 * against current weather and whatever evaluations already exist.
 */
public DailyBriefingResponse republish();   // == publish(buildSlate())
```

| Caller | Today | Becomes |
|---|---|---|
| `BriefingController.java:73` (`POST /api/briefing/run`, `@PreAuthorize("hasRole('ADMIN')")`) | `briefingService.refreshBriefing()` | `briefingService.republish()`. **Kept.** It is wired to a real button — `JobRunsMetricsView.jsx:364–376` → `briefingApi.js:25` → `POST /api/briefing/run` — and it is now *safe* in a way it was not: nothing selects from the publication, so a mid-day republish can no longer change what the next cycle evaluates. It must never submit a batch. |
| `ScheduledForecastService.java:104` (inside `refreshDailyBriefing()`, `:102–108`) | registered as the `daily_briefing` job target at `:65` | **Delete the method, the registration, the `briefingService` field (`:36`), constructor parameter (`:50`), assignment (`:55`) and javadoc line (`:45`).** This is dead code in production: `V68__scheduler_job_config.sql:25` seeded the `daily_briefing` row and `V103__retire_daily_briefing_cron.sql:13` deleted it; `grep -rn "daily_briefing" src/main/resources/db/migration/*.sql` shows no re-insert. `DynamicSchedulerService.registerJobTarget` only puts into a map (`:77–80`) and `initSchedules()` iterates **config rows** (`:92–95`), so with no row the target is never scheduled. |
| `PipelineOrchestrator.java:427` | `briefingService.refreshBriefing()` then `persistPicksForCycle(runId)` | `DailyBriefingResponse published = briefingService.publish(slate); persistPicks(runId, published);` — see §4.7.4. |

Two supporting changes:

- `BriefingService.getCachedDays()` (`:392–395`) → **`BriefingSlateHolder`**, a two-field bean
  (`AtomicReference<BriefingSlate>`) written by `buildSlate()` and read by
  `KingTideHotTopicStrategy` and `SpringTideHotTopicStrategy`. This is what fixes circularity 2, and
  it lets both strategies drop `@Lazy BriefingService` (`KingTideHotTopicStrategy.java:73`,
  `SpringTideHotTopicStrategy.java:67`) — the Spring cycle stops existing rather than being
  deferred. On startup the holder is repopulated from the newest `briefing_slate` row.
- `BriefingModelTestService.java:100` reads `getCachedBriefing()` purely for `briefing.days()`
  (`:111`, `bestBetAdvisor.compareModels(briefing.days(), driveMap)`). Retarget it to
  `briefingSlateHolder.get()` — a comparison harness should compare models against the **roster**,
  not against a rollup one of the models already wrote.

---

### 4.7.4 `briefing_slate` — DDL

**Postgres 17 only.** Migrations never run against anything else: `application.yml:19` and
`application-prod.yml:23` enable Flyway against Postgres, and `src/test/resources/application.yml:13`
disables it. Write Postgres SQL.

⚠️ **But the JPA *mapping* is still constrained by H2**, and this is worth stating plainly because
correction (1) invites the opposite conclusion. `src/test/resources/application.yml:3,10,13` is
H2 in-memory with `ddl-auto: create-drop` and Flyway off — the default `@SpringBootTest` context
generates its schema **from entity annotations**, which is exactly what `IntegrationTestBase`'s own
javadoc (`IntegrationTestBase.java:24–30`) documents. Six classes carry `@SpringBootTest`
(`GoldenHourApplicationTests`, `ResilienceConfigTest`, `AbstractControllerTest`,
`JwtAuthenticationFilterTest`, `LocationFailureTrackingTest`,
`DynamicSchedulerServiceIntegrationTest`) plus the two integration bases. So: **DDL is free to be
Postgres-native; entity mappings must remain H2-generatable.** That is the answer to correction (1)'s
"investigate what the test-scope H2 dependency is actually used by".

```sql
-- V137__briefing_slate.sql
--
-- The roster a cycle selected from, recorded 1:1 with the cycle.
--
-- Exists because selection used to read publication: ForecastTaskCollector built its
-- candidate set from briefingService.getCachedBriefing() while the cycle tail rewrote that
-- same briefing. The slate is the upstream artefact both halves now read, so a cycle
-- evaluates the roster it built rather than the one the previous cycle published.
--
-- Also the selection record §2.7 demands: "what population did this cycle choose from?" is
-- answerable from SQL, not only from a Java deserialisation.

CREATE TABLE briefing_slate (
    -- PK, not a surrogate id: exactly one slate per cycle, enforced by the key itself
    -- rather than by a separate UNIQUE index.
    pipeline_run_id     BIGINT      PRIMARY KEY
                        REFERENCES pipeline_run(id) ON DELETE CASCADE,
    built_at            TIMESTAMPTZ NOT NULL,
    reference_date      DATE        NOT NULL,
    window_days         INT         NOT NULL,
    locations_succeeded INT         NOT NULL,
    locations_failed    INT         NOT NULL,
    -- The day -> event -> region -> slot hierarchy, Jackson-serialised. JSONB, not TEXT:
    -- see the note below.
    days                JSONB       NOT NULL,

    CONSTRAINT ck_briefing_slate_counts
        CHECK (locations_succeeded >= 0 AND locations_failed >= 0),
    CONSTRAINT ck_briefing_slate_window
        CHECK (window_days BETWEEN 1 AND 7),
    -- A malformed payload fails at write, in the cycle that produced it, instead of at the
    -- next read weeks later. Only expressible on JSONB.
    CONSTRAINT ck_briefing_slate_days_is_array
        CHECK (jsonb_typeof(days) = 'array')
);

-- Deliberately no index beyond the primary key. The only access paths are by
-- pipeline_run_id (the PK) and ORDER BY built_at DESC LIMIT 1 on a table bounded at ~60
-- rows by the retention rule below. Add an index when a query needs one, not before.
```

**Why JSONB and not TEXT**, in order of weight:

1. `jsonb_typeof(days) = 'array'` is a **write-time integrity check**. On TEXT the column accepts
   `'oops'` and the failure surfaces at deserialisation in a later cycle.
2. It is the selection record. §2.7's whole point is that the population a statistic was computed
   over must be inspectable. `SELECT pipeline_run_id, jsonb_array_length(days) FROM briefing_slate`
   and `jsonb_path_query` over slots answer "what did cycle 412 choose from?" without a Java
   deserialiser. TEXT makes that a Java-only artefact — the same mistake as
   `forecast_run_disposition` living apart from the results.
3. JSONB stores decomposed and de-duplicates object keys. The payload is ~500 slots repeating the
   same ~30 keys, so this is a real size win, and both forms TOAST-compress.
4. There is no counter-reason. `daily_briefing_cache.payload` is TEXT (`V59:6`) because V59 was
   written under the H2 assumption that correction (1) retires. That column is only ever written and
   read whole by Jackson and never queried, so leave it — the slate is a different kind of object.

Cost of JSONB: parse-on-write and no preserved key order/whitespace. Both irrelevant; we round-trip
through Jackson.

**Separate table, not a column on `pipeline_run`.** `pipeline_run` is the ops-list table:
`PipelineRunService.findRecent()` (`:222`) and `findRunning()` (`:203`) select entities, and JPA
loads every non-lazy column. A ~0.5 MB JSONB payload would be dragged into every pipeline-run list
render. A `@Lob`/`FetchType.LAZY` attribute would need bytecode enhancement to actually be lazy.
A child table keyed by the parent's id costs one join on the one path that wants it.

**Payload size — UNVERIFIED.** I have no production DB access. The slate is a strict subset of what
`daily_briefing_cache` already stores (no gloss, no best-bets, no hot topics, no Claude scores), so
measure the ceiling with:

```sql
SELECT length(payload) AS chars, pg_column_size(payload) AS stored_bytes
FROM daily_briefing_cache WHERE id = 1;
```

**Retention.** `pipeline_run` has no prune anywhere in the tree
(`grep -rn "pipeline_run" src/main --include=*.java | grep -i "delete\|prune"` returns nothing), so
`ON DELETE CASCADE` alone does not bound growth. Rather than add a scheduler row, prune inside
`BriefingSlateStore.save()` — it runs twice a day:

```java
/** Keeps the slate table to the window the disposition record uses (30 days, V101). */
private static final int RETENTION_DAYS = 30;
```

```java
@Repository
public interface BriefingSlateRepository extends JpaRepository<BriefingSlateEntity, Long> {
    Optional<BriefingSlateEntity> findTopByOrderByBuiltAtDesc();
    int deleteByBuiltAtBefore(Instant cutoff);
}
```

```java
@Service
public class BriefingSlateStore {
    public void save(long pipelineRunId, BriefingSlate slate);      // upsert + prune
    public Optional<BriefingSlate> find(long pipelineRunId);
    public Optional<BriefingSlate> findLatest();                    // startup rehydration
}
```

The entity maps `days` as:

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "days", nullable = false)
private String days;
```

No entity in the tree uses `@JdbcTypeCode` today (`grep -rn "JdbcTypeCode\|SqlTypes" src/main` is
empty), so this is the first. **Risk, stated rather than assumed:** Hibernate 7 (Spring Boot
4.1.0, `pom.xml:10`) maps `SqlTypes.JSON` to `jsonb` on the Postgres dialect and to H2's `json` on
H2Dialect — but the H2 half is exercised by `ddl-auto: create-drop` in six `@SpringBootTest`
classes and I have not run it. Verification gate, both rungs required:
`./mvnw test -Dtest=GoldenHourApplicationTests` (H2 context loads, schema generates) **and** a new
`BriefingSlateRoundTripIT extends IntegrationTestBase` (Postgres 17 Testcontainer + Flyway).
If H2 refuses, fall back to `@Column(columnDefinition = "TEXT")` on the entity while keeping
`JSONB` in the migration and writing through a `CAST(:days AS jsonb)` native upsert — the DDL
stays Postgres-native either way.

---

### 4.7.5 `PipelineOrchestrator` — the SLATE phase

**`PipelinePhase`** gains one value, first in the enum so ordinal order matches execution order:

```java
/**
 * Builds the cycle's briefing slate (weather, horizon cloud, marine, clarity caches, slot
 * hierarchy) and persists it to {@code briefing_slate}. Free — no Claude call — and it does
 * not touch the served briefing. Selection reads the slate this phase produced, which is why
 * it runs first: before it existed, the collector read the previous cycle's publication.
 */
SLATE,
```

`BRIEFING` is **not** renamed to `PUBLISH`. `pipeline_run_phase.phase` and
`pipeline_run.current_phase` are persisted history (`V102__pipeline_run.sql`); renaming would need
two UPDATEs that retroactively describe old cycles as running code that did not exist. Update its
javadoc (`PipelinePhase.java:54`) to say it runs `publish(slate)`, and the class javadoc's
"gloss and best-bet are intentionally not phases" note (`:20–24`) to say they are inside `publish`.

**New phase order.** Both cycle types: `SLATE → FORECAST_BATCH_SUBMIT → FORECAST_BATCH_WAIT →
[RETRY_FAILED] → BRIEFING`.

`STABILITY_RECLASSIFY` (`PipelinePhase.java:33`) exists solely to record the intraday cost gate —
its own javadoc says "the cost-gate that decides which locations are re-evaluated
(TRANSITIONAL/UNSETTLED) versus skipped as settled (`SKIPPED_NO_REFRESH_NEEDED`)", and
`IntradayEligibilityPolicy.java:47–53` is that gate. With no gates the phase records nothing. **If
the gate-removal area deletes `IntradayEligibilityPolicy`, delete `STABILITY_RECLASSIFY` and the
`ephemeral` flag with it** — which collapses `submitPhase`'s nightly/intraday fork
(`PipelineOrchestrator.java:360–395`, ~35 lines including the `Consumer<ReclassSummary>` hook, the
`betweenSteps` lambda and the two-branch failure attribution) into a single `startPhase / submit /
completePhase`. That deletion is jointly enabled and I do not claim it in my line count.

**`runCycle`** loses its `EligibilityPolicy` parameter (gate-removal) and gains the slate phase.
`runNightlyCycle` / `runIntradayCycle` (`:223–227`, `:249–253`) collapse into one method plus a
strategy factory that resume also needs:

```java
/** The cycle's window filter — a scope fact, not a gate: which slots are this cycle's job. */
private CandidateCollectionStrategy strategyFor(CycleType cycleType) {
    return cycleType == CycleType.INTRADAY
            ? new IntradayCandidateCollectionStrategy(clock)
            : NightlyCandidateCollectionStrategy.INSTANCE;
}

public void runCycle(CycleType cycleType) {
    PipelineRunEntity run = pipelineRunService.startRun(cycleType);
    Long runId = run.getId();
    BriefingSlate slate;
    try {
        slate = slatePhase(runId);
    } catch (RuntimeException e) {
        pipelineRunService.failRun(runId, "Slate phase failed: " + e.getMessage());
        return;
    }
    if (!slate.aboveThreshold()) {
        // The weather fetch is too degraded to select from. Abort BEFORE any spend; the
        // served briefing is left exactly as it was (see §4.7.8).
        pipelineRunService.failRun(runId, "Slate below weather-coverage threshold: "
                + slate.locationsSucceeded() + "/" + slate.locationsTotal() + " locations");
        return;
    }
    try {
        submitPhase(runId, cycleType, slate, strategyFor(cycleType));
    } catch (Exception e) {
        pipelineRunService.failRun(runId, "Submit phase failed: " + e.getMessage());
        return;
    }
    backgroundExecutor.execute(() -> waitAndPublishTail(runId));
}

private BriefingSlate slatePhase(Long runId) {
    pipelineRunService.startPhase(runId, PipelinePhase.SLATE);
    try {
        BriefingSlate slate = briefingService.buildSlate();
        briefingSlateStore.save(runId, slate);
        pipelineRunService.completePhase(runId, PipelinePhase.SLATE, slate.summary());
        return slate;
    } catch (RuntimeException e) {
        pipelineRunService.failPhase(runId, PipelinePhase.SLATE, e.getMessage());
        throw e;
    }
}
```

**The tail reloads the slate from the store rather than closing over the in-memory value.** That is
the reason `briefing_slate` is persisted at all: `waitAndBriefPhase` runs on a different thread
(`:283`) and, after a restart, in a different **process**. Closing over the object would make the
resume path (`:339`) unable to publish.

```java
private void waitAndPublishTail(Long runId) {
    // ... FORECAST_BATCH_WAIT and RETRY_FAILED unchanged (:397–423) ...
    pipelineRunService.startPhase(runId, PipelinePhase.BRIEFING);
    try {
        BriefingSlate slate = briefingSlateStore.find(runId).orElseThrow(() ->
                new IllegalStateException("No briefing_slate for pipeline run " + runId));
        DailyBriefingResponse published = briefingService.publish(slate);
        persistPicks(runId, published);
        pipelineRunService.completePhase(runId, PipelinePhase.BRIEFING, null);
    } catch (RuntimeException e) {
        pipelineRunService.failPhase(runId, PipelinePhase.BRIEFING, e.getMessage());
        pipelineRunService.failRun(runId, "Publish failed: " + e.getMessage());
        return;
    }
    pipelineRunService.completeRun(runId);
}
```

`persistPicksForCycle` (`:533–568`) becomes `persistPicks(runId, published)` and **loses two
things**: the `briefingService.getCachedBriefing()` re-read (`:538`) and the
`if (briefing.stale())` guard (`:544–549`). Both existed only because the orchestrator had to
recover from the cache what `refreshBriefing()` had done. Publication is terminal — the orchestrator
now holds the response it published. The stale guard's stated purpose ("its picks are carried-forward
last-known-good, not this cycle's") is structurally impossible once `publish` returns its own
result. −12 lines, and one class of silent cross-run corruption removed by construction.

---

### 4.7.6 `resumeRunningCyclesOnStartup` — SLATE is resumable

Today (`:322–342`) the rule is: `STABILITY_RECLASSIFY`, `FORECAST_BATCH_SUBMIT` or `null` → `failRun`;
anything else → re-dispatch the tail. The stated reason (`:316–320`) is "batches may have been
submitted to Anthropic in a partial state we can't safely resume".

**SLATE is resumable, and the argument is that the reason above does not apply to it.** Nothing has
been submitted to Anthropic during SLATE — the phase precedes `FORECAST_BATCH_SUBMIT` entirely. Its
only external writes, checked one at a time:

| Write | Idempotent? | Evidence |
|---|---|---|
| Open-Meteo forecast + cloud batches | yes, read-only | `BriefingService.java:813`, `:926` |
| `location.grid_lat/grid_lng` capture | yes — only sets when `!loc.hasGridCell()`, to the same snapped value | `:857–879` |
| `marine_wave` rows | yes — find-or-create keyed on `(location_id, evaluation_date, event_type)`, then setters | `MarineWaveRefreshService.java:112–128` |
| NLC clarity cache | yes — `cache.set(...)`, replace-in-full | `NlcClarityService.java:76–79` |
| meteor clarity cache | yes — same shape | `MeteorClarityService.java:60–65` |
| `briefing_slate` | yes — PK is `pipeline_run_id`, so the save is an upsert | §4.7.4 |

The cost of *not* resuming is real: a crash at 01:02 during a nightly SLATE means no nightly cycle at
all until the next day, because the cron has already fired. The cost of resuming is one repeat of a
free Open-Meteo fetch.

One correctness guard that must not be skipped. `IntradayCandidateCollectionStrategy` pins "today"
at **construction** (`IntradayCandidateCollectionStrategy.java:50`), so an intraday cycle resumed the
next morning would build the window (T sunset, T+1 sunrise, T+1 sunset) around the *new* today while
the run's `trigger_time` says yesterday. Bound the resume by the safety timeout:

```java
for (PipelineRunEntity run : running) {
    PipelinePhase phase = run.getCurrentPhase();
    if (phase == PipelinePhase.SLATE) {
        // Nothing has been submitted to Anthropic yet and SLATE's writes are all idempotent,
        // so the pre-submission failure rule does not apply here. But a cycle's window is
        // pinned to its trigger time, so only resume while that window is still current.
        Duration age = Duration.between(run.getTriggerTime(), clock.instant());
        if (age.compareTo(safetyTimeout) >= 0) {
            pipelineRunService.failRun(run.getId(),
                    "Restarted during SLATE and the cycle's window has aged out (" + age + ")");
            continue;
        }
        LOG.info("Resuming pipeline run {} from SLATE (age {})", run.getId(), age);
        backgroundExecutor.execute(() -> resumeFromSlate(run.getId(), run.getCycleType()));
    } else if (phase == PipelinePhase.FORECAST_BATCH_SUBMIT || phase == null) {
        pipelineRunService.failRun(run.getId(),
                "Process restarted during submission — cannot safely resume");
    } else {
        LOG.info("Resuming pipeline run {} from phase {}", run.getId(), phase);
        backgroundExecutor.execute(() -> waitAndPublishTail(run.getId()));
    }
}
```

`resumeFromSlate` re-runs `slatePhase` (its `startPhase` must be idempotent for an already-open
row — mirror the `atOrPastRetry` guard idiom at `:405–410`), then `submitPhase`, then the tail.
`phase == null` still fails: an unknown state is not a resumable one.

---

### 4.7.7 What the collectors consume

`ForecastTaskCollector` and `BriefingCandidateCollector` both take `DailyBriefingResponse` today.
Both take `BriefingSlate`.

```java
// ForecastTaskCollector
public ScheduledBatchTasks collectScheduledBatches(BriefingSlate slate,
                                                   CandidateCollectionStrategy candidateStrategy);

// BriefingCandidateCollector
public Result collectForecastCandidates(BriefingSlate slate,
                                        CandidateCollectionStrategy candidateStrategy);
```

Consequences inside my area:

- `ForecastTaskCollector` **drops its `BriefingService` dependency entirely** — field `:96`,
  constructor parameter `:151`, javadoc `:132`, assignment `:169` — plus the two "no cached
  briefing" abort branches (`:272–276`, `:717–721`). The bean edge that made selection depend on
  publication stops existing at compile time.
- `ForceEvalHeadlineSelector.selectForceEvalKeys(briefing)` (`:363`) takes a
  `DailyBriefingResponse`. §4.1 deletes the whole force-eval mechanism; if for any reason it
  survives, it takes `BriefingSlate` instead.
- The `[BATCH DIAG]` header at `:282–285` reads `briefing.days().size()` → `slate.days().size()`,
  and should also log `slate.builtAt()` so the log states, per cycle, that selection used a slate
  built in **this** cycle. That line is the observable proof the bug is gone.
- `BriefingCandidateCollector.findLocation(name)` (`:285–290`) calls
  `locationService.findAllEnabled()` **once per slot** — a repository round-trip per slot, hundreds
  per cycle. `BriefingSlot` has carried `locationId` since it was added to the record
  (`BriefingSlot.java:54`), and `BriefingSlotBuilder` always populates it
  (`BriefingSlotBuilder.java:204`, `:235`). A slate is always built in-cycle, so the legacy-null
  case in `BriefingSlot`'s javadoc cannot arise on a slate. Replace with one
  `Map<Long, LocationEntity>` roster load at the top of the pass, keyed by `slot.locationId()`.
  `SKIPPED_UNKNOWN_LOCATION` survives as a structural bound.

**Interface I need from the gate-removal area** (stated, not duplicated): after that work,
`BriefingCandidateCollector`'s constructor should be `(TravelDayService, LocationService, Clock)` —
`briefingEvaluationService`, `freshnessResolver` and `stabilitySnapshotProvider` (`:54–56`, `:69–81`)
all go with the freshness and stability gates, taking `buildStabilityLookup()` (`:313–333`),
`mostVolatileStability()` (`:335–353`) and `logStabilityBreakdown()` (`:292–311`) with them. My
change is compatible either way: the slate replaces the *source* of the candidate set; the gate
removal shrinks what happens to each candidate.

Note the collapse is dramatic. Of the six skip categories the pass records, three go
(`SKIPPED_CACHED` `:229`, `SKIPPED_HARD_CONSTRAINT` `:253`, and the `SKIPPED_STABILITY` branch the
triage loop records at `ForecastTaskCollector.java:471–474`) and three remain, all structural:
`SKIPPED_PAST_DATE` (`:162`), `SKIPPED_UNKNOWN_LOCATION` (`:266`) and the one surviving policy gate,
`SKIPPED_TRAVEL_DAY` (`:188`).

---

### 4.7.8 The gloss-less window is eliminated, and what the served cache holds

**Where the served briefing comes from.** `GET /api/briefing` → `BriefingController.java:58` →
`getCachedBriefingForApi()` (`:303–307`) → `reEnrichVerdicts(getCachedBriefing())` →
`getCachedBriefing()` (`:247`) → `cache.get()` (`:248`).

**Every writer of `cache`.** `grep -n "cache.set" BriefingService.java` returns exactly four:
`:229` (`@PostConstruct loadPersistedBriefing`), `:543` (success branch), `:558` (stale branch),
`:568` (partial branch). All four are inside `refreshBriefing()`'s publication tail or startup.

That is the proof, and it is a proof about *code shape*, not about timing: **`buildSlate()` contains
none of those lines, because all three live in `publish()`.** So the served cache between SLATE and
PUBLISH is bit-for-bit the previous cycle's fully-published response — same `generatedAt`, same
`headline`, same per-region `glossHeadline`/`glossDetail`, same `bestBets`, same `bestBetStatus`.

| Field of the served response | Between SLATE and PUBLISH |
|---|---|
| `generatedAt`, `headline`, `days`, `bestBets`, `bestBetModel`, `bestBetStatus`, `partialFailure`, `failedLocationCount` | previous publication, untouched |
| per-region `glossHeadline` / `glossDetail` | previous publication's prose — **never null-during-cycle** |
| `auroraTonight` / `auroraTomorrow` | live, overlaid at `:253–254` (unchanged today) |
| per-slot Claude scores and each region's `displayVerdict`, `scoredLocationCount`, `confidence` | live — `reEnrichVerdicts` (`:333–352`) re-derives from `EvaluationViewService` on every request, so results landing mid-cycle appear immediately (unchanged today) |
| `hotTopics` | **changes**: recomputed live at `:259` and now sourced from the *new* slate's days via `BriefingSlateHolder` |

That last row is the one honest caveat and must not be buried. It is a bounded improvement — tide
hot topics stop being a cycle behind (circularity 2) — and it changes only almanac-class facts, never
gloss. If it is judged undesirable, the alternative is to have `BriefingSlateHolder` published at
PUBLISH rather than SLATE, at the cost of leaving circularity 2 in place. I recommend the slate.

**Contrast with the naive head-position refresh**, from source. Calling `refreshBriefing()` at the
head runs the whole method, so `cache.set(response)` at `:543` fires with `days` enriched at `:486`
from evaluations that have not been submitted yet, and gloss generated at `:489` against those
missing scores. The served briefing would then carry that gloss until the tail's second full
`refreshBriefing()` completed — bounded by `DEFAULT_SAFETY_TIMEOUT` at 4 h
(`PipelineOrchestrator.java:105`), calibrated on observed afternoon batch latencies of 98–173 min
(`:99–103`). It would also run `generateGlosses` and `advise` twice per cycle. The split runs the
free half at the head and the paid half once at the tail, and the head touches no writer of `cache`.

---

### 4.7.9 `BRIEFING_WINDOW_DAYS = 5` — both halves verified, and it retires

**Backend half — verified.** `BriefingService.java:118` is `= 5`; the javadoc at `:97–117` states the
mechanism precisely: the briefing is consumed by the *next* cycle, its first date has aged to
yesterday, `BriefingCandidateCollector` drops it as `PAST_DATE` (`:151–172`), so a four-date window
arrives one date short and T+3 was unreachable. Measured: zero candidates at `days_ahead = 3` and
5,684 at `days_ahead = -1` over 14 days / 28 cycles.

**Frontend half — verified.** `DailyBriefing.jsx:108` `const MAX_VISIBLE_EVENTS = 6;`
`selectUpcomingEvents` (`:114–124`) walks days → eventSummaries, skips past events, and `return`s at
6. **Every** render path is downstream of it:

- `upcomingEvents` (`:1162–1165`) → `dayDates` (`:1166`)
- `HeatmapGrid events={upcomingEvents}` (`:1537`)
- `mobileEvents` (`:1247–1255`)
- `summaryPills` (`:1211`) → `buildSummaryPills`, further capped by `STRIP_MAX_DAYS = 4` (`:150`, `:230`)
- expanded day cards keyed by `selectedDate` from `dayDates` (`:1258`)
- "Other days" pills iterate `dayDates` (`:1471`)
- `astroDayDates` (`:1120–1124`)

Six events is at most three whole days (both solar events each), so `dates[4]` — T+4, the fifth
date — is **never rendered**. Both halves of the CHANGELOG claim hold.

**Does the split retire it? Yes.** The compensator's cause was that the slate was a cycle old. With
`buildSlate()` at the head of the cycle that consumes it, `dates[0]` is always today and the
`PAST_DATE` bucket for it disappears, so a four-date window reaches T+3 with nothing to spare.
**Set `BRIEFING_WINDOW_DAYS = 4`** and replace the 21-line javadoc with the fact that the window is
T+0..T+3 to match the evaluation horizon.

**The saving is gloss calls and nothing else** — worth stating because it is smaller than it looks:

- **Open-Meteo: zero.** `fetchWeatherSequential` (`:787–848`) and `fetchHorizonCloud` (`:893–949`)
  batch per *grid cell*, not per date; the response covers the horizon regardless. The fifth date
  costs no API call.
- **Marine: zero API calls.** `MarineWaveRefreshService.refresh` fetches once per location
  (`:80`) and loops dates only for upserts (`:83–89`) — the fifth date costs 2 DB upserts per
  coastal location.
- **NLC/meteor: one hour key each.** Negligible.
- **Gloss: real.** `BriefingGlossService.collectWorkItems` (`:194–210`) emits one work item per
  `(day × event × region with an eligible slot)`, each a Haiku call (`:213–228`). Dropping a date
  removes `2 × R` calls per publish, where `R` = regions with at least one eligible slot per event.
  At 2 cycles/day that is `4R` calls/day. The summary design's "~14 gloss calls per refresh" for one
  date implies `R ≈ 7`; **R is UNVERIFIED here.** Measure it:

```sql
SELECT jr.id, jr.started_at,
       count(*) FILTER (WHERE a.request_url = 'briefing-gloss') AS gloss_calls
FROM job_run jr
JOIN api_call_log a ON a.job_run_id = jr.id
WHERE jr.run_type = 'BRIEFING'
  AND jr.started_at > now() - interval '14 days'
GROUP BY jr.id, jr.started_at
ORDER BY jr.started_at DESC;
```

Divide by 5 for the per-date rate. At `R = 7` and the observed $0.00206/call (§1) it is ≈ $1.73 —
about **£1.35/month**. Small; take it because the mechanism goes with it, not for the money.

**The second saving is not monetary and is larger.** With five dates the collector iterates T+4
slots that the horizon bound then rejects. With four dates those candidates never exist, so a whole
class of skip disposition stops being generated.

---

### 4.7.10 Failure semantics

| Scenario | Behaviour | Served briefing |
|---|---|---|
| SLATE: no colour locations at all | `buildSlate()` returns `BriefingSlate.empty()`; `publish` short-circuits. Preserves `BriefingService.java:421–425` exactly, which returns before touching the cache. | untouched |
| SLATE: weather fetch below 50 % | `slate.aboveThreshold()` false → `failRun` **before** SUBMIT. No batch, no Claude call, nothing published. | untouched, ages |
| SLATE: exception | `failPhase(SLATE)` + `failRun` | untouched |
| SLATE ok, EVALUATE times out | `BatchSafetyTimeoutException` → `failPhase` + `failRun`, tail returns without publishing. **This is today's behaviour already** — the catch at `PipelineOrchestrator.java:437–449` never reaches `refreshBriefing()`. It is the 1 failure in 60 cycles (§1). | untouched |
| SLATE ok, EVALUATE partially fails | RETRY_FAILED runs (`:479–506`), then PUBLISH proceeds. Regions with no result get `scoredLocationCount = 0` and the honesty filter rewrites them (`BriefingHonestyFilter`, applied at `:305`); `ConfidenceDeriver` returns null on zero coverage (`:738`). | fresh, honest about coverage |
| PUBLISH fails (gloss/best-bet/persist) | `failPhase(BRIEFING)` + `failRun`. Every `cache.set` is inside `publish`'s success path. | untouched |

**Delete `lastKnownGood` and the stale write path.** Under the split, a below-threshold slate never
reaches `publish`, so nothing can overwrite the cache with something worse — `cache` **is** the last
known good, by construction. Remove: the field (`:129`), the startup set (`:230`), the success-path
set (`:544`), and the entire below-threshold branch (`:550–574`).

`DailyBriefingResponse.stale` stays on the DTO — `DailyBriefing.jsx:1287–1288` renders it — but
becomes **derived at serve time** rather than written at build time, which is the same
classify-for-display rule §4.5 applies to freshness:

```java
/**
 * A publication older than this reads as stale. Two cycles run per day (01:00 and ~14:00 UTC,
 * V73 and V105), so crossing 26h means at least two consecutive cycles failed to publish.
 */
private static final Duration STALE_AFTER = Duration.ofHours(26);
```

computed inside `getCachedBriefingForApi()` (`:303–307`) from `generatedAt`. The frontend's
tooltip text ("Last refresh failed — showing cached data") should become "No successful refresh
since {generatedAt}", which is now what the flag actually means. Trace confirmed: the only other
reader of `stale()` is `PipelineOrchestrator.java:544`, deleted in §4.7.5.

---

### 4.7.11 Tests

**Break and must change**

| File | Sites | Change |
|---|---|---|
| `BriefingServiceTest.java` (2404 lines) | ~50 `refreshBriefing()` calls | **Split the class**, do not add a `refreshBriefingForTest()` helper — a helper that recombines the halves is how they get re-tangled. `BriefingSlateBuildTest` takes `ColourFilterTests` (`:175`), `SolarEventFilterTests` (`:473`), `GridDeduplicationTests` (`:784`), `PartialBatchResponseTests` (`:1119`), `HorizonGridKeyTests` (`:1231`). `BriefingPublishTest` takes `RefreshLifecycleTests` (`:1533`), `PersistBriefingEntityTests` (`:1838`), `CachedScoreEnrichmentTests` (`:1968`). `ServeTimeFallback` (`:372`), `GetCachedBriefingAuroraTests` (`:1327`), `GetCachedBriefingHotTopicTests` (`:1388`) and `ServeTimeReEnrichment` (`:2250`) stay in `BriefingServiceTest` — they exercise the serve path, which is unchanged. |
| `BriefingServiceTest.CacheLoadingLkgTests` (`:1268–1326`) | 1 test, 58 lines | **Delete** with `lastKnownGood`. |
| `PipelineOrchestratorTest.java` (856) | 14 `verify(briefingService).refreshBriefing()` at `:180, 197, 238, 319, 347, 359, 392, 410, 424, 701, 749, 772, 802, 852` | → `verify(briefingService).publish(any(BriefingSlate.class))`; `verify(briefingService, never())` variants likewise. `HappyPath.completes_full_sequence` (`:144`) asserts the SLATE phase first. `PickPersistence` (`:466–628`) stops stubbing `getCachedBriefing()` and stubs `publish` to return the response. `skips_persist_when_briefing_stale` (`:565`) and `skips_persist_when_no_cached_briefing` (`:586`) are **deleted** — both guard a re-read that no longer happens. |
| `BriefingControllerTest.java:153–176` | 3 tests | `verify(briefingService).republish()`; the 403/401 cases are unaffected. |
| `ForecastTaskCollectorTest.java` (1557), `ForecastTaskCollectorForceEvalTest.java` (350), `CollectForecastTasksCachedGateTest.java` (350), `BriefingModelTestServiceTest.java` | `getCachedBriefing()` stubs | fixtures build a `BriefingSlate` instead of a `DailyBriefingResponse`. The latter two are deleted outright by the gate-removal / force-eval work; I do not claim them. |
| `KingTideHotTopicStrategyTest.java`, `SpringTideHotTopicStrategyTest.java` | ~100 `when(briefingService.getCachedDays())` stubs | mechanical: `when(briefingSlateHolder.get()).thenReturn(slateWithDays(...))`. |

**New**

```java
// BriefingPublicationIsTerminalTest — the invariant, in the ScopeGuarantees idiom already used
// at PipelineOrchestratorTest:446 and the reflection idiom at CollectForecastTasksCachedGateTest:110.

@Test @DisplayName("no selection-side type declares a BriefingService or DailyBriefingResponse field")
void selection_never_reads_publication();
// Reflects over ForecastTaskCollector, BriefingCandidateCollector, ScheduledBatchEvaluationService,
// PipelineOrchestrator: assert no declared field or constructor parameter type is
// BriefingService or DailyBriefingResponse. PipelineOrchestrator keeps BriefingService (it
// calls buildSlate/publish) — assert instead that it declares no DailyBriefingResponse *field*
// and that BriefingService exposes no public method returning DailyBriefingResponse that
// service.batch types can reach.

@Test @DisplayName("BriefingService has no method named refreshBriefing")
void combined_method_is_gone();
// Guards against the split being quietly re-merged: assert
// Arrays.stream(BriefingService.class.getMethods()).noneMatch(m -> m.getName().equals("refreshBriefing")).

@Test @DisplayName("buildSlate writes nothing the serve path reads")
void slate_build_leaves_served_cache_untouched();
// Publish once, capture getCachedBriefingForApi(); call buildSlate(); assert the served
// response is the SAME generatedAt, headline, bestBets and per-region gloss strings.
// This is §4.7.8's proof as an executable assertion.

@Test @DisplayName("hot topics are derived from the slate, not from the published cache")
void hot_topics_read_the_slate();
// Publish a briefing with a king-tide day; buildSlate() a window with none; assert the live
// hot topics on the next serve carry the slate's answer. Pins circularity 2 closed.

// PipelineOrchestratorTest additions
@Test void slate_phase_runs_first_and_persists();
@Test void below_threshold_slate_fails_run_before_submit();       // no submitForecastBatch interaction
@Test void empty_slate_publishes_nothing();
@Test void resumes_mid_slate_when_within_safety_timeout();
@Test void fails_mid_slate_resume_when_window_aged_out();
@Test void publish_result_is_the_sole_source_of_persisted_picks(); // never calls getCachedBriefing

// BriefingSlateTest — JaCoCo-real coverage of the derived methods
@Test void aboveThreshold_zeroLocations_isTrue();
@Test void aboveThreshold_oneOfThree_isFalse();
@Test void aboveThreshold_oneOfTwo_isTrue();
@Test void isEmpty_noDays_isTrue();
@Test void compactConstructor_nullListsBecomeEmpty();

// BriefingSlateRoundTripIT extends IntegrationTestBase — Postgres 17 + Flyway
@Test void slate_round_trips_through_jsonb();
@Test void second_save_for_same_run_upserts();
@Test void cascade_delete_removes_slate_with_its_pipeline_run();
@Test void malformed_days_payload_is_rejected_by_the_check_constraint();
```

---

### 4.7.12 Line count, honestly

This section is **not** a net deletion, and pretending otherwise would misreport the trade.

**Production lines removed (~90):**

| Removal | Lines |
|---|---|
| `lastKnownGood` + stale branch — `BriefingService.java:129, 230, 544, 550–574` | 28 |
| `ScheduledForecastService` dead briefing path — `:36, 45, 50, 55, 65, 96–108` + import | 19 |
| `BRIEFING_WINDOW_DAYS` javadoc `:97–117` collapsed to ~6 | 15 |
| `persistPicksForCycle` cache re-read + stale guard — `PipelineOrchestrator.java:538–549` | 9 net |
| `ForecastTaskCollector` `BriefingService` field/param/assignment + two null-briefing guards — `:96, 151, 169, 272–276, 717–721` | 14 |
| `BriefingCandidateCollector.findLocation` `:285–290` minus the roster map replacement | 2 net |
| Repointing `getCachedDays` to the holder, deleting two `@Lazy` annotations | 3 |

**Production lines added (~300):** `BriefingSlate` ~70, `BriefingSlateEntity` ~75,
`BriefingSlateRepository` ~15, `BriefingSlateStore` ~70, `BriefingSlateHolder` ~30,
`PipelineOrchestrator` slate phase + resume + `strategyFor` ~45, `PipelinePhase.SLATE` ~8,
`V137__briefing_slate.sql` ~30.

**Test lines removed (~120):** `CacheLoadingLkgTests` (58) plus the two deleted
`PipelineOrchestratorTest` stale/no-cache cases (~45) and the threshold assertions inside
`RefreshLifecycleTests`.

Net ≈ **+210 production lines**. The justification is that this is the one item in the whole redesign
that is a **defect fix**, not a subtraction (§0), and it is the enabler for other areas' deletions:
`ForceEvalHeadlineSelector` (167 lines) takes a `DailyBriefingResponse` and only exists to punch
through a gate reached via the published briefing; `submitPhase`'s cycle fork (~35 lines) collapses
once `STABILITY_RECLASSIFY` goes; and the whole cached/hard-constraint/stability skip machinery is
reached through the collector's briefing read. Ship it alone (§5, Phase 1), before the gates.
