### Removed — VERY_SHORT_TERM, SHORT_TERM and LONG_TERM run types

Phase 3 of retiring the synchronous forecast engine (`docs/engineering/sync-engine-retirement-plan.md`).
The three term constants leave `RunType`, along with `RunType.defaultDateRange` (the Wildlife comfort
refresh now builds its own six-day range). Migration V196 deletes the term-type rows of `model_selection` and
`job_run` (the past manual runs' `api_call_log` rows cascade), clears the Prompt Test rows and drops the
`optimisation_strategy` table. Local H2 files get the same clean-up at startup from `LocalH2TermRunTypeCleanup`.
The unused retry plumbing goes too: `RunProgress.stop`, the retry lock and record, `RetryBlock`, and the
`retryable` and `retryBlockedReason` fields of the run-complete event.
