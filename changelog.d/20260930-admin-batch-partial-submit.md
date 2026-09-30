### Fixed — admin "Submit scheduled batch" no longer reports failure when only the coastal bucket landed

The admin region-filtered batch submission (`POST /api/admin/batches/submit-scheduled`) splits its
tasks into an inland and a coastal bucket and submits each separately. When the inland bucket had
tasks but its own Anthropic submission failed, and the coastal bucket's submission succeeded, the
endpoint answered 422 "failed" — hiding a live coastal batch. The bug was in the result selection: a
failed submission returns a non-null `EvaluationHandle.empty()` (`batchId() == null`), not `null`,
so `inlandHandle != null ? inlandHandle : coastalHandle` picked the failed inland handle over the
genuinely submitted coastal one. `doSubmitForecastBatchForRegions` now returns the first bucket
Anthropic actually accepted, inland preferred, via a `batchId() != null` check on each handle rather
than a bare null check.

Beyond the misleading failure response, the next press of "Submit scheduled batch" would have hit
409 "already in progress" from the hidden coastal batch that was, in fact, still running.
