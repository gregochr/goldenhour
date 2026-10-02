### Fix — a run that finishes with failures keeps its progress panel, so "Retry failed" can be pressed

On Operations, Data, Job Runs, the run-progress panel was removed in the very tick its run completed,
so the "Retry failed" button (shown only for a finished run with failures) was never on screen and
the retry feature was unreachable from the UI. The panel now decides from the completion payload's own
`failed` count: a run with none clears exactly as before; a run with failures stays, with its failed
places, the Retry button and a "Dismiss" button, and the runs list still reloads. Starting another run
replaces the kept panel, and none of the run buttons is blocked by it. A retry run is followed in a
panel nested under the original and, finished or not, stays until Dismiss. Dismissing puts focus on the
"Forecast Runs" heading; while a retry is out the Retry button is `aria-disabled` rather than
`disabled`, so focus is not dropped.
