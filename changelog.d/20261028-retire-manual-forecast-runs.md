### Removed — admin manual forecast runs and optimisation strategy controls

Phase 1 of retiring the synchronous forecast engine (`docs/engineering/sync-engine-retirement-plan.md`).
Production forecasts run only through the batch pipeline, so the admin buttons that still started the
old engine are gone: the map popup's "Run Forecast", the Job Runs Very Short-Term, Short-Term and
Long-Term buttons (with their slot picker), and "Retry failed" on the run progress panel. The backend
endpoints behind them (`POST /api/forecast/run`, `/run/very-short-term`, `/run/short-term`,
`/run/long-term` and `/run/{id}/retry-failed`) are no longer mapped, nor is `PUT /api/models/optimisation`;
`GET /api/models` no longer returns `optimisationStrategies`. The tide refresh dialog loses its region
checkboxes, which never had any effect. Run Config drops the three term tabs and the Sentinel
Sampling and Tide Triage cards (it opens on Briefing), and lists the Bluebell gloss model's run type
on the configurable list. `PUT /api/models/active` now answers 400 for a run type that screen does
not manage. The Job Runs run-type filter offers only types that runs are recorded under, with the
batch entry now labelled "Batch". The app-wide "Forecast run completed" banner is removed: the only
run that still reports completion is the admin's Light Pollution refresh, which should not show every
reader a banner. The engine classes stay in place, unreferenced, until the next phase.
An unknown API path now answers 404 instead of 500.
