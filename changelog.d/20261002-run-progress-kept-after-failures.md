### Fix — a run that finishes with failures keeps its progress panel, so "Retry failed" can be pressed

On Operations, Data, Job Runs, the run-progress panel was removed in the very tick its run completed,
so the "Retry failed" button (shown only for a finished run with failures) was never on screen and
the retry feature was unreachable from the UI. The panel now decides from the completion payload's own
`failed` count: a run with none clears exactly as before; a run with failures stays, with its failed
places, the Retry button and a "Dismiss" button, and the runs list still reloads. Starting another run
replaces the kept panel, and none of the run buttons is blocked by it. A retry the server accepts
becomes the active run: the panel follows it by id, it survives a tab switch like any run, and if it
ends with failures it is kept with its own Retry and Dismiss. Dismissing puts focus on the "Forecast
Runs" heading.

Leaving the Job Runs tab and coming back no longer strands the panel: `RunProgressTracker.subscribe()`
now replays the same `run-complete` event (kept until the run is evicted after 30 minutes) to a
subscriber that arrives after the run finished, so a remounted panel completes exactly as a live one
does (a clean run that finished while you were away clears itself; one with failures is kept). A
subscriber to a run the tracker does not hold (evicted, or lost to a restart) is sent a terminal
`run-expired` event after a 5-second grace period (long enough for a run that is only about to be
registered) and sees "This run's progress is no longer available." with Dismiss. The panel's stream
no longer re-opens on every parent render.
