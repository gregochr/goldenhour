# Handover: persist `forecast_evaluation` rows for prompted batch slots

Self-contained implementation plan. Backend + one migration + zero frontend changes (by design —
see R1). The ripple-effect review below was done up front over every reader of the table
(2026-08-30, three read-only sweeps: backend readers, frontend consumers, batch-pipeline
linkage); the design exists to dissolve the hazards it found, and the implementing session
should re-verify the cited lines rather than re-run the whole census.

## Why (all the context you need)

In the batch era, `forecast_evaluation` receives rows **only for triage stand-downs**
(`ForecastService.fetchWeatherAndTriage` saves at :376-380 and :406-410 and nowhere else on the
batch path); prompted slots write their results to `cached_evaluation` and `forecast_score`
only. Measured consequence (§9 of `cloud-approach-veto-fix.md`, "The promptable cut, measured"):
the post-deploy verification window has `ratedCount` 0 everywhere and will keep it, so

- the veto demotion's pre-registered post-deploy instrument (rating distributions in
  `vetoFired`/`stripMissed`) is structurally dead,
- ERA5 verification now verifies only stand-downs — no prompted forecast is checked,
- the calibration gate (`findCalibrationPairs`, filters `rating IS NOT NULL`) starves,
- and the far-term Haiku→Sonnet flip's evidence gate is broken.

One write fixes all four: prompted batch slots also persist a `forecast_evaluation` row —
inserted at submit (the row is the durable carrier of the 66-field forecast snapshot, which is
unavailable at result time and lost on restart), scored in place when the batch result lands.
This also moves toward the dual-engine consolidation: the table becomes the record of "a
forecast was made", whichever engine made it, and `cached_evaluation` is demoted from
"the only place batch ratings exist" to a pure serve cache (a named retirement candidate for
the consolidation — do NOT retire it here).

## The design (settled; deviations need a recorded reason)

**R1 — Pending rows are invisible to every serve path.** The single most important rule, and
what makes the frontend untouched. A pending row must never win a "latest row per slot" read:

- `ForecastEvaluationRepository.findLatestRunPerSlotByLocationIds` (the map's query,
  `ForecastController.getForecasts` :158-208) excludes PENDING rows, so the slot is not
  `covered` and the **sparse cached-view fallback keeps serving the previous score** — that
  fallback exists precisely for the submit→result window (the controller's own javadoc at
  :146-148 says so). Without this exclusion, every pipeline run blanks the map's prompted pins
  for hours: the pending row wins the `MAX(forecastRunAt)` subquery, suppresses the sparse
  fallback, and non-admin users have no reveal toggle (`MapView.jsx:1337-1348`, `showUnrated`
  admin-only).
- `EvaluationViewService.loadLatestForecasts` (:230-254) and `findTopBy...Desc` (:576-579)
  likewise exclude PENDING — otherwise a pending row shadows the previous cycle's triage/scored
  row for any slot `cached_evaluation` doesn't cover (region-less locations), silencing it in
  `/api/briefing/evaluate/scores` and briefing enrichment (`mergeToView` falls to
  `Source.NONE` at :732).
- A second cross-source hazard dissolves with these two: a slot triaged last run but submitted
  this run must not render as a stand-down off the *briefing cache's* stale `triageReason`
  (`frontend/src/utils/standDown.js:31-34`) — with R1 the forecast source keeps serving the
  prior row, so the resolution logic sees what it saw before.
- `getHistory` and `getCompare` (admin surfaces) **include** pending rows deliberately — they
  show every run. Document in their javadoc that a row's rating can arrive after its
  `forecastRunAt` (the compare timeline plots submit-time timestamps for batch ratings).

**R2 — Pending is an explicit column state, never an inference.** One migration (⚠️ read the
next V-number from the tree ON MAIN at implementation time — two V-numbers have collided
before) adds nullable `batch_state VARCHAR(20)` to `forecast_evaluation`:
`PENDING` / `SCORED` / `ABANDONED`; null for every existing row and for all sync-engine and
triage writes (their semantics are unchanged). R1's exclusions filter on
`batch_state = 'PENDING'`, not on null-column patterns. Rationale: an abandoned slot (batch
expired, retry unreconstructable) must be distinguishable from an in-flight one forever, and
"rating null AND triage null" cannot say which. Partial index only if EXPLAIN demands it —
V128's `(location_id, target_date, target_type, forecast_run_at)` already serves every new
access path; do not add indexes on spec.

