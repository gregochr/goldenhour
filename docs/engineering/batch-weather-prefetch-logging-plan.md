# Batch weather pre-fetch on the job run — plan

**Status:** planned 2026-10-31. One PR.

## Why

On 2026-10-31 the owner used up the day's Open-Meteo free-tier quota in the afternoon. The 21:05
SCHEDULED_BATCH run then showed 22/22, All OK, with only an ANTHROPIC group in its API Call
Breakdown. The batch path's weather pre-fetch (`ForecastTaskCollector` →
`BatchWeatherPrefetcher.prefetchBatchWeather` → `OpenMeteoService.prefetchWeatherBatchResilient`
→ `OpenMeteoClient.fetchForecastBriefingBatch` / `fetchAirQualityBatchResilient`, plus the cloud
pre-fetch) writes no `api_call_log` rows. So nothing on the run says whether Open-Meteo answered,
partly answered or returned 429s. The production log is the only record.

There is a second gap. When the pre-fetch aborts (0 locations, or below
`minPrefetchSuccessRatio`), the cycle either gets a disposition-only anchor run whose note says
"all candidates cached/skipped", or, when the first pass produced no dispositions, **no run at
all** (`ScheduledBatchEvaluationService.persistCycleDispositions` returns early). An outage is
invisible in the Runs list.

## Design

**Capture.** The job run does not exist when the weather is fetched (collect happens before
submit), so the outcome travels as a value.
- New record `model/PrefetchCall`: `ServiceName service` (existing `OPEN_METEO_FORECAST` /
  `OPEN_METEO_AIR_QUALITY`; no new enum value), `String label` (e.g. `batch-prefetch forecast
  chunk 2/5`), `int coordsRequested`, `int coordsReturned`, `LocalDateTime calledAt` (UTC),
  `long durationMs`, `Integer statusCode`, `boolean succeeded`, `String error`, `int attempts`.
- `OpenMeteoClient`: new `fetchForecastBriefingBatch(coords, Consumer<PrefetchCall> sink)` and
  `fetchAirQualityBatchResilient(coords, sink)`, carrying the same Resilience4j annotations as the
  existing methods. Move the bodies into private implementations. The existing one-argument
  methods (used by `BriefingWeatherLoader`, `WildlifeComfortRefreshJob`) call the private
  implementation with a no-op sink, never the annotated overload (a self-call would skip the
  proxy). One `PrefetchCall` per chunk, in both the success and the catch branch, including the
  single-chunk path (try/catch, record, rethrow; behaviour unchanged). `durationMs` covers the chunk
  and its own retries, not the gap between chunks. Status code: walk the cause chain for a
  `RestClientResponseException`; else 429 if the message contains "429"; else null.
- `OpenMeteoService.prefetchWeatherBatchResilient(coords, sink)` replaces the one-argument version
  (its only caller is the batch pre-fetcher). In its two catch blocks, record one call-level
  failure row **only if the client recorded no row for that service**. This covers a circuit
  breaker `CallNotPermittedException` or a rate-limiter rejection, which fail before the method
  body runs (the `open-meteo-briefing` breaker is shared with the briefing refresh).
- Cloud pre-fetch: `prefetchCloudBatch` / `prefetchPressureLevels` take a sink instead of their
  `JobRunEntity` parameter (its only caller passes null) and emit one call-level row each with
  requested/returned counts. The cloud batch is the largest user of the quota. The inversion
  profile fetch stays out (best effort, own fetcher).
- `BatchWeatherPrefetcher.prefetchBatchWeather(candidates, sink)` and
  `prefetchBatchCloudPoints(candidates, weather, sink)`.

**Carry.**
- New record `service/batch/WeatherPrefetchReport`: `List<PrefetchCall> calls`,
  `int locationsRequested`, `int locationsReturned`, `String abortReason` (null unless aborted);
  `none()`, `attempted()`, `aborted()`.
- `ScheduledBatchTasks` and `RegionFilteredBatchTasks` gain a `weatherPrefetch` component. Keep
  the existing constructors, defaulting to `none()`, so existing test sites compile unchanged.
  `RegionFilteredBatchTasks` gets an `aborted(report)` factory alongside `empty()`.
- `ForecastTaskCollector`: a per-call local `ArrayList<PrefetchCall>` sink. Attach the report on
  every return after the pre-fetch: zero results (abortReason `0/N locations returned`), below
  threshold (`x/N locations returned, ratio r below threshold t`), nothing left after triage, and
  the normal return. The same in `collectRegionFilteredBatches`.

