### Added — Scheduler fire log

Every fire of a scheduled job (its own schedule, an admin "Run now", or a solar job's startup catch-up) is now
recorded in a new `scheduler_fire_log` table (V215): when it started and finished, how it ended (`SUCCEEDED`,
`PARTIAL`, `FAILED`, `SKIPPED`, or `RUNNING`), a one-line summary and what triggered it. A job reports its outcome
through `SchedulerFireOutcome` (a scoped value, no signature change); one that throws is recorded `FAILED` and the
exception propagates as before, and a failure to write the log never stops a job. Fires left `RUNNING` by a
previous process are marked `FAILED` at startup. A new daily job, `scheduler_fire_log_cleanup` (03:57 UTC), keeps
14 days. `scheduler_job_config` gains `schedule_changed_at`, stamped by schedule edits, type switches, pause and
resume. Backend only: nothing on screen changes yet.

Also fixes a lost-update race: a job's fire wrote `last_fire_time` and `last_completion_time` by reading the row
and saving the whole entity, so a Pause or schedule edit committed while the job was running could be reverted
when the fire ended. Both are now column-scoped updates.
