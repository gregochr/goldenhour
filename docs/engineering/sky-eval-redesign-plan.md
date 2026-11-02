# Sky Eval redesign (Operations → Sky Eval)

**Status:** planned 2026-11-02, owner asked for it · three phases, one PR each · backend + frontend (admin-only page)

**Design:** `docs/engineering/design/sky-eval-redesign/` — `README.md` (the spec, high fidelity, final copy),
`sky-eval-redesign.html` + `sky-eval.js` (a clickable prototype on synthetic data; open the HTML in a browser),
`screenshots/01–05`. Where this plan and the design README disagree, **this plan wins** (it records what the
codebase needs); otherwise follow the README for layout, copy, colours and encodings exactly.

## Why

The page answers three questions badly today: which model to run in production, whether a change made ratings
worse, and how to kick off a quick check. The redesign builds the page around those three jobs (Models, Fixtures,
Runs tabs, plus a Run a check panel). It needs two things the backend does not have: every eval run tied to a
**version** (the commit column has always been empty in production), and **derived** views (sessions, verdict,
lane stats, before/after per version) that the house rule says the server computes.

## Ground facts (read before building)

- Tables: `sky_rating_eval_run` (V117, V121 `batch_id`), `sky_rating_eval_result` (one row per fixture × repeat,
  `rating`, `expected_min/max`, `miss_direction` BELOW/ABOVE/null), `sky_rating_eval_model_exclusion` (V213).
  Latest migration is V214; take the next free number at build time.
- `GitInfoService` reads `git.properties` from the git-commit-id plugin. **The production image is built without
  `.git`** (`backend/Dockerfile` copies only `pom.xml` and `src`; `failOnNoGitDirectory=false`), so
  `git.properties` is absent and every production run has an empty `git_commit_hash`. Production images are built
  by `.github/workflows/deploy.yml` on a `v*` tag only, so in production a "version" changes once per release.
- Production lanes come from `model_selection` via `ModelSelectionService.getActiveModel(RunType)`:
  `BATCH_NEAR_TERM` (T, T+1) and `BATCH_FAR_TERM` (T+2, T+3). Never hard-code the lane models. Today they are
  Sonnet 5.5 and Haiku 4.5; the two lanes may name the same model, and the page must cope (two lane cards, same model).
- Costs are stored in micro-dollars; the page shows **£**. Convert with `ExchangeRateService.getRate(LocalDate)`
  at the run's start date, server-side.
- Weekly schedule: job `sky_rating_eval` in `scheduler_job_config` (cron + status); models = `scheduledModels()`.
- Fixtures: `SkyRatingEvalFixtures.ALL` (name, band). "Date added" = the first `run_timestamp` at which a result row
  for that fixture exists (null if never run).
- Model display names and colours: the README's oklch colours keyed by enum (`SONNET_55`, `HAIKU`, `HAIKU_55`,
  `SONNET` = Sonnet 4.6, `OPUS`); any other selectable model gets a neutral fallback colour. Names come from the
  existing `getAvailableModels` / model label helpers, not a new hard-coded table where one exists.

## Phase 1 — backend (one PR)

### 1a. Wire the version
1. `deploy.yml` backend build: add build args `GIT_COMMIT_SHA=${{ github.sha }}`, `GIT_COMMIT_TIME` (ISO-8601 from
   `git log -1 --format=%cI`) and `GIT_COMMIT_MESSAGE` (`git log -1 --format=%s`), computed in a step after
   checkout. Pass the message safely (it can contain quotes): write outputs with the `$GITHUB_OUTPUT` heredoc form.
2. `backend/Dockerfile` runtime stage: `ARG`/`ENV` for the three, next to `APP_VERSION`.
3. `GitInfoService`: when `git.properties` is absent **or** has an empty `git.commit.id`, fall back to the
   `GIT_COMMIT_SHA` / `GIT_COMMIT_TIME` / `GIT_COMMIT_MESSAGE` env vars (read through an injectable lookup so it is
   testable; no `System.getenv` in tests). Add `getCommitMessage()`; add `git.commit.message.short` to the pom's
   `includeOnlyProperties` for local builds. Blank → treat as unknown (null), never "".
