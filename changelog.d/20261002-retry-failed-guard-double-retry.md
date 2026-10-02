### Fixed — "Retry failed" cannot be started twice, or on a run that is still going

Two quick presses, or two admins, each started a retry of the same slots, and Retry could be called
while the original run was running. `retry-failed` now answers 409 `{error}`: *This run is still going.
Retry is offered when it has finished.*; and, for a second retry of one run, *This run has already been
retried as run N. Retry that run's failures instead.* A retry of the retry run still works, so retries
chain. The check, the start and the record happen under one per-run lock, so concurrent requests start
exactly one run. The record is held on the tracker's in-memory entry: it does not survive a restart and
is forgotten when the entry is evicted.

A retry that never ran does not block: if run N completed holding no task at all (it failed before it
registered its tasks), or its tracker entry has been gone for 5 minutes, the original can be retried
again; a run N that ran, even with failures of its own, still takes the retry. The panel already shows
the server's 409 sentence through its existing error line, which a second admin's panel now uses.
