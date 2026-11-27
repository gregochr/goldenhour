# CI prompt-regression spend on Runs › Cost trends (C1–C3)

Owner decision, 2026-11-27 (design handoff `design_handoff_ci_spend`): the two paid CI jobs in
`.github/workflows/prompt-regression.yml` (`Prompt regression — sky`, `Prompt regression — ask`) spend real
Anthropic money on the separate, spend-capped `PROMPT_REGRESSION_ANTHROPIC_API_KEY`. That spend is shown on
**Runs › Cost trends** as a **Testing** group, next to the app's figures and **never mixed into them**: CI spend
follows PRs, not the schedule, so a busy week of development must never read as "the app got dearer".

Today nothing records it. CI writes surefire reports only, and production never sees CI.

**Shape:** CI records token counts per model and uploads them as a small JSON artifact (C1). Production pulls
those artifacts hourly with a read-only GitHub token, prices them with `CostCalculator`, and stores them in
`ci_spend_run` (C2). The Cost trends page and the Day view show them in their own block (C3). One PR per phase,
each branched from `main` after the previous one merged.

## Invariants (every phase)

1. **No assertion in any prompt-regression test changes.** The paid test classes change only how they build
   their client. In `SkyRatingRegressionTest` and the seven `Ask*RegressionTest` classes, the
   `AnthropicApiClient` import is swapped for `RecordingAnthropicApiClient`'s and `new AnthropicApiClient(client)`
   becomes `new RecordingAnthropicApiClient(client)`. Checkstyle's `UnusedImports` covers test sources, so the
   old import must go. `ValleyMistPromptRegressionTest` shares `AnthropicApiClient`'s package, so it only gains
   the new import. The review diffs every one of these files and accepts nothing else.
2. **The paid jobs stay unapproved, unawaited and never required.** No `needs:` beyond `changes`, no
   environment reviewer, no required check, and no change to `scripts/prompt-regression-paths.sh`. A red run is
   reported in the PR summary, never waited on.
3. **Token counts only, anywhere.** No prompt text, model output, fixture content, keys, headers or signed URLs
   go in the shards, the artifact, the step summary, the logs or the database.
4. **The app's figures are untouched.** `RunsCostService`, `RunsDayService`, `RunsLoader` and `RunDeepType` keep
   their behaviour. Every existing field of `RunCostResponse` and `RunDayResponse` is computed exactly as today.
   Testing is never a `RunDeepType`. The poller writes no `job_run` and no `api_call_log` (scheduled jobs write
   only `scheduler_fire_log`, which Runs never reads).
5. **One price table.** Prices come from `CostCalculator` (non-batch) on production, never from CI.
6. **Don't change the app's cost logic or any prompt.** The only change to `CostCalculator` is an additive
   read-only accessor (C2).

## C1: record usage in CI and upload it

### The recorder (test sources only)

Every paid test builds its own SDK client and wraps it in `AnthropicApiClient`. Those are 9 classes:
`SkyRatingRegressionTest`, `ValleyMistPromptRegressionTest` and the seven `Ask*RegressionTest`. Their paid calls
go only through `createMessage` (`ClaudeEvaluationStrategy`) and `createAskMessage` (`ClaudeAskEngine`). Nothing
records `SystemPromptTokenCountTest`, which uses the free `countTokens`. `PromptReplayHarnessTest` calls
`messages().create` directly, but the workflow never runs it, so it is out of scope and the PR body says so.

- `backend/src/test/java/com/gregochr/goldenhour/testsupport/promptusage/RecordingAnthropicApiClient`
  extends `AnthropicApiClient` and overrides `createMessage` and `createAskMessage`.
  - The usage key is the **requested** model, `params.model().asString()`. That always equals an
    `EvaluationModel.getModelId()`, and the API's echoed id might carry a dated suffix.
  - On success it records `TokenUsage.from(message.usage())` and sums the **clamped** fields of that
    `TokenUsage`: `inputTokens`, `outputTokens`, `cacheReadInputTokens`, `cacheCreationInputTokens` (the total)
    and `cacheCreationOneHourTokens` (the subset).
  - On a throw it counts one `erroredCalls` and rethrows. A client timeout can still be billed, so CI spend is a
    slight **under**-count. The ledger says so.
  - Resilience4j annotations are inert here (no Spring proxy), exactly as today.
