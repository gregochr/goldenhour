# AGENTS.md — backend (Java / Spring Boot 4)

Context for AI code review of the Java backend. Read this before flagging anything:
several of this project's most bug-shaped constructs are deliberate, documented
decisions. Flagging them as defects is noise; the real review value is in what
this file does *not* cover.

## Build & verification

- `cd backend && ./mvnw compile -q` — fast compile check (no Docker).
- `./mvnw checkstyle:check` — fails fast on style (120-char lines, Javadoc on
  public classes/methods, no unused imports, 4-space indent).
- Full `./mvnw clean verify` needs Docker (5 Testcontainers integration classes
  under `src/test/java/.../integration/`); it runs JaCoCo (**80% line coverage
  per class**) and SpotBugs (`High` threshold + FindSecBugs).
- Gate on Maven's **exit code**, never on grepping its output.

## Code Review Rules

Focus on: correctness bugs, concurrency issues (the app uses virtual threads and
a shared `ThreadPoolTaskScheduler`), resource handling, missed null paths on
nullable record components, and Flyway migration safety. Skip style nits —
Checkstyle and SpotBugs gate those in CI.

### Deliberate decisions — do NOT flag these as bugs

1. **Two calendars, one clock — on purpose.** All `daysAhead` / horizon
   derivation routes through `util/ForecastHorizon` with an injected `Clock`.
   "Today" is the **Europe/London** civil date; `now` comparisons against solar
   event times stay **UTC**. `ForecastHorizon`'s javadoc enumerates the sites
   deliberately still on UTC (e.g. FORCE_STALE, `PromptTestService.resolveDates`)
   and a separate list of known-unfixed ones. A mixed-calendar file is not
   automatically wrong — check the javadoc lists before flagging. Aurora night
   selection uses an **instant** (`now.isBefore(dawn)`), not a calendar date, and
   must stay that way. The two aurora *task* dates are labels only — nothing
   interprets them.

2. **Two tide axes, never merged.** *What kind* of tide event (spring/king) is
   **lunar** (`LunarPhaseService`, delegating all astronomy to `solar-utils`);
   *which dates* and *how big* are **measured from stored heights**
   (`TideSizeIndex`, per-location thresholds). Never OR the height test into the
   king label; never make spring lunar-only. King trumps spring **per day**,
   never per window. Both hot-topic strategies must fetch slots via
   `findTidalSlot` (the narrower accessors NPE on a run's lagging days).
   `classifyTide` measures perigee from the syzygy instant, not per date.

3. **`forecast_evaluation` is a live second engine, not legacy.** The map view
   (`GET /api/forecast`) and the calibration gate read it. Do not propose
   removing it or "consolidating" it in a review — the dual-engine
   consolidation is a tracked architecture item, not a cleanup. Note also: no
   store keeps per-run rating history; `cached_evaluation` and `forecast_score`
   are latest-wins by design.

4. **Hot topics are recomputed live on every serve.** Build-time enrichment
   must ride an in-memory `AtomicReference` carrier (`NlcClarityService`,
   `MeteorClarityService`, `SurgeCurveService` pattern), never the persisted
   briefing payload — persisting a field the live rebuild lacks *guarantees*
   it is discarded. Series must be `List<Double>`, never `double[]` (record
   equality). After a restart the carrier is empty until the next cycle; the
   degraded pill is deliberate — never synthesise to fill it.

5. **The far-field cloud sample may soften, never escalate.** Measured error is
   bimodal (+2.9pp softening vs ~+68pp hardening). Any change giving the 226 km
   reading confirmatory/escalatory force is wrong regardless of how plausible
   it looks. The cloud-approach demotion is unconditional (not keyed on the
   coned horizon reading).

6. **Inversion scoring: moisture is counted once** (temp-dew gap ≡ RH), and
   **STRONG requires a measured temperature reversal aloft** — no surface-only
   path may reach it; missing profile caps at MODERATE and WARNs.

7. **Confidence channel semantics.** `ConfidenceDeriver` returns **null** for
   zero coverage (must read provisional, not falsely confident). Rating stats
   use the region's *voting* slots; the coverage denominator stays `scoreable`
   (canopy-inclusive) — pessimistic on purpose. Stars/quality are never touched
   by confidence.

8. **Auth gates that look like gaps but aren't.** `/api/tides*`, `/api/almanac`,
   `/api/regions/drive-times`, and everything under `/api/user/settings` are
   Bearer-only with **no role gate**, deliberately (tests pin this). LITE users
   may `POST /api/outcome` — observations are the scarce input to calibration.
   Conversely: `/api/user/settings*` must **never** be added to
   `HttpCachingConfig`'s ETag allow-list (personal data would persist in a
   browser cache JavaScript cannot evict on logout);
   `HttpCachingConfigTest.personalDataPathsAreNeverFiltered` pins it per path.

