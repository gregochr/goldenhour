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
any task still in progress, so a run never completes while a place is still "evaluating", and
closing the `job_run` throwing no longer stops the tracker hearing the run is over. Bortle
enrichment, which shared the same unguarded thread, gets the same guard.

A failed batch weather prefetch no longer aborts the run: every place fails individually with
"Weather data could not be fetched (Open-Meteo unavailable).", the run completes and Retry failed has
places to retry. The `run-complete` payload gains a nullable `reason` (a fixed phrase, never an
exception message), and the panel shows it, and keeps a run that failed with no tasks at all, with
Dismiss, instead of clearing it as clean. A run in which every non-skipped place failed now reads
FAILED rather than PARTIAL when other slots were merely skipped. Classifying Claude failures and
aligning `job_run` counts with the tracker are separate follow-ups.
