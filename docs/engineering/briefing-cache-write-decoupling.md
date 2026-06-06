# Briefing cache-write decoupling — investigation & scoping

**Status:** Investigate + Scope (no build). Stop for review.
**Incident:** 2026-06-06 — Planner showed empty best-bet ("No standout
recommendations…") and "Showing 0 of 42 cells" despite a clean batch night.
**Scope boundary:** Persistence of scored data. Does **not** touch Hot Topics,
triage, the scoring visitors, or the v2.13.2 sky/tide decomposition.

---

## TL;DR — the headline finding

**The stated hypothesis is not supported by the code.** The incident assumed a
single cause: "a breaker rejection on the briefing's Claude call prevented /
rolled back the `cached_evaluation` write." In the real code those are **two
independent subsystems** and the cache write has **no breaker-protected Claude
call anywhere upstream of it**:

- The `cached_evaluation` write happens on the **batch-result path**
  (`BatchResultProcessor` → `ForecastResultHandler.flushCacheKey` →
  `BriefingEvaluationService.writeFromBatch` → `persistToDb`). That path
  downloads results with the **raw** `AnthropicClient` (no Resilience4j
  annotations) and computes the rating with pure arithmetic
  (`RatingCombiner.combine`). **No `AnthropicApiClient.createMessage` call — the
  only breaker-protected entry point — sits between "batch results ready" and
  the cache write.**
- The 53 `HALF_OPEN` rejections at 14:11 came from the **briefing narrative**
  calls — `BriefingGlossService.generateGlosses` and
  `BriefingBestBetAdvisor.advise` — both of which are wrapped in catch-all
  blocks that **swallow the failure and return a degraded result**. They never
  write the cache and never abort the briefing.

So the incident conflated two simultaneous-but-unrelated symptoms:

| Symptom | Real cause | Breaker involved? |
|---|---|---|
| Empty best-bet ("No standout recommendations") | `advise()` hit the half-open breaker → caught → returned `List.of()` → frontend fell back to the mechanical headline | **Yes** — but the failure is already handled gracefully |
| "Showing 0 of 42 cells" (empty ratings) | `cached_evaluation` genuinely had no `source='BATCH'` rows for the cycle → the batch results never reached the cache | **No** — this path has no breaker-protected call |

**The breaker explains the missing _prose_, not the missing _ratings_.** A
perfectly-tuned breaker would not have changed the empty-cells outcome.

The empty cache has a **different, genuinely-coupled root cause** that the
incident's framing pointed at the wrong layer: `BatchResultProcessor` is an
**all-or-nothing, flush-only-at-the-end, never-retried** pipeline. A single
transient exception while downloading results from Anthropic — plausibly the
*same* blip that tripped the breaker, surfacing on the raw streaming call —
discards the entire batch's already-scored results and marks the batch `FAILED`
forever. That is the real "persistence is hostage to an Anthropic call" defect,
and it is the thing to fix.

---

## Step 0 — The control flow, traced in real code

### 0.1 Where `cached_evaluation` is written (the only writer)

`BriefingEvaluationService.persistToDb` is the sole writer of the
`cached_evaluation` table
([`BriefingEvaluationService.java:341-378`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java)).
It is **private** and called only from `writeFromBatch`
([`:155-164`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java)),
always with `source="BATCH"` (the SSE writer was retired in Pass 3.3.3 — see the
class javadoc at `:38-39`).

`writeFromBatch` has exactly two callers, both in `ForecastResultHandler`:

1. **Batch path** — `flushCacheKey`
   ([`ForecastResultHandler.java:218-220`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java)),
   called once per cache key by `BatchResultProcessor`.
2. **Sync path** — `handleSyncResult`
   ([`ForecastResultHandler.java:253-256`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java)),
   only when `task.writeTarget() == BRIEFING_CACHE`.

### 0.2 The production briefing data path is the batch path — and it is breaker-free

```
ScheduledBatchEvaluationService.submitForecastBatch        (async submit, nightly)
  → ForecastTaskCollector.collectScheduledBatches          (dispositions EVALUATED, buckets tasks
                                                            with WriteTarget.BRIEFING_CACHE — :402)
  → evaluationService.submit(...)                          (batch submitted to Anthropic)
        … hours later, Anthropic finishes …
BatchPollingService.pollPendingBatches  (every 60s)         (raw client .retrieve — BatchPollingService:98)
  → status == ENDED → BatchResultProcessor.processResults
       → processForecastBatch                               (BatchResultProcessor:130)
            → anthropicClient.messages().batches()
                  .resultsStreaming(...)                    (RAW client, line 145 — NOT breaker-wrapped)
            → ForecastResultHandler.parseBatchResponse      (per response, line 261)
                  → buildResult → RatingCombiner.combine     (pure arithmetic — no Claude)
            → flushCacheKey(...) per cache key              (after the loop, lines 288-290)
                  → writeFromBatch → persistToDb            (the cache write)
```

Two facts kill the stated hypothesis on this path:

- **Result download uses the raw SDK client.** `BatchResultProcessor` injects
  `AnthropicClient anthropicClient`
  ([`:63`, `:145`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchResultProcessor.java)),
  not the resilient `AnthropicApiClient`. The `@CircuitBreaker(name="anthropic")`
  annotation lives **only** on `AnthropicApiClient.createMessage`
  ([`AnthropicApiClient.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/AnthropicApiClient.java)).
  The breaker cannot reject `resultsStreaming` or `retrieve`.
- **The rating is computed without Claude.** `buildResult`
  ([`ForecastResultHandler.java:288-307`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java))
  calls `ratingCombiner.combine`, which is a plain arithmetic mean over the
  already-parsed sky score plus the deterministic tide visitor
  ([`RatingCombiner.java:57-69`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/visitor/RatingCombiner.java)).
  "Sky (Claude)" in v2.13.2 means the sky value was *parsed from the batch
  response*, not re-fetched. No call is made here.

### 0.3 The briefing's Claude calls sit AFTER the cache and fail soft

`BriefingService.refresh` order
([`BriefingService.java:310-321`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java)):

```java
days = enrichWithCachedScores(days);          // :310 — READS cached_evaluation
if (succeeded > 0) {
    days = glossService.generateGlosses(...);  // :314 — Claude (breaker), per region/event
}
List<BestBet> bestBets = succeeded > 0
        ? bestBetAdvisor.advise(...)           // :319 — Claude (breaker), single call
        : List.of();
```

Both Claude steps **read** the cache and run **after** it. Both are fully
caught:

- `BriefingBestBetAdvisor.advise` wraps its entire body — including the
  `anthropicApiClient.createMessage` at `:454` — in
  `try { … } catch (Exception e) { return List.of(); }`
  ([`:475-478`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BriefingBestBetAdvisor.java)).
  A breaker rejection yields **empty picks**, never a cache write or a thrown
  exception.
- `BriefingGlossService.callGloss` catches per-item and leaves the gloss null on
  failure (gloss exception handler logs and continues).

Neither advisor calls `writeFromBatch`. Neither can roll back or skip the cache.

### 0.4 The one place a Claude failure DOES skip a cache write — and why it wasn't this incident

On the **sync** path, `EvaluationServiceImpl.evaluateNowForecast` makes the
breaker-protected `anthropicApiClient.createMessage` call at
[`:201-212`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/EvaluationServiceImpl.java),
and only afterward calls `handler.handleSyncResult` (`:227`). On failure,
`handleSyncResult` returns `Errored` **before** the `writeFromBatch` line and the
cache write is skipped
([`ForecastResultHandler.java:229-234`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java)).
**This is a real latent coupling — the Claude call precedes and gates the cache
write in the same method.**

But it was **not** the 2026-06-06 mechanism, because the only forecast caller of
`evaluateNow` is `ForecastService.java:440`, and it passes
`WriteTarget.NONE` ([`:439`](../../backend/src/main/java/com/gregochr/goldenhour/service/ForecastService.java)) —
so even on success this path never writes `cached_evaluation`. The briefing
Planner is fed exclusively by the batch path. The sync coupling is worth closing
defensively (Step 2.4) but did not strand the 230.

### 0.5 Why "230 EVALUATED" never meant "230 cached"

`DispositionCategory.EVALUATED` is stamped at **collection/submission time**, not
at result time —
[`ForecastTaskCollector.java:428-429`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java)
records `EVALUATED` the moment a candidate is bucketed for batch submission.
So `forecast_run_disposition = 230 EVALUATED` means **"230 candidates were
submitted to a batch"**, full stop. It says nothing about whether the results
came back and were cached. The divergence the incident flagged
(`EVALUATED=230` vs `BATCH cache rows=0`) is exactly the gap between *submitted*
and *persisted*, and is the invariant we should start asserting (Step 3.3).

### 0.6 Where the 230 scored results actually went — the confirmed mechanism

`cached_evaluation` is the only place batch ratings land for the Planner;
`forecast_evaluation` is written only by the sync `ForecastService` path
(`WriteTarget.NONE`), which the batch never touches. So the ratings did not go to
the wrong table — they were **never persisted**. The code has a single
all-or-nothing seam where that happens
([`BatchResultProcessor.processForecastBatch`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchResultProcessor.java)):

1. Results are parsed in a loop and **accumulated in an in-memory `Map byKey`**
   (`:131`, `:263-267`). **Nothing is flushed until the entire stream
   completes** (`:288-290`).
2. If `resultsStreaming(...)` throws **anywhere mid-stream** — a transient
   network/Anthropic error, the very kind that also tripped the breaker — control
   jumps to the `catch` at `:272-277`, which calls `markFailed` and `return`s.
   **`byKey` is discarded. Every already-parsed result in it is lost**, even the
   ones that streamed fine before the error.
3. `markFailed` sets `status = FAILED` (`:615-616`). The poller only ever
   re-examines `SUBMITTED` batches
   ([`BatchPollingService.java:81`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchPollingService.java)).
   **There is no reaper and no retry for `FAILED` (or `COMPLETED`) batches** —
   confirmed against `ForecastBatchRepository` (only `findByStatus…(SUBMITTED)`
   and admin listings). Anthropic retains the results for ~29 days, but our code
   never asks again. **Permanent loss.**

Note the status semantics: `status = succeeded > 0 ? COMPLETED : FAILED` (`:298`)
is only reached if the stream finishes without throwing. If the incident's
"`187 = 213/213` COMPLETED" reading comes from the **Anthropic request_counts**
mirrored onto `job_run` during polling
([`BatchPollingService.java:110-115`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchPollingService.java)),
then the Anthropic side genuinely succeeded 213/213 while our
`forecast_batch.status` could still be `FAILED` with zero flush — which reconciles
"Anthropic succeeded" + "zero BATCH rows" perfectly. (The alternative — `status`
truly `COMPLETED` yet zero rows — would require `persistToDb` to have caught a DB/
serialization error for all keys at `:374-377`, which is unrelated to Anthropic
and would show `"Failed to persist evaluation cache"` WARNs.) **Confirming which
requires the two log lines and the real `forecast_batch.status` — see Step 3.4.**

---

## Step 1 — Breaker config assessment (secondary)

The `anthropic` circuit breaker is configured identically across all profiles
(`application-example.yml`, `application-local.yml`, `application-prod.yml`;
test omits the auto-transition flag):

| Property | Value |
|---|---|
| `slidingWindowType` | `COUNT_BASED` |
| `slidingWindowSize` | `10` |
| `minimumNumberOfCalls` | `5` |
| `failureRateThreshold` | `50%` |
| `waitDurationInOpenState` | `60s` |
| `permittedNumberOfCallsInHalfOpenState` | `3` |
| `automaticTransitionFromOpenToHalfOpenEnabled` | `true` |

Retry (`anthropic`): `maxAttempts=4`, `waitDuration=1s`, exponential ×2,
`exponentialMaxWaitDuration=30s`.

**Assessment.** The 53 `HALF_OPEN` rejections are a direct artefact of this
config meeting the briefing's **fan-out**, not of an over-long closure:

- After a trip, the breaker waits 60s, then auto-transitions to `HALF_OPEN` and
  permits only **3** probe calls. The briefing's narrative phase fires a **burst**
  of Claude calls — one gloss per GO/MARGINAL region-event plus the best-bet —
  easily 50+ near-simultaneous calls. In `HALF_OPEN`, all but 3 are rejected
  outright with the exact "does not permit further calls" message seen at 14:11.
  53 rejections is consistent with ~one briefing's worth of gloss/best-bet fan-out
  arriving inside the probe window.
- `waitDurationInOpenState=60s` is **not** itself unreasonable for a seconds-long
  blip; the problem is that a *burst* of callers hitting the narrow `HALF_OPEN`
  gate produces mass rejections even though Anthropic had already recovered.

**Verdict:** the breaker config is a plausible contributor to the **empty
best-bet prose** (the burst out-runs the 3-call half-open gate), but it is **not**
why the cells were empty. **Do not treat breaker tuning as the fix.** If we later
want to soften the prose failure, the lever is the *narrative fan-out* (sequence
or bulkhead the gloss calls, which already partly happens) rather than widening
the breaker. Out of scope for the persistence fix.

---

## Step 2 — Decoupling design (the fix, designed not built)

The guiding principle from the incident is correct even though its mechanism was
mis-located: **scored data must persist the moment it is parsed, independent of
any Anthropic-infrastructure call (breaker-protected or raw) and independent of
the narrative step.** The work is to make `BatchResultProcessor` durable and to
close the latent sync coupling.

### 2.1 Recommended: persist incrementally inside the batch loop, isolated from streaming failure

**Recommendation: flush per result (or per small group) as it is parsed, in its
own transaction, inside a failure-isolating boundary — never accumulate the whole
batch in memory and flush only at the end.**

Concretely, in `processForecastBatch`:

- Replace the accumulate-then-flush-at-`:288` model with an **incremental
  upsert**: after each successful `parseBatchResponse`, write that result to
  `cached_evaluation` immediately. Because the Planner reads per
  `region|date|targetType` key, group within the loop and flush a key as soon as
  its region's responses are seen, or simplest: upsert-merge each result into its
  key's row as it arrives (the row is small and the upsert is idempotent —
  §2.3/§2.4).
- Wrap the **streaming download** so that an exception mid-stream **flushes
  whatever has already been parsed** before marking the batch failed. The current
  `catch` at `:272-277` must not discard `byKey`. At minimum: in the `catch`,
  flush the accumulated `byKey` first, then record the partial failure.
- A batch that streamed *some* results successfully should **not** be a dead
  `FAILED` with zero persistence. Either mark it `COMPLETED` with an errored-count
  reflecting the truncation, or introduce a re-processable state (§2.5).

Why this over the alternatives:

- *Persist from the batch step before any narrative call* — already true; the
  narrative runs in a different service entirely. The real gap is **within** the
  batch step. So the fix belongs here, at the single shared
  `ForecastResultHandler`/`BatchResultProcessor` seam from v2.13.2, not in the
  briefing flow.
- *Reorder the briefing flow so persist commits before the Claude call* — not
  applicable; the briefing flow does not persist the cells at all (it only reads
  them and writes narrative). Reordering it changes nothing.

### 2.2 Graceful degradation of the narrative step (already partly present — make it complete)

When the breaker blocks the briefing's Claude calls, the user should see **the
scored ratings + a degraded/absent narrative**, never an empty Planner. Current
state:

- Best-bet failure already falls back to the **mechanical headline**
  (`advise` returns `List.of()`; `headlineGenerator.generateHeadline(days)` runs
  unconditionally at `BriefingService.java:317`). Gloss failure already leaves a
  null one-liner. **These fallbacks are correct and need no change** — *provided
  the cells are populated.*
- The defect is therefore upstream: with §2.1 the cells are durable, so the
  existing mechanical-headline fallback produces a sensible Planner from persisted
  ratings even with zero Claude narrative. **The best-bet does not need a new
  non-Claude selector — `generateHeadline` already is one.** (If we want a richer
  non-Claude "best bet" over persisted ratings later, that is a follow-up, not
  required to close this incident.)

### 2.3 Transaction boundaries

- `persistToDb` is **not** `@Transactional` and `BatchResultProcessor` /
  `BatchPollingService` are **not** `@Transactional` either (confirmed — no
  annotations). Each `cachedEvaluationRepository.save` therefore commits in its
  own default transaction. **There is no shared transaction that a later failure
  could roll back** — so the "rolled back" half of the hypothesis is also
  unsupported.
- The design must keep it that way: the incremental per-key upsert (§2.1) must
  commit independently so a later streaming error cannot undo earlier writes.
  Make the per-key flush explicitly its own transaction (e.g. a
  `@Transactional(propagation = REQUIRES_NEW)` flush method, or keep relying on
  the per-`save` autocommit) and ensure no enclosing `@Transactional` is
  introduced around the stream loop.

### 2.4 Idempotency / re-run safety

`persistToDb` already upserts by cache key (`findByCacheKey().orElseGet(new)`,
[`:352-359`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java)),
and the in-memory cache `put` replaces by key. So a re-run (or the proposed
reprocess of a stranded batch) **safely overwrites** rather than duplicating.
Two caveats for the incremental design:

- If we flush a key incrementally *as results arrive*, a key with N locations
  will be written N times (growing each time). That is fine for correctness
  (idempotent upsert, last-write-wins) but means an early read sees a partial
  region. Acceptable — partial-but-present beats empty. If undesirable, buffer per
  key and flush once the key is complete, while still flushing the buffer in the
  stream `catch`.
- `writeFromBatch` also writes `evaluation_delta_log`
  ([`:171-223`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java));
  repeated incremental writes would emit extra delta rows. Gate delta logging to
  the complete-key flush, or accept the extra rows (already WARN-isolated).

### 2.5 Reprocess stranded batches (close the permanent-loss gap)

Independent of incremental flush, add a path to recover a batch whose Anthropic
results succeeded but whose local processing failed:

- Either **do not mark `FAILED`** when the stream threw after partial success
  (use a re-processable status the poller will retry), **or** add a small reaper
  that finds `FAILED` forecast batches whose `endedAt` is recent and whose
  Anthropic `request_counts.succeeded > 0`, and re-runs `processResults`
  (idempotent thanks to §2.4). Anthropic keeps results ~29 days
  ([`BatchPollingService.java:124-129`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchPollingService.java)
  documents this), so reprocessing is viable well after the fact.

### 2.6 Close the latent sync coupling (defensive)

In `handleSyncResult`, the cache write is skipped when the (preceding)
breaker-protected Claude call failed (§0.4). This path is dormant for the briefing
today (`WriteTarget.NONE`) but is a loaded gun for any future force-evaluate/JFDI
caller that sets `BRIEFING_CACHE`. Low-cost hardening: keep the parse→combine→
persist ordering such that a *successful* parse always persists, and ensure no new
caller can introduce a Claude-call-then-persist-in-one-try shape. No behavioural
change needed now; flag it so it is not reintroduced.

---

## Step 3 — Verification plan (prove scored data survives a breaker/transport blip)

### 3.1 Regression guard: streaming throws mid-stream → already-parsed ratings still persisted

The core test. Drive `BatchResultProcessor.processForecastBatch` with a
`resultsStreaming` iterator that yields a few **succeeded** responses and then
**throws** (simulating the transient Anthropic/transport error that coincided with
the breaker trip). Assert:

- `BriefingEvaluationService.writeFromBatch` / `persistToDb` was invoked for the
  results that streamed **before** the exception (today it is invoked **zero**
  times — `byKey` is discarded). This is the failing-then-fixed assertion.
- The corresponding `cached_evaluation` rows exist with `source='BATCH'`.

This directly encodes the "230-evaluations-survive" case without needing a real
breaker trip.

### 3.2 Best-bet/Planner produces a sensible result from persisted ratings with no Claude narrative

With `cached_evaluation` populated but `AnthropicApiClient.createMessage`
stubbed to throw (breaker-open simulation), assert `BriefingService.refresh`:

- still returns a Planner with the persisted cell ratings (non-zero "cells"),
- falls back to the mechanical headline (`headlineGenerator`), and
- returns empty/absent best-bet and null glosses without throwing.

(Most of this is existing behaviour; the test locks it as a contract so a future
change cannot couple the cells to the narrative.)

### 3.3 Reprocess idempotency

Process the same batch twice (or reprocess a `FAILED`-but-results-available batch
per §2.5) and assert no duplicate `cached_evaluation` rows and last-write-wins
ratings.

### 3.4 Empirical post-deploy invariant (and the missing diagnostic for THIS incident)

On any night, **`cached_evaluation` must have `source='BATCH'` rows whenever
`forecast_run_disposition` shows `EVALUATED > 0`.** Operationalise it:

- Emit/clarify the two existing INFO logs as the audit trail:
  `"Forecast batch complete: … {} cache keys written"`
  ([`BatchResultProcessor.java:292`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchResultProcessor.java))
  and `"BATCH results persisted to DB for key: …"`
  ([`BriefingEvaluationService.java:369`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java)).
  Their **absence** for a cycle with `EVALUATED > 0` is the signature of this bug.
- To *confirm* the 2026-06-06 mechanism beyond the code analysis, check for that
  cycle: (a) the real `forecast_batch.status` for 187/188 (`FAILED` ⇒ §0.6
  stream-throw path; `COMPLETED` ⇒ look for `persistToDb` WARNs instead), (b) any
  `"Failed to stream results"` ERROR at `:273`, (c) any `"Failed to persist
  evaluation cache"` WARN at `:375`. One of these will distinguish "stream threw,
  results discarded" from "flushed but DB-rejected".

---

## What this does and does not change

- **Refutes** the literal hypothesis (breaker rejection on a briefing Claude call
  prevented/rolled back the cache write). The cache write is already off the
  breaker path and in its own transaction.
- **Re-locates** the real defect to `BatchResultProcessor`'s all-or-nothing,
  flush-at-end, never-retried result handling — which *is* a persistence-hostage-
  to-an-Anthropic-call coupling, just the raw streaming download rather than the
  breaker-protected message call.
- **Keeps** the existing graceful narrative fallbacks (mechanical headline, null
  gloss); they are correct once the cells are durable.
- **Does not** touch triage, scoring visitors, the v2.13.2 decomposition, Hot
  Topics, or the breaker config.

**Single most important fact (answering the brief's core question):** No — a
breaker rejection on the briefing's Claude call does **not** currently prevent or
roll back the `cached_evaluation` write on the batch path; that write has no
breaker-protected call upstream and commits in its own transaction. The scored
data was stranded **inside `BatchResultProcessor`**, where a transient
results-download error discards the whole batch's parsed-but-unflushed results and
marks the batch permanently `FAILED`. The fix is to flush each parsed result
durably as it arrives (and to recover stranded batches), not to move a persist
"ahead of" a Claude call that does not gate it.