- `PromptUsageRecorder` is a process-wide singleton. It keeps per-model counters (`calls`, `erroredCalls`,
  `input`, `output`, `cacheRead`, `cacheWrite`, `cacheWrite1h`) that are thread-safe, because Ask records from
  worker threads.
- **Shards, not one file.** Surefire runs `forkCount=1C`, and the sky job runs two Maven invocations (the hard
  step and the advisory ValleyMist step, with no `clean` between them), so several JVMs record.
  - After every recorded call, the recorder rewrites its JVM's whole state to
    `$PROMPT_REGRESSION_USAGE_DIR/shard-<pid>-<uuid>.json`. It writes `….json.tmp` first, then does an atomic
    move, and creates the directory if it is missing.
  - Incremental writes mean a job cancelled mid-run still leaves its spend so far.
  - With the env var unset (local runs, ordinary CI) nothing is written. The directory is injectable for tests.

### Merging and uploading (workflow)

- New `scripts/prompt-regression-usage.py` (stdlib only, `python3 -I`, always exits 0, like the summary script).
  - Arguments: `--suite` only. Every path is derived from the script's own location (the repository root is two
    levels up), so no caller-supplied text reaches the filesystem.
  - It reads `GITHUB_RUN_ID`, `GITHUB_RUN_ATTEMPT`, `HEAD_SHA`, `PR_NUMBER` and `STARTED_AT` from the
    environment.
  - It sums `shard-*.json` only. It ignores `*.tmp`, and skips a malformed shard with a warning.
  - It counts `cases` (tests) and `failed` (failures + errors) from the surefire XML.
  - It writes `backend/target/prompt-regression-usage-<suite>.json`:
  ```json
  { "schema": 1, "suite": "sky", "runId": 123456789, "runAttempt": 1, "headSha": "…", "pr": 1912,
    "startedAt": "…Z", "finishedAt": "…Z", "complete": true, "cases": 42, "failed": 0,
    "models": [ { "model": "claude-sonnet-5-5", "calls": 42, "erroredCalls": 0, "input": 612000,
                  "output": 38000, "cacheRead": 434000, "cacheWrite": 21000, "cacheWrite1h": 0 } ] }
  ```
  - `erroredCalls`, `cacheWrite1h` and `complete` extend the handoff's example. `complete` is false when no
    surefire report exists. With no shards it writes `models: []`.
  - It prints one line of token totals to stdout, and the workflow appends that line to `$GITHUB_STEP_SUMMARY`.
