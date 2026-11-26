# Cloud verification backfill: stop exhausting Open-Meteo's archive allowance

Single PR. Backend only. Investigated 2026-11-25.

## What production showed

- `scheduler_fire_log` for `cloud_verification_backfill`, 19–25 Nov: the 00:20 UTC tick verifies ~1,040, the 01:20 tick
  stops PARTIAL after 10 batches, and every tick after that is FAILED "archive returned no observations — stopped
  after 0 batch(es)" until the allowance frees (some days at 14:20 or 06:20, most days not at all). About 20 red
  rows a day in Operations' "Needs a look".
- The prod container's log: `Open-Meteo archive chunk failed (...): 429 Too Many Requests: "{"reason":"Daily API
  request limit exceeded. Please try again tomorrow.","error":true}"`. A `curl` to the archive from the prod host
  returned the same body; a `curl` to `api.open-meteo.com/v1/forecast` from the same host at the same moment
  succeeded. **The archive's allowance is separate from the forecast API's; forecasts are not affected.**
- `cloud_verification` rows per `verified_at` day: 1,588–2,867, every one with `horizon_low_cloud` non-null. No
  blank rows survive (the clear-then-retry loop works), so **no data repair is needed**.
- `forecast_evaluation` per `target_date` (13–19 Nov): ~2,300 evaluations but only ~420 distinct
  `(location_id, target_type)` events, **~5.4 evaluations per event**.

## Cause

Open-Meteo charges one call per **location** in a multi-coordinate request. Each evaluation samples 5 points
(3 cone bearings, observer, far-solar), so ~2,300 evaluations × 5 ≈ 11,500 calls a day against a 10,000 daily
allowance. `SCHEDULED_BATCHES_PER_RUN`'s Javadoc costs a batch at "~25 requests"; the real cost is ~500 calls.

Every evaluation of the same event (each forecast run, each `daysAhead`) asks for the **same five points**:
same location, same date, same solar azimuth. The archive is fetched ~5.4 times for each answer.

Deduplicating within the existing batch would save almost nothing: candidates are ordered
`targetDate, id`, and a forecast run inserts one row per event consecutively, so 100 consecutive ids are ~100
distinct events. The ordering has to change for deduplication to bite.

## Changes

### 1. Fetch each distinct point once (`CloudVerificationService.verifyDate`)

- Build the unique point list per date keyed on the **exact computed coordinates** (a `record PointKey(double lat,
  double lon)` or equivalent; `double` equality is exact here because identical inputs to
  `DirectionalSamplingGeometry` produce identical outputs). Each candidate keeps five indices into the unique list.
- Row building is unchanged and stays per candidate: `findBestIndex` still uses that candidate's own
  `solarEventTime` and `targetType`, so two evaluations that share points but (unexpectedly) differ in event time
  still read their own hour.
- Keying on coordinates rather than `(location, targetType)` means two evaluations whose azimuths differ even
  slightly simply fetch separately: correct either way, never a wrong shared answer.

### 2. Make duplicates land in the same batch (`CloudVerificationRepository.findUnverified`)

- `ORDER BY e.targetDate ASC, e.location.id ASC, e.targetType ASC, e.id ASC`. `countUnverified` has no ORDER BY and
  is unaffected (the two must still agree on the WHERE clause, which does not change).
- A batch of 100 then holds ~18 events, so ~93 calls instead of ~500. An event split across a batch boundary is
  fetched twice: a bounded, small loss.

### 3. Budget the scheduled tick in calls, not batches (`CloudVerificationBackfillRunner`)

- `BackfillBatch` gains `archiveCalls` (the number of **unique points** requested, i.e. calls charged) and
  `quotaExhausted` (boolean). Keep `EMPTY` and `isBlank()`; update every constructor call.
- Replace `SCHEDULED_BATCHES_PER_RUN` with `SCHEDULED_CALL_BUDGET_PER_RUN = 2_500` (half the 5,000/hour cap). The
  scheduled tick runs batches until the next batch would start with the budget already spent (check before each
  batch: stop once `spent >= budget`), the queue is empty, a batch is blank, or the quota is exhausted. Keep a batch
  ceiling as a loop safety stop (`MAX_BATCHES`).
- The manual `start()` path keeps `MAX_BATCHES` and no call budget (an admin asked for it); the quota stop (§4)
  protects it.
- Rewrite the Javadoc with the real arithmetic: one call per location; ~420 events × 5 points ≈ 2,100 calls a day
  after dedup, ~21% of the archive's 10,000 daily allowance; the backlog grows once a day at 00:00 UTC when a new
  target date passes `ARCHIVE_LAG_DAYS`, so one tick normally drains it.

### 4. Stop at the first "request limit exceeded" (`OpenMeteoArchiveClient`)

- Open-Meteo answers a spent allowance as **HTTP 429 with the JSON body**
  `{"reason":"Daily API request limit exceeded. Please try again tomorrow.","error":true}` (seen in prod), and has
  also been seen as HTTP 200 with the same envelope (`rejectErrorPayload`'s Javadoc). Detect both. For a 429, catch
  `RestClientResponseException` and match on `getResponseBodyAsString()` (fall back to `getMessage()`); for the 200
  envelope, match the `reason` that `rejectErrorPayload` reads. **Only `Daily API request limit exceeded` and `Hourly
  API request limit exceeded` (case-insensitive) count as a spent allowance.** ⚠️ `Minutely API request limit
  exceeded` is NOT: the free tier allows 600 calls a minute and the 200 ms chunk pacing exceeds that within a tick,
  so the existing 60 s `hitRateLimit` back-off is what carries a tick past it today. A minutely refusal keeps that
  back-off (make sure the back-off also triggers when the 429 is detected from the body).
- On that, **stop requesting the remaining chunks** of the call and leave their slots null.
- ⚠️ **Do not throw out of `fetchArchiveBatch`.** It carries `@Retry(name = "open-meteo")` and
  `@CircuitBreaker(name = "open-meteo")`, the **same instances the forecast client uses**. An exception escaping
  would be retried three times and could open the shared breaker, cutting off forecast fetches over an archive-only
  quota. Report through the return value instead: change the return type to a small record, e.g.
  `ArchiveBatchResult(List<OpenMeteoForecastResponse> responses, String quotaExhaustedReason)` (null reason when not
  exhausted), keeping `responses` positionally aligned and the same length as `coords`, nulls where unfetched. The
  single-chunk path (`coords.size() <= BATCH_COORD_LIMIT`) gets the same detection.
- The existing 60 s back-off on a plain `429` (`hitRateLimit`) stays for other 429s (a per-minute burst) but must not
  fire for the quota case, which returns immediately instead.

### 5. Report a spent allowance as SKIPPED/PARTIAL, not FAILED (`CloudVerificationBackfillRunner.reportRun`)

- `CloudVerificationService.backfill` sets `quotaExhausted` on the `BackfillBatch` from the client result.
  When the quota is exhausted, **do not save rows whose `horizonLowCloud` is null** (they would only be written
  and deleted again each hour); rows with observations are saved as today, and incomplete ones are still cleared
  by `clearIncompleteVerifications` at the next tick. `rowsWritten` counts rows actually saved.
- In the runner, a quota-exhausted batch ends the run with a distinct outcome, **checked before `isBlank()`**, and
  **after** `status.withBatch(batch.withObservations(), …)` has counted that batch's good rows (otherwise a batch
  that saved 40 rows would report "0 verified"):
  `SKIPPED` with summary `"0 verified · paused: Open-Meteo archive allowance used up; resumes when it resets"` when
  nothing was verified this run, `PARTIAL` with `"<n> verified · paused: …"` otherwise. A blank batch with **no**
  quota signal stays `FAILED` exactly as now (a real outage must still be loud).
- Keep summaries within the 200-character `summary` column.
- `BackfillStatus.lastError` for the admin status endpoint: set it to the same "paused: …" text so the manual-run
  poller shows why it stopped.

- Note (unchanged, out of scope): `start()` calls `run()` on itself, so `@Async` does not apply and the manual run
  executes on the request thread. Do not change that here.

## Tests (backend standards: `docs/engineering/test-improvement-standards.md`; pin every stub argument, no bare
`any()` except in `never()`/lambdas)

- `OpenMeteoArchiveClientRequestTest` (`MockRestServiceServer`, real HTTP 429 with body): 429 with the daily-limit body on chunk 2 of 3 → chunk 3 never requested, result
  carries the reason, slots 2–3 null, slot 1 filled, no exception; HTTP 200 error envelope with "Hourly API request
  limit exceeded" → same; a 429 with the **Minutely** body → back-off behaviour, no quota reason, later chunks still
  requested (make the back-off sleep injectable or overridable so the test does not wait 60 s); a plain 429 without
  that text → old back-off behaviour; single-chunk path with the quota body → reason set.
- `CloudVerificationServiceTest`: five candidates for one event (same location, azimuth, event time) → the client is
  asked for exactly 5 coordinates, and all five rows get the same observations; two events → 10 coordinates;
  positional mapping still correct when duplicates are interleaved; quota reason → `BackfillBatch.quotaExhausted()`;
  `archiveCalls` equals unique points.
- Update every `fetchArchiveBatch` stub in `CloudVerificationServiceTest` (~20) and
  `CloudVerificationServiceEdgeCasesTest` (`:93`) for the new return type, pinning their arguments.
- `CloudVerificationServiceTest`: quota exhausted → rows without observations are not saved.
- `CloudVerificationBackfillRunnerTest` (rewrite the `SCHEDULED_BATCHES_PER_RUN` test at `:186-190`): the call budget stops the scheduled tick (e.g. batches reporting 1,000
  calls → stops after the third); quota on the first batch → `SKIPPED` with the paused summary; quota after progress
  → `PARTIAL`; quota on a batch that itself saved 40 rows → `PARTIAL` "40 verified"; blank without quota → still `FAILED` with today's message; manual run ignores the call budget.
- `CloudVerificationRepositoryTest` (existing H2 `@DataJpaTest`) for the new ORDER BY: duplicates of one event come
  out adjacent even when their ids interleave with another location's.

Coverage: 95% line / 90% branch per class, so the new result record and the point key need their branches covered.
Do NOT edit `V131__seed_cloud_verification_backfill_job.sql` even though it mentions the old constant (Flyway
checksum).

## Docs

- `docs/engineering/reference/platform.md` line ~198, the `cloud_verification_backfill` outcome row: add the
  `SKIPPED`/`PARTIAL` "paused: Open-Meteo archive allowance used up" outcomes.
- `changelog.d/20261125-cloud-verification-archive-quota.md`, `### Fixed — …`.

## Out of scope

- Dropping a sample point, or paying for an Open-Meteo API key.
- Deleting or rewriting existing `cloud_verification` rows (none are blank).
