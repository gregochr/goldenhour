### Added — Scheduler jobs report their outcome

Every scheduled job now tells the scheduler's fire log what its fire amounted to, instead of
every fire that did not throw being recorded as `SUCCEEDED`. A job reports one of `SUCCEEDED`
(it did its job, including "nothing to do"), `SKIPPED` (a deliberate guard: already running, a
feature switched off, a cycle submission dropped by the cycle guard), `PARTIAL` (some items
failed while others succeeded) or `FAILED` (it caught and swallowed a whole-job failure), with a
one-line plain-English summary such as "3 rows deleted", "No batches in flight", "1 of 3 batches
submitted · 480 requests lost" or "NLCNET scrape failed: connect timed out". Forecast cycles also
link their fire to the pipeline run. Summaries carry counts only, never account details. Behaviour,
retries and logging of the jobs are unchanged; the only non-reporting edit is that
`RefreshTokenRepository.deleteAllByExpiresAtBefore` now returns the number of tokens it removed.
Scheduler redesign phase S2.