9. **`HotTopicAggregator` must not serve the almanac feed.** Ten of thirteen
   strategies ignore the date range. `AlmanacSource` is the whole-range
   contract.

10. **Optimisation-strategy `shouldSkip` is guarded by `!triggeredManually` and
    all sync-engine call sites pass manual=true.** Known, documented; don't
    re-report as dead code without checking the memory note first.

### Bugs that were fixed and must not come back

- **`forecast_score` is read in production; the dual-write swallow is
  deliberate but its consequence is not nil.** `ForecastDtoMapper` serves the
  API's Claude BLUEBELL rating from that table and `SurvivorSignalReader`
  reads components for hot topics, so the old "nothing reads it yet / the
  record being proven" comments were false and are corrected. Keep swallowing
  — the serving path must not fail for a secondary write — but the ERROR must
  keep naming the consequence. Rows UPSERT latest-wins, so a lost write is
  staleness until that slot is next evaluated; the one permanent case is a
  slot first evaluated at **T+0**, which gets no later run.

- **An absent air-quality reading is `null`, never `BigDecimal.ZERO`.**
  `OpenMeteoResponseParser.toDecimal` used to zero-fill missing PM2.5, dust
  and AOD. Zero is not neutral for these three: the system prompt grades AOD
  against `0.05-0.15 clean (baseline)`, so a fabricated `0.000` claims
  exceptionally clean air. Air quality returns a 120-hour window against the
  forecast's 168, so this fired on every slot past ~T+4 13:00 UTC. The prompt
  renders `N/A`; a measured zero still renders as zero, and telling the two
  apart is the whole point. Every consumer was already null-safe — the zero
  was defeating those guards, not satisfying them.

- **Accumulated batch results are flushed before any failure path returns.**
  `BatchResultProcessor` collects results in memory and they become durable
  only in `flushAccumulated`. The stream's catch block used to `return`
  before it, discarding every already-parsed (and already-billed) response.
  Flushing partial contents is safe because those writes merge, not replace.
  Residual, on purpose: responses the stream never returned are still lost
  and the batch still goes terminal — replay needs per-response checkpointing.
- **The `forecast_batch` row is persisted immediately after the Anthropic
  call, before job-run bookkeeping.** It is the only thing polling discovers
  work through. If it cannot be written, `submit` throws
  `OrphanedBatchException` — it must **never** return `null`, which every
  caller reads as "nothing was submitted" and the orchestrator turns into a
  terminal zero-batch cycle that briefs from stale cache.
- **The job-run link is a targeted `linkJobRun` UPDATE, never a second
  `save(entity)`.** Writing the row early makes it pollable early, so by the
  time bookkeeping runs the poller may already have completed the batch.
  Merging the in-memory instance back would restore its construction-time
  defaults and revert `COMPLETED` to `SUBMITTED`, putting processed results
  back in the polling set.

- **Every outbound `RestClient` carries timeouts.** The shared bean was
  `RestClient.create()` — no request factory, so no read timeout — while the
  Open-Meteo proxies beside it had 10s/30s for exactly that failure. 14
  production classes share it, including Turnstile on the login path and the
  three health indicators feeding the single-threaded status-SSE scheduler.
  Build from `AppConfig.timeoutRequestFactory()`; give a slow API its own
  longer-lived client rather than dropping the timeouts. A "returns non-null"
  test does not catch this — it passes against the untimed client too.
- **`POST /api/locations` is ADMIN-gated.** It was the only mutation on
  `LocationController` without `@PreAuthorize`, and `/api/**` →
  `.authenticated()` is not a gate. Creation defaults `enabled` to true and a
  coastal one spends a billable WorldTides request inside `LocationService.add`.
- **`CompletableFuture.runAsync`/`supplyAsync` always take an explicit
  executor.** Omitting it selects `ForkJoinPool.commonPool()`, sized for
  CPU-bound work; these calls block on sockets. Use `forecastExecutor`.

### Conventions

- Table `locations` is **plural**; its metadata side-tables
  (`location_tide_type` etc.) are singular. Matching a hand-written query to
  the wrong form is a real, previously-shipped bug class.
- New Flyway migrations: read the latest number from
  `ls src/main/resources/db/migration/ | sort -V | tail -1` **on main** — any
  written-down number has rotted.
- No business logic in controllers; no magic numbers; `RestClient` only (no
  WebFlux); resilience via Resilience4j annotations.
- Tests: be specific in Mockito stubs/verifies — flag broad `any()` /
  `lenient()` usage. Never freeze a test clock to the wall-clock date.
- **Never modify assertions in `src/test/java/.../regression/`** (prompt
  regression tests encode ground truth against real Claude output; only the
  owner updates them).

### Review output

Prioritize findings (P0 crash/data-loss/security, P1 correctness, P2 the rest),
give `file:line` references, and state what was not examined. A finding that
contradicts a rule above needs the evidence spelled out, not just the claim.
