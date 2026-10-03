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
- **Settled once per cycle, one at a time, in trigger order, durably.** Settles run under one in-JVM
  lock (commit included; this is a single-instance app) and the failure count is incremented by the
  database and read back. Each settle claims its cycle with a conditional update of the new
  `pipeline_run.failures_settled_at` column in the same transaction as its counter writes, so a
  cycle is counted exactly once, a run the process stopped before it settled is settled when it
  resumes (the orchestrator settles every run on its way to the briefing), and a run already
  settled is refused.
- **Two settle modes, and a durable retry.** A settle is FULL (counts failures and applies resets)
  only when it runs at the cycle's own tail and the cycle's trigger time is newer than the newest
  already-settled cycle. Any cycle settled later than that (an older cycle after a newer one, e.g.
  an admin's Run now while an older cycle was still waiting; a replay; one recovered at startup)
  is settled RESETS_ONLY: its successes and triage still reset counters, but nothing is counted and
  nothing disabled, because counting it after the newer cycle's success could restart a streak
  that success had broken. A settle that fails (its claim rolls back with it, so the cycle stays
  unclaimed) is logged at ERROR while the run still completes, and a sweep settles it later: at
  startup after the running cycles are resumed, and at the start of every tail settle, it finds
  pipeline runs with no claim triggered in the last 7 days and not still RUNNING, and settles each
  RESETS_ONLY in trigger order. The first deploy of V163 leaves every recent run unclaimed; the
  first sweep over them only zeroes counters that are already zero.
- **A cycle that submitted no batch** (everything cached, skipped or triaged away, or every
  submission failed) keeps its dispositions on an anchor job run that nothing else ties to the
  pipeline run, so the pipeline run now records it (`pipeline_run.disposition_job_run_id`), in the
  same transaction as the disposition rows themselves: a link that cannot be written rolls the rows
  back with it (and fails the submission step visibly) instead of leaving rows with no link, which
  would settle with no evidence and be claimed for good. A cycle
  that legitimately triaged candidates away therefore resets those places, even across a restart; a
  cycle whose submissions all failed (the 2026-09-29 shape) holds only `SUBMISSION_FAILED` rows and
  still counts nobody.
- **Migration V163** adds those two nullable columns to `pipeline_run`. It is proven before merge
  only by CI's Backend job (Testcontainers), since the development machine has no Docker.
- **Known gap.** A bluebell or woodland request that failed is never retried, so such a failure
  stands for the cycle.
- **Success evidence** is read from `forecast_score` rows stamped with the cycle's pipeline run as
  well as from `api_call_log`, because the audit rows are best-effort (a batch whose job-run
  bookkeeping failed logs nothing) and a place that really scored must still be reset. A failure
  still needs positive failure evidence, so a gap can only under-count.
  A cycle whose audit evidence is incomplete (a forecast batch with a null job run, whose results
  were never logged) counts no failures, since a failed row cannot then be shown to be the place's
  last word; its successes still reset.
- **The failure columns are written only by column-scoped updates.** `consecutive_failures`,
  `last_failure_at` and `disabled_reason` are `updatable = false` on `LocationEntity` (as the
  `app_user` settings columns are) and `LocationEntity` is `@DynamicUpdate`, so an admin's
  metadata edit loaded before a settle and saved after it can no longer undo a committed
  disable or restore a reset counter. `enabled` is `updatable = false` too (the admin toggle writes
  it through `updateEnabled` and remains last-writer-wins), because `@DynamicUpdate` alone does not
  stop a detached entity, merged after a settle by a job that loaded it outside a transaction, from
  writing a stale `enabled` back. Those detached jobs (grid-cell backfill, the briefing's grid-cell
  capture, Bortle enrichment) now write only their own columns with scoped updates. No migration.
