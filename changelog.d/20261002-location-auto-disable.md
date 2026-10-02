### Added — a place that keeps failing its scheduled runs is auto-disabled, and the admin is told

CLAUDE.md has long said "per-location failure tracking, auto-disable after 3 failures", but the
counting code was never written: `consecutive_failures`, `last_failure_at` and `disabled_reason`
were only ever reset, never set. `LocationFailureService` now counts, under rules chosen so that one
bad night, or a night when everything failed, can never empty the roster.

- **Which runs.** Scheduled pipeline cycles only (nightly and intraday). `PipelineOrchestrator`
  settles each cycle once, inside its BRIEFING phase, after the batch results and the retry have
  landed and before the briefing is built. A hand-started run never reaches the service, and a cycle
  whose safety timeout fired is not settled, since its results are unknown.
- **What counts.** A place's outcome for the cycle is derived from what was recorded: a scored
  result or a triage is "got through"; a collection error (`SKIPPED_ERROR`) or an errored batch
  result with no success for the same place is "failed"; everything else (cached, past date, travel
  day, hard constraint, stability skip, submission failure, no result recorded) is "not attempted".
  Any success in the cycle, in any lane or in the retry batch, makes the place "got through".
- **When a failure is counted.** Once per place per cycle, and only if at least half of the other
  places attempted in that cycle got through. A cycle that fails everything, as on 2026-09-29 when
  all 510 candidates failed at once, counts for nobody. A place that got through is reset to 0; a
  place not attempted keeps its count.
- **At 3 consecutive counted failures** the place is disabled (`enabled = false`, a fixed-shape
  `disabled_reason` such as "Auto-disabled after 3 consecutive failed scheduled runs (last
  2026-10-02: weather data could not be fetched)." and `last_failure_at`), and the admins get one
  email per cycle through `AdminAlertService`. If more than 5 places qualify in a single cycle
  (`MAX_DISABLED_PER_CYCLE`) none is disabled, an ERROR is logged and the admins are told that
  something systemic is wrong; the counters still advance.
- The writes are column-scoped updates on `LocationRepository`, so a count cannot overwrite an
  admin's concurrent edit of the same place. No migration.
