# Aurora: a restart must not re-buy a standing alert's scoring

Status: planned 2026-10-30. One phase, one PR.

## The defect, confirmed in production

`AuroraStateCache` holds the alert (`state`, `currentLevel`, `cachedScores`, trigger, counts) in memory only and
starts IDLE. After a restart, the first poll that still reads tonight at MODERATE or above answers NOTIFY
(IDLE + MODERATE/STRONG) and pays for triage plus an `AURORA_EVALUATION` Claude call over every Bortle-eligible
location. Before the restart the same reading had been answering SUPPRESS.

Production, 30 Oct 2026 (Operations → Job Runs): `AURORA_EVALUATION` at 06:00 (v2.35.0, £0.31), 09:50 (v2.35.1,
£0.32) and 11:43 (v2.35.2, £0.38). v2.35.1 was promoted at 09:32 and v2.35.2 at 11:28. The v2.35.2 container's log:

```
11:42:59.840  Started GoldenHourApplication in 23.415 seconds
11:43:02.508  Forecast lookahead: maxKp=5.0 level=MODERATE action=NOTIFY
11:43:02.601  Aurora NOTIFY (MODERATE): 220 candidate location(s) (Bortle ≤ 4)
11:47:21.073  Aurora scoring complete: 220 location(s) scored, highest=3★
12:17:21.432  Forecast lookahead: maxKp=5.0 level=MODERATE action=SUPPRESS   (and every poll after)
```

Same Kp 5.0, same MODERATE: the 11:43 scoring bought nothing new. The 06:00 run had no deploy before it and is
taken to be the alert's genuine first NOTIFY. Two of the day's three runs were restart re-buys.

## Why there is nothing to restore from today