**R3 — The submit→result link is the row's primary key, carried in the custom_id.** Format:
append an optional final segment `r{rowId}` → `fc-{locationId}-{date}-{TARGET}-r{rowId}`.
The format is fully centralised in `CustomIdFactory` (build :64-69, parse :192-213); extend
`ParsedCustomId.Forecast` with a nullable `evalRowId`. ⚠️ The tail parser
(`extractDateAndTarget` :277-300) takes the segment after the LAST hyphen as the `TargetType` —
a naïve append breaks all three parsers and the result side then **discards the paid
response** as `MALFORMED_ID` (`BatchResultProcessor` :233-242). Parse the optional `r\d+`
suffix off FIRST, then run the existing logic. Backward compatibility is mandatory: batches
submitted by the previous binary are in flight at deploy — old-format ids must parse (null
`evalRowId` → skip the row update, log at INFO; everything else proceeds). Why not the
alternatives, so nobody relitigates: a natural-key "newest pending row" lookup goes ambiguous
whenever nightly + intraday + JFDI overlap on one slot, and JFDI batches have
`pipeline_run_id = null` so run-scoping degrades exactly there; a link table written after
`BatchSubmissionService.submit` returns has a crash window in which the 60 s poller can beat
the link write. The embedded id exists atomically with the submission payload.

