# Model test screens retirement: Location Model Test and Briefing Model Test

**Status: Phase 1 merged (#1517, shipped in v2.35.2); Phase 2 in PR.** Written against `main` at `99de0d9ed` (v2.35.1).

Owner decision (2026-10-30): keep **Sky Eval** as the only model-test screen in Operations, and delete
Location Model Test and Briefing Model Test. Part of the pre-launch tech-debt sweep.

### Phase log

| phase | branch | commit | date | notes |
|---|---|---|---|---|
| plan | — | — | 2026-10-30 | Survey by the planner (grep trace of every reference). Review: two lenses (backend/data; frontend/tests/docs), 19 charges; all folded in, `ReplayJson` added to the deletions. |
| 1 | `chore/model-test-retirement-phase-1` | see PR | 2026-10-30 | Built to plan. Also reworded README line 28 ("Model comparison harness", now a stale claim) to name Sky Eval; migration is V199. |
| 2 | `chore/model-test-retirement-phase-2` | see PR | 2026-10-30 | Built to plan: V200 drops the four tables, migration test seeds both families (incl. `parent_run_id` re-run) and checks regions/locations survive. |

---

## 1. Why

- **Location Model Test** (`ModelTestView`, `/api/model-test/*`) runs the sky-rating prompt once per
  model on live data and shows whether Haiku, Sonnet and Opus *agree*. Sky Eval
  (`SkyRatingEvalView`, `SkyRatingEvalService`) runs the same prompt against frozen fixtures with
  ground-truth bands, `pass^k` over N runs, direction-bucketed misses and a drift trend. It answers
  the better question and knows the 5.5 models; Location Model Test only knows `HAIKU/SONNET/OPUS`.
  Last run on production: 2026-03-03.
- **Briefing Model Test** (`BriefingModelTestView`, `/api/briefing/compare-models*`) compares the
  best-bet advisor across `HAIKU, SONNET, SONNET_ET, OPUS, OPUS_ET`. No 5.5 models. Last run on
  production: April 2026. It is also a **scheduled daily job** (`briefing_model_comparison`, seeded
  ACTIVE by V79 at 05:00 UTC, moved to 06:30 by V173). If that row is still ACTIVE in production it
  costs five Claude calls a day; whether it is depends on hand edits, so the migration removes it
  either way.

## 2. What stays (do not delete)

- `BriefingBestBetAdvisor` itself: `advise`, `replayWithPrompt`, `currentSystemPrompt`,
  `getModelDisplayName`, and the private helpers `rejectionReason` / `extractFirstText` (still used by
  `advise` and `replayWithPrompt`). Live consumers: `BriefingService`, `AdvisorReplayController`,
  `PipelineRunPickEntity` / `PipelineRunsView`.
- `EvaluationModel.SONNET_ET` / `OPUS_ET`: still referenced by `BatchSummary`, the advisor and
  `utils/modelLabels.js`, and stored in historic `job_run` / `api_call_log` rows. Not touched.
- `ForecastDataAugmentor` (used by `ForecastService` and many others), `EvaluationService
  .evaluateWithDetails` (used by `SkyRatingEvalService`), `SolarService`, `OpenMeteoService`, repository finders (all have other
  callers). Only their Javadoc mentions of the deleted services change.
- `DynamicSchedulerService`: unchanged. A `scheduler_job_config` row with no registered target is
  logged and skipped (`scheduleJob`, `install`), so ordering between code and row removal is safe in
  both directions.
- `BriefingModelComparisonRescheduleMigrationTest`: it migrates only to `172`/`173`, so the later row
  delete does not affect it. Keep it (historic migrations stay proven).
- Historic migrations V34, V38, V39, V63, V79, V82, V125, V137, V151, V173: immutable.

## 3. Phase 1: delete the code and the scheduled job row (one PR)

### Frontend

- **Delete:** `components/ModelTestView.jsx`, `components/BriefingModelTestView.jsx`,
  `api/modelTestApi.js`, `api/briefingModelTestApi.js`, `test/ModelTestView.test.jsx`,
  `test/BriefingModelTestView.test.jsx`.
- **`components/ManageView.jsx`:** remove the two imports, the `modeltest` and `briefingmodeltest`
  entries in the Operations `tabs` list, and their two render branches. An old
  `#manage/modeltest` hash then falls back to `data/users` through `parseHash`'s existing default;
  no redirect needed.
- **Test mocks:** drop the `vi.mock` lines for the two views in all four files (a factory mock of a
  missing path may never be resolved, so the runner will not name them):
  `test/ManageViewRewind.test.jsx:20-21`, `test/RunProgressKeptAfterFailures.test.jsx:26-27`,
  `test/ManageViewSubRegions.test.jsx:21-22`, `test/ManageViewWaitlist.test.jsx:39-44` (multi-line).
  No test asserts the Operations tab list or count; add one assertion to an existing `ManageView*`
  test that "Location Model Test" and "Briefing Model Test" are absent and "Sky Eval" is present.
- **Comments that cite the deleted code as a live example** (never change test logic):
  - `test/formFieldFocusRules.test.js:129, 269, 350` and `index.css:3708`: reword to "the
    since-deleted `ModelTestView`" or drop the citation. The fixture tests at :269-283 pin the
    comment-in-template shape on their own, and `ModelTestView`'s only `focus:outline-none` is inside
    a comment, so no count changes.
  - `utils/locationTypes.js:98` cites `ModelTestService`: fold it into the existing "(the synchronous
    engine and the Prompt Test harness did too, before they were deleted)" clause.
    `test/locationTypes.test.js:73`: likewise merge into its existing "since-deleted Prompt Test"
    wording rather than adding a second clause.
