### Fixed — a hand-started run always reports that it has finished

A forecast run started from the Operations tab or a map popup ran on a thread whose result nobody
read, so any exception escaping the run was silent: `job_run.completed_at` stayed null, the progress
panel sat at "running" and the map popup's spinner never stopped. On 2026-10-02, with Open-Meteo
unreachable, the log said "Open-Meteo batch prefetch failed" and then nothing. A task whose Claude
evaluation failed was also left in `EVALUATING` for good, so even a "completed" run reported
`RUNNING`.

`ForecastCommandExecutor.execute` is now the one guard for all five run endpoints. Whatever
escapes the pipeline, unfinished tasks are published FAILED (one event each, naming the step they
were stuck in), the tracker emits `run-complete` exactly once, and the `job_run` is closed, each
step in its own try; the exception is logged once at ERROR. An `Error` gets the same completion and
is rethrown. A run that fails before it registered with the tracker is registered and completed as
FAILED rather than left to the panel's "no longer available" path. A normal completion also fails
any task still in progress, so a run never completes while a place is still "evaluating". Closing
the `job_run` is tried twice (the in-memory `completedAt` is set before the save that can fail, so it
is not taken as proof the row was closed), a second failure is logged at ERROR, and the tracker is
told the run is over regardless. Bortle enrichment, which shared the same unguarded thread, gets the
same guard. The legacy wildlife engine now registers its tasks before submitting them and reports each
one COMPLETE or FAILED, and on a non-manual run the places the stability filter drops are published
SKIPPED, so neither can be swept to FAILED on a run that went well.

A failed batch weather prefetch no longer aborts the run: every place fails individually with
"Weather data fetch failed for Aira Force SUNSET: Weather data could not be fetched.", the run
completes and Retry failed has places to retry. The run-level reason carries the cause (the Open-Meteo
phrase when the failure is a weather fetch, the generic one otherwise), and the `job_run` is closed
with the failed-task count instead of 0 succeeded / 0 failed, with the log saying weather could not be
fetched rather than that every task was triaged. Task error messages published to the panel are capped
at 200 characters (the full text stays in the server log).

The `run-complete` payload gains a nullable `reason` (a fixed phrase, never an exception message).
The panel shows it, keeps a run that failed with no tasks at all (with Dismiss) instead of clearing it
as clean, reads "(Failed)" in its header for a FAILED run and omits the meaningless "0/0" count of a
zero-task run. The map popup's Run Forecast shows the reason (or a fixed sentence) instead of refreshing
as if something had updated, and the app-wide banner is a red "Forecast run failed" line rather than
a green "completed — 0 locations updated". A run in which every non-skipped place failed now reads
FAILED rather than PARTIAL when other slots were merely skipped. Classifying Claude failures and
aligning `job_run` counts with the tracker are separate follow-ups.
