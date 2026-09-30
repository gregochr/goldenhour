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
  anywhere in the cause chain, plus 429 — see the P1-B round below for why 429 is retried here).
- **Batch creation is not idempotent and carries no idempotency key, so a retry after a failure
  that actually succeeded remotely (a read timeout after acceptance, say) would pay for and
  orphan a second batch nothing tracks.** Before every retry attempt (never the first),
  `AnthropicBatchClient` lists the 20 most recent batches and adopts one instead of creating a
  duplicate when it finds exactly one whose `createdAt` is at or after the first attempt's start
  and whose total request count matches.
- **Round 2 (a Codex review of PR #949, two P1s), before this shipped:**
  - **P1-A — the size+timing match alone could adopt a SIBLING bucket's batch id.** Two buckets of
    the same size, submitted seconds apart in the same cycle, satisfied the original match test
    equally well, and adopting the wrong one would hit `forecast_batch`'s unique
    `anthropic_batch_id` constraint (or, worse under concurrent callers, attach a batch under the
    wrong custom ids). Fixed two ways: (1) a new `ForecastBatchRepository.existsByAnthropicBatchId`
    excludes any candidate already tracked — `BatchSubmissionService` persists a bucket's row
    immediately after `create()` returns, before the next bucket is even built, so every
    genuinely-succeeded sibling is already tracked by the time a later bucket's retry runs this
    check; (2) if MORE THAN ONE untracked candidate still matches, `AnthropicBatchClient` no
    longer guesses — it logs every candidate id at ERROR as a possible orphan needing manual
    recovery and throws a new, deliberately non-retryable `AmbiguousBatchAdoptionException`
    (wrapped in the usual `BatchRetryExhaustedException`), rather than adopting one or creating
    another. The previous 5-second negative clock-skew tolerance was also dropped outright — the
    cutoff is now exactly `firstAttemptStart` (inclusive) — since once tracked siblings are
    excluded, a tolerance's only remaining job was absorbing clock skew, at the cost of reopening
    the same false-match risk in miniature against an unrelated untracked batch.
  - **P1-B — the shared client's own transport-level retry (408/409/429/5xx, up to 3 attempts)
    could fire multiple non-idempotent `create()` calls inside ONE attempt this class's own retry
    loop counted as one**, before the duplicate-batch guard ever ran. `AnthropicBatchClient` now
    derives a client with transport retries disabled (`anthropicClient.withOptions(o ->
    o.maxRetries(0))`) once in the constructor and uses it for every `create()` and `list()` call,
    so every actual HTTP attempt is one this class's own retry (and guard) can see. Consequence:
    with the transport layer no longer retrying 429 for this call, `BatchSubmitRetryPredicate` now
    retries 429 itself — the one 4xx exception, safe specifically because a 429 means the batch was
    never created, so retrying carries none of the duplicate-batch risk the guard exists for. Every
    other 4xx (400/401/403/404/408/409/413) stays non-retryable.
  - The list call itself is best-effort — a failure there just proceeds to create, rather than
    blocking the retry on a diagnostic-only check.
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
