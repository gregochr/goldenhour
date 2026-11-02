# Run Config: measured costs and true copy

**Status:** planned 2026-11-01, reviewed (data, backend, copy lenses) · one phase, one PR · backend +
frontend (admin-only screen)

## Why

Every cost figure on Operations → Run Config (`frontend/src/components/ModelSelectionView.jsx`) is a
hand-typed constant, and much of its explanatory copy describes code that has since changed. The
owner's production query of 2026-11-01 (last 30 days of `api_call_log` joined to `job_run`):

| Tab | Screen says | Production measured |
|---|---|---|
| Briefing (best-bet advisor) | 12 calls/run, "every 2 hours", ~$0.005/call (Sonnet 4.6) | **1 call/run, 2.3 runs/day, $0.039/call** (9.5k in, 726 out) |
| Aurora | 35 calls/night × ~$0.005 | **1 call/run, $0.44–0.45/call** (Sonnet 4.6: 12k in / 27k out; Sonnet 5.5: 19k in / 40k out), ~0.6 runs/day |
| Batch (Sonnet 5.5) | ~£0.0002/request, £0.02–0.08/run, 50–400 requests | **$0.0028/request (~£0.0022), 143 requests/batch job, $0.39/batch job**, 6.9 batch jobs/day |
| Batch (Haiku 4.5) | ~£0.0001/request | **$0.0014/request**, 91 requests/batch job |
| Haiku 5.5 | ~£0.00002/request | no production calls yet |

The fix: the backend serves measured figures, the client renders them (backend-heavy rule), and every
sentence on the screen is rewritten to match the code.

## Data: which rows belong to which tab (verified by review)

All Anthropic calls are `api_call_log` rows with `service = 'ANTHROPIC'` and `cost_micro_dollars`
computed by `CostCalculator` from the response's token usage (not an Anthropic-reported price).

| Tab | Rows |
|---|---|
| `BRIEFING_BEST_BET` | `job_run.run_type = 'BRIEFING'` and `request_url = 'briefing-best-bet'`. Only `BriefingBestBetAdvisor` (L251, L263, called from `BriefingService:713`) writes these; the replay harness writes nothing. Exclude the `briefing-gloss` rows in the same runs. |
| `AURORA_EVALUATION` | Sync: `job_run.run_type = 'AURORA_EVALUATION'` (only `EvaluationServiceImpl:262` starts one). Batch: rows whose `custom_id` starts `au-`, under a `SCHEDULED_BATCH` job run, model taken from the response (`AuroraResultHandler`). |
| `BATCH_NEAR_TERM` / `BATCH_FAR_TERM` | `is_batch` rows with `custom_id` starting `fc-`, `bb-` or `wd-` **whose batch belongs to a pipeline cycle**: join `forecast_batch` on `batch_id` and keep `pipeline_run_id IS NOT NULL`. That drops the admin force-submit route (`ForceSubmitBatchService:103-158`), which sends plain `fc-` ids for T+0–T+3 on the near-term model and would otherwise pollute FAR. Bucket per row in Java: `days_ahead = target_date − ForecastHorizon.civilDate(started_at)`; 0–1 NEAR, ≥ 2 FAR. Verified: intraday and evening cycles never produce `days_ahead ≥ 2` (`IntradayCandidateCollectionStrategy:51-58`, `EveningCandidateCollectionStrategy:41`); force-evals are always ≥ 2 and run on the far model; `bb-`/`wd-` take the same near/far decision (`ForecastTaskCollector:461-515`); retries pick their model by `days_ahead` (`BatchRetryService:406-411`). |

Corrections the review made to the first draft:
- `jfdi-`/`force-` custom ids are never generated (`CustomIdFactory.forJfdi`/`forForce` have no
  callers); no exclusion is needed for them.
- The cache primer (`pw-`) writes **no** `api_call_log` row (`BatchCachePrimer:55-67`); its cost is
  unrecorded. The screen says so in one line; recording it is out of scope.
- A retry batch is its own `SCHEDULED_BATCH` job run (`BatchRetryService:251` →
  `BatchSubmissionService:190`). "Runs" for the batch tabs are therefore **pipeline cycles**
  (`forecast_batch.pipeline_run_id`), not job runs; per-run figures are per cycle.
