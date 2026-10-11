# Ask PhotoCast — the night (astro conditions, Bortle and aurora as a tool)

**Status (2026-10-11):** **N0 merged** (#1093, the astro schedule). **N1 merged** (#1099) (branch
`feat/ask-night-tool`): the snapshot's nights, `AskWindowKind`, `rank_night_spots`, `list_windows` night rows,
the prompt block, the night eligibility rule and `canSeeAurora` threading, the thread's `nightVersion` reset and
the stub. **N3 merged** (#1098) (branch `feat/ask-night-client`): the night pick card, the night
payload fetch, the Ask-Map handoff both ways and the `nightVersion` echo. **N4 built** (merged as #1103; branch
`feat/ask-night-docs`, stacked on N1 and N3): `AskNightRegressionTest` (assertions pending the owner's
approval, never run) and the docs sweep, including the "As built" notes in §3 below. **N2 built, PR pending** (branch
`feat/ask-ready-night-sky`): the Ready `NIGHT_SKY` question, its night version and the astro job's night-only
precompute dispatch (§3). N0–N4 built; N2 PR pending.
Where the code and this plan differ, the "As built" notes in §3 and CLAUDE.md's *Ask — the night tool* and
*Ask — the client* say what shipped; the sections above them are the intent.

Owner ask, 2026-10-11: *"are there clear skies on the durham coast tonight?"* failed (the model
explored for four turns and never submitted — fixed separately by forcing `submit_answer` on the
last turn) and would, after that fix, have been an honest can't-answer: Ask's five tools reach only
the solar forecast and the hot topics. But the app already serves, per location and night, an
**astro-conditions** score (cloud, visibility, moonlight; `AstroConditionsService`,
`GET /api/astro/conditions?date=`), the location's **Bortle class** and **sky brightness**, and —
for PRO/ADMIN — the **aurora forecast result** (the Claude-rated 1–5★ per location for a night,
`aurora_forecast_result`, `GET /api/aurora/forecast/results`). The owner: *"we have the Bortle
ratings and astronomy forecasts, so we have the mechanisms"* — yes; what is missing is a tool.

This plan adds **one tool, one Ready question, and the client's night pick card**, on the same
contract the solar picks already use. Builds on `ask-photocast-plan.md` and `ask-thread-plan.md`;
read their "As built" notes first.

---

## §1 Invariants that do not move

1. **The validator decides everything.** A night pick is served only if `rank_night_spots` returned
   it in this conversation (or the thread offered it) AND it is still night-eligible against the
   live data at answer time (§2.4). Every card fact is re-joined from served data.
2. **Role gates are the API's, exactly.** Astro conditions and Bortle are served to every role today
   (`/api/astro/conditions` is Bearer, no role gate); aurora results are PRO/ADMIN
   (`AuroraForecastController`). The tool withholds aurora figures from a LITE asker, so nothing
   PRO-only reaches LITE through Ask (CLAUDE.md's existing rule — "no aurora-score tool" — becomes
   "an aurora figure only for the roles the API serves it to").
3. **A Ready answer is shared by every user**, so a Ready night answer carries astro and Bortle only,
   never an aurora figure.
4. **Honesty**: a night with no astro run says so; a location with no aurora result for that night
   has no aurora figure; a quiet aurora night says quiet (the alert level), never "possible".
5. **No new store**: the snapshot reads the same rows the Map's night rows read; the client joins
   night picks to the astro/aurora payloads for the two nights, fetched once at the shell (§2.6 —
   the Map's own copies are `useState` inside `MapView`, not a cache the Plan tab can read).
7. **Tonight is a night, not a date** — defined by `AuroraForecastRunService.currentNightDate()`
   (the running night until nautical dawn), never the UK civil date (at 00:30 the civil date would
   name the unscored NEXT night and drop the one in progress). Tomorrow night = that date + 1.
6. The thread, the budget, the guards, `tool_choice`, the body limit — untouched.

---

## §2 Design

### §2.0 N0 — the data must exist first (found in review, 2026-10-11)

Production has **no `astro_conditions` row since 2026-07-31**: `AstroConditionsService` is
written only by the hand-started synchronous engine (`ForecastCommandExecutor`, O-22) and has no
scheduler row. `aurora_forecast_result` has rows only for nights an admin ran `POST
/api/aurora/forecast/run` or the `aurora_batch_evaluation` job scored (220 rows for 2026-10-10, the
Kp 6 night — that is where "182 of 220 dark-sky locations" came from); a quiet night has none.
Bortle coverage is complete (263 of 263 enabled locations, every region), so the "dark-sky only"
filter admits everything. A tool over data that is usually absent would answer "no night forecast
yet" most nights, so **N0 schedules the astro job**: a `DynamicSchedulerService` job
`astro_conditions_refresh` (seeded by a migration like `wildlife_comfort_refresh`), once a day at
**15:30 UTC** (after the 14:00 intraday cycle, before dusk), evaluating **tonight and tomorrow
night** for every enabled location with a Bortle class, through `AstroConditionsService
.evaluateAndPersist(dates)` exactly as the engine calls it — Open-Meteo only, no Claude cost, and **N2 makes this job itself dispatch `AskReadyPrecompute` after a
successful run** (background executor, 5-minute deadline, nothing thrown reaches the job, counted
against `ready.max-cycles-per-day`), because `PipelineOrchestrator` dispatches the precompute only
after ITS cycles (14:00, 18:00) and §2.5 versions `NIGHT_SKY` on this run's timestamp — without the
dispatch the 14:00 answer is withheld from 15:30 until the 18:00 cycle rewrites it — one
`job_run` of `RunType.WEATHER`-style accounting (decide the run type by reading how the engine
records it today). An upsert per (location, date) — see the two corrections below §2.0: the running night is left as scored before dawn, and stale rows of now-ineligible locations are excluded by N1's query, not deleted here. Aurora rows stay as they
are (the poller's alert decides when a night is scored; a quiet night is reported from
`AuroraStateCache`'s level, §2.2).

⚠️ **Two N0 corrections from review (round 3).** (1) **Pre-dawn "Run now" must not rescore the
running night**: `OpenMeteoClient.fetchForecastBatch` returns hours from UTC today, and
`AstroConditionsService.coversWholeNight` checks only that the array reaches the DAWN date, so a
run between midnight and nautical dawn would score yesterday's night on its post-midnight hours
alone and overwrite the complete row the 15:30 run wrote. The job therefore scores the nights
`max(currentNightDate(), today)` and that + 1 — before dawn that is today's and tomorrow's nights,
the running one left as scored; the scheduled 15:30 run is unaffected (`currentNightDate()` is
today). (2) **`evaluateAndPersist` is an upsert per (location, date), not a replace-per-date**: it
loads existing ids and saves only the locations it rescored, never calling `deleteByForecastDateIn`,
so a location disabled or stripped of its Bortle class after a run keeps its old row for that date.
N0 does not change the service; instead the N1 snapshot query joins the location and takes only
`enabled = true AND bortle_class IS NOT NULL` rows, **and only the rows of the night's LATEST
GENERATION**: `evaluateAndPersist` stamps one `Instant now` per call (line ~90) onto every row it
writes, so a night's generation is `max(runTimestamp)` for that date and a spot is a row whose
`runTimestamp` equals it — a run that fails for some Open-Meteo chunks (each chunk's failure is
caught per location, ~114) leaves the failed locations' rows from the previous afternoon, when D was
"tomorrow", beside the fresh ones, and a date filter alone would rank a day-old clear-sky forecast
with today's. (The Map's `/api/astro/conditions` keeps its existing, unfiltered read — a
pre-existing surface, out of scope here.)

⚠️ **(3) The Ready precompute must follow the astro refresh** (round 6): the pipeline dispatches
`AskReadyPrecompute` after its own cycle, but this job is standalone, and §2.5's version rule means
the 15:30 run's new `run_timestamp` withholds the `NIGHT_SKY` answer the 14:00 cycle wrote — for
the whole evening, until the 18:00 cycle. N2 therefore has `AstroConditionsRefreshJob` dispatch
the precompute after a SUCCESSFUL refresh (the orchestrator's own shape: background executor,
5-minute deadline, nothing thrown reaches the job; counted against `ready.max-cycles-per-day` like
a scheduled one). N0 itself does not touch Ask.

### §2.1 The snapshot — `AskSnapshot.nights`

`AskSnapshotBuilder` adds `nights: List<Night>` for **tonight and tomorrow night** — tonight per
§1.7's `currentNightDate()` rule (the two nights the Map's window control carries: `mapEvents.nightRow` for `ASTRO` and
`AURORA`), each `Night(date, kind ASTRO|AURORA, windowId, nightStart, nightEnd, spots:
List<NightSpot>)`:

- `ASTRO` night for date D exists when `astro_conditions` holds rows for D (the Map's
  `available-dates`), read through a NEW `AstroConditionsRepository.findByForecastDateFetchingLocation`
  (a `JOIN FETCH` of the location and its region, the shape of the aurora query below) — never the
  existing `findByForecastDate`: `AstroConditionsEntity.location` is `LAZY`, and `AskReadyPrecompute`
  builds the snapshot on the background executor outside any transaction, so reading the name,
  region, coordinates or Bortle off a lazily-loaded location there is a `LazyInitializationException`
  and no `NIGHT_SKY` answer is ever written; its spots are those rows: `NightSpot(locationId, name, regionName, lat, lon,
  astroStars, astroSummary, cloudExplanation, visibilityExplanation, moonExplanation, moonPhase,
  moonIlluminationPct, bortleClass, skyBrightnessSqm, canopy)` — `regionName` is NEVER null here:
  the builder SKIPS a row whose location has no region (`LocationEntity.region` is optional, the
  admin form allows "None", the query is `LEFT JOIN FETCH l.region`), exactly as the briefing
  collector never walks `unregioned()`, and it skips a row whose location is now disabled or has
  no Bortle class, for astro AND aurora rows alike; `nightStart/End` from the row
  (`nauticalDuskUtc`/`nauticalDawnUtc`, the same `resolveNightWindow` the DTO uses). The rows come
  through the fetch-joined, enabled-and-Bortle-filtered query named above — the DTO path's
  `findByForecastDate` is never used here (lazy location, background precompute). ⚠️ **Two more
  exclusions, both the briefing's own rules**: a location with NO region is dropped (the collector
  walks `eventSummary.regions()` and never `unregioned()`, `LocationEntity.region` is optional and
  the admin form allows "None", so the query is `LEFT JOIN FETCH l.region` and the builder skips a
  null region rather than throwing on `regionName`); and a **canopy-only** location
  (`LocationEntity.isWoodlandOnly()` — V169's northern bluebell woods are enabled AND carry a Bortle
  class) is dropped, because a wood's star means the opposite of a sky star — `NightSpot` carries
  `canopy` so the rule is visible, and `isPickEligible`'s night arm repeats the solar arm's
  non-canopy test.
