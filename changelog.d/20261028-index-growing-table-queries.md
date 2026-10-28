### Changed — index the queries that scan growing tables

V196 adds five indexes so queries on the tables that grow without limit stop scanning them: the batch
lookups on `api_call_log (batch_id)`, the hourly pending-evaluation sweep on `forecast_evaluation`, the
per-cycle scored-components read on `forecast_score (pipeline_run_id)`, the nightly by-date read on
`forecast_evaluation (target_date, target_type)` and `job_run (run_type, started_at)`. No behaviour
changes; no index is dropped.