- `prompt-regression.yml`, in **both** paid jobs:
  - Job env:
    - `PROMPT_REGRESSION_USAGE_DIR: ${{ github.workspace }}/backend/target/prompt-regression-usage`;
    - `HEAD_SHA: ${{ github.event.pull_request.head.sha || github.sha }}`;
    - `PR_NUMBER: ${{ github.event.pull_request.number }}` (empty on a manual run).
  - A first step `Record start time` that writes `STARTED_AT` to `$GITHUB_ENV`.
  - After Summarise, a `Write usage` step (`working-directory: .`) and an `Upload usage` step
    (`actions/upload-artifact@v4`), both **`if: always()`**.
    - The artifact is named `prompt-regression-usage-<suite>-${{ github.run_attempt }}`. The attempt suffix
      keeps re-runs unambiguous.
    - `retention-days: 90` (capped by the repository's own retention setting), `if-no-files-found: ignore`.
  - Header comments record that the usage artifact holds token counts only and feeds Cost trends.
  - Existing steps, conditions, environment, concurrency and timeouts are unchanged.
- Later edits to the recorder or the usage script alone do not trigger the paid suites, because the paths script
  is unchanged. A broken recorder shows up on the next prompt-changing PR as a missing or empty artifact.
  Recorded as accepted.

### Tests and gates

- `RecordingAnthropicApiClientTest` and `PromptUsageRecorderTest` (ordinary group, so normal CI runs them) cover:
  - both overrides, keyed by the requested model;
  - the clamped 1-hour portion;
  - the throw path (`erroredCalls`, rethrown);
  - concurrent recording without lost counts;
  - a shard written only when a directory is configured;
  - the temp-then-move write.
- `scripts/test/prompt-regression-usage.test.sh` (modelled on `prompt-regression-summary.test.sh`) covers:
  - sums across shards, ignoring `.tmp`;
  - a malformed shard skipped;
  - counts from surefire XML;
  - no shards, and no reports (`complete: false`);
  - output keys exactly as the schema;
  - no field other than counts and identifiers.
- Update `scripts/test/prompt-regression-workflow.test.sh` to match steps by **exact name** (today it takes the
  first step starting with "Upload"). It asserts that the surefire uploads keep `${{ !cancelled() }}`, that both
  jobs have `Write usage` and `Upload usage` with `always()`, and that neither job gained `needs:` beyond
  `changes` or a different environment.
- Gates: the build ladder, `scripts/test/run-all.sh`.
- **The C1 PR runs both paid suites** (its edits to the regression classes match the paths script). That run is
  the end-to-end check. Download both artifacts and confirm:
  - they hold counts only;
  - the model ids equal `EvaluationModel` ids;
  - the totals are plausible.
  A red suite is reported, not awaited.

## C2: fetch, price and store

### Storage (`V232__ci_spend_run.sql`, Postgres; Testcontainers test, proven in CI only)

- `ci_spend_attempt` records that an attempt is settled, so later polls skip it with no API call.
  - Primary key (`github_run_id`, `run_attempt`).
  - Columns `settled_at`, and `suites_ran` varchar(16) (for example `sky,ask` or empty).
- `ci_spend_run` holds one row per suite that ran.
  - `id` bigserial; `github_run_id` bigint, `run_attempt` int, `suite` varchar(16), **unique
    (`github_run_id`, `run_attempt`, `suite`)**.
  - Run identity: `event` varchar(32), `head_sha` varchar(40), `pr_number` int null, `pr_title` varchar(300)
    null, `html_url` varchar(300).
  - Times and outcome: `started_at` / `finished_at` timestamptz, `conclusion` varchar(32), `cases` / `failed`
    int null.
  - Spend:
    - `usage_state` varchar(16): `KNOWN`, `UNKNOWN` or `UNPRICED`;
    - `errored_calls` int;
    - `cost_micro_dollars` bigint null (null unless `KNOWN`);
    - `exchange_rate_gbp_per_usd` double precision null;
    - `fetched_at` timestamptz.
  - Index on `started_at`.
- `ci_spend_model_usage` holds one row per model.
  - `id`, and `ci_spend_run_id` as a foreign key with cascade.
  - `model` varchar(64), `calls`, `errored_calls`.
  - Tokens (bigint): `input_tokens`, `output_tokens`, `cache_read_tokens`, `cache_write_tokens`,
    `cache_write_1h_tokens`.
  - Rate snapshot (double precision, null when unpriced): `input_usd_per_mtok`, `output_usd_per_mtok`,
    `cache_write_usd_per_mtok`, `cache_write_1h_usd_per_mtok`, `cache_read_usd_per_mtok`.
  - `cost_micro_dollars` bigint null.
- `scheduler_job_config` seed row: `ci_spend_poll`, `FIXED_DELAY` 3600000, initial delay 300000,
  `status='DISABLED_BY_CONFIG'`, `status_before_config_disable='ACTIVE'`,
  `config_source='photocast.ci-spend.enabled'`.
  - A `SchedulerJobCatalogue` draft (uses Claude: no; service: GitHub).
  - A `configGateFor` branch in `DynamicSchedulerService` for that source, on `CiSpendProperties::isActive`.

### Config (`CiSpendProperties`, `photocast.ci-spend.*`)

- `enabled`: default false.
- `github-token`: env `PHOTOCAST_CI_SPEND_GITHUB_TOKEN`. A fine-grained token, **Actions: read** on
  `gregochr/goldenhour` only. Never logged, never served, omitted from `toString()`.
- `repository`: default `gregochr/goldenhour`, validated as `owner/name`.
- `workflow-file`: default `prompt-regression.yml`.
- `lookback-days`: default 14, range 1–90. Set it to 90 once to backfill.
- `max-calls-per-poll`: default 300.
- `api-base-url`: default `https://api.github.com`.
- `isActive()` is `enabled && hasToken()`.

`application-example.yml` and `configuration.md` list them all. Prod exposes only `health,info` actuator
endpoints, so config is never served.

### The GitHub client

- `GitHubActionsClient` is a `@HttpExchange` interface. It sends `Authorization: Bearer`,
  `Accept: application/vnd.github+json` and `X-GitHub-Api-Version`.
- It is built on **its own request factory that never follows redirects**: `JdkClientHttpRequestFactory` with
  `HttpClient.Redirect.NEVER` and the app's timeouts. `AppConfig.timeoutRequestFactory()` is a
  `SimpleClientHttpRequestFactory`, which follows GET redirects and would carry the token to the blob host.
- The artifact-zip call returns `ResponseEntity<Void>`. The `Location` it gives is fetched by a separate
  `RestClient` with **no Authorization header**, also with redirects off. It requires `https`.
- Logs name the blob URL's host only, never the URL. `SecretRedactor` gains patterns for
  `Authorization: Bearer …` / `bearer …`, GitHub token shapes (`github_pat_…`, `gh[pousr]_…`) and signed-URL
  parameters (`sig=`, `se=`, `skoid=`, `sktid=`, `sv=`). The poller passes every exception message through it.

### The poller (`CiSpendPollService`, job `ci_spend_poll`)

1. **No overlap.** An `AtomicBoolean` guard makes a second concurrent fire (from Run now or a reschedule) return
   at once.
2. **Budget.** Count API calls and stop cleanly at `max-calls-per-poll`. The next poll resumes, because settled
   attempts cost nothing.
3. **List** the workflow's runs created in the last `lookback-days`
   (`/repos/{repo}/actions/workflows/{file}/runs?created=>=YYYY-MM-DD&per_page=100`), paginated with a page cap.
   - **No `status` filter**: a run whose attempt 2 is in progress reports `in_progress`, and its attempt 1 must
     still be seen.
   - A re-run started after a run leaves the lookback window is not seen. Accepted and documented.
4. For each run and each attempt `1..run_attempt` that has no `ci_spend_attempt` row:
   1. Read the attempt (`/runs/{id}/attempts/{n}`). If its `status` is not `completed`, leave it for a later
      poll.
   2. Read its jobs (`/runs/{id}/attempts/{n}/jobs`). A suite **ran in attempt n** only when all of these hold:
      - its job (exact names `Prompt regression — sky` / `Prompt regression — ask`) has
        `run_attempt == n`, so jobs carried over by "Re-run failed jobs" don't count;
      - the job's conclusion is not `skipped`;
      - its paid Maven step (exact names `Sky rating and token-count tests` / `Ask regression tests`, pinned by
        the workflow test) has a non-null `started_at`.

      A job cancelled while queued or before Maven spent nothing and gets no row.
   3. For each suite that ran, find the artifact `prompt-regression-usage-<suite>-<n>` (`/runs/{id}/artifacts`,
      filtered by `name`), and check `expired` and `size_in_bytes` ≤ 1 MB before downloading.
   4. Settle the attempt (insert the `ci_spend_attempt` row) when every suite that ran is final:
      - a `KNOWN` or `UNPRICED` row is final;
      - an artifact-less suite (`UNKNOWN`) is final only once the attempt's `updated_at` is more than **one hour**
        old. Artifacts upload during the job, so a completed attempt cannot gain one later; the hour is a
        listing-lag grace, during which the attempt stays unsettled and an `UNKNOWN` row can still be upgraded;
      - an invalid artifact is retried and the attempt settles anyway after **3 rejected polls** (logged once at
        settle; the count is in memory, so a restart only means a few more tries).
      Identity note: `pull_requests` on the attempt is usually empty for an open same-repository PR, so the
      `commits/{sha}/pulls` fallback (step 5) is the normal way a PR number is found, not an edge case.
5. **Untrusted input.** A same-repository PR head controls the workflow, so the artifact is attacker-influenced.
   - The download stream is capped at 1 MB, whatever `Content-Length` says.
   - The zip is read in memory with `ZipInputStream`, through a counting stream that fails past 64 KB
     uncompressed. `entry.getSize()` is never trusted, and nothing is written to disk.
   - It must hold exactly one entry, named `prompt-regression-usage-<suite>.json`.
   - Strict Jackson reads it into a record, and these must hold:
     - `schema == 1`;
     - `suite` matches;
     - `runId` and `runAttempt` equal the API's;
     - at most 10 models, each id matching `[a-z0-9.-]{1,64}`;
     - every token count an integer in 0..10^8;
     - `calls` and `erroredCalls` in 0..5000.
   - Identity, event, PR number, title, SHA, URL, times and conclusion come **from the GitHub API, never the
     file**.
     - `pr_title` is `display_title` truncated to 300 characters, and only when `event == pull_request`.
       Otherwise it is null and shown as "Manual run".
     - `pr_number` is `pull_requests[0].number`. When that is empty (a merged PR or a deleted branch), it falls
       back to `/repos/{repo}/commits/{head_sha}/pulls`.
     - `html_url` is built server-side as `https://github.com/{repo}/actions/runs/{id}/attempts/{n}`.
6. **Price.** Each model is resolved with `EvaluationModel.fromModelId(String)`, a new exact match on
   `getModelId()` that takes the first constant in declaration order (`SONNET_ET` / `OPUS_ET` share their base
   model's id and tier). It has no substring fallback. Because C1 records the requested id, exact matching holds.
   - Known model: cost is `costCalculator.calculateCostMicroDollars(model, usage, false)`. Its rate snapshot
     comes from a new read-only `CostCalculator.ratesFor(EvaluationModel)` returning a `ModelRates` record built
     from the existing private getters. No pricing code changes.
   - Unknown model: the run is `UNPRICED`. Its tokens are stored and its cost is null, shown as "unpriced",
     never £0.
   - The run's `exchange_rate_gbp_per_usd` is `ExchangeRateService`'s rate for `started_at`'s **UTC** date, the
     same basis as `job_run`'s own rate.
7. **Store** each (run, attempt, suite) in its own `REQUIRES_NEW` transaction (`TransactionTemplate`), so one
   failure never poisons the others:
   - Absent: insert `KNOWN`, `UNPRICED`, or `UNKNOWN` when the suite ran but no artifact exists (never
     uploaded, or expired).
   - `UNKNOWN`, with a valid artifact now: replace it (conditional on `usage_state = 'UNKNOWN'`).
   - `KNOWN`: never rewritten.
   - Invalid artifact: one log line (run id, attempt, suite, reason; **no file content**). Write nothing.
   - A `DataIntegrityViolationException` raised by the unique key is caught **outside** the transaction and
     treated as already stored.
8. **Reprice.** Each poll also reprices `UNPRICED` rows whose models now resolve, from their stored tokens,
   without fetching anything.
9. **Errors.** HTTP 401, 403, 404, 5xx or a rate limit end the poll with one redacted warning. Rows already
   committed stay.

### Tests

- Migration: a Testcontainers test covering the unique key, the cascade, `ci_spend_attempt` and the seed row.
- `CiSpendPollServiceTest`, with a mocked `GitHubActionsClient` and every stub argument pinned. Cases:
  - first poll inserts;
  - a re-poll makes no API calls past the list, because attempts are settled;
  - attempt 2 adds a row;
  - a carried-over job from attempt 1 adds nothing in attempt 2;
  - a skipped job, or a job cancelled before Maven, adds nothing;
  - an in-progress attempt is left;
  - missing and expired artifacts give `UNKNOWN`, and a later artifact upgrades it;
  - each limit is rejected and logged without content: bad schema, mismatched runId, oversized `size_in_bytes`,
    oversized stream, too much uncompressed, two entries, wrong entry name, negative or huge counts;
  - an unknown model gives `UNPRICED`, and repricing makes it `KNOWN`;
  - a manual run gives a null title and PR;
  - empty `pull_requests` falls back to the commits call;
  - the budget stops the poll;
  - the overlap guard;
  - a 403 ends the poll;
  - a duplicate-key race is treated as stored.
- Client: against a local mock server, the zip call is **not** followed, and the blob request carries **no**
  Authorization header.
- Pricing parity: the stored cost equals `calculateCostMicroDollars(model, usage, false)`. Building a
  `CostCalculator` from a `CostProperties` filled with the stored snapshot gives the same cost (the same code
  path, so exact). This holds for each priced model and includes the 1-hour cache writes.
- `SecretRedactorTest` gains cases for bearer, `github_pat_`, `ghs_` and a signed blob URL. A captured-appender
  test proves the token never appears in a log line. `CiSpendProperties.toString()` omits the token.
  `configGateFor` maps the new source.

### Docs

- `pipeline.md`: cost tracking, the new source and its separation.
- `configuration.md`.
- `database-migrations.md` (V232).
- `claude-cost-ledger.md`, a new **Testing (CI prompt regression)** area covering:
  - what it costs, and the key's own cap;
  - the existing rule for when the suites run;
  - the slight under-count (errored calls);
  - a monthly manual cross-check against the CI key's console spend, as a watch item.

## C3: the read side and the page

### Backend

- `CiSpendCostService` lives in `service.runs`, because `RunFx` is package-private there. It is built **beside**
  `RunsCostService` and `RunsDayService`, which stay untouched.
  - `AdminRunsController` (or a thin composer it calls) takes the app response and asks
    `CiSpendCostService.testing(firstDay, splitDay, lastDay, allTime)` using **the app response's own bounds**.
    It then returns `response.withTesting(...)`.
  - The day endpoint gets the same treatment with `withPromptTestRuns(...)`.
- Shared arithmetic: extract only `percent(double, double)` and
  `perRunMultiple(double lastGbp, int lastRuns, double priorGbp, int priorRuns)` into a package helper.
  `RunsCostService`'s private wrappers delegate to it. Direct unit tests of the helper (zero base, negative base,
  zero runs) come **before** any code moves.
- Runs are bucketed by the UK civil date of `started_at` (`ForecastHorizon.civilDate`, the same as app runs).
  CI rows are bounded to `[firstDay, lastDay]` in every range, "all" included, so they never reach outside the
  app's days. A CI run before the app's earliest day cannot be opened in the Day view: `requireSelectable` sends such a day to today, and the behaviour is deliberately unchanged because production always has app runs from before any CI run. Accepted.
- GBP per run is `cost_micro_dollars × its stored rate`. `UNKNOWN` and `UNPRICED` runs count toward `runs` and
  `unknownRuns`, but not toward spend, and they set `costComplete = false`.
- `RunCostResponse` gains a trailing nullable `testing` and a secondary constructor with the old arguments
  (`testing = null`), so existing call sites compile unchanged.
  - `CostTesting(List<CostTestingType> types, List<CostTestingDay> days, CostTestingTotals totals)`.
  - `CiSuite` enum: `SKY("Prompt tests · sky")`, `ASK("Prompt tests · ask")`. Stack order is sky, then ask.
  - `CostTestingType`: suite, label, last/prior GBP, delta, deltaPercent, lastRuns, priorRuns,
    perRunChangePercent, unknownRuns, costComplete.
  - `CostTestingDay(date, byType, totalGbp)` is **dense**: exactly one per `days` date, in the same order (a
    test asserts this).
  - `CostTestingTotals`: last/prior GBP, delta, percent, runs, priorRuns, unknownRuns, `lastUsd`.
  - `testing` is null when the window has no CI row.
- `RunDayResponse` gains a trailing `promptTestRuns` and a secondary constructor (empty list). Each
  `PromptTestRunRow` carries:
  - the run: `prNumber`, `prTitle`, `suite`, `attempt`, `conclusion`, `htmlUrl`, `startedAt`;
  - the results: `cases`, `failed`;
  - the tokens: `input`, `output`, `cacheRead`;
  - the spend: `costGbp`, `costUsd`, `usageState`.

  It is ordered by `startedAt`, then suite, then attempt. `htmlUrl` is served only when it starts with
  `https://github.com/{repo}/actions/runs/`.
- **Isolation test, real:** a Spring Boot Testcontainers test with real app fixtures **and** real `ci_spend_run`
  rows goes through the actual endpoints:
  - cost, for 30 days and for all;
  - day, including `filterCounts`, `earliestDay` and `summary`;
  - calendar;
  - scheduler job cost.

  It compares the JSON, minus `testing` and `promptTestRuns`, against the same request with the CI rows deleted.
- `api-endpoints.md` notes the new fields. Both endpoints stay admin-only and outside `HttpCachingConfig`.

### Frontend (`components/runs/cost/*`, `components/runs/day/*`, `utils/runsCost.js`)

The mock is `CI Spend.html`, in the handoff.

- **With `testing` null**, the page is unchanged except that "All types" reads "All app types". The divider and
  the Prompt tests chip are not rendered.
- **Headline.**
  - `verdictHeadline` takes an `appLabel` flag ("App spend …") that is set only when `testing` is present. The
    verdict computation is untouched.
  - The all-time `allTimeHeadline` / `allTimeBody` follow the same flag.
  - Under the headline, outside the `hasSpend` guard so it shows even when the app spent nothing, comes one line:
    - 30-day: `▨ Plus £3.40 on prompt tests (14 runs, was £1.62) · show in chart`;
    - all time: `▨ Plus £X on prompt tests since …` (no "was");
    - with unknown runs, add "· N usage unknown";
    - when shown, the link reads `hide in chart`.
- **Chart.**
  - After a `ChipDivider` comes one **Prompt tests** chip (`aria-pressed`), off by default. It is local view
    state shared with the headline link and not persisted.
  - The testing layer is independent of the app-type selection, which lives in the URL. It is always drawn at
    full opacity, on top of the day's app stack, sky then ask.
  - The y-axis peak includes testing only while it is shown. The 7-day average stays the app figure. The
    `empty` check counts testing when it is shown.
  - The tooltip and each day's `aria-label` add, while testing is shown:
    - the suite lines;
    - "App" with the app total;
    - "Total" with app + testing.
  - Hatching:
    - SVG `<pattern>`s whose ids come from `useId()`;
    - colours as tokens `--color-test-sky` (#9CCBD1) and `--color-test-ask` (#C6D8E3) in `index.css`'s
      `@theme static` block;
    - swatches (chip, table, headline) as a Tailwind arbitrary `bg-[repeating-linear-gradient(...)]` class that
      names the token in full (per the scanner note in `index.css`);
    - no inline styles.
  - Footnote, shown only when `testing` is present: "Hatched = prompt tests (CI), counted separately from the app.
    Off by default."
  - Clicking a day still opens it.
- **Table**, only when `testing` is present:
  - After the app rows comes **App subtotal** (today's total figures).
  - Then a `TESTING · CI PROMPT REGRESSION` group row (`col-span-full`, one cell, so it survives the 640px grid).
  - Then one row per suite:
    - hatched swatch;
    - last, prior and change;
    - why `Runs 9, was 4 · £/run +7%`, plus "· N usage unknown" when there are any;
    - a hatched sparkline from `testing.days`.

    These rows are **inert**: no `aria-pressed`, no stretched button, no hover background.
  - Then **Testing subtotal** (why: "the CI key has its own spend cap").
  - Then **Total**: app + testing, with the combined $ and the existing `£1 = $x`.
  - `AllTimeTable` gets the same structure on its Spend + sparkline grid, with no prior or change.
- **Day view.**
  - After the app run list comes a **Prompt test runs** section whenever `promptTestRuns` is non-empty. It
    ignores the run filters.
  - On a day with CI runs and no app runs, the empty message reads "No app runs on this day."
  - Each row (layout as the mock) shows:
    - `PR #1912` and the title, or "Manual run";
    - the suite;
    - `42 cases · 0 failed · 612k in · 38k out · 71% cached · attempt 1`;
    - the outcome:

      | Outcome | Text |
      |---|---|
      | `success` | "passed" |
      | `failure` with `failed > 0` | "N failed" |
      | `failure` with `failed = 0` | "failed" |
      | `cancelled` | "cancelled" |
      | other | the raw value |

    - the cost, or "usage unknown" / "unpriced" (never £0);
    - a `run ↗` link (`target="_blank" rel="noopener noreferrer"`), rendered only when `htmlUrl` starts with
      `https://github.com/`.
  - Footnote: "Priced by the server with the same CostCalculator rates as the app, from the token counts the run
    reported."
  - The summary's spend is unchanged.
- **Range buttons:** no change (the server applies the range).
- **Phone**: run rows wrap as in the mock's 640px rule, done with `max-[639px]:` variants (the same breakpoint
  as `useIsMobile`, `(max-width: 639px)`) rather than the hook. Desktop and tablet are otherwise unchanged.
- **Tests (Vitest).** No whole-tree snapshots; assert on test ids and text.
  - With `testing` null, the divider, chip, testing line, footnote, testing rows and subtotals are absent, and
    the "All app types" label is present.
  - With the same app data, `testing` null and `testing` present (toggle off) give identical headline percent,
    7-day average series, App subtotal and y-axis max.
  - Toggling via the chip and via the link; tooltip totals; all-time variants; unknown and unpriced; the day
    list's states and link guard; `runsCost.js` helpers.
  - Coverage floors hold, and the changed-lines check passes.
- **Browser check** on local port 8083 as admin, with seeded `ci_spend_run` rows, at desktop and phone width.
  The app has one (dark) theme.

### Docs

The Runs and Cost trends section of the reference docs (`platform.md`, admin features) and `api-endpoints.md`.

## Not doing

- Push from CI to production (a write surface and a CI credential on production).
- Anthropic's usage/cost report API as the source (needs an org admin key, and gives daily totals only).
- Counting CI spend in any alert, budget, spend cap or app figure.
- Renaming "Sky eval, the test harness" in the verdict body. It is the in-app eval and a separate thing; revisit
  only if the owner finds the two confusing.

## Review record

Adversarial plan review, 2026-11-27, with five read-only reviewers. Each lens's findings were folded into the
sections above.

- **Secrets and workflow rules.**
  - The default request factory follows the redirect and would send the token to the blob host. Fixed: its own
    factory with redirects off.
  - Signed URLs could reach the logs. Fixed: logs name the host only, and the redactor masks `sig=`.
  - The one-line test edits would leave an unused import and fail Checkstyle. Fixed: the import is swapped.
  - The workflow test picked the first "Upload" step. Fixed: steps are matched by exact name.
  - Errored calls can still be billed. Fixed: they are counted, and the under-count is noted.
  - The count bounds allowed a large fake spend. Fixed: realistic bounds and stream caps.
- **Idempotency.**
  - Jobs carried over by "Re-run failed jobs" would add phantom UNKNOWN rows. Fixed: the job's `run_attempt`
    must equal the attempt.
  - Hourly re-reads would burn the rate limit. Fixed: the `ci_spend_attempt` settled marker and a per-poll call
    budget.
  - Jobs cancelled before Maven would read as UNKNOWN. Fixed: the paid step must have started.
  - A unique-key race would poison the transaction. Fixed: `REQUIRES_NEW` per suite and an overlap guard.
  - `status=completed` would hide attempt 1 while attempt 2 runs. Fixed: the filter is dropped and each
    attempt's status is checked.
  - A manual run's title, and an empty `pull_requests`, were mishandled. Fixed.
- **Pricing.**
  - The echoed model id might not match exactly. Fixed: the requested id is recorded.
  - UNPRICED rows were final. Fixed: they are repriced.
  - `RunFx` cannot be the poller's source, and its date basis differed. Fixed: `ExchangeRateService` on the UTC
    date.
  - Parity is tested through the same `CostCalculator` code path.
- **Isolation.**
  - The "all" range could count CI rows outside the app's days. Fixed: CI rows are bounded to them.
  - Adding fields would break positional constructors. Fixed: secondary constructors, composed in the
    controller.
  - The mocked isolation test was vacuous. Fixed: a real endpoint-level Testcontainers test.
  - Helper extraction is pinned by direct tests first.
- **UI.**
  - The chip and divider showed when `testing` was null. Fixed.
  - Tooltip and aria totals, the isolate interaction, the `empty` check, the all-time variants, dense
    `testing.days`, inert testing rows and the group-row grid were missing or unclear. Fixed.
  - Hatching is now specified in Tailwind and `useId` terms.
  - Day-view filters, the empty message, outcome text and the link guard. Fixed.
  - The testing line sat inside `hasSpend`. Fixed: moved outside it.
  - The plan asked for whole-tree DOM snapshots and a light-scheme check. Dropped: the test standards forbid
    such snapshots, and the app has no light scheme.