4. Migration: `ALTER TABLE sky_rating_eval_run ADD COLUMN git_commit_message VARCHAR(500)` and
   `ADD COLUMN fixture_names TEXT` (comma-separated fixture names for a subset run; NULL = all fixtures). Entity
   fields, `startRun` stamps the message (truncated to 500). Testcontainers migration test in the existing style.
   Historic rows stay NULL: they show as "unknown version".

### 1b. Fixture subsets for manual checks
- `startRun(model, trigger, runsPerFixture, List<String> fixtureNames)` (keep the old overload delegating with
  "all"). Unknown fixture names → `IllegalArgumentException` (400). `fixture_count` = subset size.
- `executeRun` scores only the run's fixtures. The batch path keeps scoring all fixtures (weekly runs are always
  all), but check its failure-row pass (`SkyRatingEvalBatchService` ~L326-333, iterates `ALL`) still holds when a
  subset run exists alongside — it only handles its own runs, so it should; prove it with a test.

### 1c. `POST /api/admin/sky-rating-eval/check` (ADMIN)
Body `{models: [EvaluationModel...], runsPerFixture: 1|3|8, fixtureNames: [..] | null}`. Validates: ≥1 model, all
selectable, no duplicates; runsPerFixture in {1,3,8}; fixture names known. Creates one MANUAL run per model on the
current build, starts them **in parallel** (the existing virtual-thread executor), returns 202 with the run ids.
Keep `POST /run` as is (other callers/tests may use it).

### 1d. `GET /api/admin/sky-rating-eval/overview` (ADMIN) — everything the page renders
One read, computed in a new `SkyRatingEvalOverviewService` (no logic in the controller). Shape (records, Jackson 3):

