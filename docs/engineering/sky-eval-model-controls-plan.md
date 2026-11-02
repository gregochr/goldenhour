# Sky Eval: choose the weekly models, and filter the accuracy chart

**Status:** planned 2026-11-02, owner asked for it · one phase, one PR · backend + frontend (admin-only page)

## Why

The weekly Sky-Rating Eval scores every selectable model (`SkyRatingEvalService.SCHEDULED_MODELS =
EvaluationModel.selectable()`, since #1571), so a newly added platform model joins it automatically
(good, keep that). But there is no way to stop a model: the retired production models Opus 4.6 and
Sonnet 4.6 cost about $6 of the ~$6.50/month the eval spends (60-day production figures: Opus $7.63,
Sonnet 4.6 $4.15, Haiku 4.5 $1.09 over 8 runs each), and the owner wants to switch them off. The
accuracy chart also draws every model with history and cannot be filtered.

The page (Operations → Sky Eval, `frontend/src/components/SkyRatingEvalView.jsx`) today has one
row of radio buttons. It picks the model for a manual "Run Eval" and for the per-fixture drift panel
("pick model above"). That row stays exactly as it is. Two new rows are added.

## Backend

1. **Migration** (next free number; read `ls backend/src/main/resources/db/migration/ | sort -V | tail -1`):
   `sky_rating_eval_model_exclusion(model VARCHAR(20) PRIMARY KEY, excluded_at TIMESTAMP NOT NULL)`.
   An **opt-out** list: a model is scheduled unless it has a row. That keeps "a new model joins the
   weekly eval with no edit" true by construction. A row for a model that is no longer selectable is
   ignored (and harmless). Testcontainers migration test in the style of the existing ones.
2. **Entity + repository** for it (map the model the same way `sky_rating_eval_run.model` is
   mapped). Repository test.
3. **`SkyRatingEvalService`:** replace the static `SCHEDULED_MODELS` with an instance method
   `scheduledModels()` = `EvaluationModel.selectable()` minus the excluded models, in selectable order.
   Use it in both weekly paths: the synchronous `runScheduled()` and
   `SkyRatingEvalBatchService.runScheduledBatched()`. When the list is empty, the weekly run does
   nothing: no runs created, **no batch submitted**, one INFO log line. Update the javadoc that says
   "every selectable model".
4. **Schedule methods:** `List<ScheduledModelView> schedule()` (one row per selectable model:
   `model`, `scheduled` boolean) and `setScheduled(EvaluationModel model, boolean scheduled)`
   (insert or delete the exclusion row; idempotent; rejects a non-selectable model with
   `IllegalArgumentException`).
5. **Endpoints** on `SkyRatingEvalController`, both `@PreAuthorize("hasRole('ADMIN')")`:
   - `GET /api/admin/sky-rating-eval/schedule` → `[{model, scheduled}]` in selectable order.
   - `PUT /api/admin/sky-rating-eval/schedule/{model}` with body `{"scheduled": true|false}` → 200
     with the full updated list; 400 for an unknown or non-selectable model (match how `POST /run`
     maps `IllegalArgumentException`).
   - Check that `HttpCachingConfig` does not ETag/shallow-cache `/api/admin/**` responses in a way
     that would serve a stale list after a PUT; if it would, exclude the path.
   - Add both to `docs/engineering/reference/api-endpoints.md`.
6. Manual `POST /run` is unchanged: any selectable model can still be run by hand, scheduled or not.

## Frontend (`SkyRatingEvalView.jsx`)

Three labelled rows, top to bottom. Labels are short plain words.

1. **"Run now"** — the existing radio row, runs/fixture input and Run Eval button, unchanged
   (same testids, same behaviour, still drives the per-fixture drift panel).
2. **"Weekly run"** — one checkbox per selectable model (same `models` list from
   `getAvailableModels`, same label and colour classes). Loaded from `GET …/schedule`. Toggling one
   PUTs at once: optimistic, the checkbox is disabled while its request is in flight, and on error it
   reverts and shows the message in the existing `ErrorBanner`. The existing
   "N fixtures · weekly when the scheduler job is resumed" line moves under this row. When no model
   is checked, the line says the weekly run will be skipped. If the schedule fails to load, the row
   shows the error and its checkboxes are disabled (never shown as all-ticked by guess). testids
   `sky-eval-schedule-${m}`.
3. **"Show on chart"** — one checkbox per model that the accuracy chart can draw
   (`orderModelsPresent(runs, models)`, i.e. models with completed runs, in display order). Filters
   only the "Model accuracy over time" chart: hidden models' lines and legend entries are removed and
   the Y-axis floor (`accuracyDomainFloor`) is computed from the visible series only. Line colours
   stay tied to the model, not to its position among visible models. Remembered per browser in
   `localStorage` as the **hidden** set (key `skyEval.chartHidden`), so a new model is shown by
   default; every read and write in try/catch, and the page works with storage unavailable. If every
   model is unchecked, show a short "No models selected" note in place of the chart. testids
   `sky-eval-chart-${m}`.

A newly added model appears in rows 1 and 2 at once (it is selectable, and scheduled by default) and
in row 3 after its first completed run (before that it has no line to draw).

Desktop and phone: the rows wrap (`flex-wrap`), no horizontal scroll at 375 px. Tailwind only,
PropTypes on any new component, no inline styles. Admin-only page, so no role gating beyond what
exists.

## Tests

Backend (read `docs/engineering/test-improvement-standards.md`): service `scheduledModels()` (none
excluded = selectable; one excluded removed; exclusion of a non-selectable model ignored; order
kept); `setScheduled` insert/delete/idempotent/rejects non-selectable; batch path with an empty list
submits no batch and creates no runs; sync path likewise; controller GET/PUT happy paths, 400, and
403 for non-admin; replace the tests that pin `SCHEDULED_MODELS` (`SkyRatingEvalServiceTest` ~L54,
`SkyRatingEvalBatchServiceTest` ~L113-136) with ones against `scheduledModels()`. Strict stubs with
concrete arguments, no `any()` where the value is known.

Frontend (read `docs/engineering/frontend-test-standards.md`), in `SkyRatingEvalView.test.jsx`:
schedule checkboxes render from the API; toggle PUTs the right model and value; error reverts and
shows the banner; schedule load failure disables the row; chart checkbox hides a line and its legend
entry; hidden set persists to and restores from localStorage; a model absent from storage is shown;
storage that throws still renders; all-unchecked note; the radio row still drives the manual run and
drift panel.

## Docs and changelog

- This plan goes to `docs/engineering/sky-eval-model-controls-plan.md`.
- Update whichever `docs/engineering/reference/*.md` describes the Sky Eval weekly model list
  (`grep -rn 'SCHEDULED_MODELS\|Sky-Rating Eval\|sky-rating eval' docs/engineering/reference/`).
- `changelog.d/20261102-sky-eval-model-controls.md`: `### Added — Choose which models the weekly
  Sky Eval runs, and filter its accuracy chart`.

## How we will know it worked

After deploy, the owner unticks Opus and Sonnet 4.6 under "Weekly run"; the next weekly batch
creates runs only for the ticked models, and the eval's monthly cost drops to around $1.

## Plan review corrections (2026-11-02) — these override the sections above

1. **PUT path variable:** take `{model}` as a `String` and parse it with `EvaluationModel.valueOf` inside the
   service/controller so an unknown name becomes `IllegalArgumentException` → 400 (binding the enum
   directly gives `MethodArgumentTypeMismatchException` → 500 via `GlobalExceptionHandler.handleUnexpected`).
   A missing/null `scheduled` in the body is also 400. Tests: unknown name (e.g. `BOGUS`), non-selectable
   (`WILDLIFE`), missing body field.
2. **PUT returns only the toggled model's row** `{model, scheduled}`, and the frontend applies only that
   row, so two quick toggles whose responses arrive out of order cannot undo each other. Test it.
3. **API functions** `getSkyRatingEvalSchedule()` and `setSkyRatingEvalScheduled(model, scheduled)` go in
   `frontend/src/api/skyRatingEvalApi.js`, and must be added to the explicit `vi.mock` factories in
   `frontend/src/test/SkyRatingEvalView.test.jsx` (~L11-16) and `frontend/src/test/instantsAbroad.test.jsx`
   (~L57-62).
4. **Chart colours:** `modelColour(m, i)` takes `i` from the full `modelsPresent` list, never the visible
   subset.
5. **Chart filtering:** `accuracyFloor` reads only visible models' keys; day points with no visible value
   are dropped so no empty X slot remains; the "No models selected" note sits inside the existing
   `modelAccuracySeries.length > 0` guard.
6. **LocalH2EnumWidener:** add `TARGETS` lines for the new exclusion table's `model` column and for the
   existing `sky_rating_eval_run.model` column (already a gap), so a future model can be written to an
   existing local H2 database. Update its test if one pins `TARGETS`.
7. **Javadoc references:** fix `{@link SkyRatingEvalService#SCHEDULED_MODELS}` at
   `SkyRatingEvalBatchService.java` ~L126 and the javadoc at `SkyRatingEvalService.java` ~L198-200.
8. **HttpCachingConfig:** no change; admin paths are not in `REVALIDATABLE_READ_PATHS`.
9. **Docs:** only `docs/engineering/reference/api-endpoints.md` (~L188) needs the two new endpoints.
   Keep the "weekly when the scheduler job is resumed" wording in the Weekly run row.
10. **Idempotent insert:** exists-then-save; test a repeat call. Keep the explicit "Show on chart"
    checkbox row (owner asked for controls, not legend clicks).
