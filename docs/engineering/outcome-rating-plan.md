# Map tab — rate how a past sunrise or sunset turned out ("How was it?") (2026-10-15)

**Source**: the design bundle vendored at `docs/design/outcome-rating/` — `README.md` (the spec),
`Outcome Prototype.html` (the prototype), `Outcome Flow v2 (static states, superseded layout).html`
(the copy table and decisions list), fifteen screenshots. `docs/design/outcome-rating/VENDORING.md`
records what was copied. Read the spec before any phase; **this document is the *port* plan**: what
the codebase already has, what is genuinely new, where the spec and the code disagree on purpose,
and how the work cuts into single-session phases for **Sonnet** sessions.

**The one-sentence version.** Pro and Admin readers record, with one tap inside the Map tab's
location callout, how a sunrise or sunset that has already happened actually looked (1–5★, or
"Didn't see it"), per user, upsert-only, against the last forecast run before the event; to reach a
past window the Map's window control steps backwards through today's elapsed windows and the two
served past days, then fetches one more stored day at a time back to T-4; nothing says whether the
forecast was right. The record is the calibration gate's input (CLAUDE.md "Calibration gate
(ADMIN)"), which has had almost nothing to score because the only way to record an outcome was an
API call.

**Cadence per phase** (CLAUDE.md "UI Work — Review Cadence"): build → tests → adversarial review of
the diff (prosecutor lenses + one refuter per charge, all read-only, run in the FOREGROUND so their
results return to the session that launched them) → fix survivors → measurement where §7 asks for
one → changelog.d entry → commit → push → PR → CI and Codex green → merge. ⚠️ **The owner authorised
push, PR and merge for every phase of this series on 2026-10-15** ("Push and Open a PR. Merge once
CI is green and Codex is happy"); CLAUDE.md's standing "never push" rule is lifted for exactly this
series and nothing else — never tag, never run `release.sh`. Backend gate before any push (JDK 23
on this Mac, see `no-jdk-25-on-this-mac` in memory; `$SCRATCH` is the session scratchpad, never
bare `/tmp`):
`cd backend && export JAVA_HOME=/Users/chrisgregory/Library/Java/JavaVirtualMachines/openjdk-23.0.2/Contents/Home MAVEN_OPTS=--enable-preview && ./mvnw clean verify --batch-mode --no-transfer-progress -Djava.version=23 -Dmaven.compiler.enablePreview=true -Dtest='!**/integration/**,!EvaluationModelTest' -DfailIfNoSpecifiedTests=false >"$SCRATCH/v.log" 2>&1; echo "exit: $?"`
— gate on the echoed exit code, never a grep. Frontend gate: `cd frontend && npm run lint && npm test
&& npm audit --audit-level=high && npm run build`. Paste the relevant section of THIS plan and the
spec into every review agent's prompt — an agent cannot open a path it was not given.

**Check open PRs first.** `gh pr list --state open` and grep the titles for *outcome*, *rating*,
*past*, *window control*, *callout*. Checked 2026-10-15 at plan time: three open PRs (#1132 a jsdom
bump, #843 and #815 docs), none touching any file this plan names.

---

## §0 Status

**Status: O0 — plan written 2026-10-15 against `origin/main` at `015d93fb` (#1184), reviewed by
four prosecutor lenses (§5a) and amended. No code yet.**

Phase log (O0 creates the first row; every phase appends its own in the same commit as its code —
the commit column names the PR once it lands). ⚠️ Two phases that run in parallel both append here:
**whichever merges second rebases its row onto the other's** (a one-line conflict, resolved by
keeping both rows in phase order).

| phase | branch | commit | date | notes |
|---|---|---|---|---|
| O0 | `claude/forecast-accuracy-ratings-5fbd0d` | (this PR) | 2026-10-15 | Plan + bundle vendored at `docs/design/outcome-rating/`. No code. Four review lenses at plan time (§5a): backend, frontend, spec fidelity, conventions and sizing — 3 P1s and ~30 P2/P3s, all accepted and folded in. Codex rounds 1–5: five P1s on provenance (the served result, not the newest cycle; only one that had arrived by the event; the fallback must not admit a late batch's own row — hence `scored_at`, V178; it must honour a stability retraction as of the event; and the calibration join must use the resolved row id, not the natural key), all accepted. |

---

## §1 What the codebase already has — corrections to the spec

Numbered so a phase log can cite them. Every file:line was read against `015d93fb`; **re-verify
before editing**, code moves. Paths are `frontend/src/` and
`backend/src/main/java/com/gregochr/goldenhour/` unless given in full; frontend tests live under
`frontend/src/test/`, never beside their modules.

### 1. The forecast list already serves two past days, and the Map tab hides every one of them

The spec's open item "confirm how many past days `/forecast` returns today, and set it to 2" is
already true: `GET /api/forecast` serves `today − ForecastHorizon.SERVE_PAST_DAYS (2)` through
`today + 5` (`controller/ForecastController.java:144–219`, `util/ForecastHorizon.java:149`), rich
rows from `forecast_evaluation` plus sparse `cached_evaluation` rows, each retracted by the slot's
latest stability skip. `GET /api/briefing/evaluate/scores` serves the same days
(`BriefingEvaluationController.java:66–80`). Those rows reach the client: `App.allDates`
(`App.jsx:332–335`, `conversions.colourForecastDates`) holds today−2 and today−1, and every
location's `forecastsByDate` carries their rows.

**But the Map tab refuses to show them, by three rules that must each be loosened (O3a):**

- `utils/mapEvents.js` `hasSolarRow` (`:426–428`) — `Boolean(servedWindow) || (inForecastDomain &&
  date >= todayStr)`. A served window comes from the briefing's `heatStripCards`, which withdraw a
  window once `PlanWindowProjector.hasPassed` (its earliest event time + 30 min); so **today's
  elapsed sunrise stays in the roster as a D-13 filler row** (`served: false`, no verdict,
  labelled "Today", the dropdown shows `—`), while yesterday and today−2 are excluded outright.
  `solarRowPredicate` (`:492–502`) applies the same rule, and **it gates every chip, pin and
  callout rating**: `MapView.solarWindowOnScreen` (`components/MapView.jsx:3231–3236`) →
  `getRatingForLocation` (`:3284–3313`), which reads the briefing score index first, then the
  forecast row (`briefingScore?.rating ?? forecast?.rating`).
- `isForwardableRow` (`mapEvents.js:686–691`) forwards a solar row to App only when
  `row.date >= todayStr`; a non-forwardable row is kept local by `MapView.selectEvRow`
  (`:4941–4994`, which already calls `onSelectDate?.(row.date, { isNight })` at `:4986`) through
  `setLocalNightDate`, which only ASTRO/AURORA read. **A past solar row has nowhere to go.**
- `utils/mapDates.js` `resolveMapDate` (`:366–381`) never returns a past date — `notPast = d >=
  todayStr` — except for an explicit NIGHT selection (`selectedIsNight`, keyed on provenance, never
  value: Codex, #803). `App.selectDate(date, {isNight})` (`App.jsx:313–316`) is the one writer and
  already destructures an options object; its other two callers (the Ask handoff, the aurora jump)
  cannot reach a past solar row, so a default `isPast: false` there is safe.

The night precedent is the shape to copy, not the local-override half of it: D-14 (`map-past-nights
-d14` in memory, #841) kept a past NIGHT on screen by forwarding it to App with a provenance flag
the clamp honours. A past SOLAR window gets the same: a `selectedIsPast` flag beside
`selectedIsNight` (§2 #2).

### 2. "Today" and "Tomorrow" exist; "Yesterday" does not

`utils/windowFirstCards.js` `dayLabelFor(dateStr, todayStr, tomorrowStr)` (`:61–66`) returns
`Today`, `Tomorrow` or a long weekday name (`en-GB`, UTC). The spec's past labels are `Today`,
`Yesterday`, then `{Dow} {d}` (`Fri 16`); future days keep the existing labels. O3a adds a
`pastDayLabel(dateStr, todayStr)` beside it (§2 #2) rather than widening `dayLabelFor`, whose two
Plan-tab callers never see a past date.

### 3. The callout is `.wf-callout`, 286px / 266px on a phone, with no notion of a passed window

`components/map/MapCallout.jsx`: `CALLOUT_WIDTH = 286`, `CALLOUT_WIDTH_MOBILE = 266` (`:32`, `:40`
— **the spec's 296 is the map-landing bundle's figure and is not changed here**). There is no
`#cal`, no `.cv2`: the verdict strip is `.wf-callout-verdict` (`:753–809`), the "why" prose is
`button.wf-callout-reason` with its `Four days here ›` caption (`:881–938`), the facts row
`.wf-callout-facts` (Drive / Leave by / Dark sky, `:939–948`, from `utils/mapCallout.calloutFacts`
`:179–209`, which takes `{driveMinutes, distanceMiles, eventTimeIso, bortleClass}` and no event),
then `TideFitBlock`, `EclipseSpotLine`, topics, the "Every event here" strip, and — **outside the
scrolling body** — the actions row `.wf-callout-actions` (`:1044–1060`, "Zoom to it" and "Open in
Plan"). The rating block goes **inside `.wf-callout-body`, immediately after the facts row** (which
is hidden on a past window, §2 #6), so it shares the card's one scroller and sits "between the
forecast why text and the action buttons" as the spec asks (§4 #12: it is not the LAST thing before
the actions, as it was in the prototype).

It knows the window only through `event` (= `activeMapEvent`), has no `role` prop (MapView reads
`useAuth().role`, `:1455`), reads a clock once (`now ?? appNow()`, `:675`, for a hide's comfort
day) and — **load-bearing** — repaints its placement from a dependency list (`:462–465`) **of its
own props and state**: anything whose change alters the card's height must be named there, and a
child's private state cannot be. The rating block switches between a 44px star grid and a 40px "You
rated" row, so its `changing` / `adminOpen` state is **owned by `MapCallout`** and passed down
(§2 #4); `MapCallout.test.jsx:1126–1145` already pins the repaint for `event?.id` with
`vi.spyOn(currentMap, 'latLngToContainerPoint')` + `rerender`, the shape to copy.

### 4. Pins and chips have no corner badge; the Ask pick is the nearest precedent and it is not one

`components/map/PinsLayer.jsx` draws `button.wf-pin` per location (`:477–531`; `data-ask="pick"`
pins at `:422–464` put the rank INSIDE the dot). `components/map/MapLabels.jsx` draws
`button.wf-maplab-chip` (`:783–827`; the pick chip at `:727–766` prefixes a 17px
`span.wf-maplab-pick-rk` circle, `data-multi` for `1·2`). Both are `position: absolute` with no
`overflow`, so either can host an absolutely positioned child. The only existing top-right tag in
the stylesheet is the heat-strip card's BEST BET `.wf-hc-lg` (`index.css:2613–2625`). The data
route is `MapView.labelSpots` (`:3974–4087`, `spotOf(loc)` at `:3975–4034`): a new per-spot field
rides it like `askPick` does, with its PropTypes in both layers. A badge is `aria-hidden` and its
meaning joins the button's `aria-label` the way `tideAccessibleClause` does. ⚠️ **The chip placer
measures the chip's box** (`MapLabels.jsx:493–503`, `measuredChip`): a badge overhanging that box
by design (top −7 / right −9) is invisible to it and would collide at any density, so the overhang
is folded into the measured rect whenever the badge is drawn (§2 #4).

### 5. The old outcome stack is a different feature and stays as it is

`actual_outcome` (V2/V17/V47; `entity/ActualOutcomeEntity.java`) has **no user**, no run reference,
no unique key, free-text notes and two 0–100 scores; `POST /api/outcome` (`OutcomeController.java:84`)
is a plain insert open to every role (`UserRole`'s javadoc even calls outcome recording a LITE
right — O5b corrects the words). Its only live reader is the calibration gate,
`ForecastEvaluationRepository.findCalibrationPairs` (`:282–294`, a theta join — the house JPQL
shape), consumed by `ForecastCalibrationService.report` (`:68–97`). ⚠️ **`latestPerSlotHorizon`
(`:107–131`) keeps one pair per `CalibrationPair.slotHorizonKey()` = `locationName|targetDate|
targetType|daysAhead`** — it was written to collapse RUNS of one forecast, and has no outcome
identity in its key, so two outcomes of one slot (two users, or one row in each table) would
collapse to whichever the unordered query returned first. §2 #6 adds the identity. The frontend's
`OutcomeModal.jsx` is mounted nowhere; `useForecasts` still fetches `GET /api/outcome/all` for the
last seven days and attaches `loc.outcomes`, which nothing reads (`src/test/useForecasts.test.js`
references `outcomes` 23 times, so removing it is a real test edit, not a line). The spec says
leave the modal alone, and this plan does: **the new record is a new table** (§2 #1), the old
endpoints are untouched, and the calibration gate learns to read both (§2 #6). The dead
`fetchAllOutcomes` call is removed in O4b only because the new hook replaces it on the same screen
(§4 #7).

### 6. The server already knows every event instant and every run

`service/SolarEventFreshness.eventTime(location, date, type)` (`:84–91`) gives a location's sunrise
or sunset as naive UTC; `PlanWindowProjector.hasPassed(eventTime, now)` (`:559–561`,
`eventTime.plusMinutes(30).isBefore(now)` — strict, null reads as not passed) is the one "has this
window passed" rule, read by the briefing, the digest and `RewindEventService`.
**The forecast a slot is SERVED with is one resolution, and it already exists**:
`EvaluationViewService.getLiveScoresForEnrichment(regionName, date, targetType)` (`:998`) returns,
per location name, the `BriefingEvaluationResult` the Plan tab, the map's score index and the
best-bet advisor all read — the cached entry when it is at least as fresh as the slot's latest
`forecast_evaluation` row, else that row (`cachedIsAtLeastAsFresh`, `:1667–1673`), with the
stability-skip retraction applied and retracted markers removed. A result carries `submittedAt`
(the cycle's `PipelineRunEntity.triggerTime` for a batch in a cycle, the batch's own submit instant
for an ad-hoc batch, the call start for a sync/admin run; null on a legacy row) and `evaluatedAt`
(arrival). ⚠️ A later cycle that FAILED, skipped the slot (Gate 4, triage) or submitted no result
writes nothing for it, so "the newest `pipeline_run` before the event" names a cycle the reader never
saw a forecast from — the stale-row case CLAUDE.md's `forecast_score` notes and Codex's review of
#1185 both name. The rated run is therefore the SERVED result's own instant (§2 #1), never a
roster-wide cycle lookup. `pipeline_run.trigger_time` (V102, `PipelineRunEntity.triggerTime`, an
`Instant`, never pruned) is matched by equality to a served `submittedAt` to recover the cycle id;
`forecast_evaluation.forecast_run_at` (naive UTC;
`findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc` `:195–206` skips PENDING,
takes a `Pageable`) is the fallback for a slot with no live result at all, and the only trace of a
hand-started run (the map's Run Forecast, the Operations buttons write no `pipeline_run`).

### 7. The user is the principal's username, and a per-user read must stay out of the ETag list

`JwtAuthenticationFilter` makes the principal the username (`:88–95`); services resolve the row
with `AppUserRepository.findByUsername(auth.getName())` (`UserSettingsService.getUserId`, `:617–625`,
404 when missing). `HttpCachingConfig`'s revalidatable set is exact-path (`:72–106`); `/api/outcome`
and `/api/outcome/all` are already pinned as never-filtered in
`HttpCachingConfigTest.personalDataPathsAreNeverFiltered` (`:90–120`). New `/api/outcome/ratings*`
paths are unfiltered by construction and go on that test's list (§2 #1). `GET /api/forecast?date=`
is ETag'd automatically and safely (the body is not personal). `GlobalExceptionHandler` maps
`IllegalArgumentException` → 400 `{"error": …}` (`:84–85`) and `NoSuchElementException` → 404
(`:47–49`); there is no `DataIntegrityViolationException` handler.

### 8. The find-or-insert shape for an upsert without `ON CONFLICT` already exists — and it only works OUTSIDE a transaction

`service/ask/AskUsageStore.ensureRow` (`:136–149`) + `AskUsageRepository.insertRow` (native INSERT,
`:68–74`): exists-check, native insert, catch `DataIntegrityViolationException`, re-check, then a
conditional JPQL `UPDATE`. ⚠️ **`AskUsageStore` carries no `@Transactional`** (`:26–149`); only the
repository methods do. That is what makes the catch work on Postgres — inside one transaction a
unique violation aborts it and the re-check fails ("current transaction is aborted") — and the
breakage is invisible to H2 and to the `@DataJpaTest` race (`AskUsageStoreTest.java:41–42`,
`NOT_SUPPORTED`). The outcome upsert copies the whole shape, store class included (§2 #1), and the
CI-only migration test races it on Postgres as `AskUsageMigrationTest.java:167–172` does.

### 9. Rewind reaches GETs under `/api/outcome` and the Map's "today"

`RewindFilter` honours `X-Rewind-To` for an ADMIN's GET (`:95`) outside `/api/admin/` (`:88`), so a
rewound read of the new rating endpoints sees the rewound clock (they take explicit dates and read
no clock, so nothing changes); a PUT is never rewound, so "has the event passed" on a write is
always the real clock. On the client `swrCache` is disabled while rewound and `appNow()` is the
rewound instant, so `todayStr` and every past-window label move with it. Decision §5 #6: the rating
block, the badges, the ratings read and the older-day fetch are **hidden under a rewind** — a rewind
is a screenshot of the forecast as it stood, and a rating recorded later would contradict the
picture; `App.jsx:117–147` (`AskWhenLive` / `RewindGate`) is the precedent.

### 10. The one-day sparse read already works, with a bounded over-read

`EvaluationViewService.loadCachedEvaluations(start, end, …)` (`:1734–1790`) iterates
`for (date = start; !date.isAfter(end); …)` — one day is one iteration — and its DB half calls
`findByEvaluationDateGreaterThanEqual(start)` then drops rows after `end` (`:1762–1768`), so
`cachedOnlyViewsForDateRange(d, d, …)` returns the day's sparse rows. The cost: a `?date=T-4`
request loads every `cached_evaluation` row from T-4 to the forward horizon and keeps one day's —
pointless but bounded (regions × ~6 days × 2), noted in O2's javadoc, not fixed (changing
`loadCachedEvaluations` is out of scope).

---

## §2 What is genuinely new

### 1. The record, its upsert and its three endpoints (backend, O1)

**Table `outcome_rating` (V177; next after V176, re-read the tree before writing):**

```sql
CREATE TABLE outcome_rating (
    id                  BIGSERIAL    PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    location_id         BIGINT       NOT NULL REFERENCES locations (id),
    event_date          DATE         NOT NULL,
    event_type          VARCHAR(10)  NOT NULL,
    rating              INTEGER,
    event_time          TIMESTAMPTZ  NOT NULL,
    pipeline_run_id     BIGINT,
    forecast_run_at     TIMESTAMPTZ,
    forecast_evaluation_id BIGINT REFERENCES forecast_evaluation (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_outcome_rating_slot UNIQUE (user_id, location_id, event_date, event_type),
    CONSTRAINT ck_outcome_rating_range CHECK (rating IS NULL OR (rating BETWEEN 1 AND 5)),
    CONSTRAINT ck_outcome_rating_event_type CHECK (event_type IN ('SUNRISE', 'SUNSET'))
);
CREATE INDEX idx_outcome_rating_user_date ON outcome_rating (user_id, event_date);
CREATE INDEX idx_outcome_rating_slot ON outcome_rating (location_id, event_date, event_type);
```

- `rating` **null means "not seen"** — the spec's data model, verbatim. A row's existence is the
  answer; there is no separate flag. `user_id` cascades (a deleted user takes their ratings, the
  `ask_usage` precedent — V166's header explains why `UserService.deleteUser` needs it);
  `location_id` does not (locations are never hard-deleted; `actual_outcome` is the precedent).
  `BIGSERIAL` ↔ `GenerationType.IDENTITY` and `TIMESTAMPTZ` ↔ `Instant` both have
  `ddl-auto: validate` precedents (`AskUsageEntity`, `AskLogEntity`).
- `event_time` is the event instant the server computed for THIS location (not the roster's
  earliest), stored so the admin footer's "Xh before sunrise" needs no recomputation and survives a
  later change to the solar library. `pipeline_run_id` / `forecast_run_at` name the forecast being
  rated (below); nullable because a slot can have been shown with no run behind it at all.
  `forecast_evaluation_id` is the one `forecast_evaluation` row that was available by the event
  and not retracted at it (step 2's candidate, resolved on EVERY write whether or not step 1
  answered the run instant) — the row the calibration gate pairs this rating with (§2 #8), so no
  join ever has to redo the availability arithmetic; nullable when no such row exists.
- Entity `OutcomeRatingEntity` (`@Table(uniqueConstraints = …)` so the H2 `create-drop` schema
  carries the key; `userId` and `locationId` as plain `Long` columns like `AskUsageEntity`;
  `eventType` as `TargetType` `@Enumerated(STRING)` length 10 — SUNRISE/SUNSET only by the CHECK,
  never HOURLY; a fresh table needs no `LocalH2EnumWidener` line). ⚠️ **Write discipline, the
  `app_user` / `locations` pattern**: every column is written by the native insert and the JPQL
  `UPDATE` below and nothing else — `rating`, `eventTime`, `pipelineRunId`, `forecastRunAt`, `forecastEvaluationId` and
  `updatedAt` are `updatable = false` with no setter, so a later `save()` of a loaded row cannot
  become a second writer. `OutcomeRatingRepository`:
  `existsByUserIdAndLocationIdAndEventDateAndEventType`, a native `insertRow(...)`, a JPQL
  conditional `updateRow(...)` returning the count, `findByUserIdAndEventDateBetween`, and
  `findAllForSlot(locationId, date, type)` as a constructor projection over the theta join `FROM
  OutcomeRatingEntity r, AppUserEntity u WHERE u.id = r.userId AND …` (the `findCalibrationPairs`
  shape) ordered by `updatedAt DESC`.
- **Which forecast is rated** (`OutcomeRatingService.resolveRatedRun(location, date, type,
  eventInstant)` → `RatedRun(pipelineRunId, runAt, forecastEvaluationId)`): **the forecast actually served for this slot**
  (§1 #6; the spec: "the last forecast run before the event time, even if it was stale because of
  batch failures — staleness is useful data", so the age of the forecast the reader SAW is what is
  recoverable). Step 1: for a regioned location,
  `evaluationViewService.getLiveScoresForEnrichment(regionName, date, type).get(location.getName())`
  — the one serve-time resolution the map and the Plan tab read. **The result must have been
  AVAILABLE by the event**: its `evaluatedAt` (arrival / write time) must be `<= eventInstant`
  (a batch submitted before the event but landing after it — the Batch API allows 24 h — was not
  what the reader saw at it; a legacy result with no `evaluatedAt` is skipped). A result that
  passes gives `runAt` = its `submittedAt` (the forecast's own age), else its `evaluatedAt`, and
  `pipelineRunId` = the `pipeline_run` whose `trigger_time` EQUALS that `submittedAt` (new finder
  `PipelineRunRepository.findByTriggerTime(Instant)` → `Optional`; a cycle's batch results carry
  exactly that instant), else null (an ad-hoc batch, a sync run, a legacy row). Step 2, when step 1
  yields nothing — an unregioned location (the batch never scores one), a slot with no live
  result, or a served result that arrived AFTER the event: the slot's latest `forecast_evaluation`
  row that **has something to say** (`rating IS NOT NULL OR triage_reason IS NOT NULL`, the
  `hasSomethingToSay` rule — a triage row is a decision the reader saw) and **was available by the
  event**: `COALESCE(scored_at, forecast_run_at) <= eventInstant`, **and not retracted as of the
  event**: the slot's latest `SKIPPED_STABILITY` disposition with `created_at <= eventInstant`
  (a new bounded sibling of `ForecastRunDispositionRepository.findLatestStabilitySkipTimestamps`,
  by location name, date and type — the 30-day retention covers T-4) retracts any row whose
  availability instant precedes it, exactly as `EvaluationViewService.isRetractedByStabilitySkip`
  (`:611–621`) does at serve time, because a row the pipeline had already declined to re-look at
  was absent from the map at the event; a skip recorded AFTER the event is ignored (it retracts
  what the reader saw later, not what they saw then — which is also why step 1's live accessor,
  whose retraction is as-of-now, can return nothing for a slot step 2 then correctly answers).
  With `pipelineRunId = null`. **The step-2 candidate is resolved on every write even when step 1
  answered**, and its id is stored as `forecastEvaluationId` (null when there is none). Neither →
  all null — the slot was shown with no forecast behind
  it; the rating is still recorded (the block mounts on `event.past` when the place has no row of
  its own) and the calibration gate, which pairs on a scored `forecast_evaluation` row, simply
  never pairs it. ⚠️ **`forecast_evaluation.scored_at` is new (V178, same phase)** — a
  nullable `TIMESTAMPTZ` stamped from the clock wherever a row GETS its rating or triage reason:
  `ForecastService.buildEntity` for a row inserted with one (the sync engine, a triage row), and
  `ForecastResultHandler.scoreEvaluationRow` for a batch's PENDING row scored in place; a row
  still PENDING or ABANDONED keeps null (and has nothing to say, so the finder never picks it).
  Without it the fallback cannot tell a late batch's OWN row from the forecast that stood before
  it: a batch row's `forecast_run_at` is stamped when the row is built (`ForecastService.java:817`),
  BEFORE the batch's `submittedAt` is known (`BatchSubmissionService.java:143–152`), so a
  "submitted earlier" bound would admit the very row step 1 just set aside (Codex, #1185 round 3).
  Legacy rows with a null `scored_at` fall back to `forecast_run_at`, the stated residual, and age
  out of the T-4 window within days of the deploy.
  **Never a roster-wide "latest cycle before the event"** (§4 #2). Resolved on every write
  (idempotent in practice: nothing re-scores a slot whose date has passed, and a late batch landing
  after the event is set aside by the availability gate, so the answer is fixed once the event is) and on the admin read, so a slot with no ratings still shows its run.
- **The upsert** is split in two classes, the `AskUsageStore` shape: `OutcomeRatingStore`
  (`OutcomeRatingRepository` its only dependency, **no `@Transactional` on the class or the
  method** — a javadoc cites §1 #8 and `AskUsageStore` for why) does `upsert(userId, locationId,
  date, type, rating, eventTime, run, now)`: exists → native insert → on violation re-check → one
  JPQL `UPDATE … SET rating, eventTime, pipelineRunId, forecastRunAt, forecastEvaluationId,
  updatedAt WHERE userId, locationId, eventDate, eventType`. `OutcomeRatingService.rate(username, locationId, date, type,
  rating)` orchestrates: resolve the user (404 "User not found" if gone); the location must exist
  (404; a disabled location may still be rated — its past rows are still served); `rating` null or
  1..5, else 400 `"rating must be between 1 and 5"`; `eventType` SUNRISE/SUNSET else 400 (the
  request DTO's validation, before any lookup); **the event must have passed**:
  `PlanWindowProjector.hasPassed(solarEventFreshness.eventTime(location, date, type), now)` with
  `now` from the injected `Clock` (a PUT is never rewound), else 400 `"That sunrise has not
  happened yet"` / `"… sunset …"`; no lower bound on `date` (the superseded board's "any time means
  no cut-off"; the Map reaches T-4 and nothing else creates rows). `updatedAt` from the clock.
  Returns `OutcomeRatingDto(locationId, locationName, eventDate, eventType, rating, updatedAt,
  forecastRunAt, pipelineRunId)`; the range read joins names with ONE `findAllById` over the
  distinct location ids per request, never per row.
- **Endpoints** (`OutcomeRatingController`, `@RequestMapping("/api/outcome/ratings")`; the old
  `OutcomeController` is untouched):
  - `GET /api/outcome/ratings?from=&to=` → the caller's own rows in the range, no role gate (a LITE
    account has no rows, so it answers `[]` naturally; a downgraded account would see its old rows,
    harmlessly). 400 for `from > to` or a span over 31 days (`MAX_RANGE_DAYS`, the shape
    `ForecastController.getHistory` uses for its cap).
  - `PUT /api/outcome/ratings` `{locationId, eventDate, eventType, rating}` (`rating` absent or
    null = not seen) → 200 with the DTO. `@PreAuthorize("hasAnyRole('ADMIN', 'PRO_USER')")` — a
    LITE PUT is 403 ("writes are rejected server-side"). PUT, not POST: the call is idempotent and
    replaces.
  - `GET /api/outcome/ratings/all?locationId=&date=&eventType=` → ADMIN only →
    `{eventTime, forecastRunAt, pipelineRunId, ratings: [{username, rating, updatedAt, mine}]}`,
    every user's row for the slot, **newest first**, `mine` true on the caller's own row (the
    client hoists it to the top, the prototype's order — §4 #13). Under `/api/outcome/`, so a
    rewound admin GET sees the rewound clock — harmless, it reads none.
- `HttpCachingConfigTest.personalDataPathsAreNeverFiltered` gains `/api/outcome/ratings` and
  `/api/outcome/ratings/all`. `AbstractControllerTest` gains a `@MockitoBean OutcomeRatingService`.
  `OutcomeRatingMigrationTest` (CI-only, extends `IntegrationTestBase`, the `AskUsageMigrationTest`
  shape: columns, the unique key, both CHECKs, the user cascade, the location FK, and a 12-thread
  upsert race through the REAL `OutcomeRatingStore` — the Postgres proof §1 #8 needs).

### 2. Past rows in the window control (frontend, O3a; the fetch and the EARLIER group O3b)

`buildMapEvents` (`utils/mapEvents.js:557`) takes a new input, `pastSolarWindows` — built in
`WindowFirstMapPane` from `locations[].forecastsByDate` for every (date, targetType) with at least
one row and `date <= todayStr`: `{date, targetType, earliestEventTime}` (the earliest
`solarEventTime` among the rows, UTC). A solar row is **past** (`row.past === true`) when it has
NO served window AND it is in the forecast domain AND `date <= todayStr` — a pure filter over the
briefing's own withdrawal, so the Map and the Plan tab retire a window at the same serve (CLAUDE.md
Backend-heavy sixth class (e): "whether a window has passed still reads the served instant"),
exactly when today's elapsed sunrise becomes a filler today (§4 #4 records the per-place
consequence). A past row is `{id: 'solar:DATE:TYPE', kind: 'solar', eventType, date, past: true,
served: false, scored: false, inForecastDomain: true, label, dayLabel, time, bestRating: null,
badges: [], pickKind: null}`; **its `time` is set in `buildMapEvents` from `earliestEventTime`
through `formatTimeUk`** (a base that exists even with no briefing and an empty scope pool, which
`withScopedSolarTimes` (`utils/mapScopedTime.js:46`) returns rows unchanged for at `:49`/`:63`);
that function then narrows a past row's time to the scope pool's earliest as it does for a served
row — it stops skipping `served === false` rows when `row.past` (a past window's time is a fact of
the rows, not something "lent"). `hasSolarRow` / `solarRowPredicate` admit a date with a past
window; the filler branch keeps its `date > todayStr` cases. Ordering is unchanged (by date,
sunrise before sunset).

`pastDayLabel(dateStr, todayStr)` (new, `utils/windowFirstCards.js` beside `dayLabelFor`):
`Today`, `Yesterday`, else `{Dow} {d}` (`Fri 16`, `en-GB` short weekday, UTC day-of-month, no
leading zero).

**Selection plumbing** (O3a): `isForwardableRow` forwards a `row.past` solar row;
`MapView.selectEvRow` calls `onSelectDate(row.date, {isPast: true})` for one (and `{isNight}` as
today); `App.selectDate` gains `isPast` beside `isNight`, stored as `selectedDateIsPast`;
`resolveMapDate` gains `selectedIsPast`: usable when `dates.includes(d) && d <= todayStr` (today's
own elapsed window is a past selection on today's date). The night exemption is untouched. Every
other route to `selectDate` keeps the default `false`, so a stale past pick cannot hold the map
after a reload (the clamp falls through to the today-forward pick, as for a stale solar date).

**The pill** (O3a, `components/map/WindowControl.jsx:212–282`): when `active.past`, a
`span.wf-win-past` reading `Past` (uppercased by CSS, the prototype's `.wpast`) replaces
`<VerdictCell>` (`:272`); tag style per spec §2 (mono 9.5px, uppercase, `.08em`, ink-3, 1px
border-light, radius 4, padding 1px 5px). The pill's accessible name says "past" in words. The
phone override pill reads the same. `buildEvVerdicts` already skips `served === false` rows
(`mapVerdict.js:287`), so no verdict, tint or tick is drawn.

**The callout** (O3a): `calloutFacts` takes a `past` flag and hides Drive and Leave by when set
(the spec: "Past windows hide Drive / Leave by"; Dark sky stays). The verdict strip keeps the
forecast star — "the forecast star stays where it already is". `onOpenWindowPanel` stays withheld
for a past row (it is `served === false`), as it is for a filler today; **"Open in Plan" is
withheld too** (§4 #14: the location sheet it opens is built from the briefing's windows, which
never hold a past one; "Zoom to it" stays). `mapPeek.otherWindow` (`utils/mapPeek.js:49–64`) skips
`row.past` rows unconditionally — in the forward scan AND in the all-stand-down fallback — so the
collapsed "Other windows" summary never names a window that has gone. `landingCardModel` already
skips `!row.served` rows.

**The dropdown** (O3b, `WindowControl.jsx:296–420`): an **EARLIER** group above the day groups,
rendered when any row is past or more history can be fetched: header `EARLIER` left, `rate how it
went` right (not uppercase, weight 400); the **Load row** first while `canLoadOlder`
(`button.wf-win-row.wf-win-load`: a leading `↑` chip in the kind-chip slot, `--color-tide` on a 1px
`rgba(111,168,176,.35)` border — the prototype's `.wk.lo`; `Load {Dow} {d}` in `--color-tide`;
sub-line `or press ‹ past the oldest window · fetches {Dow} {d}`; the row's accessible name says
what it fetches; press → closes the dropdown, moves focus to the pill **in the handler, before the
state change** (`useRowFocusRescue` records only `[data-ev-id]` rows and clears on pointerdown,
`hooks/useRowFocusRescue.js:49–56`, so it will NOT rescue this row), then `onLoadOlderDay()`); then
each past day's sub-header (`pastDayLabel`, mono 9.5px/600 ink-2) followed by its rows (sunrise
first) showing the kind chip, the **time bold in ink-2** (the prototype's `.wr.past .wm b`), and on
the right `Not rated` (ink-3) or `{n} rated` (star gold) — the count is O4b's; O3b renders `Not
rated` for every past row and leaves a `ratedCount` prop null. Past rows wear `data-past`. The
existing future groups follow unchanged — ⚠️ **today may appear in both**: at Thu 08:15 the elapsed
sunrise sits under a past sub-header `Today` and the sunset under the future group `THU 15 · today`
(the prototype's `render()` resets `day` at the past→future boundary); do not dedupe.

**Stepping** (O3b): `atStart` becomes `activeIndex <= 0 && !canLoadOlder`; `step(-1)` at index 0
with `canLoadOlder` calls `onLoadOlderDay()` instead (and so does `ArrowLeft`, `:169–175`); the
`‹` stepper's accessible name at index 0 with `canLoadOlder` reads `Load {Dow} {d}`, not "Previous
event". While `olderDayLoading` the pill reads `Loading {Dow} {d}…` (`wf-win-loading`,
`aria-busy`) and both steppers plus the Load row are disabled (§4 #10); when the day lands,
selection moves to that day's **sunset** row if it exists, else its sunrise — from `‹`, `←` AND the
Load row alike (§4 #11: the README is the spec here; the prototype's Load row keeps the current
window); a day that loads with no rows moves nothing, says nothing, still counts toward T-4, and
the Load row offers the next older day (§4 #15). At the oldest reachable day (`T-4` loaded, or
loaded-empty) `‹` is disabled (the existing `:disabled` rule, `index.css:4843`, opacity .35 — the
spec's .3 is not adopted, §4 #16).

**The phone peek sheet** (O3b, `components/map/MapPeekSheet.jsx:325–385`, `MapPeekWindowsSection`):
the same EARLIER header, Load row and past sub-headers above the flat list; a past row shows
`PAST` where a verdict would be.

**Older-day fetch** (O3b, `hooks/useForecasts.js`): `loadOlderDay(date)` calls the new
`fetchForecastsForDate(date)` (`api/forecastApi.js`, `GET /api/forecast?date=`), keeps the answer
in hook state as `olderDays: Map<date, rows>` (an empty answer is a loaded-empty entry), and
**re-merges every entry inside every `load()` after `buildLocations`** — ⚠️ `load()` rebuilds
`locations` from the list payload on every refresh (`:144`; `refresh()` runs after a forecast run
and a drive-time change, `App.jsx:691`), and without the re-merge a loaded T-3 would vanish,
`allDates` would lose the date, `resolveMapDate` would refuse the selection and the map would jump
forward while `canLoadOlder` still read false. Merge rule: an older day's rows never replace an
existing (date, type) entry the list payload carries. Exposes `{olderDayLoading: date | null,
loadedPastDays}`. **Never written to `swrCache`** (the cached payload is the list endpoint's,
revalidated as a whole; an older day is re-fetched on the next session, which is the cheap and
correct outcome). A failed fetch leaves the roster unchanged, clears `olderDayLoading` and reports
through the hook's `error` (user-initiated). `canLoadOlder` and the next older date are derived in
the pane from `allDates ∪ loadedPastDays` against `ukDateStrOffset(-MAX_PAST_DAYS)`: the next older
date is the day before the oldest known date, and nothing older than T-4 is ever asked for. The
pane passes `onLoadOlderDay`, `olderDayLoading` and `canLoadOlder` to `MapView`, which hands them to
`WindowControl` and `MapPeekSheet`.

**Ratings on a past window** (O3a): with `solarRowPredicate` admitting past windows,
`getRatingForLocation` answers for them with no further change (briefing scores for today−2
onward, the forecast row's rating — rich or sparse — for T-3/T-4); the chips, pins and the callout
star all follow.

### 3. The heat field on a past window (frontend, O3c)

The field is keyed on the briefing's rendered events three times over, and all three must learn
the past windows **in the pane, never in the briefing context**: `WindowFirstBriefingContext.jsx:697`
calls `buildHeatSpots(locations, scoreRows, upcomingEvents)` with the SAME `upcomingEvents` that
builds the Plan's `windowCards` (`:622`), `paneItems` (`:667`) and `regionSeries` (`:724`) —
extending it there would put past windows into the Plan matrix, which spec §1 forbids. So
`WindowFirstMapPane`: (a) builds a past-only event list from `pastSolarWindows` and merged score
rows — `scoreRows` plus `{locationId, locationName, date, targetType, rating}` rows drawn from
`forecastsByDate` for every past slot the scores list lacks (T-3/T-4, and any past slot
`/evaluate/scores` does not carry) — and runs `buildHeatSpots` + `buildHeatPointSets` over them;
(b) extends `heat.windows` (built at `:490` from `heatStripCards`) with one entry per past window
`{key, date, targetType, bestRating, confidence: null}` and `heat.pointsByKey` with the past
windows' point sets — because `MapView.jsx:2924–2928` finds `heatWindow` in `heat.windows` and
`:3056` reads `heat.pointsByKey.get(heatWindow.key)`, so a window absent from `heat.windows` paints
`EMPTY_POINTS` and `fillerHasRatings` (`:3585–3589`) treats it as a filler; (c) gives the past
window's `bestRating` as a **client max over the merged rows** — `windowUnscored` (`:3114–3120`)
reads `heatWindow?.bestRating` to decide whether the legend key is withheld, and a rated past
window must show its key. ⚠️ That max is a NEW named member of CLAUDE.md's licensed Map-tab class
(the `mapEvents.bestOfNight` shape: a max across a roster that no served field states, because the
briefing serves no past window) — `mapEvents.pastWindowBest`, recorded in O6 beside `bestOfNight`,
not folded into the filter/map class. The kernel's haze reads the inferred confidence cap
(`medium`, `MAX_INFERRED_TIER`) because a past window carries no served confidence — accepted
(§4 #5). The field, the legend and the counts footer then behave on a past window exactly as on a
served one.

### 4. `GET /api/forecast?date=YYYY-MM-DD` — one stored day (backend, O2)

The existing `getForecasts` body becomes a private `serveRange(from, to, auth)`; the list endpoint
calls it with the window it has today, and a new `@GetMapping(params = "date")` on the same path
(the `params` condition is the more specific mapping and resolves beside the bare `@GetMapping` and
`/{id:\d+}`) calls it with `from = to = date` after validating `today − ForecastHorizon.MAX_PAST_DAYS
(4, new) <= date < today` — 400 `{"error": "date must be between T-4 and yesterday"}` otherwise,
so the endpoint can never become a second serve path for days the list already carries or for the
future. Stored rows only: the rich rows, the stability-skip retraction and the sparse
`cached_evaluation` rows (§1 #10), exactly as the list does; **nothing in it may reach
`runForecast`, a batch or a model** (pinned by a test that the controller's run-capable
collaborators are never called). `ForecastHorizon.MAX_PAST_DAYS` carries a javadoc naming its
reader (the Map tab's older-day fetch) and the relation to `SERVE_PAST_DAYS` (the list's past
edge; the one-day read covers the gap between them). `/evaluate/scores` does not learn `?date=`:
the chips read the forecast row's rating where there is no briefing score, and the field takes the
same rows (§2 #3).

### 5. The rating block (frontend, O4a)

`components/map/OutcomeRatingBlock.jsx`, mounted by `MapCallout` inside `.wf-callout-body` after
the facts row when `event.past && !isHide && !rewound && placePassed`, where `placePassed` is
`isEventTimePast(the selected place's own forecast row's solarEventTime)` when that row carries a
time, else `event.past` — the per-place check that keeps the client and the server's own
`hasPassed` (which is per place) in step along a coastline (§4 #4). Every string lives in
`components/map/outcomeRatingCopy.js` (the `WindowControl.PICK_TEXT` shape), with a header
separating the spec's strings from the four plan-introduced ones (§4 #17). Props: `{event,
locationId, mine, role, saving, saveError, changing, onChangingChange, onRate, admin}` where `mine`
is `{rating: number | null, updatedAt} | null` (null = unrated), `role` from MapView's
`useAuth()`, `onRate(value)` with `value` 1..5 or `null` (not seen), `changing` / `adminOpen`
**owned by `MapCallout`** (§1 #3: reset when `${locationId}|${event.id}` changes, and named in the
repaint dependency list so a 40px→44px switch re-anchors the card), and `admin` null until O5a.

States and copy, from spec §3 verbatim:

| state | render |
|---|---|
| Unrated | row `How was it?` (13px/600) left, meta right-aligned (mono 9.5px ink-3): `{Sunrise\|Sunset} was {today\|yesterday} {HH:MM}` or `… was {Dow} {d}, {HH:MM}` — the clock time is the window's own `event.time`; then five `button.wf-rate-star` in a 5-column grid (gap 5px), then full-width `button.wf-rate-ns` `Didn't see it` |
| Saving | the grid and the not-seen button at opacity .55, `aria-disabled`, non-interactive |
| Rated | `You rated ★★★☆☆ 3★` (stars 15px, filled `#E5A00D`, empty `#4A3A2E`, `aria-hidden`; the numeral mono 12px/600 ink; a visually-hidden "3 out of 5") left, `button.wf-rate-change` `Change` right; the grid hidden |
| Not seen | `You marked this not seen` + `Change` |
| Changing | the Unrated layout with the meta reading `Tap to replace your {n}★` / `Tap to replace "not seen"`, the current choice `aria-pressed` and highlighted; one tap replaces it |
| Save failed | the grid stays (returns to Changing when a value existed) with a `role="status"` line `● Not saved. Tap a star again.` (12px ink-2) — the 8px dot `--color-verdict-marginal` amber, the only amber in the flow. **Lifetime**: the line stays while the retry is saving, and clears on the next successful save, on `Change`, and on re-selection of the spot or the window (the prototype resets `S.err` on step, menu pick and spot pick) |
| Lite | `How was it?` + `ProPill`; **the five stars dim to opacity .45 and `Didn't see it` keeps full opacity** (the prototype's `.lock` is on `.stars` only — screenshot 08), both `aria-disabled` and both locked: a tap on either calls nothing and swaps the line (12px ink-3) `Rate how the sky turned out.` for `Rating is part of Pro.` (no upgrade route exists in this app beyond the words — §4 #6; the tap is accepted rather than `pointer-events: none`, §4 #18) |
| Future window | not mounted |

Star button: min-height 44px (phone and desktop alike), 1px `#4A3A2E` border, radius 9, bg
`#1E1712`, `★` (16px, ink-3) over the numeral (mono 10.5px/600, ink-2); hover border ink-3;
selected border `#E5A00D`, bg `rgba(229,160,13,.12)`, star gold; `aria-label="Rate the
{sunrise|sunset} {n} out of 5"`. `Didn't see it`: min-height 40, 1px `#3A2C23` border, radius 9,
12.5px/500 ink-2; selected like a selected star. `Change`: min-height 40, padding 0 12px, 1px
`#4A3A2E` border, radius 8, 12.5px/500 ink-2. Container: 1px top border `#3A2C23`, padding-top 9,
column, gap 7. Every colour is a token where one exists (the phase reads `@theme` and names the
token per literal — `--color-plex-border-light`, `--color-plex-border`, `--color-plex-panel`,
`--color-verdict-marginal` — adding `--color-star`, `--color-star-off` and
`--color-star-selected-bg` only for the three the stylesheet lacks).

**Focus**: a press that unmounts its own button moves focus to a survivor BEFORE the state change
lands — `Change` → the current choice's star (or star 1); a star / not-seen press → the `Change`
button once it mounts (a `useLayoutEffect` keyed on the state transition); the §1 #3 rule, which
the Map tab has had to relearn five times (`map-landing-plan.md` §3 L4–L6). Escape inside the block
is left to the pane (it deselects the callout — the spec says nothing and a dismiss is the honest
answer). Arrow keys are not handled (five buttons, not a radiogroup — the spec's buttons carry
numerals and there is a sixth, "Didn't see it", that is not a value on the same scale).

**The data** (`hooks/useOutcomeRatings.js`, mounted in `WindowFirstMapPane` beside the forecasts
it joins to; nothing in `swrCache`, `localStorage` or module scope, so a logout cannot carry one
user's ratings to the next): `mine: Map<"locationId|date|type", {rating, updatedAt}>`, loaded by
`GET /api/outcome/ratings?from=<oldest known past date>&to=<today>` on mount, re-read when an older
day lands and when the forecasts refresh; `rate(locationId, date, type, value)` is **optimistic** —
writes `mine` first, PUTs, and on failure restores the previous value (or deletes the key when
there was none) and reports `saveError` for that key; `saving` per key; a late response from an
overtaken request is dropped (the Ask `send` rule; the negative tests settle inside an awaited
`act`, `frontend-test-standards.md` `:502`). A LITE role never calls `rate`. Ratings are not asked
for at all while rewound (§5 #6). The API bindings are `fetchMyRatings(from, to)` and
`putRating(body)` in a new `api/outcomeRatingApi.js`.

### 6. Counts and the badge (frontend, O4b)

**Menu counts**: `ratedCountByWindow` — for each past row, the number of `mine` keys on its
(date, type), a count including "not seen" (the prototype's `myCount` counts any record) —
derived in the pane from the hook's map and handed to `WindowControl` / `MapPeekSheet` as
`ratedCount` on the row; `{n} rated` in star gold, else `Not rated`.

**The badge**: `spot.myRating` (`1..5`, `'NOT_SEEN'`, or null) rides `labelSpots`, set only when
the active window is past; `PinsLayer` and `MapLabels` draw `span.wf-rated-badge` top-right
(`{n}★` / `–`; mono 9px/700, gold on `#0e0b09`, 1px `#4A3A2E` border, radius 6, padding 0 3px,
line-height 14, offset top −7px / right −9px, `aria-hidden`) and append `, you rated {n} out of 5`
/ `, you marked this not seen` to the button's accessible name. The chip's `measuredChip` rect
(`MapLabels.jsx:493–503`) and the pin's grow by the overhang (7px up, 9px right) whenever the
badge is drawn, so the label placer sees it (§1 #4) — a unit-tested placer rule, not a Chromium
measurement (§7). Badge on chips AND pins (§4 #19).

**Both are per-user joins**, not derivations over the shared briefing: the reader's own records
from a never-cached endpoint, joined to the window and the spot — the Coming up badge's class
(CLAUDE.md Backend-heavy, the fourth, per-user-join class: "what is new *to you*"), whose
member list O6 grows by exactly two (`ratedCountByWindow`, `spot.myRating`).

### 7. The admin list (frontend, O5a)

`admin` prop on the block: `{open, onToggle, loading, rows, eventTime, forecastRunAt}`. A
collapsible box `All ratings · {n}` (collapsed by default, `▾/▴`, `aria-expanded`) under the
reader's own block; rows **the caller's own row first (`mine`), then the rest newest first** as
served; each `{username}` · stars (`aria-hidden`, with a visually-hidden "N out of 5") or `Not
seen` · `HH:MM` (today on the UK clock) / `{Dow} HH:MM` otherwise, from `updatedAt`; empty state
`No ratings yet.`; footer `Rated against the run at {Dow} {d} {Mon}, {HH:MM} · {Xh} before
{sunrise|sunset}` from `forecastRunAt` and `eventTime` — `X` is whole hours rounded to the nearest
hour when the gap is an hour or more (`2h`), else minutes (`40 min`); with no run (`forecastRunAt`
null) the footer reads `No forecast run recorded before this {sunrise|sunset}` (both
plan-introduced, §4 #17). The list is fetched on first expand (`GET /api/outcome/ratings/all`,
`fetchAllRatingsForSlot`) and re-fetched after the admin's own save lands. `adminOpen` resets with
`changing` on a spot OR window change (the prototype resets it on a spot change only — §4 #20).
ADMIN only; the block never mounts it for another role.

### 8. The calibration gate reads the new ratings (backend, O5b)

`ForecastEvaluationRepository` gains `findCalibrationPairsFromRatings(from, to)`: a constructor
projection over the theta join `FROM ForecastEvaluationEntity e, OutcomeRatingEntity r WHERE
e.id = r.forecastEvaluationId AND e.rating IS NOT NULL AND r.rating IS NOT NULL AND r.eventDate
BETWEEN :from AND :to` — **joined on the row id the rating resolved at write time** (§2 #1), never
on the natural key: a natural-key join would admit every rated row for the slot, and
`latestPerSlotHorizon` would then crown a batch that landed after the event (Codex, #1185 round
5); the id is the one row proven available by the event and unretracted at it, so the gate's
"newest run per slot and horizon" has exactly one candidate per rating. A rating with a null
`forecastEvaluationId` pairs with nothing, by construction. ⚠️ **No
`null` literals in the `SELECT new`** (no repository here does it and HQL's constructor resolution
on an untyped null is not proven for this Hibernate): `CalibrationPair` gains a second, explicit
constructor taking only the columns this join has (`fierySkyActual`, `goldenHourActual`, `wentOut`
null — the new record has none; `wentOut` stays null so no reader infers a trip) and the query
projects only those. ⚠️ **The dedup key gains an outcome identity** (§1 #5): `CalibrationPair`
carries `outcomeKey` (`"rating:" + userId` for the new table, `"outcome:" + id` for the old) and
`slotHorizonKey()` appends it, so `latestPerSlotHorizon` collapses only RUNS of one forecast for
one outcome, as it was written to. `ForecastCalibrationService.report` concatenates both queries
before `latestPerSlotHorizon`; `outcomesInRange` counts both tables' rows in the window, **all
rows, "not seen" included**, the rule the old table already follows (`:79`). Several users rating
one slot give several pairs — each is an observation, which is what a calibration sample is; the
report's bucket counts say "pairs", not "slots". `CalibrationReport` needs no new field.
`UserRole`'s javadoc stops calling outcome recording a LITE right.

---

## §3 Phases

One Sonnet session each, one branch each (`claude/outcome-rating-o<N>-<slug>`), one PR each.
**Dependencies and parallelism**: O1 ∥ O2 ∥ O3a (three disjoint file sets — O1 backend outcome
files, O2 `ForecastController`/`ForecastHorizon`, O3a frontend only); O3b needs O2 and O3a merged;
O5b needs only O1 and may run beside O3b; O3c needs O3a; O4a needs O1, O3a and O3c (the callout
mounts on `event.past`; the hook needs the backend); O4b needs O4a and O3b (the menu counts);
O5a needs O4a; O6 needs everything. Never more than two phases open at once, and never two that
touch one source file. Each phase re-verifies every file:line it touches against `origin/main`
before editing, reads `docs/engineering/test-improvement-standards.md` (backend) or
`docs/engineering/frontend-test-standards.md` (frontend), writes its
`changelog.d/<its own date YYYYMMDD>-outcome-rating-o<N>-<slug>.md` (`### Added` for code phases,
`### Changed` where it changes an existing behaviour, `### Docs` for O6; **one file per `###`
block**) and appends its phase-log row to §0 **in the same commit**.

### O1 — backend: the record and its endpoints

Files: `V177__outcome_rating.sql`, `V178__forecast_evaluation_scored_at.sql`;
`entity/ForecastEvaluationEntity.java` (+ `scoredAt`), `service/ForecastService.java` and
`service/evaluation/ForecastResultHandler.java` (the two stamp sites), `ForecastEvaluationRepository`
(+ one bounded slot finder), `ForecastRunDispositionRepository` (+ one bounded skip finder); `entity/OutcomeRatingEntity.java`;
`repository/OutcomeRatingRepository.java`; `PipelineRunRepository` (+ one finder);
`service/OutcomeRatingStore.java`, `service/OutcomeRatingService.java`;
`model/OutcomeRatingDto.java`, `OutcomeRatingRequest.java`, `SlotRatingsDto.java`;
`controller/OutcomeRatingController.java`; tests: `OutcomeRatingStoreTest` (`@DataJpaTest` on H2
with `@Transactional(propagation = NOT_SUPPORTED)` and an executor race, the `AskUsageStoreTest`
shape — the store has one dependency, so the real store races over the real repository),
`OutcomeRatingServiceTest` (Mockito, exact inputs), `OutcomeRatingControllerTest` (extends
`AbstractControllerTest`; `@WithMockUser(roles = …)` for ADMIN / PRO_USER / LITE_USER on every
endpoint: LITE GET 200 `[]`, LITE PUT 403, admin-all PRO 403), `OutcomeRatingRepositoryTest`
(`@DataJpaTest`: the slot read with usernames, the range read), `integration/OutcomeRatingMigrationTest`
(CI-only; also asserts the V178 column through `information_schema`), `ForecastResultHandlerTest` and
`ForecastServiceTest` (the stamp sites), `ForecastEvaluationRepositoryTest` (the bounded finder),
`HttpCachingConfigTest` (two paths added), `AbstractControllerTest` (one `@MockitoBean`).

1. The migration (§2 #1 verbatim; a header comment in the V166 voice: why null = not seen, why
   the user cascades and the location does not, why the run is nullable, why `event_time` is
   stored). Re-read `ls backend/src/main/resources/db/migration | sort -V | tail -1` first.
2. Entity (write discipline per §2 #1) + repository: the native insert, the conditional update,
   the range read, the slot read with usernames (the theta join, newest first).
3. `forecast_evaluation.scored_at` (V178, nullable `TIMESTAMPTZ`, no backfill; a one-line
   migration with a header saying what it proves) + `ForecastEvaluationEntity.scoredAt`
   (`Instant`, nullable) + the two write sites (`ForecastService.buildEntity` when the row carries
   a rating or triage reason; `ForecastResultHandler.scoreEvaluationRow`), pinned: a sync row is
   stamped at insert, a triage row at insert, a PENDING row null until scored then stamped, an
   ABANDONED row never. Then `resolveRatedRun` (§2 #1) through
   `EvaluationViewService.getLiveScoresForEnrichment`, the new `PipelineRunRepository
   .findByTriggerTime` and a new slot finder bounded on `COALESCE(scoredAt, forecastRunAt) <=
   :instant` with the has-something-to-say rule; pinned with exact instants: a cached result that
   arrived before the event whose `submittedAt` matches a cycle (id + instant); one from an ad-hoc
   batch (instant, no id); a legacy result with `evaluatedAt` only (instant, no id); a result
   submitted before but ARRIVED after the event (set aside → the latest row scored by the event,
   never the late batch's own row even though that row's `forecastRunAt` precedes the event; nulls
   when no such row); a slot the serve path has nothing for, with a row scored before the event
   (its instant, no id) and with one scored after it (nulls); a legacy row with null `scoredAt`
   taken on `forecastRunAt`; an unregioned location (step 1 skipped); a retracted result (not
   returned by the live accessor → step 2); `forecastEvaluationId` set from step 2's candidate
   whenever one exists, step 1 answering or not, and null otherwise; a row scored before the event with a stability skip
   recorded between its scoring and the event (retracted → an earlier unretracted row, else nulls);
   the same row with the skip recorded after the event (it stands).
4. `OutcomeRatingStore.upsert` — no `@Transactional`, the javadoc citing §1 #8; the race test.
   `rate(...)`: the refusals in order (unknown user 404, unknown location 404, rating out of range
   400 at 0 and 6 with 1 and 5 accepted and null accepted, HOURLY 400, event not passed 400 at
   exactly the afterglow boundary — `hasPassed` is strict, so `eventTime + 30 min == now` is NOT
   passed) and the happy path writing every column.
5. The three endpoints (§2 #1): role matrix, 400 bodies `{"error": …}` through the existing
   `IllegalArgumentException` mapping, the 31-day range cap, `from > to`, PUT body with `rating`
   absent → not seen, admin-all with zero rows still carrying `forecastRunAt`, `mine` set on the
   caller's row, the name join done once per range read (pin the repository call count).
6. Gate, review lenses (runtime, test quality against the backend standards, conventions —
   Checkstyle javadoc on every public member, JaCoCo 80% per class: cover the DTO's null branches
   with assertions, not deletions), changelog, phase-log row.

Acceptance: every endpoint answers per §2 #1's matrix in `OutcomeRatingControllerTest`; the store's
H2 race passes 12 threads; the migration is proven by CI only (say so in the PR body — "pending
CI", the CLAUDE.md framing); the local gate passes on JDK 23 with the one exclusion the memory
note names.

### O2 — backend: `GET /api/forecast?date=`

Files: `controller/ForecastController.java`, `util/ForecastHorizon.java`, tests
`ForecastControllerTest` (extends `AbstractControllerTest`; `getForecasts_*` at `:106–262` — add the
`?date=` cases beside them), `ForecastHorizonTest`, `EvaluationViewServiceTest` (one case).

1. `ForecastHorizon.MAX_PAST_DAYS = 4` with its javadoc (§2 #4).
2. Extract `serveRange(from, to, auth)` from `getForecasts`; the list endpoint's behaviour is
   byte-for-byte unchanged (its existing tests stay green untouched).
3. `@GetMapping(params = "date")` on the same path: the bound (400 for today, tomorrow, T-5, a
   malformed date; 200 for T-1 and T-4), the rich + sparse assembly for one day, the retraction
   applied, LITE score selection applied, **no run collaborator touched** (a test that the
   command factory / executor / batch services are never called — whichever of the controller's
   injected collaborators can trigger work).
4. One unit test over the real `EvaluationViewService` with a mocked repository returning a T-3
   `cached_evaluation` row, pinning that `cachedOnlyViewsForDateRange(d, d, …)` returns it (§1
   #10); the over-read noted in the endpoint's javadoc.
5. Gate, lenses (runtime, test quality, conventions), changelog, phase-log row. The endpoint is
   ETag'd by path with no change to `HttpCachingConfig` (say so in the javadoc).

Acceptance: `ForecastControllerTest`'s existing `getForecasts_*` tests pass unmodified; the new
`?date=` tests cover the four refusals and the two admissions; no run collaborator is invoked.

### O3a — frontend: past rows, the PAST pill and the selection plumbing

Files: `utils/mapEvents.js`, `utils/mapDates.js`, `utils/mapScopedTime.js`,
`utils/windowFirstCards.js` (`pastDayLabel`), `utils/mapPeek.js`, `utils/mapCallout.js`, `App.jsx`
(`selectDate`, `selectedDateIsPast`, the `resolveMapDate` call), `components/WindowFirstMapPane.jsx`
(`pastSolarWindows`), `components/MapView.jsx` (`selectEvRow`, the `calloutFacts` call, the
"Open in Plan" withhold), `components/map/WindowControl.jsx` (the PAST tag only), `index.css`
(`.wf-win-past`); tests under `src/test/`: `mapEvents.test.js`, `mapDates.test.js` +
`mapDatesAbroad.test.js`, `mapScopedTime.test.js` (find it), `windowFirstCards.test.js`,
`mapPeek.test.js`, `mapCallout.test.js`, `WindowControl.test.jsx`, `MapCallout.test.jsx`, an `App`
test in the `AppRewind.test.jsx` shape for the `isPast` route.

1. `pastDayLabel` (§2 #2): today, yesterday, `Fri 16`, `Sat 1` (no leading zero), pinned in UTC
   and under a non-UK zone.
2. Past rows in `buildMapEvents` (§2 #2): the `pastSolarWindows` input, `row.past` (not served,
   in domain, `date <= todayStr`), the base `time` from `earliestEventTime`, the `hasSolarRow` /
   `solarRowPredicate` widening, today's elapsed sunrise becoming past exactly when its served
   window is withdrawn (pin: served → not past; withdrawn and today → past; a date with rows and
   `date > todayStr` still a filler), T-1 and T-2 rows present, ordering unchanged,
   `withScopedSolarTimes` narrowing a past row's time and leaving it when the pool is empty,
   `isForwardableRow` true for a past row.
3. `resolveMapDate` `selectedIsPast` (§2 #2): a past date is usable only with the flag and only
   when in `allDates`; a stale past pick without the flag falls through; the night exemption's
   tests untouched. `App.selectDate(date, {isPast})`, `selectedDateIsPast`, forwarded through
   `onSelectDate`; `MapView.selectEvRow` sets it for a past row; `userHasOverriddenEvent` keeps the
   auto-event effect from undoing the event type (`:1457`, `:2206–2216`).
4. The PAST tag on the pill (§2 #2) and the override pill, the accessible name, the CSS.
5. `calloutFacts` `past` flag (Drive and Leave by hidden, Dark sky kept); "Open in Plan" withheld
   on a past window; `otherWindow` skipping past rows in both paths.
6. Gate, lenses (runtime, test quality, conventions — `pastSolarWindows`, `pastDayLabel`, the
   `past` filter are the already-licensed filter/map/select class over served facts; say so in
   the module headers), changelog, phase-log row.

Acceptance: on a fixture with today's elapsed sunrise, T-1 and T-2 rows, the EV list holds past
rows for all five windows, each selectable through App with the flag, the pill reading `PAST`,
the chips answering with the forecast rows' ratings, the callout showing no Drive / Leave by and no
"Open in Plan"; a stale past selection without the flag falls through to today.

### O3b — frontend: the EARLIER group and the older-day fetch

Files: `api/forecastApi.js` (`fetchForecastsForDate`), `hooks/useForecasts.js` (`loadOlderDay`,
`olderDays`, the re-merge), `components/WindowFirstMapPane.jsx` (`canLoadOlder`, the next older
date, the three props), `components/MapView.jsx` (pass-through), `components/map/WindowControl.jsx`
(EARLIER group, Load row, stepping, keyboard, loading pill, focus), `components/map/MapPeekSheet.jsx`
(the same group and rows), `index.css`; tests: `useForecasts.test.js`, `WindowControl.test.jsx`,
`MapPeekSheet.test.jsx`, `WindowFirstMapPane` tests (find the file), a cascade test in the
`mapWindowControlWidthCascade.test.jsx` shape if any new rule competes with an existing one (the
pill's fixed width — `min-width: 0` beside `overflow: hidden` is a no-op, the
`window-control-fixed-width` memory).

1. `fetchForecastsForDate` and `loadOlderDay` (§2 #2): merge-without-replace, the loaded-empty
   entry, **the re-merge on every `load()`** (pin: a refresh keeps a loaded T-3's rows and
   `allDates`), `olderDayLoading`, never `writeSwrCache`, a failed fetch leaving the roster
   unchanged and `olderDayLoading` cleared with the error reported.
2. `canLoadOlder` and the next older date in the pane; nothing older than T-4 is ever requested
   (pin: at T-4 loaded, `canLoadOlder` false; at T-4 loaded-empty, false; with only today loaded,
   the next older date is T-1).
3. `WindowControl` (§2 #2): the EARLIER header, the Load row with its `↑` chip, text, sub-line and
   accessible name, the press closing the menu and focusing the pill before the state change, past
   sub-headers, the bold ink-2 time, `Not rated` placeholder, `data-past`, today appearing in both
   groups, `‹` and `ArrowLeft` at index 0 calling `onLoadOlderDay` with `‹`'s accessible name
   naming the day, the loading pill with both steppers and the Load row disabled, the landing on
   the loaded day's sunset from all three routes, an empty day moving nothing, `‹` disabled at the
   oldest reachable window.
4. `MapPeekSheet` (§2 #2): the same group and rows; `PAST` in the verdict slot.
5. CSS: `.wf-win-earlier`, `.wf-win-load`, `.wf-win-loading`, the past sub-header, all on existing
   tokens; the cascade test if needed.
6. Gate, lenses (runtime, CSS/tokens, test quality, accessibility, conventions), §7 measurement
   (the pill with `PAST` at 320px; the EARLIER menu at 320 and 1280), changelog, phase-log row.

Acceptance: from today's sunrise, `‹` reaches T-4 (or a loaded-empty day) and never issues a
request older; a refresh keeps every loaded day; the menu's EARLIER group matches screenshots 02
and 15 at the measured widths.

### O3c — frontend: the heat field on a past window

Files: `components/WindowFirstMapPane.jsx` (the past-only event list, the merged score rows,
`heat.windows` and `heat.pointsByKey` extensions), `utils/mapEvents.js` (`pastWindowBest`),
`utils/heatSpots.js` (only if a helper is needed — `buildHeatSpots` itself is unchanged);
tests: the pane's tests, `mapEvents.test.js`, `MapViewHeat.test.jsx` (a past window paints points;
the legend key shows on a rated past window and is withheld on an unrated one; the counts footer).

1. The merged score rows and the past-only event list in the pane (§2 #3); `buildHeatSpots` over
   them; `heat.pointsByKey` extended.
2. `heat.windows` extended with `{key, date, targetType, bestRating: pastWindowBest(rows),
   confidence: null}`; `pastWindowBest` beside `bestOfNight` with the licence note in its javadoc.
3. `MapViewHeat.test.jsx`: a T-1 window with briefing scores paints; a T-4 window with forecast
   rows only paints; a past window with no scored place paints nothing and withholds the key.
4. Gate, lenses (runtime, test quality, conventions — the new licensed member named), changelog,
   phase-log row.

Acceptance: the Heat|Pins toggle behaves on a past window as on a served one; the Plan matrix's
`upcomingEvents` and its tests are untouched.

### O4a — frontend: the rating block and its data

Files: `api/outcomeRatingApi.js` (new), `hooks/useOutcomeRatings.js` (new),
`components/map/OutcomeRatingBlock.jsx` (new), `components/map/outcomeRatingCopy.js` (new),
`components/map/MapCallout.jsx` (mount, owned state, repaint deps, `placePassed`),
`components/WindowFirstMapPane.jsx` (the hook, `rewound`), `components/MapView.jsx` (props),
`index.css`; tests: `useOutcomeRatings.test.js`, `OutcomeRatingBlock.test.jsx` (every state in
§2 #5's table, one test per row plus the transitions and the error line's lifetime),
`MapCallout.test.jsx` (mount conditions; the repaint re-running on `changing`, the existing
`:1126–1145` shape), a `mapCalloutRatingCascade.test.jsx` pinning the block's rules against the
real stylesheet.

1. The API bindings and the hook (§2 #5): optimistic write, rollback on failure, per-key
   `saving`/`saveError`, the overtaken-response drop, the range re-read when an older day lands
   and on refresh, nothing stored outside React state, no request while rewound, the Lite role
   making the initial read only.
2. The block (§2 #5): states, copy, focus handoffs, `aria-pressed` on the current choice in
   Changing, the status line and its lifetime, the Lite lock (stars dim, not-seen does not; taps
   call nothing; the line swaps), the 44/40px targets, tokens.
3. `MapCallout`: the mount condition (`event.past && !isHide && !rewound && placePassed`), the
   owned `changing`/`adminOpen` state reset on `${locationId}|${event.id}`, both in the repaint
   dependency list (pinned), the facts row already hidden (O3a).
4. Gate, lenses (runtime, CSS/tokens, test quality, accessibility — the block speaks its state
   through one status line and one visually-hidden phrase, never twice; conventions; "what this
   makes harder for O4b/O5a"), §7 measurement (the block at the 266px phone callout and at 286:
   the five-column grid's cell width, the meta line's wrap at `Sunset was Sat 11, 18:17`),
   changelog, phase-log row.

Acceptance: every row of §2 #5's table has a test; a failed save restores the previous value and
shows the line; the callout re-anchors when the block changes height; the measurement is in §7.

### O4b — frontend: the counts and the badge

Files: `components/WindowFirstMapPane.jsx` (`ratedCountByWindow`, `myRating` per spot),
`components/MapView.jsx` (`labelSpots`), `components/map/PinsLayer.jsx`,
`components/map/MapLabels.jsx` (badge + `measuredChip` overhang), `components/map/WindowControl.jsx`
+ `MapPeekSheet.jsx` (`ratedCount`), `hooks/useForecasts.js` (drop `fetchAllOutcomes` and
`loc.outcomes`; `readValidCache` stops requiring `outcomes` so an old cached payload still
hydrates), `index.css`; tests: `PinsLayer.test.jsx`, `MapLabels.test.jsx` (the placer rect with and
without the badge), `WindowControl.test.jsx`, `MapPeekSheet.test.jsx`, `useForecasts.test.js`
(its 23 `outcomes` references).

1. `ratedCount` on past rows and the `{n} rated` / `Not rated` cell in both menus (§2 #6),
   counting "not seen".
2. The badge on pins and chips, only on a past window, with the accessible-name clause and the
   measured-rect overhang (§2 #6).
3. Remove `fetchAllOutcomes` from `useForecasts` and `loc.outcomes` from `buildLocations` (§4 #7);
   the `forecastApi.js` bindings and `OutcomeModal` stay.
4. Gate, lenses, changelog (two files: `### Added` for the counts and badge, `### Changed` for the
   old outcomes fetch), phase-log row.

Acceptance: a rated spot wears its badge on both representations on a past window and none on a
served one; the placer test shows the badge inside the measured rect; `useForecasts` makes two
requests on load, not three.

### O5a — frontend: the admin list

Files: `components/map/OutcomeRatingBlock.jsx` (the `admin` prop), `outcomeRatingCopy.js`,
`api/outcomeRatingApi.js` (`fetchAllRatingsForSlot`), `hooks/useOutcomeRatings.js` (the per-slot
admin read), `MapView.jsx` / `MapCallout.jsx` (pass `admin` for ADMIN only); tests
`OutcomeRatingBlock.test.jsx` (own row first, then newest first; empty state; footer with and
without a run; the hour rule at 59 min / 60 min / 89 min / 90 min; the `HH:MM` vs `{Dow} HH:MM`
rule at the UK midnight boundary; ADMIN-only mount), `useOutcomeRatings.test.js`.

1. The admin list (§2 #7): fetch on first expand, re-fetch after the admin's own save, the
   footer rules, the reset rule.
2. Gate, lenses, changelog, phase-log row.

Acceptance: screenshot 09 / 14's states have tests; a PRO role never fetches the slot list.

### O5b — backend: the calibration gate reads the new ratings

Files: `model/CalibrationPair.java` (the second constructor, `outcomeKey`, `slotHorizonKey`),
`repository/ForecastEvaluationRepository.java` (`findCalibrationPairsFromRatings`),
`service/ForecastCalibrationService.java`, `entity/UserRole.java` (javadoc); tests
`ForecastEvaluationRepositoryTest` (beside the `findCalibrationPairs_*` neighbours at `:713–766`:
a rating row pairs with the row its id names and with no other row of the slot, even a newer one;
a not-seen row does not; a null id pairs with nothing; two users give two pairs), `ForecastCalibrationServiceTest`
(two users of one slot survive `latestPerSlotHorizon`; one row in each table survives; two RUNS of
one forecast for one user still collapse to the newest; `outcomesInRange` counts both tables).

1. §2 #8 verbatim.
2. Gate, lenses, changelog (`### Changed`), phase-log row.

Acceptance: the four service cases above; a late-landing newer row for the slot never enters a
pair; the repository query compiles against H2 with no `null` literal in its projection.

### O6 — docs sweep

Files: `CLAUDE.md` — the overview's "Outcome recording is API-only (no UI since 2026-02-27)"
sentence; the Roles table's LITE row and its note; the Forecast endpoints (`GET /api/forecast?date=`);
the Outcomes endpoints (the three new routes and their gates); the Calibration gate paragraph
(both tables, the outcome identity in the key); the Map tab bullet (past windows, the
`selectedIsPast` provenance flag, the EARLIER group, the older-day fetch); the Backend-heavy
bullet — (i) the sixth class's member (e) sentence "whether a window has passed still reads the
served instant, so the two tabs retire it at the same minute" stays true (the past rule is the
served withdrawal) but gains the per-place clause §4 #4 states, (ii) the fifth class ("Members:
those two, nothing else") grows to three with `mapEvents.pastWindowBest`, (iii) the fourth
per-user-join class ("Members: those three, nothing else") grows to five with `ratedCountByWindow`
and `spot.myRating`, (iv) one sentence naming O3a's filter/map helpers; a "Role gating — UI
pattern" assessment bullet for the rating block (every role sees the block; LITE's stars dim and
every tap answers in words; the write is 403 server-side; the `aria-disabled` rather than
`disabled` choice and why); the Rewind paragraph ("Ask PhotoCast is hidden under a rewind" gains
the rating block, badges and fetch); the Dev Setup Docker note's "Thirteen classes extend
`IntegrationTestBase`" → fourteen; the migrations table — V177. Also
`docs/engineering/v1-retirement-plan.md` §8 item 7 (a status line: the outcome UI has a v2 home),
`docs/engineering/map-tab-v2-plan.md` §4 (one entry: past solar windows and the `selectedIsPast`
flag), this plan's §0 (COMPLETE, every §6 question closed with a date),
`docs/design/outcome-rating/VENDORING.md` ("Final count"). No code.

---

## §4 Disagreements with the spec, on purpose

1. **The phone callout is 266px, not 296.** The spec's figure is the map-landing bundle's; the
   callout's width was decided at L3 and is not reopened. The five-column grid fits: 266 − 2·10
   padding − 4·5 gaps = 226 / 5 ≈ 45px a cell (§7 measures it).
2. **The forecast being rated is the SERVED result's own instant, gated on availability, not a
   cycle lookup.** The spec says "`forecast_run_id` (or run timestamp)" and "the last forecast run
   before the event". A cycle that failed, skipped the slot or landed nothing is not a forecast the
   reader saw, and a result that landed after the event was not seen at it either; so the record
   resolves the slot through the same serve-time precedence the map reads (§1 #6, §2 #1), takes a
   result only if it had ARRIVED by the event, stores its submission instant (the forecast's age),
   recovers the cycle id only where a cycle's trigger instant matches it exactly, and otherwise
   falls back to the latest `forecast_evaluation` row proven available by the event through a new
   `scored_at` stamp (V178). Raised by Codex on #1185 in three rounds: the first draft took the
   newest cycle before the event, the second gated on submission alone, the third bounded the
   fallback by submission order, which a late ad-hoc batch's own row satisfies.
3. **`event_time` is stored per location**, not derived from the window: the admin footer's "Xh
   before sunrise" is a fact about THIS place's sunrise, and the roster's earliest would misstate
   it by up to twenty minutes along a coastline.
4. **A past window is the briefing's own withdrawal, with a per-place check at the block.** The
   spec says "event time < now"; the briefing withdraws a window when its EARLIEST event time plus
   the 30-minute afterglow has passed (`PlanWindowProjector.hasPassed`), and the Map reads that
   withdrawal, so the two tabs retire a window at the same serve (CLAUDE.md's invariant). For a
   place further north or west the window may then be "past" on the pill minutes before that
   place's own afterglow ends, and the server's PUT would refuse it; so the block itself mounts
   only when the selected place's own `solarEventTime` has passed by the same rule (`placePassed`,
   §2 #5) — the pill says PAST, the callout waits a few minutes. The afterglow itself is a
   disagreement with the spec's bare "event time < now", on both sides, on purpose: one shared rule.
5. **The heat field paints a past window from the same ratings the chips read** (§2 #3) — the
   spec's prototype has placeholder pins and says nothing; a pins-only past window would make the
   Heat|Pins toggle lie on half the roster. The kernel's haze reads the inferred cap (`medium`)
   because a past window carries no served confidence; accepted, and recorded in the pane's
   comment. The past window's `bestRating` is a client max, a new licensed member (O6).
6. **The Lite tap has no upgrade route to wire.** The spec says "wire it to the existing upgrade
   path"; this app has none (CLAUDE.md "Role gating — UI pattern": `ProPill` or "Upgrade to Pro"
   text, never a link). The tap shows `Rating is part of Pro.` and nothing else.
7. **`useForecasts` stops fetching the old outcomes.** `fetchAllOutcomes` has fed `loc.outcomes`
   to no reader since the outcome UI died (2026-02-27); the new hook is the replacement on the same
   screen. The `forecastApi` bindings and `OutcomeModal` stay, as the spec asks.
8. **No "Before event" copy.** The superseded board's `You can rate this after sunrise at {HH:MM}.`
   is not in the prototype (the block is simply absent on a future window) and is not built.
9. **`GET /api/forecast?date=` admits T-1 as well as T-3/T-4.** The spec bounds it to T-3 and T-4;
   the list's past edge is a constant that can move, and a fresh deployment may serve no rows for
   T-1 at all, so the honest contract is "any past day back to T-4 that the list did not already
   answer for", enforced by the client never asking for a day it already holds.
10. **"Loading" is not timed, and the steppers are disabled during it.** The prototype's 600ms is a
    demo and its `‹` stays enabled behind an `if (fetching) return` guard; here the pill shows the
    loading state for as long as the request takes and both steppers plus the Load row are
    disabled, so the state is visible rather than silently swallowed.
11. **The Load row lands on the loaded day's sunset, like `‹` and `←`.** The README says all three
    routes do; the prototype's `fetchOlder(step)` keeps the current window for the Load row
    (`build(step ? 1 : S.ei + 2)`). The README is the spec.
12. **The block is not the last thing before the actions.** The spec places it "between the why
    text and the action buttons"; in this callout `TideFitBlock`, `EclipseSpotLine`, the topics and
    the "Every event here" strip also live there, and the block sits right after the facts row,
    above them — the prototype's callout had none of those.
13. **The admin list serves newest first with the caller's row hoisted.** The prototype shows the
    admin's own row first and the rest in fixture order (screenshot 09 newer above older); the
    server orders by `updatedAt` descending and marks `mine`, the client hoists.
14. **"Open in Plan" is withheld on a past window.** The spec keeps the action buttons; this
    callout's "Open in Plan" opens `LocationFourDaySheet` on the briefing's windows, which never
    hold a past one, so the press would open a place's upcoming windows under a card about a past
    one. "Zoom to it" stays.
15. **An older day that loads empty is silent.** The spec and the prototype have no such state (a
    stored day always has rows in the demo); here the roster for T-3 may be empty on a fresh
    deployment, so the fetch records the day as loaded, moves nothing, prints nothing, and the Load
    row offers the next older day — the day still counts toward T-4.
16. **`‹` at the oldest window is opacity .35, not .3.** The stylesheet's one `:disabled` rule
    for the steppers (`index.css:4843`) is reused, not duplicated for a 0.05 difference.
17. **Four plan-introduced strings** (`No forecast run recorded before this {sunrise|sunset}`, the
    `40 min` footer variant, `Load {Dow} {d}` as the `‹` stepper's accessible name, `Loading …` as
    an `aria-busy` state) are marked as such in `outcomeRatingCopy.js` so the copy module is never
    read as the spec.
18. **The Lite lock is `aria-disabled` with the tap accepted, not `pointer-events: none` +
    `disabled`.** CLAUDE.md's pattern greys with `pointer-events: none`; the spec asks the tap to
    answer (`Rating is part of Pro.`), which `pointer-events: none` would prevent, and a `disabled`
    control that held focus drops it to `<body>` (the Ask field's rule).
19. **The badge draws on chips as well as pins.** The spec says "on pins"; the heat view's chips are
    the default representation and the prototype's pins are its only one, so the badge rides both,
    with the placer overhang folded in (§1 #4).
20. **`adminOpen` resets on a window change as well as a spot change.** The prototype resets it on
    a spot pick only; the list is per (spot, window), so a stale open list over a new window would
    show another window's ratings until refetched.

---

## §5 Decisions taken at plan time (defaults every phase builds on)

1. **One table, three endpoints, PUT for the write.** `outcome_rating` beside `actual_outcome`,
   never a `user_id` column bolted onto the old table (its rows are a different record with no
   user, and its API stays open to LITE for the calibration input it was built for).
2. **LITE reads, never writes**: the GET of own ratings has no role gate (LITE has no rows), the
   PUT is `hasAnyRole('ADMIN', 'PRO_USER')`, the admin read `hasRole('ADMIN')`.
3. **The event must have passed on the server's clock** (the 30-minute afterglow, `hasPassed`, per
   place), and nothing else bounds the date: the Map only reaches T-4, and a future ad-hoc reader
   could legitimately backfill.
4. **Optimistic saves, per-key rollback, one status line.** No toast, no modal, no confirm.
5. **"Not seen" is `rating: null` on the wire and in the table.** No enum, no second column.
6. **Hidden under a rewind**: the block, the badges, the ratings read and the older-day fetch all
   stand down while `getRewindTo()` is set (the `AskWhenLive` precedent); past windows themselves
   still step, because the rewound clock defines them.
7. **The admin list is fetched on expand**, never with the ratings read; it is one slot's rows.
8. **Counts and badges are per-user joins** of the reader's own records from a never-cached
   endpoint onto the window and the spot — the Coming up badge's class (CLAUDE.md Backend-heavy's
   fourth, per-user-join class), two new named members; the past window's `bestRating` is a client
   max in the fifth, Map-tab class, one new named member. Nothing else derives.
9. **The calibration gate reads both tables** and treats each user's rating as a pair, with an
   outcome identity in the dedup key so the run-collapse never collapses users.
10. **No notifications, no history view, no Plan-tab surface**, as the spec's overview says; none
    of the phases adds one.
11. **The upsert runs outside any transaction**, in its own store class, for the reason §1 #8 gives.

### §5a Adversarial review of this plan (2026-10-15, O0)

Four read-only prosecutor lenses over the first draft (backend; frontend; spec fidelity and copy;
conventions, process and sizing). Every charge was accepted and folded in, no refutation round was
run (each carried a file:line). The three P1s: the calibration dedup key collapsing users
(§1 #5, §2 #8); the heat-field extension naming the briefing context's `upcomingEvents` — the
Plan matrix's own list — and missing the `heat.windows` / `pointsByKey` / `bestRating` gates
(§2 #3); the older-day merge not surviving `useForecasts.load()` (§2 #2). Of the P2s: the upsert's
transaction trap and the store extraction (§1 #8, §2 #1); the `forecast_evaluation` fallback being
unreachable without the later-wins rule (§2 #1, §4 #2); `null` literals in a constructor projection
(§2 #8); the callout repaint rule needing the state lifted (§1 #3); `otherWindow`'s scan and
fallback (§2 #2); a past row's time with an empty scope (§2 #2); the badge overhang invisible to the
placer (§1 #4, §2 #6); the past-window rule against CLAUDE.md's "same minute" invariant (§2 #2,
§4 #4); the licensed-class accounting (§5 #8, O6); the Lite pattern divergence (§4 #18); O3 and O4
too large for one session (split into O3a/b/c and O4a/b; O5 into a/b); the §0 table conflict
between parallel phases (§0); acceptance lines (§3); the Codex-clean rule (§8); the changelog
filename date and the two-files rule (§3); the role-gating assessment and two more CLAUDE.md
sentences (O6); the prototype's Load-row, dropdown-close, admin-order, Lite-lock, error-lifetime,
`↑` chip, both-groups-today, empty-day and block-position behaviours (§2, §4 #11–#20).

**Codex, round 1 on #1185 (P1, accepted):** "the newest `pipeline_run` before the event" misattributes
a rating whenever a later cycle failed, skipped the slot or landed nothing — the reader saw the older
forecast, and the footer's age would describe a cycle that served them nothing. The rated run is now
the slot's SERVED result through `EvaluationViewService.getLiveScoresForEnrichment`, with the cycle
id recovered by exact instant match only (§1 #6, §2 #1, §4 #2, O1 step 3).

**Codex, round 2 on #1185 (P1, accepted):** gating on `submittedAt` alone still accepts a batch
submitted before the event that LANDED after it. The served result now counts only when its
`evaluatedAt` is at or before the event; a late one is set aside and the earlier-submitted
`forecast_evaluation` row stands in, with the residual stated (no arrival stamp on that table — §6 Q5).

**Codex, round 3 on #1185 (P1, accepted):** a "submitted earlier" bound on the fallback admits a late
ad-hoc batch's OWN row, whose `forecast_run_at` is stamped at row build, before the batch's
`submittedAt`. The arrival stamp Q5 deferred is now part of O1 (`scored_at`, V178), and the fallback
selects the latest row proven available by the event.

**Codex, round 4 on #1185 (P1, accepted):** a `SKIPPED_STABILITY` recorded before the event retracts
the row from the served view, so the fallback must apply the same retraction as of the event and
ignore skips recorded after it (§2 #1 step 2, O1 step 3).

**Codex, round 5 on #1185 (P1, accepted):** a natural-key calibration join admits a batch that
landed after the event, and `latestPerSlotHorizon` would crown it. The rating now stores
`forecast_evaluation_id` — the row step 2 resolves on every write — and the calibration pairs join
on that id (§2 #1, §2 #8, O5b).

---

## §6 Owner questions — none blocks a phase

- **Q1** Should the calibration gate prefer the new per-user ratings over `actual_outcome` once
  both exist for a slot? Default (§5 #9): union, each a pair, never collapsed. Revisit when the
  report has enough rows to matter.
- **Q2** Should an admin be able to delete a user's rating? Not in the spec; not built. The
  database admin can.
- **Q3** The spec's "set `/forecast` to 2 past days" — already 2; nothing to do. If the owner wants
  three served days up front, `SERVE_PAST_DAYS` is the one constant (`Rewind.MAX_AGE` is
  `SERVE_PAST_DAYS + 1` and follows it).
- **Q4** Should the Plan tab's location sheet show a rated past window? Out of scope (spec §1: not
  in the Plan tab).
- **Q5** (closed 2026-10-15, Codex round 3) `forecast_evaluation` gains an arrival stamp,
  `scored_at` (V178, O1), because without it the fallback cannot exclude a late ad-hoc batch's own
  row. Legacy rows keep the stated residual until they age out of the window.

---

## §7 Verification — what each phase measures

O3b: the pill with `PAST` beside the day label and time at a 320px viewport (no overflow, the
fixed pill width holding); the EARLIER menu at 320 and 1280 (the Load row's two lines, the
sub-headers). O4a: the block inside the 266px and 286px callouts (cell width ≥ 44px, the meta line's
wrap). Both in headless Chromium over the real compiled CSS, the shape `spread-row-plan.md` §7 used.
O4b: the badge-versus-placer question is a **placer rule tested in `MapLabels.test.jsx`**, not a
Chromium measurement — the placer runs over live Leaflet rects a static fixture does not have, and
the signed-in app is off limits to a session (the `cannot-sign-in-for-browser-checks` memory), so
every phase's PR body says "measured in a fixture, not seen signed-in" and the owner's browser
check is owed at the end: the EARLIER group, the PAST pill, the block's six states, the badge at a
dense zoom, the admin list.

---

## §8 Process

Each phase is a **fresh Sonnet session in its own worktree**, launched by the planning session with
this plan's §3 phase text, the relevant §1/§2/§4/§5 entries and the spec pasted in (the plan is on
main once O0 merges; an agent's worktree is cut from `origin/main`, so a dependent phase must not
start until its predecessor's PR has MERGED). The session: builds → runs the gate → runs its review
lenses in the foreground → fixes → measures (§7) → writes its changelog.d entry and its phase-log
row → commits (conventional commit, the attribution trailer) → pushes its branch → opens the PR
(`gh pr create`, body ending with the generated-with line) → watches CI and Codex → fixes anything
either raises, pushing follow-up commits and asking `@codex review` after each → merges with
`gh pr merge --squash --match-head-commit "$(gh pr view <N> --json headRefOid -q .headRefOid)"`
only when CI is green AND Codex has answered for the HEAD sha. ⚠️ **Codex clean** is the "Codex
Review Summary" comment's row reading `✅ Completed` on the head's short sha **with no Codex review
posted against that sha** — the row names the head sha while still 🔄 Running, so the status word
matters; a 👍 reaction on the PR body is the same answer; 👀 is in progress, never clean; a
force-push does not re-trigger it. The planning session's `watch-pr.sh` applies exactly that rule.
The planning session verifies each merge on `origin/main` before launching the next phase. The owner
is told only when a phase is genuinely blocked (a Codex finding the session cannot resolve with
cited evidence, a CI failure outside the diff) — never for a decision §5 already takes.
