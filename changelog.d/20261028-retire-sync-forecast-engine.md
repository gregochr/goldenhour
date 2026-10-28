### Removed — synchronous forecast engine

Phase 2 of retiring the synchronous forecast engine (`docs/engineering/sync-engine-retirement-plan.md`).
With the admin buttons gone in Phase 1, nothing reached the old per-slot engine, so it is deleted:
`ForecastCommand`, `ForecastCommandFactory`, `ForecastCommandExecutor`, `FailedSlotRetryService`,
`SentinelSelector`, and `ForecastService`'s `runForecasts`, `evaluateAndPersist`, `persistCannedResult`
and wildlife-hourly path. Forecasts evaluate through the batch pipeline only: `EvaluationServiceImpl.evaluateNow`
and `ForecastResultHandler.handleSyncResult` now refuse a forecast task, while Aurora's synchronous path is
unchanged. The Sentinel Sampling and Tide Triage optimisation strategies lose their entity, repository,
service and type (the `optimisation_strategy` table is dropped in a later phase), and the unused tide
alignment triage goes with them. The forecast-result notification channels (email, Pushover, macOS toast)
and their `notifications.email|pushover|macos-toast|schedule` configuration are deleted; admin alert emails
and user emails are unchanged. The run-complete stream `GET /api/forecast/run/notifications` is deleted
(its banner went in Phase 1), along with the stop-on-rejected-key plumbing only the old engine used. The
`forecast` and `claude` bulkhead instances are removed from the YAML profiles. `forecast_evaluation`
and its batch-path writers are untouched.