- No dead helpers follow: every utility, component and CSS class the two views import has other
  users (checked in review).

### Backend: delete these classes

- `controller/ModelTestController`, `service/ModelTestService`, `service/BriefingModelTestService`.
- `entity/ModelTestRunEntity`, `ModelTestResultEntity`, `BriefingModelTestRunEntity`,
  `BriefingModelTestResultEntity`, `entity/RerunType` (only `ModelTestService` and
  `ModelTestRunEntity` use it).
- `model/ModelTestRunDto`, `ModelTestResultDto`, `BriefingModelTestRunDto`,
  `BriefingModelTestResultDto`.
- `repository/ModelTestRunRepository`, `ModelTestResultRepository`,
  `BriefingModelTestRunRepository`, `BriefingModelTestResultRepository`.
- `util/ReplayJson` and `util/ReplayJsonTest`. Its only main-code caller is `ModelTestService:96`
  (its Javadoc says it exists for the prompt-test and model-test harnesses, both now gone);
  `SkyRatingEvalFixtures:17` only names it in Javadoc and builds its own mapper. Reword that
  Javadoc and `DirectionalCloudDataJsonTest.java:13` so neither names `ReplayJson`.

### Backend: edit

- **`BriefingController`:** remove the `BriefingModelTestService` field, constructor parameter and
  Javadoc `@param`, the three `/compare-models*` handlers (:176-206), the DTO imports and
  `java.util.List` (:25, used only by those handlers). `RequestParam` and `PostMapping` stay (other
  users).
- **`BriefingBestBetAdvisor`:** delete both `compareModels` overloads, `callModel`, and the
  `ComparisonRun` / `ModelComparisonResult` records. Remove the imports `ThinkingBlock` (:8) and
  `java.util.ArrayList` (:39), used only there. The Javadoc at :657-664 describes `extractFirstText`
  but is attached to `rejectionReason` (:665): move it onto `extractFirstText` (minus the
  `#callModel` mention) and give `rejectionReason` its own short Javadoc.
