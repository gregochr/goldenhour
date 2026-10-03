### Added — a place that keeps failing its scheduled runs is auto-disabled, and the admin is told

CLAUDE.md has long said "per-location failure tracking, auto-disable after 3 failures", but the
counting code was never written: `consecutive_failures`, `last_failure_at` and `disabled_reason`
were only ever reset, never set. `LocationFailureService` now counts, under rules chosen so that one
bad night, a fault confined to one lane, or a night when everything failed, can never empty the
roster.

- **Which runs.** Pipeline cycles (nightly and intraday). `PipelineOrchestrator` settles each cycle
  once, inside its BRIEFING phase, after the batch results and the retry have landed and before the
  briefing is built. The Operations-tab forecast buttons and the map's Run Forecast never reach the
  service. The scheduler's "Run now" on the nightly or intraday job runs the identical cycle and
  nothing records the trigger, so it counts like a scheduled one: three presses in a day could
  disable a place (an accepted trade-off). A cycle whose safety timeout fired is not settled, since
  its results are unknown.
- **What a cycle records about a place**, per lane: a scored result or a triage is "got through"; a
  collection error (`SKIPPED_ERROR`, the collector's catch-all, so worded "data could not be
  collected") or an errored result with no success for the place is "failed"; everything else
  (cached, past date, travel day, hard constraint, stability skip, submission failure, no result
  recorded) is nothing. Any success in the cycle, in any lane or in the retry batch, makes the place
  "got through". A retry that finds the slot now triaged records a `SKIPPED_TRIAGED` disposition,
  so the place counts as answered.
- **When a failure is counted.** Once per place per cycle, and only if at least half of the LIKE
  places got through. For a failed Claude result that means the other places with a result in the
  same lane (sky, woodland or bluebell), where triage-only places are not evidence; for a collection
  error, the other places whose collection ran. So a woodland parser regression that fails every
  `wd-` result while 200 sky places score counts nobody, and so does a cycle that fails everything
  (2026-09-29: all 510 candidates failed at once). A place that got through is reset to 0; a place
  with nothing recorded keeps its count.
- **At 3 consecutive counted failures** the place is disabled (`enabled = false`, a fixed-shape
  `disabled_reason` such as "Auto-disabled after 3 consecutive failed scheduled runs (last
  2026-10-02: data could not be collected)." and `last_failure_at`), and the admins get one email
  per cycle through `AdminAlertService`, sent after the settle transaction commits. If more than 5
  places qualify in a single cycle (`MAX_DISABLED_PER_CYCLE`) none is disabled, an ERROR is logged
  and the admins are told that something systemic is wrong; the counters still advance.
- **Settled once per cycle** whatever the order cycles settle in (the last 200 settled run ids are
  remembered in memory; a restart can lose a settle but never repeat one).
- **Known gap.** A cycle that submitted no batch at all (its dispositions sit on an anchor job run
  with no `forecast_batch` row, the 2026-09-29 shape) resolves to nothing and counts nobody. A
  bluebell or woodland request that failed is never retried, so such a failure stands for the cycle.
- The writes are column-scoped updates on `LocationRepository`, so a count cannot overwrite an
  admin's concurrent edit of the same place. No migration.