- Rows with a null `target_date` (early batch failures, `BatchResultProcessor:292,303`) cannot be
  bucketed; they are all failures and are left out (their cost is 0: not billed).
- Known understatement, said on screen in the info tip: a refused or truncated **sync** aurora call
  is logged at cost 0 (`AuroraResultHandler:232`, `TokenUsage.EMPTY`) though it was billed; admin
  aurora runs for a chosen night (`AuroraForecastRunService:433`) and `AuroraGlossService` log
  nothing.

Per-call averages use `succeeded = true` rows only; per-day spend sums every row's cost.

Window: the 30 UK civil days ending today, from the injected `Clock` via `ForecastHorizon`
(`ukDayStartUtc`). `/api/models` is not under `/api/admin/`, so under Rewind the clock is rewound and
the window follows it (consistent with "rewound like any other", `platform.md`).

## Backend

New `service/RunCostProfileService` (Javadoc on every public member; no logic in the controller):

- **Query** (`ApiCallLogRepository`): a projection of only the needed columns per row (`job_run`
  run type and `started_at`, `request_url`, `custom_id`, `is_batch`, `succeeded`,
  `evaluation_model`, `target_date`, the four token columns, `cost_micro_dollars`, `job_run_id`,
  and `forecast_batch.pipeline_run_id` via a left join on `batch_id`) since the window start, for
  `service = 'ANTHROPIC'`. **Never select `request_body` or `response_body`.** No date arithmetic in
  SQL (H2 and Postgres disagree on `AT TIME ZONE` and date subtraction,
  `TideExtremeRepository.java:75-77`); classify, bucket and aggregate in Java. About 35k rows a
  month; acceptable for an admin GET behind a memo.
- **Per tab** it returns a `RunCostProfile` record:
  - `windowDays`, `runsPerDay`, `callsPerRun` (runs = job runs for Briefing and Aurora, pipeline
    cycles for the batch tabs);
  - `measured`: for each model that ran, `calls`, `usdPerCall`, `usdPerDay`;
  - `estimates`: for every `EvaluationModel.selectable()` model without measured data, `usdPerCall`
    priced by `CostCalculator.calculateCostMicroDollars(model, usage, isBatch)` on the tab's average
    token mix (taken from its most-used model), with `usdPerDay = usdPerCall × callsPerDay`.
    ⚠️ Batch requests write **1-hour** caches (`BatchRequestFactory:259`) and `CostCalculator` prices
    those at the 1-hour rate (`CostCalculator:99-103`), but `api_call_log` keeps one
    `cache_creation_input_tokens` column. For batch tabs build the `TokenUsage` with the 5-arg
    constructor, putting the average write in `cacheCreationOneHourTokens`. A unit test asserts the
    estimate for the measured model lands within 5% of its measured `usdPerCall`.
  - The estimate means "same token counts at this model's prices". It ignores tokeniser differences
    (Haiku 5.5 counts ~30% more tokens) and thinking differences; the client labels it "estimate"
    and its info tip says why.
  - `tokenRates` per selectable model, in USD per MTok, from `CostProperties` (so the cards stop
    carrying hard-coded £ strings).
  - Empty window: `runsPerDay = 0`, no figures.
- **GBP**: `gbpPerUsd` = the newest non-null `job_run.exchange_rate_gbp_per_usd` (the source the
  Runs list uses, `JobRunEntity:94`). Not `ExchangeRateService.getCurrentRate()`, which can make an
  outbound HTTP call. Null → the client shows USD only.
- **Served** on the existing admin-only `GET /api/models` as `costProfiles` (map keyed by run type)
  and `gbpPerUsd`. `ModelsController` builds its response with `Map.of` (L41), which rejects nulls:
  the service never returns null, and on any exception it logs at WARN and returns an empty map so
  the screen (and `SkyRatingEvalView`, which shares `getAvailableModels()`, `SkyRatingEvalView.jsx:162`)
  still loads. `gbpPerUsd` goes in via a null-tolerant map.