**Write.**
- New `JobRunService.logPrefetchCalls(Long jobRunId, List<PrefetchCall> calls)`: one
  `ApiCallLogEntity` per call via `saveAll`; `calledAt` from the call, `completedAt = calledAt +
  durationMs` (not `now`); `requestMethod "GET"`, `requestUrl = label`, `isBatch = false`, no
  `batchId`/`customId`; cost from `CostCalculator.calculateFlatCostMicroDollars` (0 today).
  `ApiCallLogDto` does not expose `requestUrl`, so a failed row's `errorMessage` carries the whole
  story, e.g. `chunk 3/5: 20 coords, 0 returned, 1 attempt — 429 …`, truncated to 500.
- **One run per cycle**: the rows go where the cycle's dispositions go.
  - In `submitBuckets`, after `persistCycleDispositions`: the first submitted bucket's run;
    otherwise the anchor `persistCycleDispositions` created (make it return the id); otherwise, if
    `report.attempted()`, a new anchor run. That last case closes the "no run at all" gap.
  - `startDispositionAnchorRun(int candidateCount, String notes)` overload. When the report is
    aborted the note reads `Weather pre-fetch aborted — 3/42 locations returned (Open-Meteo); no
    batch submitted`.
  - Best effort: write after the disposition persist, inside `try/catch (RuntimeException)` with a
    WARN, so it never rolls back or fails the cycle.
  - The same helper in `persistOnOrphan` before its rethrow.
  - Admin region path: write to the first non-null `jobRunId` of the inland/coastal handles. On an
    admin-path abort, log only (no anchor: `startDispositionAnchorRun` is SCHEDULED_BATCH-shaped).
- Rows are **not** copied to the cycle's other bucket runs (that would double the counts in
  `MetricsSummary`).

**Frontend.** `JobRunDetail.jsx`: `isBatchWithNoApiCalls` is `batchSummary && serviceStats is
empty`. With weather rows on the run, the "ANTHROPIC (Batch)" card (SUBMITTED/FAILED badge,
"batch failed before processing") would vanish for an in-flight or failed batch. Change it to
`batchSummary && !serviceStats.ANTHROPIC`, rendered alongside the other service groups. New
`OPEN_METEO_*` groups need no other change. Show the "Anthropic Batch ID" box only when the note
starts with `Anthropic batch: `, so the abort note is not labelled as a batch id.

## Checked: no existing derivation moves

- `BatchSummaryDeriver` reads ANTHROPIC rows with a target date only.
- `CycleLocationOutcomeResolver`, `BatchRetryService`, `EvaluationAbandonmentService` query by
  `batchId` with `isBatch = true`; the new rows have neither, so no false location auto-disable
  or retry.
- Run status, `22/22`, All OK (`RunClassifier`, `AdminRunsService`) read `job_run.succeeded/failed`.
- Cost: batch runs take `totalCostMicroDollars` from the batch; the Open-Meteo rate is 0.
- `MetricsSummary` "Slowest service" may now name `OPEN_METEO_FORECAST`. True, so fine.

## Out of scope (follow-ups)

- An aborted cycle still classifies DONE (0/0 anchor) and its pipeline run COMPLETED, not
  DEGRADED. Changing that touches `RunClassifier.ABORT_REASONS`, `BatchSubmissionSummary` and
  alerting.
- Aborted candidates still get no disposition row (a new category is a new enum value).
- Per-chunk rows for the cloud batch (`fetchForecastBatchInternal`).

## Tests

- `OpenMeteoClientTest` (mock RestClients, delays 0): 45 coords with chunk 2 returning 429 gives
  exactly 3 rows (chunk 2: 429, 0 returned, failed; chunks 1 and 3: 20 and 5 returned); the
  single-chunk failure records one row and still throws; a retry that succeeds records
  `attempts 2`; the one-argument methods still return the same list.
- `OpenMeteoServiceTest`: a `CallNotPermittedException` with no chunk rows gives one call-level
  row; with chunk rows already recorded, no duplicate.
- `JobRunServiceTest.logPrefetchCalls`: `calledAt`, status, `isBatch = false`, cost; error text
  over 500 characters truncated.
