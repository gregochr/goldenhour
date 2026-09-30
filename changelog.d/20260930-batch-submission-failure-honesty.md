### Fixed — a fully-failed forecast batch submission no longer reads as a normal night

- **A new disposition, `SUBMISSION_FAILED`**: `ScheduledBatchEvaluationService.submitBuckets`
  now rewrites a candidate's `EVALUATED`/`FORCE_EVALUATED` disposition to `SUBMISSION_FAILED`
  after submission, for every candidate whose task(s) landed only in bucket(s) whose Anthropic
  submission failed — naming the bucket(s) and, for a forced candidate, preserving that fact in
  the detail string. A candidate whose tasks span two buckets (an OPEN_FELL sky+bluebell pair)
  where only one bucket fails keeps its original category — a real request DID reach Claude for
  it — with the partial loss recorded in `detail` instead. The category is excluded, exactly like
  `SKIPPED_CACHED`, from both sides of `findLatestNonCachedDispositions`' correlated subquery (so
  it can never shadow an earlier `SKIPPED_TRIAGED` decision) and from `SupersedingDispositionService`'s
  two-value allow-list (it is not a decision against a slot's existing rating). The frontend
  Disposition Breakdown section shows it right after `EVALUATED`.
- **A new pipeline run status, `DEGRADED`**: the orchestrator still completes WAIT and BRIEFING
  exactly as before — briefing from whatever cache exists is the correct fallback — but when one
  or more forecast batch submissions failed, the `FORECAST_BATCH_SUBMIT` phase is recorded FAILED
  with a detail naming which buckets failed and how many requests were lost, and the run is
  marked `DEGRADED` (never `COMPLETED`) with that same detail as its `failureReason`. `FAILED`
  still outranks `DEGRADED`: a run that fails for an unrelated reason (safety timeout, a briefing
  exception) is unaffected. The Pipeline Runs admin UI shows an amber `DEGRADED` pill and its
  failure reason, distinct from a red `FAILED` one.
- **Every enabled ADMIN with an email is emailed once per degraded run.** A new
  `AdminAlertService` reuses the same `JavaMailSender`/from-address `UserEmailService` already
  sends transactional mail with in production — not the disabled `NotificationChannel`/
  `NotificationDispatcher` machinery, which carries forecast results to end users and was never
  meant to spend budget on an operational alert. The email names the run id, cycle type, trigger
  time (UTC), the per-bucket failure detail, and states plainly that the app is still serving
  ratings from the previous successful run. Best-effort throughout: no mail sender configured, no
  enabled admin with an email, or the send itself failing are all caught and logged, never
  allowed to change the run's status or escape into the orchestrator.
- Born from the 2026-09-29 incident (pipeline run 249): every one of that cycle's three forecast
  batch submissions failed, but the run completed and was recorded `COMPLETED` — indistinguishable
  from a night where every rating was fresh, with nobody told.