- `AURORA` night for date D exists when `aurora_forecast_result` holds non-simulated rows for D
  (`findByForecastDateAndSimulatedFalseFetchingLocation`); its spots carry `auroraStars`,
  `auroraSummary`, `triaged`/`triageReason`, `alertLevel`, `maxKp`, plus the location's Bortle and
  — when the same location has an astro row for D — that astro figure beside it. ⚠️ That query
  filters only date and `simulated`, so the builder applies the SAME eligibility it applies to astro
  rows (location enabled, Bortle class present, region present, not canopy-only) before a row
  becomes a spot — a location disabled or de-Bortled after the aurora run otherwise stays
  recommendable until the next run replaces the date.
- **Window ids are Ask's own** `YYYY-MM-DD_astro` / `YYYY-MM-DD_aurora` (`AskWindowId.parse` splits
  on the first `_`, so the two night kinds extend its `switch` without touching the solar arms; the
  thread's `PickRef` validation follows). The Map's night rows use a different id
  (`astro:2026-10-11:ASTRO`), and that does not matter: every door already resolves a window by
  **(date, kind)** — `WindowFirstMapPane` sends `eventType` + `date`, `MapView.findEvIndex` looks it
  up — so a night pick opens the Map's night row — ⚠️ **but not through the Ask handoff as it stands**:
  `MapView`'s `askWindow` effect (~3679–3685) resolves the row by (date, kind) and then calls
  `onSelectDate(row.date, { isNight: false })` unconditionally, where the ordinary night-row path
  passes `isNight: true` (~3090). Before nautical dawn `currentNightDate()` is YESTERDAY, so `App`
  would read an Ask night pick as a stale solar date and refuse it. N3 changes that handoff to pass
  `isNight: row.kind !== 'solar'` (and `setLocalNightDate` the row's date for a night row), with a
  test that fires an `_astro` pick for yesterday's running night before dawn — and the same effect
  must NOT consume the nonce until the row exists (see the ⚠️ at the end of this bullet).
  The Map-to-Ask direction needs the same: `utils/askMapContext.js`'s `askWindowOf` returns `null`
  for every non-solar row, so a question asked while the Map shows tomorrow's ASTRO or AURORA row
  carries no `windowId` and "is this any good?" cannot mean that night; N3 extends `askWindowOf`
  and `buildMapAskContext` to publish the Ask-local night id (`date_astro` / `date_aurora`) and
  its label. ⚠️ **And the handoff must wait for the night rows**: that same `askWindow` effect
  marks `appliedAskNonce.current` BEFORE the lookup and depends only on the nonce and pane
  visibility (~3673–3690), while a freshly mounted Map's astro/aurora rows arrive asynchronously
  from the availability/result fetches — so a "Show on map" from the Plan tab would consume the
  handoff with the row still absent and never apply it. N3 marks the nonce applied only once
  `findEvIndex` finds the row (re-running when `mapEvents` changes), tested with a Map whose night
  rows load AFTER the handoff fires, not a preloaded one. **The kind is an Ask-local
  `AskWindowKind {SUNRISE, SUNSET, ASTRO, AURORA}`, NOT a new `TargetType` value**: `TargetType` is
  persisted in `forecast_evaluation.target_type` (`@Enumerated(STRING)`, the `LocalH2EnumWidener`
  trap) and must not grow for an Ask-only need; `AskPick.targetType` is serialised as the kind's
  name so the client's `windowKey(date, targetType)` and `askWindow.eventType` keep working.
- The snapshot stays user-less and memoised 30 s as now; aurora rows are IN it, and the **tool**
  withholds them by role (§2.2) — AND so do the two other readers that see a snapshot without an
  asker: `AskThreadContext.of` (a client could echo a forged `_aurora` `PickRef`) and
  `AskAnswerValidator.validate`/`AskReadyFreshness.recheck` (which re-join the star into a card).
  Night eligibility therefore takes an explicit `canSeeAurora` boolean derived from
  `AskUserContext.role` (`userLess()` → false), threaded to all three; a LITE asker can never be
  offered, served or re-served an aurora pick. The Ready precompute's conversations are user-less →
  astro only.

### §2.2 The tool — `rank_night_spots`

Arguments: `night` (`"tonight" | "tomorrow"` or an ISO date among the snapshot's nights), `kind`
(`"astro" | "aurora" | "any"`, default `any`), `regions` (names, as `rank_spots`), `minStars`
(1–5, default 3), `maxBortle` (1–9), `maxDriveMinutes`, `nearHome` (both exactly as `rank_spots`;
`personal` set the same way), `limit` (≤ 10).

Result, per spot, from the snapshot only: `locationId`, `name`, `region`, `windowId`, `nightStart`,
`nightEnd` (London `HH:mm`; from the astro row — the aurora entity has no window columns, so an
aurora-only spot carries the night's astro window when one exists, else none), `bortle`,
`astroStars`, `astroSummary` (capped), `moon` (phase + illumination), `clearSky` (the astro cloud
explanation — this is what "clear skies tonight?" is asking), and **only for PRO/ADMIN**:
`auroraStars`, `auroraSummary`, `alertLevel`, `maxKp`; for a LITE asker the result carries
`auroraAvailable: false` with the note *"aurora ratings are a Pro feature"* so the model says so
instead of guessing. The result also carries, for PRO/ADMIN, the night's **aurora state** read
from `AuroraStateCache` (the alert level and tonight's max Kp; `isSimulated()` → nothing) so a quiet
night with no result rows is reported as quiet rather than unknown — ⚠️ **for the night that
resolves to `currentNightDate()` ONLY**: the cache holds no date-keyed state (its level and trigger
Kp are "the rest of tonight", `AuroraStateCache` ~282–295), so `night: "tomorrow"` or a later date
gets `auroraState: "not forecast yet"` from the result rows alone, never tonight's state relabelled. Sorted by the best of (astro,
aurora) stars, then drive. The empty answers are per KIND, never blanket, and **`any` is the UNION of
whichever kinds have rows** (empty only when neither does): `kind: "astro"` with no astro rows for
the night answers `{spots: [], note: "no astro forecast for <night> yet"}`; `kind: "aurora"`
returns the aurora-only spots whenever aurora result rows exist for that night, and so does `any`,
with the astro note carried as `note` beside them — an admin or batch aurora run can succeed on a night the scheduled astro job
failed, and that persisted PRO/ADMIN result must not be hidden behind the astro table's absence; a
region with rows elsewhere but none in it answers `note: "no night-scored places in <region>"`. `regions` are matched by `canonicalRegion`, whose name set is
the UNION of the solar windows' and the nights' regions (today it reads solar windows only). ⚠️ **A night answer is never stored in the typed cache** (`AskTools` marks the conversation
`uncacheable` the moment `rank_night_spots` is called, whichever branch runs — the same place
`personal` is set; `AskService` reads it like `personal` and skips the store): the typed cache keys
on scope/date/question/window/user with no role, `lookup` reads the shared key before the per-user
one, and freshness compares only window, eligibility, rating and verdict — so a cached night answer
could (a) hand a LITE asker's figure-less answer to the next PRO asker with the same words, (b) keep
serving an aurora figure in its retained `summary`/`why` to a user downgraded inside the TTL, and
(c) go on saying "alert active, Kp 6" after `AuroraStateCache` has gone quiet or the astro
refresh changed `clearSky` without moving the integer star. Each of those was a review finding; one
rule closes all three. The cost is one Claude call per repeat of a night question, inside the
allowance and the spend cap. (`roleBound`, the earlier design, is withdrawn.) ⚠️ **The thread needs the same discipline**
(round 7): `AskThread.staleAgainst` compares only the briefing `generatedAt`, and
`AskThreadContext.exchanges()` renders an earlier answer's summary verbatim, so a follow-up could
repeat "active, Kp 6" or a clear-sky claim after the state cache went quiet or the 15:30 refresh
moved the forecast. A night answer therefore carries `nightVersion` on the wire (the astro
generation of each night it named plus the aurora state level at answer time, an opaque string the
client echoes on the exchange, exactly as `generatedAt` is echoed), and ANY exchange carrying a
`nightVersion` that is not the live one — typed OR Ready-origin, since `staleAgainst`'s Ready
exemption exists because a Ready exchange's picks re-join live, which a verbatim night summary does
not — drops the whole thread with `threadReset: true`, `threadResetReason: "night forecast
updated"` (the forecast-moved rule, one more trigger), and `AskThreadContext` never renders a
night exchange's summary verbatim: it re-joins the exchange's night picks live, like every pick. The Ready `NIGHT_SKY`
answer is the one night answer that IS stored (`ask_ready_answer`), and its freshness identity
(§2.5) therefore carries the night's own version: the max `runTimestamp` (`run_timestamp`) of the astro rows the
answer was written from, withheld whole when the live max differs — astro only, no aurora, no
state-cache prose. The tool records `(locationId, windowId)` as
evidence exactly as `rank_spots` does.

### §2.3 The prompt

The system prompt's tool guidance gains one block: *night questions — aurora / northern lights /
stars / milky way / dark sky / "clear skies tonight" / Bortle — call `rank_night_spots`; "clear
skies tonight" is the astro cloud forecast, not the solar one; never claim an aurora is visible
without an `auroraStars` figure for that place and night; when the result says aurora ratings are
a Pro feature, say so in one clause; a night pick's `why` quotes the served clear-sky and Bortle
facts.* `list_windows` gains the night windows (kind, label "Tonight · astro", "Tomorrow night ·
aurora") so the model can resolve "tonight" — ⚠️ **AURORA windows only when `canSeeAurora`**: an
aurora window's existence is derived from `aurora_forecast_result`, whose endpoints (results and
available dates) are PRO/ADMIN, so a LITE conversation (and the user-less Ready one) lists astro
windows alone; `canSeeAurora` therefore reaches `list_windows` as well as `rank_night_spots`, the
thread re-join and the validator. The fresh-question golden prompt files move once (a
new tool + two windows) — regenerate and say so.

### §2.4 The validator and the picks

`AskSnapshot.isPickEligible` gains the night rule: a `(locationId, windowId)` on an ASTRO night is
eligible when `astroStars ≥ 4` (⚠️ the astro template's BASE score is 3★ — "no factor moved it" —
so 3 is not a good night the way a Claude-judged 3★ sunrise is; 4 means cloud, visibility and moon
all left it better than the base); a canopy-only location (`NightSpot.canopy`) never, as for a solar pick; on an AURORA night when `auroraStars ≥ 3` and `canSeeAurora`
(§2.1; a LITE asker is never offered, served or re-served an aurora pick); a `triaged` aurora row is
never eligible. `AskPick.targetType` carries the `AskWindowKind` name (§2.1), `ratingAtAnswer` is
the eligible figure, `verdictAtAnswer` is `null` (nights have no GO/MARGINAL/STANDDOWN word — the
card draws the star and the kind).
The ranking/thread rules apply unchanged (distinct by location+window; a night and a sunrise at the
same place are two windows). `AskReadyFreshness` re-checks a night pick against the live night
data (a re-run that drops a place's stars below 3 withholds the answer, as for solar).

### §2.5 The Ready question — `NIGHT_SKY`

Freshness (serve-time, all-or-nothing like every Ready answer): the stored answer carries
`astroVersion` (the max `AstroConditionsEntity.runTimestamp` — column `run_timestamp`, the
entity's only generation instant — over the night's astro rows at write time, persisted with the
Ready row) and is
withheld whole when the live generation (§2.0's `max(runTimestamp)`) differs, when any pick's live astro star differs from the stored
one, when a pick is no longer night-eligible (ASTRO ≥ 4), or when the night has passed (nautical
dawn, `currentNightDate()` moved on). It never carries an aurora figure or state-cache prose.

"Anything for the night sky tonight?" — offered only when tonight's ASTRO night holds at least one pick-eligible (≥ 4★) spot IN THE
SCOPE being precomputed — not merely when astro rows exist, since every offered scope/question is a
paid engine call and `ReadyRelevance` rejects a pick question with no pick afterwards;
tabs: Map and Plan; relevance admits only night picks and the `AURORA` hot topic; computed user-less
(astro only); withheld at serve when tonight's astro run is gone or any pick's stars moved.
`KeywordAskIntentMatcher` learns its words (`night sky`, `stars`, `stargazing`, `milky way`,
`dark sky`), strictly as the others.

### §2.6 The client — a night pick card

`utils/askModel.buildPickCards` joins a night pick by (date, kind) to the two nights' astro and
aurora payloads instead of the briefing days — ⚠️ its guard `!meridiemOf(pick.targetType)` drops
every non-solar pick today; it must gate on the KIND, not the meridiem. The payloads are fetched
ONCE at the shell level, **for the night DATES the live answer's picks name** (each night pick's
`windowId` carries its date and kind, so the shell issues `GET /api/astro/conditions?date=` per
distinct astro date and, for PRO/ADMIN, `GET /api/aurora/forecast/results?date=` per distinct
aurora date; LITE never requests the aurora ones, and never needs to know which date "tonight" is
— ⚠️ it cannot: `currentNightDate` is served only by the PRO/ADMIN aurora status endpoint and
`resolveAuroraNight(null)` falls back to the civil date, so a client-computed "tonight" would omit
the running night's payload before dawn and `buildPickCards` would drop a valid pick) — the
Map's own `astroConditionsByDate`/`auroraResultsByDate` are `useState` inside `MapView`, not a cache
the Plan tab or the dock can read, and nothing on the Map uses SWR. An aurora window draws no map
chips and the camera still fits (CLAUDE.md, unchanged); an astro window draws them. the card reads **`Tonight · 21:40–05:10 · Bortle 4`**, a `4★ sky` pill (astro) and,
for PRO/ADMIN with a result, `aurora 3★` beside it; the `why` is the model's; the summary line the
served astro summary. `pickLabel`/`meridiemOf` learn the night kinds (no AM/PM word). "Show on map"
hands the night `windowId` to the Map, whose window control already selects astro/aurora rows
(through the corrected handoff and the extended `askWindowOf` in §2.1 — `isNight` per row kind,
the night id published as request context);
"Plan this" shows the night window's start/end and the drive (`askPlan` extends `departureWithDay`
for a night start). The multi-rank chip, the thread stack, the peek — unchanged. A pick whose night
data the client does not hold (the Map has not fetched that night) is dropped, never half-drawn —
the Plan tab must therefore fetch the two nights' astro/aurora payloads when Ask is open (one GET
each, cached by SWR as the Map does; LITE gets 403 on aurora and the card shows astro only).

### §2.7 Not built

Reconciled with what shipped (N0-N4; 2026-10-11). Still not built, by design:

- No new scoring: the tool reads the astro and aurora rows as scored; no "clear skies" derivation
  of its own.
- No per-hour sky; the night window and the cloud explanation are what the row holds.
- No night Ready answer with aurora figures (shared answers stay role-free).
- No change to the aurora poller or its schedule (N0 adds the ASTRO schedule — that is the one
  schedule this plan does change); a night with no aurora run is answered from the state cache
  (tonight) or as "not forecast yet" (later nights), never synthesised.

Not built, decided during the build (named so they are not refiled as bugs):

- **"Open in Plan ›" is not drawn for a night pick** (the plan was silent): the location sheet's rows are all
  solar, so the press would land on a place's sunrises under a pick about its sky; "Show on map ›" is the
  night's door. A night pick highlights no Plan card either.
- **LITE has no night-in-progress signal on the Ask → Map handoff** (O-21): only the PRO/ADMIN aurora status
  serves `currentNightDate`, so before nautical dawn a LITE reader's `_astro` pick for yesterday's running
  night finds no Map row and the handoff waits unspent.
- **A failed night payload fetch drops that night's cards silently** until the next answer (the rule for a
  pick with no data: dropped, never half-drawn).
- **The night card's pills are untested at 320px in a real browser** (jsdom/CSS tests only).

---

## §3 Phases (Sonnet agents, the usual loop: worktree → gate → PR → Codex clean + CI + CodeQL → merge)

| Phase | Scope | Size |
|---|---|---|
| **N0 backend — the astro schedule** | §2.0: `astro_conditions_refresh` job (migration seed + `DynamicSchedulerService` target, 15:30 UTC, the nights `max(currentNightDate(), today)` and +1, every Bortle-enriched enabled location, `evaluateAndPersist`); its `job_run`; an admin "Run now" works through the scheduler as every job does. Tests mirror `WildlifeComfortRefreshJob`'s. **As built (N0, #1093, merged):** `AstroConditionsRefreshJob` (`astro_conditions_refresh`, seeded by V170, `0 30 15 * * *` UTC) scores `max(currentNightDate(), UK civil today)` and that + 1 for every enabled Bortle-enriched location through `evaluateAndPersist`; its `job_run` is `RunType.WEATHER` with a null model (no new enum value). It deletes nothing (the service upserts per location and date), so N1's query excludes the stale rows. A failed Open-Meteo fetch is swallowed inside the service (WARN, 0 scored), so the job cannot tell it from "no locations"; only a thrown exception fails the run. Local dev has no scheduler row (no migrations run locally). | S–M |
| **N1 backend — snapshot, tool, prompt, validator** | §2.1–§2.4: `AskSnapshot.nights` + builder read (same repositories the DTOs use), `AskWindowId` night kinds, `rank_night_spots` in `AskTools`/`AskToolSchemas`/`AskToolArguments` (role-gated aurora), `list_windows` night rows, prompt block, `isPickEligible` night rule, `AskPick` night kind, `AskReadyFreshness` night re-check, thread re-join of a night pick ("no longer in the forecast" when the run is gone). Goldens regenerated. Tests for every rule; a real-API check (≤ 3 paid runs) of the owner's question "are there clear skies on the durham coast tonight?" with a fixture that gains an astro night and an aurora night (PRO asker → aurora figure; LITE asker → "Pro feature" clause, no figure). **As built (N1, #1099):** `AskSnapshot.nights` (`Nights(tonight, nights, aurora)`; `Night(id, date, kind, start, end, generation, spots)`; `NightSpot` carries the astro and aurora figures, `canopy`, `triaged`), read by `AskSnapshotBuilder` fail-soft through the new fetch-joined `AstroConditionsRepository.findByForecastDateFetchingLocation` and the existing non-simulated aurora query; `AskWindowKind {SUNRISE, SUNSET, ASTRO, AURORA}` with `AskWindowId` learning `_astro`/`_aurora`; `rank_night_spots`; `list_windows` night rows; the prompt block; `AskSnapshot.isNightPickEligible` and `resolvePick`; `AskUserContext.canSeeAurora()` threaded to the tool, `list_windows`, the validator, `AskThreadContext` and `AskReadyFreshness`; `AskNightVersion`; the stub. **Where the code differs from the plan above:** (1) the argument is `regionNames`, not `regions`, and `night` also takes `tomorrow night`; (2) a spot carries `astroWindowId` and `auroraWindowId` separately, not one `windowId`, because a pick is held to the window of the figure it cites; (3) `AuroraStateCache` has no `isSimulated()`: the test is `getSimulatedData() != null`; (4) a wood is dropped by the builder, and `NightSpot.canopy` stays as the defence in `isNightPickEligible`; (5) the tool's `minStars` default is 3 (§5 Q2) while the astro pick bar is 4, so the tool can list a 3-star sky the validator will not accept as a pick, and the tool description tells the model so; (6) `AskOutcome` gained `uncacheable` and `nights`, and `AskResponse` gained `nightVersion` (`NON_NULL`) and a `threadResetReason` of `"night forecast updated"`; (7) the `nightVersion` format is the server's own, `n1;<date>=<astro generation ms|->[;a=<LEVEL|->]`, validated on the way in (400 `INVALID`, at most 200 characters), and the aurora part is present only for a PRO/ADMIN asker with tonight among the nights, so a LITE thread never resets for a state they were not told; (8) a well-formed night `windowId` the snapshot does not hold (an ended night the Map keeps on screen) is context, never a 400: it is dropped and the question answered, exactly as a passed solar window is. The three tool-schema golden files moved once. Gate: `clean verify` with the integration classes excluded, 11,700 backend tests, 0 failures, 1 skipped; no paid real-API run. `AskThreadRegressionTest` changed in two lines only (the pick's `targetType` is now an `AskWindowKind`). | L |
| **N2 backend — Ready `NIGHT_SKY`** | §2.5: the constant, availability, relevance, matcher words, precompute (astro only), serve-time withholding; tests. **N2 built, PR pending** (branch `feat/ask-ready-night-sky`). *As built — where the code differs from the text above:* (1) the stored `astroVersion` is the whole `AskNightVersion` string (`n1;<date>=<max run_timestamp millis>`), not the bare generation instant, so ONE `isLive` test answers both "the astro job re-ran the night" and "the night has passed"; it lives in `answer_json` as a property beside the answer (no column, no migration — `AskReadyStore` strips it before decoding, and a row without it reads as null and is withheld). (2) `AskReadyFreshness`'s first rule tested `live.window(id)`, a solar-only lookup the plan never named: a night window id was always "passed", so the rule now asks `hasWindow`. (3) Availability is `AskSnapshot.nightCandidates(tonight's ASTRO night, scope, false)`, the night sibling of `candidates(window, scope)`, so the offer and `admitsPick` cannot disagree. (4) `admitsPick` refuses any pick whose `targetType` is not ASTRO or whose window is not the offered one, re-reading the live spot with `canSeeAurora = false`; `admitsEvent` keeps `AURORA` and nothing else. (5) Tabs are `plan, map` (the `BEST_*` order), not `map, plan`. (6) The matcher requires "tonight" exactly once, and accepts `night sky`/`night skies`, `dark sky`/`dark skies`, `milky way`, `clear sky`/`clear skies`, `clear`, `star gazing`, `stargazing`, `stars`; "aurora", "Bortle", a place, a drive word or another night go to the engine, and a night spot's name made of ordinary words declines the match. (7) The version rides the Ready QUESTION as `nightVersion` (omitted when null); N3's `openReady` already reads it there. (8) §2.0 (3)'s dispatch counts a SCHEDULED astro run against `ready.max-cycles-per-day` and exempts an admin's "Run now" (the job's manual flag is recorded, so the admin endpoint's own rule could be mirrored). (9) The dispatched precompute is NIGHT-ONLY: `AskReadyPrecompute` gained an optional `Set<ReadyQuestion>` filter (the unfiltered callers are unchanged) and the job passes `{NIGHT_SKY}`, so after an astro run no other stored answer is touched and the cost is at most regions + 1 Haiku conversations; it is still one `ASK_READY` job run, one at a time, and counted against the ceiling when scheduled. | S–M |
| **N3 frontend — night pick card** | §2.6: the night join, the card, label/kind words, the Plan tab's night fetches, "Show on map"/"Plan this" for a night window; adversarial review before merge (a11y of the new pill, the LITE card without aurora, the 266px phone card). **As built (N3, #1098):** `utils/askModel.buildPickCards` gates on the KIND (the old `!meridiemOf(...)` guard dropped every non-solar pick) and joins a night pick by date and kind to the night payloads, dropping a pick with no payload row; `hooks/useAskNights.js` + `utils/askNights.js` fetch `GET /api/astro/conditions?date=` per distinct astro date and, for PRO/ADMIN only, `GET /api/aurora/forecast/results?date=` per distinct aurora date, **the dates the live answer's picks name**, held in `AskContext` state (nothing in `swrCache`/`localStorage`). **Where it differs from the plan:** (1) the fetch lives in `AskContext`, not at the shell, because that is where the picks and their dates are known; (2) the verdict slot shows the star pills ("4★ sky", "aurora 3★") and the kind badge reads `ASTRO`/`AURORA` (the Map's own night kind chips), not the `SKY`/`AURORA` word §5 Q3 chose; (3) "Open in Plan ›" is not drawn for a night pick (§2.7); (4) `nightLabel` is civil-date based, so before dawn the running night reads by its day name, like the Map pill. (5) the aurora pill beside an astro pick appears only when an aurora pick on the same date made the client fetch that night's aurora payload (§2.6 had it for any PRO/ADMIN pick with a result). The Ask → Map handoff (`MapView`'s `askWindow` effect) now passes `isNight` per row kind and does not consume the nonce until `findEvIndex` finds the row; the Map → Ask direction (`askWindowOf`) publishes the Ask-local night id and a kind-naming label. The thread echoes `nightVersion` per exchange only when present, and `"night forecast updated"` renders its own sentence. Gate: lint, 8,994 Vitest tests in 355 files, `npm audit --audit-level=high` and build, all exit 0, lockfile unchanged; mutation-checked on the branches named in the PR. **Tested, not seen**: no browser sign-in is possible for an agent, so the pills at 320px, the badge colours, the live Plan → Map race and the Leaflet chips are pinned by jsdom/CSS tests only; the owner's browser check is still owed. | M–L |
| **N4 docs + regression** | CLAUDE.md "Ask — the night" bullet (folded, not fragments), the Roles note, plan status line; `AskNightRegressionTest` (pending the owner's approval) with the owner's question for a PRO asker and a LITE asker. **As built (N4):** `AskNightRegressionTest` beside `AskThreadRegressionTest` (same `prompt-regression` tag, same real `ClaudeAskEngine` over a fixture snapshot, header "assertions pending the owner's approval, 2026-10-11"; it test-compiles under `-Pprompt-regression` and was **never run**, since it bills the key and none was available to the build). The fixture is two nights: tonight astro (Seaham 5, Easington 4 and Hartlepool 3 on the Durham coast, Allenheads 4 inside the home radius, Kielder 5 far inland, Dunstanburgh 4, and a canopy-only wood with Bortle 2 and 5 stars that must never be picked), tonight aurora (Dunstanburgh 4, Allenheads a triaged row; alert MODERATE, Kp 6) and tomorrow astro (Allenheads 5, Kielder 4, Seaham 3, no aurora rows). Five scripts, invariants only: (a) the owner's question, (b) the aurora question as PRO and as LITE, (c) tomorrow night, (d) "anything closer to home?" carrying an earlier night exchange with a live `nightVersion`, (e) the same with a stale one. (d) and (e) seed their earlier exchange rather than chain a paid call, and (e)'s reset is asserted through `AskThread.nightStaleAgainst` and `AskResponse.NIGHT_THREAD_RESET_REASON` (the rule `AskService` applies; `AskService` needs a database to construct, so its own wiring stays pinned by `AskServiceTest`), then the fresh question runs through the real engine with the empty thread. About seven paid calls. The fixture was checked without the model (versions live and stale, pick eligibility per role). The docs sweep reconciled CLAUDE.md's night bullets with the code. | S |

N0 → N1 → N2 sequential; N3 after N1 (it needs the served pick shape); N4 last. N2's Ready
question is only worth having once N0 has run in production for a night.

## §4 Decisions taken in this plan

1. **One tool, not two** (`kind` argument) — the owner's questions mix "clear skies" and "aurora"
   in one breath; two tools would make the model choose before it knows which the night has.
2. **Aurora withheld by the tool, not by the snapshot** — the snapshot is shared and memoised;
   role belongs to the asker, so the gate sits where the asker is known.
3. **Night eligibility is split by kind — ASTRO ≥ 4, AURORA ≥ 3** (§2.4, and `AskReadyFreshness`'s
   re-check applies the same pair): the astro template's base is 3★, so 3 is "nothing moved it", not
   a good night, where an aurora 3★ is Claude's own judgement like a solar one; `verdictAtAnswer`
   null rather than inventing a night verdict word.
4. **The client joins night picks to the Map's payloads** rather than adding night facts to the
   briefing payload — the briefing is the solar contract and ETag-shared; the night payloads already
   exist per date.
5. **Window ids are Ask's own; the doors resolve by (date, kind)**, which they already do — so the
   map, the thread and the plan work unchanged without the Map's `kind:date:EVENT` id leaking into
   the Ask contract.
6. **N0 schedules astro, and does NOT schedule aurora** — the aurora poller already decides when a
   night deserves scoring; a quiet night is answered from the state cache.

## §5 Open questions — decided for the autonomous build (2026-10-11)

- Q1 Should a LITE Ready `NIGHT_SKY` answer mention that aurora ratings exist for Pro? **No** —
  the Ready answer is shared text; the typed tool result carries the clause instead.
- Q2 `minStars` default 3 — same bar as solar picks.
- Q3 The night pick's "verdict" slot shows the kind word (`SKY` / `AURORA`) in the verdict's place
  so the card's row keeps its shape. **As built (N3):** the stars stand where the verdict does ("4★ sky", "aurora
  3★") and the kind badge reads `ASTRO`/`AURORA`, the Map's own night chips; there is no `SKY` word.

## §5a Adversarial review of this plan (2026-10-11)

Confirmed and folded in: the typed cache would have served a PRO aurora answer to a LITE asker with
the same words (`roleBound`, §2.2); the thread re-join and the validator/freshness re-check see no
asker (`canSeeAurora` threaded to all three, §2.1/§2.4); the astro base score is 3★ (bar raised to
4, §2.4); astro rows exist only for Bortle-enriched places (every place is, in production — §2.0 —
but the per-region note stays); production has had no astro row since July and aurora rows only on
scored nights (N0, §2.0; the state-cache read for quiet nights, §2.2); the Map's night id is
`kind:date:EVENT`, not `date_kind` (§2.1, D5); `TargetType` has no night value and must not gain one
(`AskWindowKind`, §2.1); the Plan tab holds no night payloads and the Map's are not a cache (the
shell-level fetch, §2.6); `buildPickCards`' meridiem guard would drop every night pick (§2.6);
`canonicalRegion` reads solar windows only (§2.2); "tonight" must be the running night, not the
civil date (§1.7); N2 deferred behind N0 in practice (§3). Refuted: the `why` text and the Ready path
as aurora leaks; the in-process memo; `triaged`/`simulated`/date meaning; `departureWithDay` for a
night; schema size (measurable, not a design problem).

Codex, three rounds on this PR (#1091): the shared cache served a LITE answer to a PRO asker
(round 1) → `roleBound` per user (round 2) → still wrong on a downgrade inside the TTL and on live
night facts moving under the TTL (round 3) → **no typed cache for a night answer at all** (§2.2);
the state cache is tonight-only (§2.2); the 4★ astro bar stated once (§4); per-kind empty answers
(§2.2); the astro read must fetch-join the location for the background precompute (§2.1); the Ask
→ Map handoff passes `isNight: false` unconditionally and the Map → Ask context publishes no night
window (§2.1, N3); N0's pre-dawn run and the upsert-not-replace semantics (§2.0).

## §6 Measured (N4)

Per-question cost with the sixth tool in the schema (every request carries it); how often
`rank_night_spots` is called on a solar question (should be ~never); the `NIGHT_SKY` Ready
withholding rate.
