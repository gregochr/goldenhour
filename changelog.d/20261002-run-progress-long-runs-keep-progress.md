### Fixed — a long run no longer loses its progress entry

`RunProgressTracker` evicted every run 30 minutes after it STARTED. A run still going after that lost
its entry mid-run: later task events were dropped, `completeRun` found nothing and never sent
`run-complete`, and any panel attached was left waiting. Now a run that has not completed is never
evicted while it is active; a run that completed (or was failed by `failRun`) is evicted 30 minutes
after it COMPLETED, so the Retry window runs from when the run finished, not from when it started;
and a run that never completes is evicted only after 3 hours with no task event or phase change. The
idle bound is that long because a live run has two silent phases, the weather and cloud prefetch
(Open-Meteo in chunks, with a 61 s backoff on each rate-limit response) and a task's wait for a
`claude` bulkhead permit, and an evicted live run never emits `run-complete`; a generous bound costs
nothing, since a run always completes unless the JVM dies, and then the in-memory tracker is gone too.
A subscriber still attached to an evicted run is sent `run-expired` and its stream ended. The tracker
takes an injected clock, so none of this is tested with a sleep.

No migration. The `run_progress_cleanup` job's description in `scheduler_job_config` (seed data from
V68) still says "older than 30 minutes".