- **Javadoc mentions only:** `LocationEntity.java:328, 341`, `ForecastDataAugmentor.java:39`,
  `EvaluationModel.java:28-29` (keep the two `*_model_test_result.evaluation_model` columns in the
  list for now, noting "historic rows only, dropped in Phase 2"; Phase 2 removes them).

### Migration `V199__retire_briefing_model_comparison_job.sql` (latest is V198; renumber if `main` moved)

```sql
DELETE FROM scheduler_job_config WHERE job_key = 'briefing_model_comparison';
```

Header comment in the house style (see V103, V198): why, and that the rollback image still registers
the target, finds no row, and schedules nothing. Check for any FK into `scheduler_job_config`
(none found at plan time) and any `scheduler_job_run`-style child table keyed on `job_key`; if one
exists, say in the header what happens to its rows.

**The tables are not touched in Phase 1.** Rollback rule (prod runs `ddl-auto: validate`): the
v2.35.x image still maps all four tables, so they can be dropped only once a release without the
entities has shipped. That is Phase 2.

**Migration test** `integration/RetireBriefingModelComparisonJobMigrationTest`, shaped on
`BriefingModelComparisonRescheduleMigrationTest` (plain Flyway, `postgres:17-alpine`, no Spring):
migrate to `198`, assert the row exists; migrate to latest, assert it is gone and every other
`job_key` present at `198` is still present with its cron unchanged.

**Local H2:** local profiles never run Flyway, so a local `briefing_model_comparison` row stays and
the scheduler logs "No registered target … skipping schedule" once at startup, and Scheduler →
"Trigger now" on that row throws `IllegalStateException("No registered target…")`
(`DynamicSchedulerService:424` via `SchedulerController:172`). Admin-only and local-only; no local
cleanup bean. Delete the row by hand in the H2 console if it bothers you. The four tables also stay in local H2 (`ddl-auto: update` never drops). Note both in
the changelog entry's last line.

### Backend tests

- **Delete:** `ModelTestControllerTest`, `ModelTestServiceTest`, `BriefingModelTestServiceTest`.
- **`AbstractControllerTest`:** remove the two `@MockitoBean` fields and imports.
- **`BriefingControllerTest`:** remove the compare-models tests; keep everything else.
- **`BriefingServiceServedMemoTest:272`:** drop the `BriefingModelTestService` mock from the
  `BriefingController` construction.
- **`BriefingBestBetAdvisorTest`:** delete exactly these units, by name, not by line range:
  - nested `CompareModelsTests` (~:2380-2507);
  - nested `ExtendedThinkingTests` (~:2539-2734) and any private helpers only it uses (every test in
    it calls `compareModels`);
  - in `Sonnet55Tests`, the methods `comparison_sonnet55` (~:3925) and `comparison_refusal` (~:3948).
  - **Keep** `ModelDisplayNameTests` (~:2508-2537, the only test of `getModelDisplayName`) and
    `Sonnet55Tests.live_refusal` / `replay_sonnet55`.
  Coverage of `rejectionReason` / `extractFirstText` stays through `TruncationHotfixTests` and the
  `advise`/`replay` tests; still read `BriefingBestBetAdvisor`'s line in the JaCoCo report.
- Do not touch `model/ModelTest.java`, `entity/EvaluationModelTest.java`, `AskReadModelTest` — name
  collisions only.

### Docs, in the same PR

- `reference/pipeline.md:12`: drop the clauses saying the 5.5 models are not in "the fixed
  comparison lists of the Model Test and Briefing Model Test screens" (four mentions).
- Root `README.md`: `:162` ("model test" among ADMIN Operations features), `:222` (heading
  "Metrics & model testing"), `:230-234` (the `/api/model-test/*` endpoints), `:255`
  (`POST /api/briefing/compare-models`).