The poller's scoring (`AuroraOrchestrator.scoreAndCache`) writes only to `AuroraStateCache`. It never writes
`aurora_forecast_result`; that table belongs to the manual forecast run (`AuroraForecastRunService`) and the
batch path, and it feeds user surfaces (the Map's night rows, `findForecastDatesWithMinStarsExcludingSimulated`).
Writing the poller's scores there would change what users see, so this plan does not touch it.

## The fix: persist the standing real alert, restore it before the first evaluation

### 1. A one-row table, `aurora_alert_snapshot` (next migration, `V203`)

| column | type | meaning |
|---|---|---|
| `id` | `SMALLINT PRIMARY KEY` (always 1, `CHECK (id = 1)`) | one standing alert at most |
| `night_date` | `DATE NOT NULL` | the poller night that last raised, escalated or confirmed the alert: the **UTC date of `TonightWindow.dusk()`** for the cycle that wrote it |
| `alert_level` | `VARCHAR(20) NOT NULL` | `AlertLevel` name, MODERATE or STRONG |
| `active_since` | `TIMESTAMP WITH TIME ZONE NOT NULL` | `AuroraStateCache.getActiveSince()` |
| `trigger_type` | `VARCHAR(30)` | `TriggerType` name, nullable |
| `trigger_kp` | `DOUBLE PRECISION` | nullable |
| `dark_sky_count` | `INTEGER NOT NULL` | |
| `clear_count` | `INTEGER` | nullable, as in the cache |
| `scores` | `TEXT NOT NULL` | JSON array of `{locationId, stars, alertLevel, cloudPercent, summary, detail}` |
| `held_level` | `VARCHAR(20)` | set while a night poll is holding the alert for a reading (`pendingHold`), else null |
| `held_trigger` | `VARCHAR(30)` | the hold's `TriggerType`, else null |
| `saved_at` | `TIMESTAMP WITH TIME ZONE NOT NULL` | |

There is no `simulated` column, because **a simulation is never written** (see 3).

The enum columns are plain `VARCHAR` written by name, not `@Enumerated` columns on an existing table, so
`LocalH2EnumWidener` needs no line. Local H2 runs `ddl-auto: update`, so the entity creates the table locally.
Flyway proves the migration only in CI. Write it for Postgres.

### 2. `AuroraAlertSnapshotStore` (new, `service/aurora`)

A small transactional component over a repository for the new entity. It does the JSON with the internal
Jackson 2 mapper (`AppConfig.objectMapper()`; not the HTTP path). It has four methods:

- `save(AuroraStateCache.RealAlertView view, LocalDate night)` upserts row 1.
- `confirm(RealAlertView view, LocalDate night)` sets `night_date` when the stored one is older and nulls any
  hold. **If row 1 is missing it upserts from `view`**, which repairs an earlier `save` that failed silently.
  It writes at most once a night and is a no-op when nothing changes. Cache the last confirmed night in memory so
  the repeat calls skip the read too.
- `hold(AlertLevel level, TriggerType trigger)` sets `held_level` and `held_trigger` on row 1. It is a no-op
  when the row is missing.
- `clear()` deletes row 1 if it exists.
- `load()` returns `Optional<Snapshot>` with the scores rebuilt from `locationRepository.findAllById`. A score
  whose location no longer exists is dropped, and a disabled location is kept as the in-memory cache would.

A database failure in `save`/`confirm`/`clear` is logged at WARN and swallowed. Persistence is an optimisation,
and it must never fail a poll or an admin request. A `load` failure logs WARN and restores nothing, which is
today's behaviour.

### 3. Writes follow the in-memory state; simulations never write

`AuroraStateCache` gains:

- `Optional<RealAlertView> realAlertView()`. Under the transition lock it returns state, level, `activeSince`,
  trigger, trigger Kp, counts and scores as one immutable record. It is empty when IDLE **or while a simulation
  is running**.
- `restore(RealAlertView view)`. Under the lock it applies the view only if the machine is IDLE with no
  simulation. Otherwise it does nothing and returns `false`. It writes every field through `become(...)` and then
  sets the scores and counts, keeping the class's "every transition writes every field" rule. It bumps the served
  epoch once.

`AuroraStateCache` itself stays free of persistence (no repository dependency). The callers do the writes:

| Event | Where | Store call |
|---|---|---|
| NOTIFY (new or escalation) | **in a `finally` around `scoreAndCache` at both NOTIFY call sites**, so the no-candidates path, the errored-Claude path and a *thrown* exception (location query, triage, `evaluateNow`) all save. Memory is ACTIVE either way, and an escalation that throws must not leave the older level's row behind | `realAlertView()` → `save(view, night)` (which nulls any hold); skip if empty (a simulation or reset landed meanwhile) |
| NOTIFY whose post-evaluate NOAA fetch failed (alert left ACTIVE, unscored) | `runForecastLookahead` catch branch | `save` the view (ACTIVE, no scores) so a restart does not re-buy it either |
| SUPPRESS | both poll methods | `confirm(view, night)` |
| Night poll **holds** the alert for a reading | `runNightPoll`, where it sets `pendingHold` | `hold(level, trigger)` |
| CLEAR | `runNightPoll`, and the deferred-hold CLEAR in `runForecastLookahead` | `clear()` |
| Admin reset | `AuroraAdminController` reset | `clear()` |
| Admin simulate start | `AuroraAdminController` simulate | `clear()`; memory has dropped the real alert, so the row must go too |
| Admin simulate clear | — | nothing; it only ends a simulation, which was never stored |

`night` is the UTC date of `tonight.dusk()` for the cycle, so the poll methods already hold it. `scoreAndCache`
needs `tonight` threaded through unconditionally; today it gets `null` for a real-time alert, so pass the night
date as its own argument rather than changing what the prompt receives.

The admin controller calls go through the store (or a small service method). Keep the controller free of
business logic, matching how it calls `stateCache.reset()` today.

### 4. Restore once, inside the first guarded cycle, before its evaluation

`AuroraOrchestrator.restoreOnce(TonightWindow tonight)` is called at the top of
`AuroraPollingJob.executePoll()`, after `tonight` is computed and before either poll method. A `restored`
`AtomicBoolean` makes it run once per JVM. It runs inside `runCycleIfIdle`'s guard, so no evaluation can race it.
It does this:

1. `load()` the snapshot. If there is none, return.
2. Let `tonightDate` be the UTC date of `tonight.dusk()`. **If the snapshot's `night_date` is earlier than
   `tonightDate − 1 day`, the snapshot is stale.** Call `clear()`, log INFO
   `Aurora snapshot discarded: night=… is stale (tonight=…)` and return.
3. Otherwise call `stateCache.restore(view)`. Log INFO
   `Aurora alert restored after restart: level=… night=… activeSince=… scores=N held=…`.
4. **If the row carries a hold, re-arm `pendingHold`** with `AuroraPollOutcome.held(held_level, held_trigger)`.
   Without this, a restart between dawn and the first daylight poll would lose the deferred dawn CLEAR
   (`runForecastLookahead`'s `pendingHold` branch). A daylight poll whose forecast is below MODERATE never
   evaluates, so the restored alert would then stand all day where memory would have cleared it. At night,
   re-arming is harmless: the next night poll overwrites `pendingHold` either way.

The window is `night_date ≥ tonightDate − 1` because **that is how long the in-memory alert lives without being
re-evaluated**. At night every poll evaluates (SUPPRESS refreshes `night_date`; CLEAR deletes the row). By day,
no poll ends an alert (aurora.md: "an alert standing at dawn … stands until the next dusk"). So a real alert
held in memory always has a `night_date` of tonight or last night. Anything older means the JVM was down across
a whole night it would have evaluated. Restoring that by day would show a days-old alert until dusk.

Restoring the scores and trigger as well as the level matters. The banner, `/api/aurora/status`, the Map's live
aurora row and the hot topics all read them. Restoring the level alone would show users an alert with nothing
rated, until it cleared.

### What this deliberately does not change

- **`pendingHold` is persisted only as the snapshot's hold columns**, so the dawn-deferred CLEAR survives a
  restart. Nothing else about the hold changes.
- **`AuroraResultHandler` (batch path) writes scores to the cache but not to the snapshot.** That is accepted:
  `aurora_batch_evaluation` is seeded PAUSED (V73), and the plan's own notes say un-pausing it needs reconciling
  anyway. After a restart the poller's last scores come back, not the batch's.
- **Race accepted: restore can undo a reset.** An admin `/reset` landing between `restoreOnce`'s `load()` and
  `restore()` (the first seconds after startup) deletes the row, and the restore then re-applies the alert. The
  next night poll re-evaluates it.
- **No new evaluation, horizon or level rule.** The poll still evaluates once with the same figure. Restore only
  sets the state the previous JVM held.
- **Escalation from a restored alert still pays**, as it would have without the restart.
- **Race accepted:** an admin reset or simulate on a request thread can land between a cycle's `realAlertView()`
  and its `save`, leaving a row for an alert memory has dropped. A later restart would restore it, and the first
  night poll re-evaluates it within five minutes. Daytime exposure is bounded by the staleness window. This is
  documented, not locked.
- **`aurora_forecast_result` is untouched**, and so are the batch path and `AuroraResultHandler`.
- **`AuroraStatusResponse` and the client are unchanged.** `activeSince` is restored, not restamped, so "alert
  since" reads true after a restart.

## Tests (backend standards: `docs/engineering/test-improvement-standards.md`)

- `AuroraStateCacheTest`:
  - `realAlertView` is empty when IDLE and empty during a simulation.
  - `restore` sets every field and bumps the epoch once.
  - `restore` is refused when ACTIVE or simulating.
  - The restored machine answers SUPPRESS for the same level, NOTIFY for a higher one, and CLEAR below MODERATE.
- `AuroraAlertSnapshotStoreTest` (Mockito):
  - save/load round-trip of scores (JSON), with a missing location dropped.
  - `confirm` only moves `night_date` forward, issues no write for the same night, nulls a hold, and upserts
    from the view when the row is missing.
  - `hold` sets the hold columns, and is a no-op with no row.
  - Repository exceptions are swallowed on save/confirm/clear and give an empty result on load.
- `AuroraOrchestratorTest` / `AuroraPollingJobTest`:
  - NOTIFY saves, including when `scoreAndCache` throws (for example triage throwing); SUPPRESS confirms; a hold
    calls `hold`; CLEAR and the deferred-hold CLEAR clear.
  - A restored hold re-arms `pendingHold`, so the first daylight poll makes the deferred CLEAR (an end-to-end
    case in `AuroraPollingCycleTest`: hold at night, restart after dawn, first daylight poll CLEARs, row gone).
  - A simulated NOTIFY saves nothing.
  - `restoreOnce` restores at `night_date` = tonight and tonight−1, discards and clears at tonight−2, runs only
    once, and does nothing when a simulation is running or the cache is already ACTIVE.
- `AuroraPollingCycleTest`: **the production replay.** A daylight MODERATE NOTIFY scores (one Claude call). Then
  a fresh `AuroraStateCache` and orchestrator over the same store simulate the restart. The next daylight poll at
  the same MODERATE must be SUPPRESS with **zero** further `evaluateNow` calls, and the restored scores must
  equal the scored ones. Add a second case where the restart is followed by STRONG: NOTIFY, one call.
- `AuroraAdminControllerTest`: reset and simulate call `clear()`.
- Testcontainers integration test (CI only, no Docker here) for V203 and the entity round-trip.

## Docs

- `docs/engineering/reference/aurora.md`: add a ⚠️ sentence to the state-machine paragraph covering the snapshot,
  the restore window and the simulation exclusion, and point here.
- `docs/engineering/reference/database-migrations.md`: one line for V203.
- `changelog.d/20261030-aurora-restart-restore.md`: `### Fixed — a restart no longer re-buys a standing aurora
  alert's scoring`.

## Separately (owner decision, not code)

The `model_selection` row for `AURORA_EVALUATION` is Sonnet 5.5. Switching it to Haiku would cut each scoring
run's cost several-fold. This plan does not change it.

## Build notes (from the plan review)

- **Entity and migration must agree under `ddl-auto: validate`** (prod, and `IntegrationTestBase`). Copy the
  one-row precedent: `V192__sub_region_picks.sql` (`SMALLINT … CHECK (id = 1)`) and `SubRegionPolicyEntity`
  (`Short id`). Put `columnDefinition = "TEXT"` on `scores` (as `AskReadyAnswerEntity` does). Model the
  integration test on `AskReadyAnswerMigrationTest`: check the columns, types and nullability in
  information_schema, and that inserting `id = 2` violates the CHECK.
- **No `@Transactional` on the store's public methods.** If a caught exception happens inside a proxied
  transaction, the transaction is still marked rollback-only, and `UnexpectedRollbackException` then reaches the
  poll or the admin request. Rely on the repository's own transactions instead. Write `confirm` as one
  `@Transactional @Modifying` repository `UPDATE … SET night_date = :n, held_level = NULL, held_trigger = NULL
  WHERE id = 1 AND (night_date < :n OR held_level IS NOT NULL)`. When it updates 0 rows and `findById` is empty,
  upsert. `hold` is a similar `@Modifying` UPDATE. Each store method wraps its repository calls in
  `catch (RuntimeException e)`.
- **`restore` assigns the fields itself**, under the lock, through a `become` overload that also takes the scores
  and counts, and bumps the epoch once, last. Do not use `become` plus the `update*` setters: each setter bumps,
  and an unlocked reader could see ACTIVE with empty scores.
- **The `AuroraPollingJob` constructor stays unchanged.** `executePoll()` calls `orchestrator.restoreOnce(tonight)`.
  Only the `AuroraOrchestrator` and `AuroraAdminController` constructors gain the store. Their call sites are
  `AuroraOrchestratorTest` ×2, `AuroraPollingCycleTest`, and `AuroraSimulationLifecycleTest:125,129`. Add
  `@MockitoBean AuroraAlertSnapshotStore` to `AbstractControllerTest` so the shared context stays single.
- **With `aurora.enabled=false`, nothing restores**, by design: `poll()` returns before `executePoll()`, so
  `poll_disabled_touchesNothing` stays green. Nothing NOTIFYs either, and the staleness check discards the row on
  re-enable. SUPPRESS must touch the store only, never `stateCache`: the `verifyNoMoreInteractions(stateCache)`
  tests in `AuroraOrchestratorTest` (NONE, SUPPRESS, held) must stay green, so `confirm` takes the view the
  orchestrator reads via `realAlertView()`… **except** that reading the view on every SUPPRESS is a `stateCache`
  interaction. To avoid it, `confirm(night, Supplier<Optional<RealAlertView>>)` calls the supplier only when it
  has to upsert. Then once a night, or never, it touches the cache. Update those tests only where that once-a-night
  read happens.
- **The replay test needs a stateful fake.** Back `AuroraAlertSnapshotStore` in `AuroraPollingCycleTest` with a fake
  `AuroraAlertSnapshotRepository` that holds the row in an `AtomicReference` (`save`, `findById`, `deleteById`,
  and the `@Modifying` updates), so the second orchestrator really reads what the first one wrote. Never stub
  `load()`.
- **JSON** goes through a private `ScoreRow(long locationId, int stars, String alertLevel, int cloudPercent, String
  summary, String detail)` record, never through `AuroraForecastScore` itself, which holds a `LocationEntity`.
  Use the Jackson 2 `ObjectMapper` bean from `AppConfig`.
- `RealAlertView` copies its scores with `List.copyOf` in a compact constructor. Every new public type and method
  gets Javadoc (checkstyle). Each catch branch and each stale, no-row and refused path gets a test (JaCoCo is 80%
  per class).
- Migration number: `V203` (latest is V200). Re-check at branch time. Add the line after
  `database-migrations.md:83`.
