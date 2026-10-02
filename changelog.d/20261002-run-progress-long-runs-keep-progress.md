### Fixed — a long run no longer loses its progress entry

`RunProgressTracker` evicted every run 30 minutes after it STARTED. A run still going after that lost
its entry mid-run: later task events were dropped, `completeRun` found nothing and never sent
`run-complete`, and any panel attached was left waiting. Now only a COMPLETED run is evicted (also one
failed by `failRun`), 30 minutes after it COMPLETED, so the Retry window runs from when the run
finished, not from when it started. A run that has not completed is never evicted, however long it has
been silent: the weather and cloud prefetch records no progress and can wait a minute on each
rate-limited Open-Meteo chunk, so any idle deadline could discard a live run. There is no leak to
bound: every hand-started run reaches `completeRun` or `failRun`, and if the JVM dies the in-memory
tracker dies with it. A subscriber that arrives after an evicted run is told `run-expired` once the
grace period passes. The tracker takes an injected clock, so none of this is tested with a sleep.

No migration. The `run_progress_cleanup` job's description in `scheduler_job_config` (seed data from
V68) still says "older than 30 minutes".
