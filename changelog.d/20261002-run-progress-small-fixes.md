### Fixed — a long run keeps its progress, Retry fires once, and a run with failures is not green

Four small corrections around the run panel and "Retry failed".

- **A long run no longer loses its progress entry.** `RunProgressTracker` evicted every run 30
  minutes after it STARTED. A run still going after that lost its entry mid-run: later task events
  were dropped, `completeRun` found nothing and never sent `run-complete`, and any panel attached
  was left waiting. Now a run that has not completed is never evicted while it is active; a run that
  completed is evicted 30 minutes after it COMPLETED (so the Retry window runs from when the run
  finished, not from when it started); and a run that never completes is evicted only after 60
  minutes with no task event or phase change (the longest silence a healthy run can have is one
  wait for a `claude` bulkhead permit, 120 s, plus one call, about 8 minutes at the outside). A
  subscriber still attached to an evicted run is sent `run-expired` and its stream ended. The
  tracker takes an injected clock, so none of this is tested with a sleep.
- **Retry cannot be started twice, or on a run that is still going.** Two quick presses, or two
  admins, each started a retry of the same slots, and Retry could be called while the original run
  was running. `retry-failed` now answers 409 `{error}`: *This run is still going. Retry is offered
  when it has finished.*; and, for a second retry of one run, *This run has already been retried as
  run N. Retry that run's failures instead.* A retry of the retry run still works, so retries chain.
  The check, the start and the record happen under one per-run lock, so concurrent requests start
  exactly one run. The record is held on the tracker's in-memory entry: it does not survive a restart
  and is forgotten when the entry is evicted. The panel already shows the server's 409 sentence
  through its existing error line, which a second admin's panel now uses.
- **The completion banner is amber when places failed.** A run that finished with some places updated
  and some failed (PARTIAL, no run-level reason) used to show the green *Forecast run completed*
  line. It now shows the amber *Forecast run completed with failures — N locations updated, M
  failed.* with Refresh. A clean run stays green; a failed or stopped-early run is unchanged.
- **Docs.** `CLAUDE.md` described the Claude and Open-Meteo retry as Spring `@Retryable` with
  `MethodRetryPredicate` and `@ConcurrencyLimit(8)`; the code uses Resilience4j `@Retry` +
  `@CircuitBreaker` with plain predicates and `@Bulkhead`. It also listed `RunProgressTracker` among
  the services injected with the Jackson 2 `ObjectMapper` bean; it builds its own.

No migration. The `run_progress_cleanup` job's description in `scheduler_job_config` still says
"older than 30 minutes" (seed data from V68, unchanged here).