- `ForecastTaskCollectorTest`: each abort path carries the report and its `abortReason`, including
  a ratio exactly at the threshold; the normal path carries the calls.
- `ScheduledBatchEvaluationServiceTest`: rows go to the first bucket's run only; abort with
  first-pass skips puts rows and the abort note on the anchor; abort with no dispositions still
  creates an anchor with the rows; a throwing `logPrefetchCalls` leaves dispositions and the
  summary unchanged; the orphan path attaches the rows.
- Move stubs of `prefetchWeatherBatchResilient(any())` to the two-argument form in every
  `ForecastTaskCollector*Test` and `BatchWeatherPrefetcherTest` (an unstubbed overload returns
  null, which reads as an abort).
- `JobRunDetail.test.jsx`: a SUBMITTED batch run with only `OPEN_METEO_FORECAST` rows (one 429)
  shows both the ANTHROPIC (Batch) card and the Open-Meteo group with its error and the heading
  `OPEN_METEO_FORECAST · 429`.

## Corrections from the plan review (binding; they override the text above)

1. **Link the report-only anchor to its cycle.** `ForecastDispositionService.persist` writes
   `pipeline_run.disposition_job_run_id` only when there are dispositions (it returns early on an
   empty list). An anchor created only for the report must be linked explicitly: when
   `pipelineRunId != null`, call `pipelineRunRepository.recordDispositionJobRun(pipelineRunId,
   anchorId)`. Otherwise `AdminRunsService.topLevel` shows it as a stray top-level row and the
   cycle's job list never includes it. Tests: the link is written
   (`ScheduledBatchEvaluationServiceTest`); an `AdminRunsServiceTest` case shows the anchor under
   its cycle, not at the top level.
2. **Cloud and pressure-level rows tell the truth.** `fetchForecastBatchInternal` swallows chunk
   failures (429s included) and `prefetchCloudBatch` logs `succeeded=true, 200` today. The new row
   is `succeeded = (returned == requested)`; when not all came back, `statusCode` is null and
   `errorMessage` reads e.g. `cloud: 120/400 points returned`. Test both the partial and the
   all-failed case, and that the row is recorded before `ForecastTaskCollector`'s catch around the
   cloud pre-fetch swallows an exception.
3. **Retry duplicates outside prod.** `@Retry(name = "open-meteo-briefing")` re-invokes the whole
   method; only `application-prod.yml` sets `maxAttempts: 1`. Add the same `open-meteo-briefing`
   retry instance (`maxAttempts: 1`) to `application-local.yml` and `application-example.yml`, so
   every profile records one row per real call, as prod does.
4. **Write the rows even if the disposition persist throws.** Persist rethrows when the link write
   fails. Capture the anchor id before the persist and write the rows in a `finally` (still
   best-effort: its own `try/catch (RuntimeException)` with a WARN), in both `submitBuckets` and
   `persistOnOrphan`.
5. **Accepted losses, stated in the code comment and the reference doc:** an exception in the
   collector after the pre-fetch (stability classification, triage) loses the report; on the admin
   region path a partial pre-fetch followed by everything triaged returns no handle and its rows
   are not written; an admin-path abort is logged only.
6. **Plan slips.** Convert or delete the two-argument `prefetchCloudBatch(allCoords, JobRunEntity)`
   overload too (test callers only). Set `createdAt` on the new rows as `logApiCall` does. An
   unstubbed Mockito overload returns an empty `Map`/`List`, not null: migrate the stubs anyway.
   Only `RegionFilteredBatchTasks.empty()` at the post-pre-fetch abort (line ~750) becomes
   `aborted(report)`; the earlier `empty()` returns stay.
7. **Frontend.** The card and the service-group list in `JobRunDetail.jsx` are mutually exclusive
   today (a ternary around lines 196–243): restructure so the ANTHROPIC (Batch) card renders when
   `batchSummary && !serviceStats.ANTHROPIC`, alongside the groups, and "No API calls recorded"
   only when both are absent. Extra tests: a FAILED batch with Open-Meteo rows still shows the card
   and its failure text; a run whose note does not start with `Anthropic batch: ` hides the
   "Anthropic Batch ID" box; the two existing card-only tests still pass.

## Docs

`docs/engineering/reference/pipeline.md`: the Job metrics paragraph gains the batch pre-fetch
rows and the always-an-anchor-on-abort rule; Resilience notes that a breaker rejection is
recorded. A `changelog.d/` entry.