- **Memo**: `MEMO_TTL = Duration.ofMinutes(10)` against the injected `Clock` (house pattern:
  `InversionProfileFetcher.MEMO_TTL_MS`, `util/DayScopedMemo`); bypassed, neither read nor written,
  when `Rewind.isActive()` (as `AlmanacService` does).
- `/api/models` is not in `HttpCachingConfig.REVALIDATABLE_READ_PATHS`; no ETag concern, and the
  payload is not personal.

## Frontend (`ModelSelectionView.jsx`)

Delete: `USD_TO_GBP`, `COST_PER_CALL`, `MODEL_INFO.*.typicalCostUsd`, the hard-coded `tokenRates`
strings, `BATCH_COST_ROWS`, `CUSTOM_COST`, the `recommended` flags and the "Recommended" badge (the
screen states facts; which model to use is the owner's call and has changed twice), and the unused
`MODEL_INFO.description` strings.

Render from `costProfiles[activeTab]` and `gbpPerUsd`:
- **Card**: "~£x per call (measured)" or "(estimate)"; token rates from the served `tokenRates`
  (£ when `gbpPerUsd` is present, else $); speed stays.
- **One cost table per tab**, replacing both the custom table and the static batch table: model,
  per call, per day, with the active model highlighted, and a header line such as "2.3 runs a day, 1
  call per run, last 30 days" ("requests" and "cycles" on the batch tabs).
- **Missing or empty data**: if `costProfiles` is absent or empty, the table says "Cost figures
  unavailable"; if a tab's window is empty, "No calls in the last 30 days". Neither breaks the
  model cards or switching.
- A footnote on every tab: "Costs are calculated from logged token counts at the configured
  prices." Batch tabs add "Includes the Batch API discount and prompt caching. The cache-priming
  call is not logged, so it is not included." Aurora adds the understatement noted above.
- Update the stale JSDoc in `frontend/src/api/modelsApi.js:5-7`.

## Copy (every sentence checked against code by the copy review)

**Tab tips (`CONFIG_TABS`):**
- Batch Near-Term: "Claude model for T+0 and T+1 in the nightly cycle, and for every intraday,
  evening and JFDI request."
- Batch Far-Term: "Claude model for nightly T+2 (settled or transitional) and T+3 (settled), plus
  forced headline contenders."
- Briefing and Aurora tips: unchanged (verified).

**Briefing, Extended Thinking panel:**
- Body: "Turns on adaptive thinking for Sonnet 4.6 and Opus 4.6. There is no fixed thinking budget;
  the token ceiling for thinking plus reply rises from 4,096 to 16,000." (`BriefingBestBetAdvisor`
  L83, L86, L215-230.)
- Keep the Haiku 4.5, Haiku 5.5 and Sonnet 5.5 "no effect" notes (verified); the Sonnet 5.5 note
  adds "and always has the 16,000 ceiling".
- Delete "Active — adds ~10 000 thinking tokens per call (Sonnet 4.6: +~£0.12, Opus: +~£0.40)".

**Aurora (new note, no toggle):** "One call rates every viable location. Sonnet 5.5 thinks adaptively
at low effort; Haiku 5.5 runs with thinking off; the other models run without thinking. The reply
budget is about 220 tokens per location, plus 4,096 for Sonnet 5.5's thinking."
(`ClaudeAuroraInterpreter` L64, L71, L204-238; `EvaluationServiceImpl` L220-280.)

**Batch info panel:**
- Opening: "Scheduled runs send every location that passes weather triage and has no fresh
  evaluation to the Anthropic Batch API, in the nightly, intraday and evening cycles." Delete
  "ready before your first morning check".
- NEAR-TERM line: "T+0 and T+1, at every stability; skipped only by triage or a fresh evaluation."
- FAR-TERM line: "Nightly cycle only: T+2 when settled or transitional, T+3 when settled, plus up to
  6 GO headline contenders at T+2/T+3 that the stability gate would skip." (Read the cap from
  `photocast.batch.force-eval-cap`'s default, `ForecastTaskCollector:165`; do not hard-code a
  different number.)