- `reference/api-endpoints.md:187` (drop `POST /api/model-test/run|run-location|rerun` and
  `GET /api/model-test/runs|results`; `rerun-determinism` was never listed but goes with the
  controller), `:193` (the "model-comparison" clause in the calibration
  paragraph), `:239` (drop the three `compare-models` endpoints).
- `reference/plan-tab.md:8`: drop "Briefing model comparison test: …" and `BriefingModelTestService →
  BriefingModelTestView` from the advisor's live consumers (two remain).
- `reference/architecture-decisions.md:10`: drop `BriefingModelTestService` and `ModelTestService`
  from the Jackson 2 user lists.
- `reference/database-migrations.md`: add V199.
- CLAUDE.md's overview mentions neither screen; no change.
- `changelog.d/20261030-retire-model-test-screens.md`:
  `### Removed — Location Model Test and Briefing Model Test screens`.

### Phase 1 browser check (UI work), local backend on 8083

- Operations sub-tabs read: Pipeline Runs, Job Runs, Run Config, Sky Eval, Scheduler, Travel Days,
  Hot Topics, Rewind, Sub-regions.
- Sky Eval opens and lists its runs.
- `#manage/modeltest` lands on Data → Users without a console error (the hash itself stays until a
  tab is clicked; nothing rewrites it).
- The card titles "Location Model Comparison Test" and "Briefing Model Comparison Test" appear
  nowhere.
- Scheduler lists no "Briefing Model Comparison" row **only if** the local row was deleted by hand;
  otherwise it may still list it (local H2 does not run V199). Say which.

## 4. Phase 2: drop the four tables (ships in the release after Phase 1)

`V200__drop_model_test_tables.sql` (renumber as needed):

```sql
DROP TABLE briefing_model_test_result;
DROP TABLE briefing_model_test_run;
DROP TABLE model_test_result;
DROP TABLE model_test_run;
```

Results before runs (each result table holds the FK to its run table). `model_test_run`'s
self-referencing `parent_run_id` FK (V39) goes with its table. `model_test_result` also has FKs *out*
to `regions` and `locations` (V34); dropping it removes the constraint V137 §6 worked around, so
`regions`/`locations` rows can be deleted afterwards. Nothing else references any of the four
(checked against every migration to V198; re-check against `main` at build time).

Migration test `integration/DropModelTestTablesMigrationTest`: migrate to the version before,
seed one run and one result in each pair (a `model_test_result` pointing at a seeded region and
location), migrate to latest, assert all four tables are absent from `information_schema.tables` and
the seeded region and location rows still exist.

Code: drop the two `*_model_test_result.evaluation_model` columns from `EvaluationModel.java:28-29`'s
Javadoc. Docs: `reference/database-migrations.md` (V200). Changelog:
`### Removed — model test tables`.

**Gate on release:** do not merge Phase 2 until the owner has released and deployed the version
carrying Phase 1.

## 5. Phase order, PRs, gates

Phase 1 → (owner release) → Phase 2, one PR each, under CLAUDE.md's build-and-merge rule: Sonnet
builds in its own worktree; local gates; ready PR; CI green; Codex 👍 on the head commit.

- Backend: the full ladder ending with `clean verify` excluding `integration/**`. Migrations are
  proven only in CI.
- Frontend: `npm run lint && npm test && npm audit --audit-level=high && npm run build`.
- After deletion, run
  `grep -rniE "ModelTest(Service|View|Controller|Run|Result)|BriefingModelTest|modelTestApi|briefingModelTestApi|compare-models|compareModels|/api/model-test|RerunType|ReplayJson|model_test_|briefing_model_comparison|Model Test" backend/src frontend/src docs/engineering/reference README.md`.
  Allowed hits only: historic migrations (V34, V39, V63, V79, V82, V125, V137, V151, V173),
  `BriefingModelComparisonRescheduleMigrationTest`, the new migration and its test, the historic
  rows of `database-migrations.md` (:17, :35, :56 and the new V199 row), and "since-deleted"
  comments. Everything else is a miss.
