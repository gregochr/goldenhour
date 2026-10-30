# Per-job time zone for CRON scheduler jobs

**Problem (2026-10-30).** `evening_sunrise_refresh` is meant to fire at 21:00 UK every evening. It was seeded
(V184) as `0 0 20 * * *` and `DynamicSchedulerService` evaluates every cron in UTC (`SCHEDULE_ZONE`), so it
fired at 21:00 BST until the clocks went back on 25 Oct 2026 and has fired at 20:00 GMT since (runs #307,
#310, #313). No UTC cron can express "21:00 UK all year".

**Fix.** A nullable `scheduler_job_config.time_zone` column. Null means UTC, so every existing job is
byte-for-byte unchanged. The evening refresh row is set to `Europe/London` with cron `0 0 21 * * *`.

## Scope (one PR)

### Migration `V202__scheduler_job_time_zone.sql`
- `ALTER TABLE scheduler_job_config ADD COLUMN time_zone VARCHAR(40);` (nullable, no default).
- `UPDATE scheduler_job_config SET time_zone = 'Europe/London', cron_expression = '0 0 21 * * *',
  description = 'Re-evaluates tomorrow''s sunrise at 21:00 UK time all year, so the bedtime decision rests on a fresh look'
  WHERE job_key = 'evening_sunrise_refresh';` Status is not touched (it stays whatever the owner set).
- Testcontainers test `SchedulerJobTimeZoneMigrationTest` (pattern: `EveningSunriseRefreshSeedMigrationTest`):
  the evening row has `Europe/London` + `0 0 21 * * *` and its status is unchanged; every other row's
  `time_zone` is null.

### Entity `SchedulerJobConfigEntity`
- `@Column(name = "time_zone", length = 40, updatable = false) private String timeZone;` with getter (and a
  setter for tests/builder, matching the file's style). `updatable = false` because only migrations write
  it; a whole-entity `save()` from `updateSchedule` must never rewrite it.
- Remember `LocalH2EnumWidener` is for enums only; a plain VARCHAR needs nothing for local H2 (Hibernate
  `ddl-auto` adds the column locally — verify how the local profile builds the schema).

### `DynamicSchedulerService`
- Replace the single `SCHEDULE_ZONE` constant with a resolver: `static ZoneId cronZone(SchedulerJobConfigEntity)`
  (package-private, reused by the watchdog) → `ZoneOffset.UTC` when `time_zone` is null/blank, else
  `ZoneId.of(value)`; an invalid id throws `IllegalArgumentException("unknown time zone …")`.
- `prepare(...)` takes the zone; the `CRON` branch builds `new CronTrigger(cron, zone)`. An invalid zone
  makes `prepare` throw, so the row is logged at ERROR and left unscheduled like any other incomplete row
  (the "each row stands alone" rule), and `updateSchedule` refuses with 400 without touching the live future.
- `SOLAR_OFFSET` keeps its fallback cron in UTC (unchanged; `SolarOffsetTrigger` is out of scope). A
  solar row never has `time_zone` set. `FIXED_DELAY` ignores it.
- `calculateNextFireTime`: for CRON use `cron.next(ZonedDateTime.now(clock.withZone(zone))).toInstant()`
  (the service already has a `Clock`; the current code reads the system clock — fix that). Null on an
  invalid zone, as for an unparseable cron.

### `ForecastCycleWatchdog.cronLastFire`
- Takes the job's zone (via the shared resolver) and walks `ZonedDateTime` instead of UTC
  `LocalDateTime`, so *did not start* is judged against the real 21:00 UK fire.
- Update the comment in `checkEveningFinished` ("The evening fires at 20:00 UTC …") to 21:00 UK; the
  look-back logic itself still holds (21:00 UK + a 2 h budget still crosses UK midnight).

### `SchedulerController.toDto`
- Add `"timeZone"`: the stored value, or `"UTC"` when null.

### Frontend `SchedulerView.jsx`
- `describeCron(cron, timeZone)`: when `timeZone === 'Europe/London'`, the hours are already UK time:
  print them as-is with the label `UK time` (e.g. "Daily at 21:00 UK time"); otherwise the existing
  UTC→UK conversion. Pass `job.timeZone` at both CRON call sites (display and the edit preview). The
  solar "Fallback cron (UTC)" line is unchanged.
- PropTypes unchanged (the job object shape is not PropTypes-declared per field; check).

## Tests (Sonnet writes, following `test-improvement-standards.md` / `frontend-test-standards.md`)
- `DynamicSchedulerServiceTest`:
  - a null-zone CRON job still schedules a `CronTrigger` in UTC (regression).
  - a `Europe/London` `0 0 21 * * *` job: with a fixed `Clock` at 2026-10-30T12:00Z the next fire is
    2026-10-30T21:00Z; at 2026-07-01T12:00Z it is 2026-07-01T20:00Z (BST). Across the change:
    clock 2026-10-24T21:30Z → next fire 2026-10-25T21:00Z.
  - an invalid zone (`"Mars/Base"`) leaves the row unscheduled with the others still installed, and
    `calculateNextFireTime` returns null.
  - `updateSchedule` on a London job keeps the zone (the cron edit is interpreted in London).
- `ForecastCycleWatchdogTest`: an evening job in `Europe/London` at 21:00 with no run, now = 21:45 UK
  in winter and in summer → "did not start" alert for the 21:00 UK fire; a run triggered at 21:00 UK →
  no alert. The existing UTC cases keep passing.
- `SchedulerControllerTest` (or wherever `toDto` is covered): `timeZone` is `"UTC"` for null and the
  stored value otherwise.
- Frontend: `describeCron` via `SchedulerView.test.jsx`: a London job renders "Daily at 21:00 UK time";
  a UTC job still renders the converted time.

## Docs in the same PR
- `docs/engineering/reference/platform.md`: "Crons are pinned to UTC" → "Crons run in UTC unless the row's
  `time_zone` says otherwise (V202; only `evening_sunrise_refresh`, `Europe/London` 21:00)"; fix the
  evening paragraph's "`0 0 20 * * *` UTC (21:00 UK in summer, 20:00 in winter)" and the watchdog's
  "from the UTC cron".
- `docs/engineering/reference/database-migrations.md`: a V202 row.
- `changelog.d/20261030-evening-refresh-uk-time.md` (`### Fixed — …`).

## Review amendments (adversarial plan review, 2026-10-30)
1. **CI break:** `integration/EveningSunriseRefreshSeedMigrationTest` asserts `0 0 20 * * *`; all migrations
   run in CI, so update it in this PR (cron `0 0 21 * * *`, `time_zone = Europe/London`, status still
   `PAUSED`; fix its javadoc and DisplayName to say V184 seeds, V202 moves it to UK time).
2. **Prove the firing trigger, not only the display:** capture the `Trigger` passed to
   `taskScheduler.schedule` for a London job and assert its zone / `nextExecution` from a fixed
   `TriggerContext`. Fixed-clock cases need their own service instance (the shared one uses
   `Clock.systemUTC()`).
3. **Resolver exceptions:** `ZoneId.of` throws `DateTimeException`; the resolver catches it and rethrows
   `IllegalArgumentException` (the type `calculateNextFireTime` and `updateSchedule` handle). Test it.
4. **Stale "20:00 UTC" comments to update:** `entity/CycleType.java:30`, `PipelineOrchestrator.java:485`,
   `EveningCandidateCollectionStrategy.java:13`, `FreshnessProperties.java:109` (also note the winter case:
   the evening result now lands about an hour later in GMT, still well before the overnight cycle),
   `ForecastCycleWatchdog.java:69` class javadoc and `:267`; `api-endpoints.md:484` (DTO gains
   `timeZone`). Never edit V184.
5. **Local H2:** `ddl-auto: update` adds the column but V202 never runs locally, so set the local evening
   row by hand for a browser check and say so.
6. `SchedulerView.jsx:450` (solar fallback) and `:610` (switch dialog) deliberately stay UTC.

## Out of scope
- Making the zone editable from the Scheduler screen.
- Moving any other job off UTC.