- Delete the "use a more capable model for near-term… re-evaluated overnight anyway" advice.
- Stability table heading: "Nightly cycle". SETTLED "T+0 to T+3"; TRANSITIONAL "T+0 to T+2";
  UNSETTLED "T+0 and T+1" (`NightlyEligibilityPolicy:50-60`). Add one line: "The intraday cycle
  covers today's sunset and tomorrow's sunrise and sunset; the evening cycle covers tomorrow's
  sunrise."
- Classifier: four signals, adding wind-gust variance (`ForecastStabilityClassifier:15-16`).
- STANDDOWN: "If every sky location is stood down by triage and no woodland or bluebell site is due,
  the batch has zero requests and is not submitted." (`ForecastTaskCollector:448`,
  `BatchSubmissionService:124-126`.)
- Delete the "Results and the JFDI button" section entirely.

**Batch model descriptions:** delete "Recommended for scheduled batch runs" (Sonnet 4.6), "Untested
against the prompt-regression suite; not the recommended default" (Haiku 5.5), "Best reserved for
key seasonal events only" (Opus), and "for overnight batch runs" (Haiku 4.5). Keep the factual
parts (tokeniser, price ratio).

**About box:** keep "each run type independently", "wildlife-only locations" and "previous forecasts
keep their original model" (all verified); delete the two advice lines.

## Out of scope (note in the PR)

- `BRIEFING_GLOSS` is configurable in `ModelSelectionService` but has no tab (~40 Haiku 4.5 calls,
  ~$0.06 per briefing run).
- The cache primer's cost is unrecorded; admin aurora runs and the aurora gloss log nothing.
- Aurora's output (27k–40k tokens per call) is the largest per-call cost in the app; a cost review
  of that prompt is a separate change.

## Tests

- `RunCostProfileServiceTest` (Mockito, fixed `Clock`): best-bet excludes gloss rows; aurora sums
  sync and `au-` batch rows; near/far bucketing at the T+1/T+2 boundary, including a `started_at`
  late in the UTC day during BST; force-submit (`pipeline_run_id` null) rows excluded; retry job
  runs counted inside one cycle; null `target_date` rows skipped; failed calls in per-day but not
  per-call; estimates priced through a real `CostCalculator` with test rates, and the measured
  model's estimate within 5% of its measured cost (1-hour cache writes); empty window; null GBP
  rate; exception → empty map; memo expiry with a fixed `Clock`; memo bypass under Rewind.
- Repository: a `@DataJpaTest` on H2 for the projection (pattern:
  `ApiCallLogCacheDiagnosticsPersistenceTest`), and a new
  `integration/ApiCallLogCostProfileIntegrationTest extends IntegrationTestBase` proving it on
  Postgres in CI (no Docker locally).
- `AbstractControllerTest`: add a `@MockitoBean RunCostProfileService` (otherwise the shared context
  fails for every subclass). `ModelsControllerTest`: `costProfiles` and `gbpPerUsd` for admin, next
  to the existing assertions (L128); 403 unchanged.
- `ModelSelectionView.test.jsx` (`MOCK_DATA` L39 gains a `costProfiles` fixture): served figures with
  measured/estimate labels; absent `costProfiles`; empty window; null `gbpPerUsd` shows USD; no
  "Recommended" badge; none of the old hard-coded £ strings; the new thinking, tier and STANDDOWN
  copy; no "JFDI" section. `SkyRatingEvalView.test.jsx` stays green unchanged.
- Jacoco is 80% per class with no excludes: give the empty-window, null-rate and exception
  branches their own tests.
- Follow `test-improvement-standards.md` and `frontend-test-standards.md`.

## Gates, docs, changelog

- Backend build ladder through `clean verify` (integration exclusion); frontend lint, test, audit,
  build.
- `docs/engineering/reference/api-endpoints.md:189`: `GET /api/models` gains `costProfiles` and
  `gbpPerUsd`; also correct `GET|PUT` to the real mappings (there is no `PUT /api/models`).
- Add a short Run Config entry to `docs/engineering/reference/platform.md` (admin features): costs
  are measured from `api_call_log`, what is and is not included.
- `changelog.d/20261101-run-config-measured-costs.md`: `### Fixed — Run Config shows measured costs
  and correct batch rules`.
- UI review cadence: adversarial review of the diff, browser check on local 8083. Local H2 has
  little call history, so say which figures were seen and which were only tested.