- `head`: `{sha, shortSha (7), message, committedAt}` of the running build; all null when unknown.
- `schedule`: `{label, jobStatus, models[]}` — label like `weekly, Sun 04:00` rendered from the job's cron in UK
  time (the design's sub-line; if the job is PAUSED say `weekly run paused`).
- `lanes[]`: `{runType, label ("T and T+1 forecasts" / "T+2 and T+3 look-ahead"), purpose ("Must be as accurate as
  possible" / "Cheaper, needs to be accurate enough to plan with"), model}` in that order.
- `fixtures[]`: `{name, bandLo, bandHi, firstSeenAt}` in `ALL` order.
- `versions[]`: distinct non-null commits seen on runs (plus HEAD), `{sha, shortSha, message, committedAt}`,
  newest first by `committedAt` (fall back to the first run time on that sha).
- `sessions[]`: runs grouped where trigger and version are equal and each run started within 40 min of the
  session's first run; newest first. Each `{id (first run id), trigger, version sha|null, startedAt, status
  (RUNNING if any running, else FAILED if all failed, else DONE), costGbp, runs[]}`; each run `{id, model,
  fixtureCount, runsPerFixture, ratings, inBand, below, above, inBandPct, costGbp, durationMs, status,
  errorMessage, progress (0–1, results persisted ÷ expected, only while RUNNING)}`.
- `runFixtures[]`: per completed run × fixture `{runId, fixture, n, inBand, below, above, mean, min, max}` from
  `sky_rating_eval_result` (null ratings = failed calls: excluded from n and from the means; say so in javadoc).
- `models[]`: one row per model that has any run or is a lane model, with: `model`, `lane` (runType or null),
  `last4WeeklyPct` (pooled in-band ÷ ratings over its last 4 WEEKLY runs that completed), `last4Below`,
  `last4Above`, `last4Ratings`, `onHeadPct` + `onHeadRuns` (all completed runs on HEAD), `previousVersionPct`
  (last 2 completed runs on the most recent earlier version it ran on; null if none), `deltaPts`,
  `status` HELD | DROPPED (`deltaPts <= -3`) | UNCHECKED (no run on HEAD) | NO_BASELINE (on HEAD, nothing before),
  `costPerRunGbp` (mean of its last 4 weekly runs), `costPerRatingGbp` and `secondsPerRating` (for the check
  estimate), `lastTestedAt`, `fresh` (tested within 21 days), `staleOnOlderVersion`.
  Sort: fresh first, then `last4WeeklyPct` descending.
- `verdict`: `{kind (DROPPED | UNCHECKED | HELD | NO_HEAD | NO_BASELINE), headline, model?, lines…}` per the
  README's three rules, evaluated on lane models only, in this order: any lane model DROPPED → "{short} made
  {model} worse."; any lane model UNCHECKED → "{short} hasn't been checked on {model} yet."; else "Both production
  models held up on today's change." Extra cases the design does not cover: HEAD unknown → "This build has no
  version recorded." (NO_HEAD); no lane model has a baseline → "Nothing to compare {short} with yet." Serve the
  body as structured parts (model, before, after, droppedButNotProd flag) so the client only formats.
- `comparisons[]`: for the last 5 versions that have runs on them **and** an earlier version with runs (HEAD
  first), `{version, rows[]}`; each row `{model, lane, beforePct, afterPct, deltaPts, notRun, moved[]}`; `moved` =
  fixtures whose in-band share changed by ≥ 20 points, `{fixture, beforePct, afterPct, direction BETTER|WORSE,
  missSide BELOW|ABOVE ("rated too low/high", the dominant side after)}`. Lane models first.
- `checkDefaults`: `{models: lane models (distinct), runsPerFixture: 3, missedLastTime: [fixture names with any
  miss in the most recent completed session]}`.

Pass rate is always in-band ÷ ratings scored, as a percentage with one decimal on the client.

**Caching:** confirm `HttpCachingConfig` does not cache `/api/admin/**` (it did not for `/schedule`); no
personal data is involved.

### Tests (phase 1)
Read `docs/engineering/test-improvement-standards.md`. `GitInfoServiceTest` (properties win; env fallback;
blank → null; message). Migration test. Service: `startRun` subset + validation; `executeRun` subset scores only
those fixtures; overview: sessions grouping (40-min edge both sides, trigger split, version split, null version),
last-4-weekly pooling, previous-version baseline (last 2 runs), each status, each verdict kind including both lanes
on the same model, comparisons ≥20-pt mover + direction + side, nulls excluded, £ conversion per run date, stale /
fresh at 21 days with an injected `Clock`. Controller: 202 for `/check`, 400 cases, 403 non-admin, overview 200.
Strict stubs, concrete arguments, no `any()` in `verify`. 80% per-class coverage.

Docs: `api-endpoints.md` (two endpoints), `platform.md` or wherever deploy build args are described (grep
`APP_VERSION`), changelog `changelog.d/20261102-sky-eval-versions-overview.md`.

## Phase 2 — frontend: shell, Run a check, Models tab (one PR)

Rebuild `SkyRatingEvalView.jsx` as a small folder `frontend/src/components/skyeval/` (`SkyEvalView`, `RunCheckPanel`,
`ModelsTab`, `AccuracyChart`, `VersionCompare`, `AllModelsTable`, plus pure helpers in `utils/skyEval.js`) and
point `ManageView` at it. One `GET /overview` load; refetch after a check is submitted and every 4 s while any
session is RUNNING (stop when none is).

- **Header** per README: title, sub-line from `schedule.label` + fixture count + scheduled model names, "Edit in
  Scheduler" link → `#manage/scheduler`. Underline tabs Models · Fixtures `n` · Runs `n sessions`; selected tab
  remembered in `localStorage` (`skyEval.tab`, try/catch, page works without storage). Fixtures and Runs tabs in
  this phase render a one-line "Coming in the next update" placeholder only if phase 3 is not merged in the same
  release; see correction 19.
- **Weekly model checkboxes move to the Scheduler**: in `SchedulerView`, the `sky_rating_eval` job row gets the
  same per-model checkboxes (reuse `getSkyRatingEvalSchedule` / `setSkyRatingEvalScheduled`, same optimistic
  behaviour and error revert as today; same testids `sky-eval-schedule-${m}`). Remove the "Show on chart"
  checkbox row and its `skyEval.chartHidden.*` storage (replaced by the chart chips).
- **Run a check panel** per README and `05-run-a-check.png`: model chips (PROD tag on lane models), "Production
  pair" shortcut, runs 1 / 3 · quick / 8 · same as weekly, fixtures All n / Missed last time · n (disabled at 0),
  version = `head` (short sha, message, "pushed 2h ago"; if HEAD unknown, show "version unknown" and still allow
  the run), estimate footer = Σ selected `costPerRatingGbp × fixtures × runs` and max `secondsPerRating × fixtures
  × runs` (models without history: "no estimate" for that part). Submit → POST `/check` → close panel, switch to
  Runs, expand the new session. Opened prefilled from the lane "Run a check" link (that model) and the stale
  footnote link (the stale models).
- **Models tab**: verdict card, two lane cards (stat strip, status bar, "Compared with, last 4 weekly" rows with
  points diff and cost multiple, stale footnote), Accuracy over time chart, "Did a change make ratings worse?",
  All models table — all exactly as the README. Charts: hand-rolled SVG like the prototype (no chart library is
  required; check whether one is already used in Operations and use it only if it can do the encodings). Hollow
  manual points, gold dashed version lines with staggered 4-char labels and hover titles, production models
  coloured 2px, others grey 1px 60%, chip/table click highlights a model.
- Tailwind only (arbitrary values for the oklch colours are fine), PropTypes, `data-testid`s on key elements,
  no inline styles (SVG attributes are fine). Phone: everything stacks; lane cards stack under 1000 px; tables
  scroll inside their card, never the page, at 375 px.

Tests: `docs/engineering/frontend-test-standards.md`. Replace `SkyRatingEvalView.test.jsx`. Cover: tab persistence
with storage throwing; each verdict kind's headline; lane status bar three states + links; compare rows (pts
colour, cost multiple, "Fell to"); version chips switch comparison; "Not run on this version"; All models sort and
stale dimming; chip highlight; Run a check defaults, Production pair, Missed-last-time count and disabled state,
estimate arithmetic, POST body, post-submit tab switch, prefilled open from both links; polling starts and stops;
Scheduler's moved checkboxes (port the existing schedule tests). Update `instantsAbroad.test.jsx` mocks if the
API module's exports change.

## Phase 3 — frontend: Fixtures and Runs tabs (one PR)

- **Fixtures tab** per README and `02`/`03`: model segmented control (models with runs, default the near-term lane
  model), grid of fixtures × that model's last 13 completed runs, sorted by last-4 misses; cell encodings, version
  borders, `·m` manual marker, tooltips; legend; row click expands the rating-history chart (band shading, mean dot
  with min–max whisker, colour by side). "See which fixtures" and "Fixtures →" links preselect a model.
  Horizontal scroll inside the grid only.
- **Runs tab** per README and `04`: sessions table (10, then "Show n older sessions"), model chips with ×repeats and
  % coloured ink/amber/coral at 97/92, expanded per-run table with error text, progress bar while running, and
  "Fixtures →". The pulsing tide dot uses `motion-safe:`.
- Delete the old view's leftover runs list and anything unused (old helpers, `/trend` client call if nothing uses
  it; keep the backend endpoint).

Tests: grid sort, cell class per state, version border, `·`, row expand, model switch, session expand, older
sessions button, running progress, failed error text, chip colour thresholds.

## Review cadence and gates
Each phase: Sonnet builds in its own worktree, runs the gates (backend ladder ending in `clean verify` with the
integration exclusion; frontend lint → test → audit → build), adversarial review of the diff, browser check on
the local backend (8083) with seeded eval rows for phases 2–3, PR, CI + Codex 👍 on the head, squash merge.
Never trigger a real eval run locally to "see it work" (it bills the Anthropic key); seed rows instead.

## Plan review corrections (2026-11-02) — these override the sections above

### Phase 1 (backend)
1. **Subset fixture count:** `finalise` (`SkyRatingEvalService` ~L455, also reached from the batch
   `reclaimOrphanWithoutBatch` / `failAll` paths) derives `fixture_count` from the run's `fixture_names` (null = ALL);
   `executeRun` resolves the same list. Test COMPLETED and FAILED finalise for a subset run.
2. **Pass rate = in-band ÷ attempts** (result rows for the run), exactly as the stored `pass_rate`: failed and
   null-rating rows are non-passes. Only means/min/max exclude nulls.
3. **Unknown version:** historic hashes are `""` (not NULL). Treat NULL and blank `git_commit_hash` as unknown
   everywhere. Route `StatusController`'s build info (~L293-299, reads Spring `GitProperties`) through the same
   fallback only if trivial; otherwise leave it and note it.
4. **A model can serve both lanes:** `models[].lanes: RunType[]` (empty = eval only). Verdict rules run per lane,
   deduplicated by model. A lane with no `model_selection` row uses `getActiveModel`'s fallback and is shown as
   configured (it is what production runs).
5. **Like-for-like:** `onHeadPct`, `previousVersionPct`, `deltaPts` and `comparisons` are computed over the
   **intersection of fixtures present on both sides**, pooled per attempt. So a "missed last time" subset check is
   compared only with the same fixtures before it. Test it.
6. **Changing fixture set:** fixtures not in `ALL` (retired) are dropped from `runFixtures`, `moved[]` and every
   pooled figure. In-band uses the stored `miss_direction` (band at write time).
7. **Trigger is `SCHEDULED`, not WEEKLY.** "Weekly" in this plan means `SCHEDULED`. The job may be paused, so
   `last4*` can be null; sort nulls last.
8. **Schedule label:** compute the next fire from the cron in the job's zone (`DynamicSchedulerService.cronZone`,
   default UTC), convert to Europe/London, print `weekly, Mon 04:00`. If the cron is not a single weekly fire,
   show the raw cron. PAUSED or DISABLED_BY_CONFIG → `weekly run paused`.
9. **Sessions:** group by `batch_id` when non-null; otherwise chain runs with the same trigger and version whose
   start is ≤ 40 min after the **previous** run's start in the chain. Session id = its first run's id.
10. **Progress:** null when `batchId != null` (batch rows only appear at reconcile). A `/check` run killed by a
    deploy stays RUNNING until the existing 24 h orphan reclaim; accept and note it.
11. **£:** convert per run with `ExchangeRateService.getRate(startedAt.toLocalDate())`, memoised per distinct date
    within the request. Sum micro-dollars per run, convert once; session cost = sum of run pounds.
12. **Queries:** bounded and constant in number: runs (entity, no result summaries); one aggregate projection over
    `sky_rating_eval_result` `GROUP BY run_id, fixture_name` → n, inBand (miss_direction null AND rating not null),
    below, above, sum/count of non-null ratings, min, max; one `MIN(run.run_timestamp) GROUP BY fixture_name` for
    `firstSeenAt`. Never loop queries per run.
13. **Entity mapping:** `fixture_names` uses `columnDefinition = "TEXT"`; message `length = 500` (prod is
    `ddl-auto: validate`). No `LocalH2EnumWidener` change. Both fields appear in the existing `/runs` payload; fine.
14. **Actions safety:** compute sha/time/subject in a step that writes single-line outputs (`%s`, newlines
    stripped) to `$GITHUB_OUTPUT`; reference `steps.git.outputs.*` only under `with: build-args`, never inside a
    `run:` script. The values are visible via `docker inspect`; they are public commit data, fine.
15. **Definitions:** "last 2 runs" / "last 4 scheduled" = pooled attempts, not mean of rates. `deltaPts` unrounded;
    DROPPED is `≤ -3.0`. Movers need n ≥ 3 on both sides and use the same pools as the rows; `missSide` null if no
    misses after or a tie. `lastTestedAt` = `completed_at` of the last COMPLETED run. Drop `staleOnOlderVersion`;
    `fresh` = lastTestedAt within 21 days. "Earlier version" = older by `committedAt`. `GIT_COMMIT_TIME` parsed with
    `OffsetDateTime.parse(..).toInstant()` (test with an offset). Dirty local builds share the clean hash; accepted.
16. **Day one:** every historic run has an unknown version, so after the first release with this change each lane
    model is UNCHECKED until a check runs, then NO_BASELINE until the next version. Expected; copy handles it.
17. **More served fields (backend-heavy):** `sessions[].byModel[{model, runCount, inBandPct}]` (the chart point and
    the Runs chip); `modelFixtures[{model, fixture, last4Misses, last4Pct}]` (Fixtures tab sort and right column);
    `lanes[].compared[{model, lanes, last4WeeklyPct, deltaPts, costMultiple, fellToOnHeadPct|null}]` (the lane
    card's "Compared with" rows; `fellTo` set when that model's `deltaPts ≤ -3` on HEAD). Points diff and cost
    multiple are relative to the lane's model.
18. **Estimate inputs:** `costPerRatingGbp` and `secondsPerRating` come from completed **MANUAL** runs (sync, not
    batch-discounted); null when none → "no estimate" for that model.

### Phases 2–3 (frontend)
19. **Phase 2 includes the Runs tab** (sessions table, per-run expand, progress, older-sessions button); only the
    **Fixtures tab** is a placeholder in phase 2 ("Fixture grid arrives in the next update."), and the "See which
    fixtures" / "Fixtures →" links are hidden until phase 3. Do not keep the old view: delete it, its recharts
    import (remove `recharts` from package.json if nothing else imports it), `chartHidden` code and ConfirmDialog
    use. Phase 3 is the Fixtures tab only.
20. **After submit,** expand the session whose `runs[]` contains the first returned run id (a new check can merge
    into an existing manual session). Test the merge case.
21. **Moved file:** repoint the six `vi.mock('../components/SkyRatingEvalView.jsx')` (ManageViewLayouts,
    ManageViewLocations, ManageViewGroups, ManageViewHashFollow, ManageViewRunsHash, RunProgressKeptAfterFailures)
    to the new path; move `instantsAbroad.test.jsx`'s `tsLabel` UK-zone test onto the new formatter and fix its
    API mock factory.
22. **Lint/a11y (`--max-warnings 0`, jsx-a11y):** every clickable row's target is a real `<button>` with
    `aria-expanded` (sessions, fixture rows) or `aria-pressed` (All models rows, chart chips, version chips).
    Expanded content sits beside the button, never inside it.
23. **Tailwind v4:** colour classes only as whole literal strings in a static map keyed by enum, never built at
    runtime. SVG `fill`/`stroke` may carry raw oklch. Fixture grid cells are drawn in SVG with `fill-opacity`
    (continuous alpha). Version gold `#EBD9A8` is a new token `--color-skyeval-version` in `@theme static`
    (`plex-gold` is bone). Fonts already load; no font work.
24. **Charts:** hand-rolled SVG with a fixed `viewBox` and `w-full`; no `clientWidth`/ResizeObserver. Scales and
    geometry in pure, unit-tested helpers in `utils/skyEval.js`.
25. **Tooltips:** SVG `<title>` on points and version labels, `aria-label` on grid cells; every tooltip fact is
    also reachable without hover (version chips/compare rows, row expand).
26. **Polling:** `hooks/useVisiblePoll.js` with `enabled = anyRunning` and a stable `useCallback`; skip a tick while
    a request is in flight; drop out-of-order responses with a sequence number. Tests per
    frontend-test-standards.md (settle inside awaited `act`).
27. **Scheduler:** new `scheduler/SkyEvalWeeklyModels.jsx`, rendered in `SchedulerJobPanel` when
    `job.jobKey === 'sky_rating_eval'`. Port SkyRatingEvalView.test.jsx's schedule tests (~L269-395: skipped note,
    load-error disabled state, out-of-order). Add a skyRatingEvalApi mock to SchedulerView*.test.jsx. "Edit in
    Scheduler" links to `#manage/scheduler/sky_rating_eval`.
28. **ManageView:** remove the `card` wrapper titled "Sky-Rating Calibration Eval" (~L449-454); the new header owns
    the title.
29. **Billing guard:** the panel replaces the old ConfirmDialog. Disable "Run check" while the POST is in flight; on
    error keep the panel open with an `ErrorBanner`.
30. **Storage:** `skyEval.tab` read back against an allowlist (`models|fixtures|runs`). Leave old
    `skyEval.chartHidden.*` keys alone.
31. **Responsive:** mirror the prototype's breakpoints (columns hidden below 900/760 px with `max-[899px]:hidden`
    etc., stat strip 2×2 below 560 px); tests assert the classes. Pulse = `animate-pulse motion-reduce:animate-none`.
32. **Client-side formatting only:** the estimate arithmetic (served per-rating cost × fixtures × runs) and
    percentage/£ formatting. Everything else listed in 17 is served.
