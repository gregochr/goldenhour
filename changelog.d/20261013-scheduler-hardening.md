### Fixed — one job's schedule configuration can no longer stop the other jobs being scheduled

`DynamicSchedulerService` is hardened ahead of any new schedule type (solar-relative-refresh plan, P1).
`initSchedules` isolates each `scheduler_job_config` row: a row with a null or unparseable cron, a null or
non-positive delay, a schedule type this build does not know, or a scheduler that throws for it is logged at
ERROR with its job key and left unscheduled, and every other job is still scheduled. `scheduleJob` switches
explicitly on the type instead of treating everything that is not CRON as FIXED_DELAY. `updateSchedule` builds
and validates the replacement first, installs it, and only then cancels the old future, so a bad request is a 400
that changes nothing and a failure to install never leaves the job unscheduled; it also refuses a job whose
stored type is unrecognised. `resume` of such a job logs ERROR, leaves its status as it was and schedules nothing.

`scheduler_job_config.schedule_type` is now read as a plain string (`updatable = false`, so no save of a loaded
row can write over it) instead of an `@Enumerated`, so a value this version does not know no longer fails the
load of the whole table or `GET /api/admin/scheduler/jobs`; the endpoint reports the raw string. This makes a
later schedule type rollback-safe. No migration: the column is unchanged.

Cron triggers are pinned to UTC (they used the JVM default zone). Production's JVM already runs in UTC, so
nothing changes there; on a London-zoned machine the trigger and the displayed next fire time now agree.
