### Changed — one 3 h 30 min safety budget per forecast cycle, and a briefing rebuild when a batch lands late

Solar-relative refresh plan, P2b (D3, B1). The pipeline's wait used to take a fresh `photocast.pipeline.safety-timeout`
(4 h) for the batch wait and again for the retry wait, and a restart restarted the clock, so one cycle could stay
RUNNING for 8 h or more, longer than the shortest gap between two cycles. The deadline is now the cycle's **first
batch submit** (`min(forecast_batch.submitted_at)` for the run) plus the timeout, read from the database on every
poll, so WAIT and RETRY_FAILED share one budget and a restart cannot extend it. The default is now **PT3H30M**
(`application-example.yml` updated; the slowest of 49 observed cycles took 181 min). A run with no batch that has a
submit instant keeps the old now-plus-timeout.

A retry is no longer submitted when under ten minutes of the budget remain: the RETRY_FAILED phase is completed with
the reason and the run carries on to the briefing with those locations left failed, rather than submitting a batch it
cannot wait for and failing a cycle whose original batches all landed.

When a FORECAST batch ends after its pipeline cycle has already finished with it (the run is COMPLETED, DEGRADED or
FAILED, for instance on the safety budget) and no other FORECAST batch of that run is still outstanding,
`LateBatchBriefingRebuild` asks for `BriefingService.refreshBriefingIfIdle()` on a background executor, so the gloss
prose and best-bet picks follow the late ratings. It never persists the cycle's picks, never dispatches the Ask Ready
or almanac refresh, never touches `pipeline_run`, and a rebuild refused because one is already running is logged and
not retried. An ad-hoc batch (no `pipeline_run_id`) and an aurora batch trigger nothing.

**Operator note:** the production host's config is hand-edited. `application-prod.yml` does not set
`photocast.pipeline.safety-timeout`, so the new default applies unless the host's own file overrides it.