**R4 — The insert site is the task-creation seam, fed by `ForecastPreEvalResult`.** The
collector already holds everything `buildEntity` needs (location, date, targetType, eventTime,
azimuth, daysAhead, model, full `AtmosphericData`) in the `ForecastPreEvalResult` returned by
`fetchWeatherAndTriage`. Add a `ForecastService` method (e.g. `persistPendingEvaluation
(preEval)`) that calls the existing private `buildEntity` with an empty evaluation, sets
`batch_state = PENDING`, saves, and returns the id; thread the id through a new nullable
`EvaluationTask.Forecast` field into `EvaluationServiceImpl.submitForecast`'s id build.
Call it at every site that turns a preEval into a submitted task: `ForecastTaskCollector`
(:539-542 sky; see R8 for the canopy lanes), `ForceSubmitBatchService` (:142-145 and the force
variant) — **a JFDI submit of a previously-triaged slot gets a pending row too** (the triage
row records the stand-down; the pending row records the override; multi-row-per-slot is the
table's designed norm — no unique constraint exists, repo javadoc :34-39). `daysAhead` and
`forecastRunAt` are submit-time facts and stay so.

**R5 — The result-time update goes through the same values as the cache write, by PK.** The
interception point is `ForecastResultHandler.buildResult` (:494-520): update the row with
`safeRating` (the `RatingValidator`-validated, `RatingCombiner`-combined rating at :511-512),
the validated potentials, summary/headline, and the response-resolved model, then set
`batch_state = SCORED`. The "sky not forecast" substitution (:497-503, rating 1 + canned
summary) **lands in the row too** — the row and `cached_evaluation` must never disagree about
one slot (the 2026-08-03 three-rules lesson). Do NOT bump `forecastRunAt`: it means "when the
weather was sampled", the freshness comparisons in `EvaluationViewService` (:797-802) stay
consistent (result-stamped `cached_evaluation` always wins), and the history endpoint's
ordering keeps its meaning. No `@Version` needed — the result processor is the only writer of
the update, by PK. Amend the repo javadoc's "runs are never updated in place" (:34) to name
this designed exception.

**R6 — A retry gets a NEW pending row; the precursor goes ABANDONED.** `BatchRetryService`
re-runs `fetchWeatherAndTriage` (:248-250) — fresh weather. The retry's rating corresponds to
a prompt built from the NEW snapshot, so updating the ORIGINAL row would staple a rating onto
weather the prompt never saw and quietly corrupt ERA5 verification's forecast-vs-analysis
pairs. So: retry inserts its own pending row (same R4 seam — the retry already reconstructs a
task), carries the new id in its custom_id (ids need not match the precursor's: retry
selection parses ids only to identify slots), and the precursor row is stamped ABANDONED at
retry-submit time. A retry that gets triaged away leaves the precursor for the sweep (R7) and
adds a triage row — expected, record it in the test.

**R7 — An abandonment sweep, event-driven where easy, time-based as the safety net.** Stamp
ABANDONED: (a) when a batch reaches terminal FAILED/EXPIRED/CANCELLED, for its still-PENDING
rows (walk the batch's custom_ids from `api_call_log`, or the R6 stamp already covered
retried slots); (b) a periodic pass marking PENDING rows older than 48 h (batch 24 h expiry +
margin) — this is the backstop that catches crash windows, unreconstructable retries and any
path the event-driven stamps miss. Constant named, not inline.

**R8 — Scope: the `fc-` sky lane only, v1.** `bb-`/`wd-` (bluebell/woodland) results keep
their current behaviour — no pending rows. Rationale: every consumer this change resurrects
(veto tallies, ERA5 verification, calibration) reads SKY forecasts; the canopy lanes can
follow later with the same suffix treatment, at which point the OPEN_FELL pairing (one slot,
one `fc-` + one `bb-` task, `ForecastTaskCollector` :582-588) needs a which-result-wins rule —
out of scope here, recorded so it is not forgotten.

## Ripple census — verdict per reader (from the 2026-08-30 sweeps; re-verify cited lines)

| Reader | Under this design | Why |
|---|---|---|
| `GET /api/forecast` (map) | unchanged during pendency; **improved** after scoring (rich rows for batch ratings, real `/{id}` detail) | R1 exclusion preserves the sparse cached fallback |
| `GET /api/forecast/{id}` | unaffected | list never serves a pending id |
| `GET /api/forecast/history` (#679) | benign: more rows, three-state rows | admin surface; javadoc note per R1 |
| `GET /api/forecast/compare` | benign: null-rating points until scored; point meaning noted | admin surface; javadoc note per R1 |
| `EvaluationViewService` → briefing/plan | unchanged | R1 exclusion; `cachedWins` clause 2 (:517-524) was already built for bare base-forecast rows |
| `ForecastCalibrationService` | **fed at last** — rated batch rows join per horizon | its own `rating IS NOT NULL` filter makes pending invisible by construction |
| Cloud verification candidates | prompted slots verified again (the point); volume grows by prompted-slots/day — watch Open-Meteo archive quota on the first catch-up | no rating filter; by the 6-day archive lag every row is SCORED or ABANDONED, so no race |
| `CloudVerificationBucket` javadoc | **must be amended**: "null rating ⇒ triaged" becomes "triaged, pending or abandoned" | the one real null-rating inference found in the codebase (:36-44) |
| `OptimisationSkipEvaluator` | dormant (all sync call sites pass manual=true); if ever revived, SKIP_EXISTING/FORCE_STALE must ignore PENDING rows — leave a comment at :132-143 | latent only |
| Frontend (all of it) | **zero changes** | absence stays absence under R1; `markerUtils`' two em-dashes key on triageReason / all-null scores, neither reachable |
| `forecast_evaluation` growth | ~2–4× rows/day; V128 serves every access path | EXPLAIN the map query post-deploy as confirmation, not as a gate |

## Tests (read `docs/engineering/test-improvement-standards.md` first; house rules apply)

- **Custom_id round-trip**: build-with-suffix → parse yields `evalRowId`; old format parses
  with null `evalRowId`; the suffix never pushes past the 64-char cap for the largest
  plausible ids; a malformed suffix is rejected as malformed, not silently dropped.
- **R1 is pinned from the serve side**: fixture with a rated row, then a NEWER pending row for
  the same slot → the map query returns the rated row and the slot is not `covered` (the
  sparse fallback fires); same shape for `EvaluationViewService`'s dedup. Member + non-member:
  a SCORED row with a newer `forecastRunAt` DOES win.
- **R5 agreement**: one fixture through `ForecastResultHandler` asserting the row's rating ==
  the `cached_evaluation` write's rating, including the sky-not-forecast substitution case.
- **R6**: retry path inserts a new PENDING row, precursor reads ABANDONED, and the retry's
  result updates the NEW row (assert by id, not by slot).
- **R7 backstop**: a PENDING row older than the cutoff goes ABANDONED; one a minute younger
  does not (band edge, both sides).
- **Migration** is proven in CI only (no local Docker — standing rule); write the
  Testcontainers coverage and read the Backend job on the PR.

## Verification ladder (gate on exit codes, NEVER grepped output)

```bash
cd backend && ./mvnw compile -q >/tmp/c.log 2>&1; echo "exit: $?"
```

```bash
cd backend && ./mvnw test -Dtest="CustomIdFactory*,ForecastResultHandler*,BatchRetry*,ForecastController*,EvaluationView*" -q >/tmp/t.log 2>&1; echo "exit: $?"
```

```bash
cd backend && ./mvnw checkstyle:check >/tmp/cs.log 2>&1; echo "exit: $?"
```

```bash
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"
```

## Pre-registered post-deploy checks (state them in the PR body)

1. Within ~7 days of deploy (6-day archive lag + one verification cycle), a windowed
   verification pull `?from=<deploy>&to=<now>` shows `ratedCount > 0` in `vetoFired` — the
   demotion tally instrument is alive again. The demotion's success criterion then reads as
   originally registered: veto-population rating mass at 1–3★, **zero above 3★**.
2. During an in-flight batch, the map serves the previous run's ratings unchanged (trigger a
   run, observe pins — this is R1's live check).
3. `ForecastCalibrationService` pair counts rise once outcomes exist to join (no gate — just
   no longer starved by construction).
4. The far-term Haiku→Sonnet flip's evidence gate is unblocked once check 1 passes.

## Out of scope — do not touch

- Retiring or bypassing `cached_evaluation`/`forecast_score` — they remain the serve cache and
  component store; this change makes the cache *redundant in principle*, and its retirement is
  a separate consolidation phase with its own ripple review.
- `bb-`/`wd-` lanes and the OPEN_FELL pairing rule (R8).
- Prompt content, triage rules, `PromptBuilder`, prompt regression tests (user-owned).
- The verification report's bucket keys and both scalars; the `promptable()` predicate.
- Frontend — if the implementation finds itself editing `MapView`/`markerUtils`, R1 has been
  violated; stop and re-read it.
