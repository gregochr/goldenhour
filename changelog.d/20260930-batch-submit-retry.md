### Fixed — batch submission now survives a transient Anthropic 5xx, and the diagnostics stop lying when it doesn't

- **2026-09-29 incident, pipeline run 249 (INTRADAY 14:00 UTC): all three Anthropic Batch API
  submissions failed with HTTP 500, one per bucket, and the cycle evaluated nothing** — the SDK
  client's own retry (`ClientOptions.maxRetries = 2`, so 3 attempts within a few seconds) had
  already run and given up before `BatchSubmissionService.submit` ever saw the failure; its
  catch-all logged ERROR and returned `null`, and the afternoon kept serving 01:00's ratings.
- **`AnthropicBatchClient`** is a new, separate `@Service` bean — separate so Resilience4j's
  `@Retry`-style AOP actually intercepts it, though the retry itself is driven programmatically
  via an injected `RetryRegistry` (the class javadoc explains why: the duplicate-batch guard below
  needs per-call state — which attempt this is, and when the first one started — that an
  annotated method has no clean way to carry). It wraps `messages().batches().create(...)` with a
  second, much longer-window retry (`"anthropic-batch"` instance: 4 attempts, 30s → 60s → 120s
  exponential backoff, no circuit breaker of its own — it must not share tripped state with the
  per-location synchronous `"anthropic"` instance) governed by a new `BatchSubmitRetryPredicate`
  (retries `AnthropicServiceException` status ≥ 500 and `AnthropicIoException`/`IOException`
  anywhere in the cause chain; never a 4xx — 429 already has its own `Retry-After` handling inside
  the SDK).
- **Batch creation is not idempotent and carries no idempotency key, so a retry after a failure
  that actually succeeded remotely (a read timeout after acceptance, say) would pay for and
  orphan a second batch nothing tracks.** Before every retry attempt (never the first),
  `AnthropicBatchClient` lists the 20 most recent batches and adopts one instead of creating a
  duplicate when it finds exactly one whose `createdAt` is at or after the first attempt's start
  (5s clock-skew tolerance) and whose total request count matches; more than one match adopts the
  newest and logs every candidate id. The list call itself is best-effort — a failure there just
  proceeds to create, rather than blocking the retry on a diagnostic-only check.
- **`BatchSubmissionService`'s contract is unchanged** (empty list → `null`; a persistence failure
  after a successful create still throws `OrphanedBatchException`; any other exhausted failure logs
  ERROR and returns `null`) but the ERROR line now says how many attempts were made
  (`BatchRetryExhaustedException.getAttempts()`), and its constructor now takes the new
  `AnthropicBatchClient` in place of the raw `AnthropicClient`. Every production caller — scheduled
  buckets, admin, force-submit, JFDI, aurora, and `BatchRetryService`'s RETRY_FAILED phase — already
  goes through this one seam and gets the retry for free; the weekly sky-rating eval harness
  (`SkyRatingEvalBatchClient`, previously calling the SDK directly) was moved onto the same seam too.
- **The `[BATCH DIAG] Submitted N ... requests` line was untrue on failure** — `submitBuckets` logged
  it unconditionally regardless of whether `evaluationService.submit` actually reached Anthropic, so
  the incident's three failed buckets each logged "Submitted" anyway. `logBatchBreakdown` now takes
  the submission's `EvaluationHandle` and logs WARN with the batch id on success or ERROR
  `"[BATCH DIAG] NOT submitted N ... requests (submission failed)"` on failure, and the trailing
  `"Forecast batch split: ..."` INFO line now appends `"submitted K/B buckets"` rather than implying
  every bucket landed. The admin region-filtered `"[BATCH DIAG] Admin batch split"` line no longer
  reports `"(empty)"` for a bucket that had tasks but failed to submit — that reading was
  indistinguishable from a bucket with no tasks at all — and now says `"(failed)"` instead.
- Dispositions and pipeline-run status are unchanged by this change — a failed bucket still records
  `EVALUATED` dispositions and the run still completes normally; fixing that is a separate,
  owner-decision-gated follow-up.
