# Ask PhotoCast — implementation plan

A plain-English question box over PhotoCast's own forecasts. The answer is one or two sentences
plus 2–3 **picks** (a location at a window) and/or **event cards** (hot topics), or an explicit
**Not in the forecast**. Picks are linked to the Map (numbered markers, everything else dimmed) and
to the Plan tab.

- **Spec:** `docs/design/ask-photocast/README.md` and `COST_PLAN.md` (the Claude Design bundle,
  vendored 2026-10-05). The four HTML files are design references, not code.
- **This plan:** written 2026-10-05 against `main` at `4c8b3fbe`, then revised after a four-lens
  adversarial review the same day (backend facts, cost and abuse, frontend feasibility, product
  honesty and delegability — 52 findings, §10 lists what changed). Every file, line number and
  symbol named here must be re-verified against the tree by the implementing session.
- **Delegation:** one Sonnet session per phase, prompts in `docs/engineering/ask-photocast-prompts.md`.

---

## §0 Status

The orchestrating session updates this table at merge time. Implementing sessions do **not** edit
it (adjacent rows conflict between open PRs).

| Phase | What | Size | State |
|---|---|---|---|
| P0 | Commit this plan, the prompts file and `docs/design/ask-photocast/` to `main` | XS | not started |
| B1 | Read model, tool functions, answer contract, validator (no Claude, no endpoint) | M/L | merged (#1008) |
| B2a | The engine: Claude tool loop, properties, run types, cost logging | L | merged (#1015) |
| B2b | Stub engine, local fixture seeder, admin dry-run | M | merged (#1017) |
| B3 | Ready answers: catalogue, precompute after the pipeline, `GET /api/ask/ready` | L | merged (#1019) |
| B4 | `POST /api/ask`: allowance, limits, spend cap, `GET /api/user/settings/ask` | L | merged (#1021) |
| B5 | Pre-filter, Ready intent match, typed cache, `ask_log`, metrics endpoint | M/L | merged (#1022) |
| F1a | Client core, unmounted: API, hooks, provider, pick model, conversation and cards | M/L | merged (#1023) |
| F1b | Phone and tablet-portrait entry: ask bar, tall sheet, shell wiring | M/L | merged (#1025) |
| F2 | Desktop: tab-row field, the `/` key, the docked column | L | merged (#1028) |
| F3 | Map linkage: numbered picks, dimming, camera, window follow | L | merged (#1029) |
| F4 | Phone Map: the ask row in the peek sheet | L | merged (#1030) |
| F5 | "Plan this", "Open in Plan", the Plan-card highlight | M | merged (#1031) |
| Z | Sweep: CLAUDE.md, prompt-regression class, measured Verify list, production enable | S/M | merged (#1032) |

Strictly sequential, top to bottom. A phase does not start until its predecessor is merged to
`main`. (B5 beside F1a was considered and refused: F1a must render replies B5 produces.)

**Binding defaults.** §6 lists owner decisions. Until the owner changes one, the default written
there is binding on the implementing session — it does not guess and does not ask.

---

## §1 Corrections to the spec — where the codebase disagrees with the bundle

Each item is a place where building the bundle literally would ship something false, broken or in
conflict with a recorded project rule.

### 1. "Best spot this weekend?" is unanswerable for most of the week
The mock's own scenario (*today is Monday 5 Oct; the weekend is Sat 10 and Sun 11*) is beyond the
scored horizon: Gate 4 never scores T+4+, and only the first six upcoming events are rendered
(`PlanWindowProjector` publishes them as `DailyBriefingResponse.renderedEvents`, capped by
`PlanRenderLimits.MAX_VISIBLE_EVENTS`; every summary carries a `BriefingWindow`, so the cap is
`renderedEvents`, not window presence). **Ready questions
are a horizon-aware catalogue** (§2.4), not a fixed six.

### 2. A Ready answer is shared, so it cannot mention home
The mock's Ready summary says *"Saltburn … is closer to home"*. Ready answers are computed once per
scope for every user: no drive time, home or reach in the prompt, the tools or the text. Drive on a
pick card is joined on the client. *"Somewhere within an hour of home?"* is a typed question.

### 3. There are 4 enabled regions, not ~20
Scopes are those four plus `ALL`: 5 scopes × ≤6 questions × cycles per day ≈ 60–90 Haiku calls.

### 4. There is no tool use anywhere in the backend
Every Claude call today is single-turn. SDK 2.60.0 has what is needed (confirmed in the jar,
package `com.anthropic.models.messages`: `Tool`, `Tool.InputSchema`, `ToolChoiceAuto`,
`ToolUseBlock` (`id()`, `name()`, `_input()`), `ToolResultBlockParam` (`toolUseId`, `content`,
`isError`), `StopReason.TOOL_USE`; `MessageCreateParams.Builder.addTool`, `toolChoice`,
`addMessage(Message)`, `addUserMessageOfBlockParams`). The loop itself is new.

### 5. Pick facts are served facts, never Claude's
Claude returns only `{locationId, windowId, why}` per pick. Name, verdict, rating, time, tide and
drive on a card are joined from served data. A pick must be a pair a tool **actually returned in
this conversation**.

### 6. Ask must not crown what the Plan tab refuses to crown
Three recorded rules apply to ranking, and the first draft of this plan broke all three:
- **The sample gate.** A region that is not `BriefingRegion.verdictEligible()` cannot supply a pick
  — the 2026-09-29 failure (a handful of hand-run 4★ ratings crowning a region) is exactly what a
  "rated, best first" list would rebuild. No rating threshold exempts it.
- **Canopy.** A wood's rating means the opposite of a sky rating (`BriefingSlot.canopy`). Picks
  come from non-canopy slots only.
- **BEST BET.** The Plan tab already names a pick per window (`BriefingWindow.pick`). Ask's tools
  expose it and a generic "best" answer must lead with it (§2.3) — two different "bests" on one
  screen is the aggregator divergence `plan-panel-data-contracts.md` records.

### 7. The "note" line in Plan this is invented local knowledge
*"Park at the Abbey and walk the 199 steps…"*. PhotoCast holds no access or parking data, and the
bundle itself lists those as unanswerable. The note shows the slot's **served** `claudeSummary`.
The system prompt forbids access, parking, walking and safety advice.

### 8. `/` is already taken, and pinned by tests
`/` opens Plan search on the Plan tab only (`WindowFirstShell.jsx:1341-1390`), refuses elsewhere,
and is pinned by `planOriginShell.test.jsx`, `WindowFirstShell.test.jsx` and
`locationSheetShell.test.jsx`. **§6 Q1.** Default: `/` moves to Ask at ≥1024px on Plan, Coming up
and Map; search keeps its ⌕ and origin buttons.

### 9. "Add to Coming up" is not built
Coming up is the ETag-shared almanac of weather and sky events, not a personal schedule, and there
is no per-user saved item anywhere. **Owner decision, 2026-10-05: the feature is removed.** The
button is never rendered and no session builds storage for it. "Plan this" ends with "Open in
Plan ›" alone.

### 10. `HotTopicStrip` no longer exists
Event cards are a new component, coloured through `badgeChannel(type)` → `--color-badge-*`
(`utils/windowFirstCards.js:720`), not the bundle's three hex values.

### 11. The floating card is deferred, and surfaces are chosen by width
Nothing in the app distinguishes iPad from desktop, and the phone layout is `max-width: 639px`, so
"iPad portrait = iPhone" cannot be literal. Worse, a 360px card over the right of the real map
covers the Regions / Heat-Pins / Filters cluster at every width, the 504px drilldown panels below
~966px and the tide strip below ~884px, and `MapCallout`'s placement band has no way to express a
right-hand obstacle. **v1 has two big-screen behaviours, not three** (§2.6): a docked column from
1024px, a bottom sheet below it. The float is §6 Q3.

### 12. The peek sheet's 74px is a load-bearing constant
`--psh: 74px`, the Leaflet corner padding, `.wf-map-chrome-bl`'s `calc(74px + …)` and
`MapCallout.PEEK_SHEET_COLLAPSED_HEIGHT` hard-code it, and `MapCallout` reads `--psh` only when it
paints. Ask makes the collapsed height 126 and adds a 112px minimised state that coexists with a
callout (F4).

### 13. `BottomSheet.jsx` is 60vh, twice, and has no Escape
`maxHeight: 60vh` on the sheet and `calc(60vh − …)` on its scroller, and no key handler. It gains
caller opt-ins (`size="tall"`, `closeOnEscape`); no existing caller changes.

### 14. The heat field is one canvas that also draws the coastline and reach rings
"Heat to fillOpacity .08" is the prototype's `L.circle`. Pane opacity would dim the coastline too.
The dim is a factor on the heat fill inside `MapHeatLayer`'s draw (`HEAT_OPACITY * fade.heat`),
with a repaint.

### 15. The mock's can't-answer regex would misfire
`/busy|crowd|park|people|open|…/` matches "open horizon" and "national park". Whole-word phrases,
run **before** the Ready intent match.

### 16. A shared cache of typed answers leaks and can be poisoned
A home-relative answer must not be served to another user, and a question whose visible form
differs from its cache key lets one user plant an answer for everyone. §2.5 steps 2 and 6.

### 17. `allowanceLeft` only on the POST response is too late
The empty state shows the count before any question. `GET /api/user/settings/ask` — under the
personal-data prefix `HttpCachingConfig` never filters.

### 18. "Resets at midnight local time" is the UK civil date
`ForecastHorizon` with the injected `Clock`.

### 19. The scripted `fallback()` must not be ported
A failed call is an error state ("Couldn't answer just now. No question used.") with a refund — a
seventh UI state the README does not list.

### 20. Refunds must not make Claude free
The cost plan refunds `answerable:false` and failures. Unbounded, that is ~7,200 free Claude calls
a day per account at the 5/min limit, ending with the global cap switching typed questions off for
everyone. A second, **never-refunded** per-user daily ceiling on engine calls bounds it (§2.5).

### 21. Simulations and rewind
`HotTopicSimulationService.isEnabled()` is global; aurora simulation is
`AuroraStateCache.getSimulatedData() != null`. While either is active, Ready precompute is skipped
and typed answers are not cached. A POST sees the real clock under rewind, so **Ask is hidden while
a rewind is active**. A rewound admin GET of `/api/ask/ready` (a GET does see the rewound clock) is served from a
snapshot built for that moment and bypasses the snapshot memo (§2.2), so its cards agree with the rewound Plan view.

### 22. Cost recording
`api_call_log.job_run_id` is NOT NULL, has no label column, and `completeRun` sums cost once, at
completion. §2.3 gives the scheme that keeps Ask spend visible in Operations and summable cheaply.

### 23. Role, horizon and drive
`GET /api/briefing` has no role variance and Ask reads the same assembly, so every role sees the
same ratings in Ask as on the Plan tab. Ask is open to every role; the allowance is the gate. There
is no aurora-score tool, so nothing PRO-only can reach LITE. `maxDriveMinutes` filters on the
asker's own stored drive times; a user with none (most LITE accounts — the refresh button is
Pro-only in the UI) gets an explanatory empty result (§6 Q7).

### 24. Home, not the Plan origin
`effectiveReachById` is **replaced** by the region-base matrix when the Plan origin moves
(`WindowFirstBriefingContext.jsx:576-578`). The server filters on home. Ask therefore always joins
the reader's **home** reach map and labels it ⌂, whatever the Plan origin — never two journeys on
one card.

---

## §2 Design

### 2.1 Identity
- **Window id:** `yyyy-MM-dd_sunrise|sunset` — the encoding `BriefingRollupBuilder.java:149-150`
  already uses. A pick also carries `date` and `targetType` separately.
- **The window set** is the events listed in the served briefing's `renderedEvents` (every event
  summary carries a `window()`; the six-event cap is `renderedEvents`), with a non-null `window()`,
  that `PlanWindowProjector.hasPassed` has not retired, and that are not travel days (the served
  briefing does not mark a travel day; B1 uses `TravelDayService.isTravelDay`). A null or empty
  `renderedEvents` yields no windows. Solar only. Every tool and every Ready predicate
  uses this set and nothing wider.
- **`generatedAt` is a label and an invalidation hint, not the identity of the ratings.** Ratings
  are re-enriched on every serve and hot topics recomputed live. Freshness is therefore checked
  against live data at serve time (§2.4), never inferred from `generatedAt`.
- **`runLabel`** is formatted on the backend in Europe/London as `HH:mm` from the `generatedAt` the
  answer was built from.
- **Scope key:** one region id, or `ALL`. Regions join `BriefingRegion` by name.

### 2.2 The read model and the tools (B1)
`service/ask/AskSnapshot` is built from `BriefingService.getCachedBriefingForApi()` — the Plan
tab's own assembly, so retraction, the sample gate and the honesty filter are inherited. Per
window: id, date, target type, event time, served verdict, best rating, and the served
`pick` (kind BEST/ALSO, region, location id). Per region: served `displayVerdict`, `meanRating`,
`verdictEligible()`. Per slot: location id, name, `claudeRating`, `displayVerdict`,
`claudeHeadline`, `canopy`, and from `tide()`: `tideState`, `tideAligned`, `tideFitPhrase`.
**Coastal** means `tide().tideState() != null`. Plus the served hot topics and the almanac entries.

The snapshot is memoised for 30 seconds (the assembly is not cheap; CLAUDE.md's digest note). *As built
(B3):* **a request under an admin's rewind (`Rewind.isActive()`, the test `AlmanacService` makes for its day cache)
neither reads nor writes the memo** and gets a snapshot built for the rewound clock, and a memo whose age is
negative (the clock has gone backwards) is rebuilt rather than reused.

**Pick-eligible slot:** non-null `locationId` and a non-blank name (a card needs one); not
`canopy`; a `claudeRating` on Claude's 1–5 scale (`RatingValidator.isInRange`, the bound
`PlanWindowProjector.usableRating` applies — a malformed 491 is refused by both) and ≥ 3; its
region `verdictEligible()`; its window in the window set.

| Tool | Arguments | Returns |
|---|---|---|
| `list_windows` | — | each window: id, day word, event, time, verdict, best rating, and `bestBet` / `alsoGood` with the named region and location when in scope |
| `rank_spots` | `windowIds?`, `regionNames?`, `coastalOnly?`, `tideState?`, `maxDriveMinutes?`, `limit` (≤8) | pick-eligible slots, best first (rating desc, then `bestBet`/`alsoGood` location first, then `tideAligned`, then name): locationId, name, region, windowId, rating, verdict, `tideState`, `tideAligned`, `tideFitPhrase`, headline (≤120 chars), `pick` flag; `driveMinutes` only when `maxDriveMinutes` was given |
| `get_hot_topics` | `types?`, `limit` (≤10) | type, label, detail (≤200 chars), date, regions |
| `get_coming_up` | `days` (≤90), `limit` (≤10) | type, title, start/end date, detail (≤200 chars) |
| `submit_answer` | the reply schema (§2.9) | terminates the loop |

Rules:
- If nothing is pick-eligible, `rank_spots` returns an empty list with a `note` saying so ("nothing
  rated 3★ or better in scope"), so "nothing is worth it" is an answer with no picks.
- `tideState` is the served state band at the event. `tideAligned` is the preference fact (the
  location's own wanted water). They are returned as two fields and never merged.
- `maxDriveMinutes` is a tool error in a user-less (Ready) conversation, and an explanatory empty
  result for a user with no drive times. Using it marks the conversation `personal`.
- An unknown window id or region name is a tool **error result**, not an exception.
- Tool results are capped at 6,000 characters in total per conversation; past the cap a tool
  returns an error result telling the model to answer.

### 2.3 The engine (B2a)
`AskEngine.answer(AskQuestion, AskSnapshot, AskUserContext) → AskOutcome` (records in §2.9).
*As built (B2a):* `answer` is the default method of an interface whose real entry is
`AskEngine.run(question, snapshot, user, AskRunOptions) → AskRun(outcome, trace, reason)`.
`AskRunOptions(anchor, readyJobRunId)` carries what a caller adds: the Ready `BEST_*` anchor (supplied
by B3, never invented by the engine) and the `ASK_READY` job run to bill. A typed conversation has a
user and no run id (it bills the daily `ASK` run); a Ready conversation has no user and **must** carry
its run id; a mismatch is an `IllegalArgumentException` (a caller bug, not a model failure). `AskRun`
adds the tool trace (B2b's dry-run) and a `reason` for a FAILED outcome, for the log only. The question's
region ids are resolved to names by the engine (an id that does not resolve fails the run before any
spend: an empty scope would widen it to every region), and that one name set is given to `AskTools`
and to `AskAnswerValidator.validate`.

- **Model:** `photocast.ask.model`, default `HAIKU`, validated to `HAIKU | SONNET` at startup. No
  `model_selection` row and no entry in `ModelSelectionService.CONFIGURABLE_RUN_TYPES` — Sonnet 5.5
  must not be selectable (it cannot disable thinking, which breaks the token and time budgets).
- **Loop:** at most 4 model turns, `tool_choice` auto, `max_tokens` 600. The reply is
  `submit_answer`'s input. A turn that ends without a tool call, a refusal, `max_tokens`, or turn 4
  without `submit_answer` is a **failure**. *As built:* refusal, `max_tokens` and the context-window
  stop are `ModelRequestSupport.checkStopReason` (the one test every Claude path shares); any other
  stop that is not `tool_use` is a failure too. Parallel tool use stays on (a turn may carry several
  calls, each answered in order in the next request); a `submit_answer` ends the loop at its place in
  the block order. A `submit_answer` that is not an object, lacks `answerable` or `summary`, or has a
  wrongly typed field or a non-integer `locationId` is a FAILED outcome (`AskAnswerParser`); extra
  fields are ignored, since nothing reads them. A tool call with an unknown name or unreadable
  arguments is an error `tool_result` (`isError`) fed back, never an exception.
- **Time:** `AnthropicApiClient` gains `createAskMessage(params, RequestOptions)` on its own
  Resilience4j instances — retry `ask` (2 attempts, 5xx only, **no** content-filter retry) and
  circuit breaker `ask` — so a user cannot open the shared `anthropic` breaker against the forecast
  pipeline. Per-call timeout 20s; a 30s deadline is checked before each turn and the remaining time
  is that call's timeout. Bulkhead `ask`: 4 concurrent, 2s wait. The instances are declared in
  `application-local.yml`, `application-example.yml`, `application-prod.yml` and
  `src/test/resources/application.yml`; a test reads the limits back from the registries.
  *As built, because the plan was silent on how these interact:* (1) **the SDK's own retries are off
  for this door** (`client.withOptions(o -> o.maxRetries(0))`, as `AnthropicBatchClient` does): the
  shared client retries 408/409/429/5xx/I-O up to twice, each retry with the request's full timeout,
  so left on it would multiply under the `ask` retry; measured, the shared door makes 3 HTTP attempts
  for a persistent 500 and the Ask door makes 1. (2) **The retry predicate is `AskRetryPredicate`**,
  any 500–599 (529 included) on an `AnthropicServiceException`: `TransientHttpErrorPredicate` is
  written for Spring's `RestClientResponseException` and never matches an SDK error; I/O failures and
  429 are not retried. (3) **The deadline is enforced by the engine, not only by the per-call
  timeout**: the second retry attempt would otherwise get a fresh timeout of the same size and the
  bulkhead can wait 2s before a call starts, so the engine stops waiting at the deadline whatever the
  call is doing and abandons (interrupts) the call. (4) The `ask` breaker ignores a rejected key
  (401/403, the shared `ClaudeBreakerIgnorePredicate.isKeyRejection`) and a full bulkhead (load, not
  an outage), and has `registerHealthIndicator: false` so questions cannot flip the application's
  health. (5) An SDK per-call timeout also counts the first request's class loading: measured 2.1s for
  a 400ms timeout on a cold JVM, 403ms thereafter.
- **System prompt:** adapted from the mock's `sys=` — plain British English, no hype, answer only
  from tool results, `why` under 22 words, 2–3 picks at different spots for where/when questions,
  `answerable:false` + `missing` when the tools can't answer. Added:
  - today's UK date and day name, the scope and the context window, as stated facts
  - for a generic "best" question, lead with the `bestBet` window when it is in scope; if the
    answer leads elsewhere, the summary says why
  - say tide "suits" a spot only when `tideAligned` is true
  - never give access, parking, walking or safety advice; never state a score or a drive time a
    tool did not return; in a user-less conversation never mention home or distance
  - no URLs
- **The question** reaches Claude only as the user message, in the sanitised form of §2.5 step 2.
- **Validation** (`AskAnswerValidator`, pure, B1):
  - a pick survives only if `(locationId, windowId)` was returned by `rank_spots` in this
    conversation and is still pick-eligible; at most 3; distinct locations; ranks renumbered
  - an event survives only if its type was returned by `get_hot_topics` / `get_coming_up`
  - `summary` required; `summary`, each `why` and `missing` are trimmed, word-capped
    (`PromptUtils.truncateToWords`: 60 / 24 / 8 words), brand-sanitised, and anything URL-like is
    stripped
  - `answerable:true` with no surviving pick or event is allowed only if a tool was called
  - **Ready `BEST_*` questions only:** if a BEST pick exists on a window the question covers, pick
    1 must be on that window; otherwise the answer is discarded (logged at WARN, not stored)
  - **Event safety notes are mandatory and never the model's:** an event's `safetyNote` (the solar
    eclipse's lens-filter warning, `HotTopic.safetyNote`) is re-joined from the served topic a tool
    returned, exactly as its label and date are. The model has no field to write one, and an event
    whose served topic has one always carries it. The Coming up feed's `ComingUpEntry` carries no
    safety note, so for an almanac entry of type `eclipse` (the SOLAR eclipse, never
    `lunar-eclipse`, whose note is not a safety warning) the Ask snapshot attaches the same
    `EclipseHotTopicStrategy.SAFETY_NOTE` constant the hot topic uses — one string, one home — and
    `get_coming_up` rows and the evidence carry it, so a solar eclipse never reaches an answer
    without the warning, whichever tool returned it.
  - Residual, stated: the summary's prose is not fact-checked. The cards beside it carry served
    facts.
- **Cost.** Two run types, `RunType.ASK` (typed and dry-run) and `RunType.ASK_READY` (precompute);
  both ≤20 chars; add both to `defaultDateRange`'s switch.
  - `ASK_READY`: one `job_run` per precompute, started and completed normally.
  - `ASK`: one `job_run` per UK day, found or created under an in-JVM lock (single instance, the
    `LocationFailureService` precedent), completed at creation. Because `completeRun` sums cost
    only once, each logged call is followed by a column-scoped
    `UPDATE job_run SET total_cost_micro_dollars = total_cost_micro_dollars + :c WHERE id = :id`,
    so Operations shows the day's Ask spend.
  - Every model turn is logged through `jobRunService.logApiCall` with its `TokenUsage` (the `url`
    argument carries `ask` / `ask-ready` for the reader of the log; nothing queries it). *As built:*
    a failed turn (an exception, a refusal, `max_tokens`) is logged too, with its status and, when the
    API returned one, its usage; the request and response bodies are **not** stored (the request is
    the reader's question). The daily run also counts questions (`locations_processed`, `succeeded`,
    `failed`, by a column-scoped `UPDATE`) so Operations shows more than cost. **Fail closed:** the
    daily run is found or created before the first model turn, so a database that cannot record the
    money stops the call that would have spent it. A
    `ASK_READY` conversation is billed to the run its caller (B3) started and passed in
    `AskRunOptions`; the engine never touches the daily run or its increments for it.
  - **A paid call's cost is never lost (fail closed).** If a turn's `api_call_log` insert fails after
    Anthropic has answered, `AskJobRunService.recordTurn` keeps the turn and its priced cost in a
    bounded in-memory holder (100 turns in detail; further cost is folded into a per-run total, never
    dropped) and latches. The typed-spend figure adds the unrecorded typed cost on every read,
    bypassing the 30s memo. `accountingAvailable()` is asked by every conversation, typed or Ready:
    early (in `run()` and before each turn, cheap refusals) and, binding, by a `CallGate` **inside
    `AnthropicApiClient.createAskMessage`** — after the bulkhead permit is taken, before every
    attempt (a retry's second attempt is another paid call), as the last step before the HTTP
    request. While anything is unrecorded it first tries to write the held turns; if it cannot, the
    attempt is refused and the run returns FAILED with `AskRun.ACCOUNTING_UNAVAILABLE` — including
    the next turn of the conversation whose write failed (the turn that carried `submit_answer` is
    already paid for and is still returned). **The window that cannot be closed:** a request already
    issued when another conversation latches cannot be recalled, so at most the calls in flight at
    that moment (the bulkhead's four) can still be paid for unrecorded; a call the engine abandons at
    its deadline may also be billed with no usage to log. Each typed turn is booked to the daily run
    as of that turn (a conversation crossing UK midnight books its later turns to the new day); the
    question is counted on the day it started. The cap reads `api_call_log`, so the two `job_run`
    increments are display-only and stay best effort. **A process restart loses the holder**: that is
    the one way spend goes unrecorded, bounded by the latch to the turns of conversations already in
    flight when the first write failed.
  - **Today's typed spend** = `SUM(cost_micro_dollars)` over `api_call_log` rows whose
    `job_run_id` is an `ASK` run started since UK midnight (uses `idx_api_call_log_job_run`),
    memoised 30s. Precompute spend does **not** count toward the typed cap.
- **Flag:** `photocast.ask.enabled`, default false (§2.9 says what each endpoint does when off).
  `photocast.ask.stub` (default false) swaps the engine for `StubAskEngine`; how exactly one engine is
  chosen, and why `stub=true` fails startup under `prod`, is in §3 B2b's *As built*.

*As built (post-Z, 2026-10-07) — the first real run of `AskPromptRegressionTest` found an events defect.* The rare-events case
("Any rare events coming up?") answered OK in 2 turns with "no rare events are flagged for the next three months" while the
fixture's snapshot held a solar eclipse, a king tide and an aurora as live hot topics. The trace: one `get_coming_up`
(`{days: 90, limit: 10}`, result `{"entries":[]}` — the fixture has no almanac entries), no `get_hot_topics`, then `submit_answer`.
The two tools split the events by horizon (hot topics are the briefing's live 5 days; the almanac is 90 days and need not list what
the forecast is flagging this week), their descriptions did not say so, and the model reasonably took the 90-day tool as
the complete one. Production has the same gap, and B3 would have skipped a `RARE_EVENTS` answer built on it (an events question
that finds nothing is not stored). Fixed in three layers, none depending on the model choosing well:
- **Data (the guarantee).** `get_coming_up` returns the almanac entries *and* the in-scope live hot topics dated within its
  horizon (`AskTools.timeline`), as one-day entries carrying the topic's label, detail and safety note, deduped against any almanac
  entry of the same type (compared upper-case, `-` as `_`) whose span holds the date. Whichever events tool the model reaches
  for, the eclipse is there; the evidence the validator holds carries it exactly once.
- **Words.** The two tool descriptions now say what each covers and tell the model to call the other for any events question;
  the system prompt says to call BOTH for events, rarities or "what's coming up", and to say "no events" only after both
  returned nothing that bears on the question (`AskToolSchemas`, `AskPromptBuilder`; the schema golden files were regenerated).
- **Enforcement.** `AskAnswerValidator.validate` takes the events question (`ReadyIntentRules.eventsQuestion`: `RARE_EVENTS` or
  `SNOW_TOPS`, by the Ready matcher's own strict rules, so a typed question and the Ready question of that text are one question
  to the matcher, the store and this rule). An answer to it that carries no event (or says it cannot answer) while the snapshot
  offers an event the question admits — in scope, hot topic or almanac entry within 90 days, read from the snapshot, never from what
  the model's calls returned — is discarded: `events question answered "none" while N events were offered`. That is a FAILED run
  (a refund for a typed question, a failed row and no store for Ready). **Deliberately narrow:** a time-bound or qualified phrasing
  ("any rare events this weekend?") is not an events question here, because there "none" can be true; those rest on the data layer.
Verified by the real regression class: all three cases pass, the rare-events trace is `get_hot_topics, get_coming_up, submit_answer`.
`AskPromptRegressionTest.ask()` now also prints the tool trace (a helper line only; the assertions are untouched).

### 2.4 Ready answers (B3)

| Id | Text | Available when | Tabs |
|---|---|---|---|
| `BEST_WEEKEND` | Best spot this weekend? | a Sat or Sun window in the window set has a pick-eligible slot in scope | plan, map |
| `BEST_SOON` | Best spot in the next few days? | `BEST_WEEKEND` is unavailable and ≥2 windows have a pick-eligible slot | plan, map |
| `BEST_NEXT` | Best spot tonight? / tomorrow morning? (from the next window) | that window has a pick-eligible slot | plan, map |
| `COASTAL_HIGH` | Best coastal spot at high tide? | a pick-eligible coastal slot has `tideState` HIGH | map |
| `AM_OR_PM` | Sunrise or sunset on Saturday? / tomorrow? (the next date with both windows in the set) | such a date exists | plan |
| `RARE_EVENTS` | Any rare events coming up? | always | coming-up, map |
| `SNOW_TOPS` | Is there snow on the tops? | always | coming-up |

- **Precompute:** `AskReadyService.precompute(pipelineRunId)`, dispatched on the background
  executor **after** `finishRun(runId)` in `PipelineOrchestrator.waitAndBriefPhase`, with its own
  5-minute deadline — it must never hold a pipeline run RUNNING (a RUNNING older cycle forces later
  tail settles to `RESETS_ONLY`) and nothing it throws can reach the run. Skipped when the briefing
  is null or `stale()`, the flag is off, or a simulation is active. At most
  `photocast.ask.ready.max-cycles-per-day` (default 6) per UK day. Scopes sequential; one
  question's failure does not stop the rest. User-less. Idempotent upsert.
- `POST /api/admin/ask/ready/precompute` (ADMIN) runs it on demand — the only way to get Ready
  answers locally, and the owner's lever after a manual briefing rebuild, which does **not** trigger
  it.
- Text that depends on "now" is fixed **at precompute time** and stored with the window ids asked
  about.
- **Store:** `ask_ready_answer(scope_key, question_id, question_text, window_ids,
  briefing_generated_at, pipeline_run_id, answer_json, created_at)`, unique on `(scope_key,
  question_id)`. `answer_json` stores each pick's **rating and verdict as they were when the answer
  was written**.
- **Serve:** `GET /api/ask/ready?scope=<regionId|all>`. Each question carries its own `generatedAt`
  and `runLabel` from its row. **Freshness is all-or-nothing:** a question is withheld if any pick's
  window has passed, any pick's live rating or verdict differs from the stored one or the slot is no
  longer pick-eligible, any event's topic is no longer live, or its named windows have all passed.
  There are no partial answers under stale prose. User-independent, so it joins
  `HttpCachingConfig.REVALIDATABLE_READ_PATHS`. Bearer, no role gate.
- The client opens a Ready answer from the list it already fetched: no POST, no allowance.

### 2.5 Typed questions (B4, B5)
`POST /api/ask`. Each step can answer on its own; nothing expensive runs before a cheaper guard.

1. **Rate limit** — 5/min per user id, in-memory sliding window → 429 `RATE_LIMITED`.
2. **Sanitise and validate** — trim; collapse whitespace; reject (400 `INVALID`) if blank, over 200
   characters, or containing anything outside letters, digits, space and `? ! ' ’ , . - : / & ( )`.
   The sanitised string is what Claude receives. The **normalised** form (lower-case, punctuation
   stripped, a fixed filler list dropped) is the cache key, so the only text Claude sees beyond the
   key is punctuation and filler words. Unknown or disabled region ids → 400.
3. **Can't-answer pre-filter** — whole-word phrases: *car park, parking, crowd(s/ed), busy, queue,
   opening times/hours, toilet(s), café/cafe, shop(s), pub, restaurant*. → `kind: cant`, no charge.
4. **Snapshot** (memoised). A `windowId` not in the window set is ignored.
5. **Ready intent match** — a deterministic keyword classifier to an *available, fresh* Ready id
   for the scope; it must match the subject **and** carry no qualifier the Ready answer ignores (a
   named place, a drive constraint, a different day). → `kind: ready`, no charge. No embeddings.
6. **Typed cache** — Caffeine, 2,000 entries, 30-minute TTL, key `(sorted region ids | ALL, UK
   civil date, generatedAt, normalised question, windowId | none, userId | shared)`. `shared`
   unless the conversation was `personal`. A hit passes the same all-or-nothing freshness check as
   a Ready answer; failing it is a miss. A hit → `kind: own`, no charge (D-6). Nothing is cached
   during a simulation.
7. **Spend cap** — today's typed spend ≥ `photocast.ask.daily-spend-cap-usd` (default 0.50) →
   503 `TYPED_UNAVAILABLE`; admins emailed once per UK day
   (`AdminAlertService.sendAskSpendCapAlert`, new).
8. **Daily ceilings** — `ask_usage(user_id, usage_date, used, engine_calls)`; find-or-insert with a
   unique-violation retry (H2 locally, so no `ON CONFLICT`), then one conditional update that takes
   both: `used < :limit AND engine_calls < :ceiling`. Limits LITE 3, PRO 30, ADMIN 30; ceiling
   3 × limit. `used` exhausted → 429 `ALLOWANCE_EXHAUSTED`; `engine_calls` exhausted → 429
   `DAILY_LIMIT`.
9. **Engine.** On failure or `answerable:false`, `used` is refunded — on the **reserved** date,
   guarded `used > 0` — and `engine_calls` is **never** refunded. A crash between reserve and
   refund loses the question; that is the safe direction.
10. **Respond, cache, log.**

`GET /api/user/settings/ask` → always 200 (§2.9); pinned in
`HttpCachingConfigTest.personalDataPathsAreNeverFiltered`.

`ask_log(id, created_at, user_id NULL REFERENCES app_user ON DELETE SET NULL, scope_key, view,
outcome, normalised_question NULL, missing NULL, duration_ms)`; `outcome ∈ {READY_MATCH,
PREFILTER_CANT, CACHE_HIT, CLAUDE_OK, CLAUDE_CANT, CLAUDE_FAILED}`. The question is stored only for
`CLAUDE_OK` and `CLAUDE_CANT`. Denied requests write **no row** (a looping client must not be able
to write millions); they are counted in memory and logged at INFO per user per hour. `ask_usage` is
`ON DELETE CASCADE`. Pruned at 90 days by a nightly job (read every `scheduler_job_config` seed
across the migrations for a free cron slot — V151's comment is out of date).
`GET /api/admin/ask/metrics?days=` (ADMIN): outcome counts, cache-hit rate, `answerable:false` rate
with the top `missing` phrases, typed and Ready spend. It never returns raw questions.

### 2.6 Client: one conversation, three surfaces
`context/AskContext.jsx` holds the **conversation** only (question, phase, kind, answer, selected
pick, plan pick, context-window flag). Whether Ask is open is **shell** state
(`WindowFirstShell`'s own `useState`), because `selectTab` runs during render and may only call the
shell's own setters.

| Width | Map tab | Plan / Coming up | Entry |
|---|---|---|---|
| < 640 | **peek** — the ask row of `MapPeekSheet` (F4) | **sheet** | peek row / 48px ask bar |
| 640 – 1023 | **sheet** | **sheet** | 260px field beside the tab row |
| ≥ 1024 | **dock** — 360px (380px from 1180) | **dock** | 340px field (260 below 1180), `/` hint |

No Ask on the Operations tab: the field is not rendered there and switching to it closes Ask.

- **Dock.** `<aside role="complementary" aria-label="Ask PhotoCast" data-ask-surface>`. It is a
  sibling of the **whole shell column** (masthead + tab row + panel), not of the pane: the shell
  root becomes a row of `[column, max 1080px] [dock]`, centred as a pair. The masthead and the
  panel therefore narrow together and O-17's "the two columns never drift apart" holds. On Plan
  and Coming up (the document scrolls) the dock is `position: sticky; top: 0; height: 100dvh` with
  its own scroller and the input pinned at its foot; on Map it is the full height of the frame.
  The map learns its new width from the `ResizeObserver` in `WindowFirstMapPane.jsx:181`.
  (The mock docks below the tab row; §4 #8.)
- **The field** sits beside the `role="tablist"`, not inside it (the tablist has roving arrow keys
  and `overflow-x: auto`). Below 720px with four tabs (an admin) it collapses to a 34px "Ask"
  button. Whether it fits is a browser check, not a jsdom test.
- **Dialogs.** The dock is not a dialog and takes no `aria-modal`. While any shell dialog
  (window popup, sheet, pick dialog, location sheet, search) or settings is open, the dock and the
  field are `inert`, and `/` is refused. Opening Ask never closes a dialog.
- **Escape.** Handled by the dock only when focus is inside it: it closes Ask, returns focus to the
  field and stops propagation. With focus elsewhere, Escape behaves exactly as today.
- **Outside press.** `useOutsideDismiss` treats a press inside `[data-ask-surface]` like a press
  inside the map frame — it dismisses nothing — so choosing a pick card does not close a drilldown.
- **Sheet.** `BottomSheet` with `size="tall"` (full height minus 24px, both 60vh sites) and
  `closeOnEscape`. Its height follows `window.visualViewport` (new `hooks/useVisualViewportHeight`)
  so the input at its foot stays above the iOS keyboard; the input is 16px (iOS zooms below that).
  The 48px ask bar is `position: fixed`, offset by `--safe-b`, below `Modal`'s z-50, and the
  document ends 58px later so the last row — and the footer that follows the pane — is not covered
  (*as built, F1b: at the end of the page, not on the pane — see the F1b note*). A tab switch closes
  the sheet (`selectTab`'s list).
- **Sheet pick cards carry "Show on map ›"** (the mock's phone behaviour): it closes the sheet,
  moves to the Map tab and leaves the picks numbered there. The answer is kept; the field (640–
  1023) or the peek row (phone, F4) reopens it.
- **Pick card model** (`utils/askModel.js`, pure): joins a served pick to the briefing days (name,
  rating, verdict, event time, tide state and tier) and to the **home** reach map (§1 #24).
  Filter/map/select over served facts — the already-licensed class. A pick with no slot in the
  client's briefing is dropped, never rendered half-empty.
- **Scope follows the Map's scope, never the camera.** Map: a focused region → that region; "My
  area" → the regions of the scope pool; "Everywhere" → all. Plan / Coming up → all. The view chip
  says what is sent ("Map · Northumberland & Tyneside", "Map · My area", "Plan · all regions").
  The window chip (Map only, solar rows only) is removable. Ready set: one region → that region's;
  otherwise `ALL` (§6 Q8).
- **Live regions:** the busy line is `role="status"`; the answer container is `aria-live="polite"`
  and empty behind every hidden layer.
- Hidden: while rewound; when `enabled` is false. Disabled: when `contentDisabled`.
- "Pro: 30 a day" is text with `ProPill`, not a link.

### 2.7 Map linkage (F3, F4)
`MapView` gains `askPicks` (`[{rank, locationId, name, date, eventType, shortWindow, rating,
verdict}]`), `askSelectedRank`, `onSelectAskPick`, `askWindow` (`{date, eventType, nonce}`).

- **Window follow is its own channel.** `askWindow` is derived in the Map **pane** (not App state:
  `AppInner` is above `AskProvider` and cannot hear the conversation — see "As built (F3)") with its
  own nonce and its own effect in `MapView` (`setEventType`, `setUserHasOverriddenEvent(true)`);
  the existing `onSelectDate` carries the date. It does **not** ride `mapTabHandoff`: that effect
  applies the Plan lens to every source that is not `'map'`, mounts `MapBreadcrumb`, and is a single
  slot a second writer would clobber.
- **Markers.** Chips and pins carry `data-ask="pick" | "fade"`. A pick chip is built from the
  **pick's own window** (rank circle, name, short window, that window's rating in its tier colour;
  no tide glyph and no tooltip borrowed from the active window). Picks go first in the label
  placer's candidate order; a pick the greedy placer still cannot fit falls back to its bare rank
  circle, so no pick is ever unmarked. Picks are appended to `labelSpots` even when filters or scope
  removed them. `fade` is `opacity: .25` and beats the tide rule's `.72`. Accessible name: "Pick 1,
  Whitby, Saturday sunrise, 5 stars".
- **Heat:** `MapHeatLayer` takes `dim` (0.36) applied to the heat fill only, with a repaint.
- **Camera.** On an answer with picks, `flyToBounds` over them, `maxZoom: 10`. Padding comes from
  the **measured** rect of the covering surface, clamped so padding never exceeds 60% of
  `map.getSize()` on either axis (padding larger than the frame puts the picks outside it — measured
  against Leaflet 1.9 it is not a NaN zoom but an Infinity one, capped to `maxZoom`; and the mock's
  490px is taller than the real phone frame). Selecting a pick: `flyTo` at zoom 10.5,
  offset by the same clamped inset. Reduced motion: no animation.
- **Selecting a pick** clears `selectedLocationName` (no stale callout for another window), sets
  `askWindow`, and does not open a callout. Tapping a map pick selects its card. Tapping an
  ordinary chip selects that location as usual.
- Clearing the answer removes every `data-ask` and the dim; it does not move the camera back.

**Phone (F4).** Heights: 126px collapsed (the ask row above the three buttons); 470px with Ask
open; 408px for the other three sections (the mock's figure once the row exists); 112px minimised.
All clamp to `calc(100% - 64px)`. `openMapMenu` gains `'peek:ask'`, meaning "the ask section is
expanded"; whether an answer exists and whether it is minimised is Ask state.

| Event | Suggestions showing | Answer expanded | Answer minimised |
|---|---|---|---|
| Map touch / drag / zoom | collapse to 126 | minimise to 112 | stay |
| Ordinary chip press | collapse; callout opens | minimise; callout opens | callout opens |
| Pick chip press | — | select card | expand, select card |
| Escape | collapse | minimise | as today (clears selection) |
| Window pill / a peek button | switch section | minimise, open that section | open that section |
| That section closes | — | — | back to the 112 line |
| ✕ in the ask row | collapse | clear answer, restore buttons | — |
| Tap the 112 line | — | — | expand |
| Tab switch away and back | closed | answer kept, minimised | kept |

`--psh` is set inline on `.wf-map-tab` from state (126 / 112); every 74 literal in the phone block
becomes 126; `MapCallout` gains a `bandKey` prop so it repaints when the sheet's height changes
(its own doc records this stale-band defect for the tide strip). *As built (F4):* **`--psh` is 74 / 126 / 112, and the
phone block's literals became `var(--psh, 74px)`, not 126** — with Ask off the sheet is what it was — and "minimised" is
derived (a settled answer, no section open), not a state of its own: see "As built (F4)".

### 2.8 Plan linkage (F5)
- **Highlight.** `WindowFirstHeatStrip` gains `highlightKeys` → `data-ask-highlight`, the spec's
  gold border **plus the pick's rank circle** on the card (the gold border alone is the existing
  `data-open` look), scrolled into view, "Ask pick N" in the accessible name. It does not open the
  popup. Live on the dock; on the sheet surface it is applied when the sheet closes. A pick whose
  window has no card highlights nothing.
- **Plan this** is a phase of the conversation. Leave home = `leaveByParts` (`utils/leaveBy.js`)
  over the home drive; Drive = `formatDriveDuration`; Best light = `lightWindows` (currently
  module-private at `utils/locationSheet.js:194` — F5 exports it) over the provider's score index
  (`buildScoreIndex(scoreRows)`); Tide = the tide-alignment index. No drive time → dashes and the
  tick line's "set a postcode" nudge. The note is the slot's served summary.
- **Open in Plan ›** → the shell's `openLocationSheet({…, inPlan: true, date, targetType})`. On the
  sheet surface Ask closes first.

### 2.9 Contracts (binding across phases)

**Config** (`photocast.ask.*`, `AskProperties`, bounds fail startup): `enabled` (false), `stub`
(false), `model` (HAIKU), `max-turns` (4, 1–6), `max-tokens` (600), `call-timeout-seconds` (20),
`deadline-seconds` (30), `rate-per-minute` (5), `limit-lite` (3), `limit-pro` (30),
`engine-ceiling-multiplier` (3), `daily-spend-cap-usd` (0.50), `ready.max-cycles-per-day` (6),
`cache.max-entries` (2000), `cache.ttl-minutes` (30), `log.retention-days` (90),
`seed-local-fixture` (false).

**Java records** (`service/ask/`):
```java
record AskQuestion(String sanitised, String normalised, String windowId, List<Long> regionIds, String view) {}
record AskUserContext(Long userId, UserRole role, boolean hasDriveTimes) {}   // null userId = user-less
record AskPick(int rank, long locationId, String locationName, String regionName, LocalDate date,
               TargetType targetType, String windowId, String why, Integer ratingAtAnswer, String verdictAtAnswer) {}
record AskEvent(String type, String label, LocalDate date, String why, String safetyNote) {}  // safetyNote nullable, omitted on the wire when null
record AskAnswer(boolean answerable, String summary, List<AskPick> picks, List<AskEvent> events, String missing) {}
record AskOutcome(Status status, AskAnswer answer, boolean personal, int turns) { enum Status { OK, CANT, FAILED } }
```

**`GET /api/ask/ready?scope=all|<regionId>`** → 200; 404 when the flag is off.
```json
{"scope":"all","questions":[{"id":"BEST_NEXT","text":"Best spot tonight?","tabs":["plan","map"],
  "generatedAt":"2026-10-05T05:02:11","runLabel":"06:02",
  "answer":{"answerable":true,"kind":"ready","summary":"…",
    "picks":[{"rank":1,"locationId":123,"locationName":"Whitby","regionName":"North York Moors & Coast",
              "date":"2026-10-05","targetType":"SUNSET","windowId":"2026-10-05_sunset","why":"…"}],
    "events":[{"type":"ECLIPSE","label":"Partial solar eclipse","date":"2026-10-10","why":"…",
               "safetyNote":"Certified solar filter on the lens — not only over your eye"}],
    "missing":null,"try":[]}}]}
```

**`POST /api/ask`** body `{"question":"…","windowId":"2026-10-05_sunset","regionIds":[3],"view":"map"}`
(`windowId` optional; `regionIds` empty = all; `view` ∈ `map | plan | coming-up`). 200:
```json
{"answerable":true,"kind":"own","summary":"…","picks":[…],"events":[…],"missing":null,"try":[],
 "allowanceLeft":2,"allowanceLimit":3,"charged":true,"generatedAt":"…","runLabel":"06:02"}
```
`kind` ∈ `ready | own | cant`. For `cant`: `answerable:false`, `picks:[]`, `events:[]`, `missing`
set, `try` = up to two `{id,text}` Ready questions, `charged:false`. Errors are
`{"error":"human sentence","code":"…"}`:

| Status | `code` | Client shows |
|---|---|---|
| 400 | `INVALID` | the error sentence under the field |
| 404 | — (flag off) | Ask hidden |
| 429 | `RATE_LIMITED` | "Slow down a moment." Nothing used |
| 429 | `ALLOWANCE_EXHAUSTED` | state 7: "Ready questions only today" |
| 429 | `DAILY_LIMIT` | state 7 |
| 502 | `ENGINE_FAILED` | the error state. "No question used" |
| 503 | `TYPED_UNAVAILABLE` | state 7 |

**Instruction for B4:** an engine run with `AskRun.accountingUnavailable()` true is **503
`TYPED_UNAVAILABLE`**, not 502 `ENGINE_FAILED` (the question is refused, not broken, so the client
shows "Ready questions only today"); it is refunded like any failure, and `typedAvailable` on
`GET /api/user/settings/ask` should read false while `AskJobRunService.accountingAvailable()` is false.
**`GET /api/user/settings/ask`** → always 200:
`{"enabled":true,"used":1,"limit":3,"left":2,"typedAvailable":true}` (flag off: `enabled:false`,
zeros).

**Admin:** `POST /api/admin/ask/dry-run` `{question, regionIds, windowId?}` → `{engine: stub|claude,
status, answer, personal, turns, reason, trace}` (the outcome plus the tool trace; with the Claude engine it
counts toward the typed spend, with the stub it costs nothing). ADMIN only; 404 while the flag is off (after
the role check); 400 for a blank or over-200-character question or an unknown, disabled or more than 20
region ids; 409 when no briefing has been built. A FAILED run is a 200 carrying `status: FAILED` and the
`reason`, since the admin is there to see why. `POST /api/admin/ask/ready/precompute` → `{written,
skipped, failed}`. `GET /api/admin/ask/metrics?days=`.

*As built (refactor, 2026-10-08) — a question's region scope is resolved once, as an `AskScope` value.* Scope had been a
bare `Collection<String>` of region names re-normalised at seven sites with four spellings of "is this region in scope",
a `(scopeKey, scopeNames)` pair carried as two parameters, and the region ids read from the database up to four times per
typed question (`validRegionIds`, `resolve`, again inside the engine, again in the cache's `store`). Now
`AskScopes.resolve(RegionRepository, Collection<Long>)` is the one merged method and `AskScope` the value it returns
(`key`, `regionIds`, `names`, `contains`, `isEverywhere`, `readyScope`, and the one `AskScope.ALL`/`ALL_KEY`). **The rules
that survive unchanged:** an unknown or disabled id fails rather than widens (`Optional.empty()`, which `AskService.validate`
and the admin dry-run turn into the same 400 `INVALID` as before); empty ids mean every region; more than 20 ids or a null
id is refused before the repository is asked. **The one-resolution rule:** `AskService.validate` resolves the ids once and
the `AskQuestion` carries the scope (`question.regionIds()` still answers, delegating); `ClaudeAskEngine`, `StubAskEngine`,
`CaffeineAskAnswerCache.store` and the Ready precompute read `question.scope()` and hold no `RegionRepository` for it (the
precompute builds each region's scope from the entities it already read). Repository reads per typed question with a region
scope: 2 before (`validRegionIds` + `resolve`) plus 1 in the real engine plus 1 in the cache's `store`, so 4; 1 now
(`AskServiceTest.regionIdsAreReadOnce`, `AskScopesTest.idsResolveToAScope`). **`AskScope` is a final class, not a record, on
purpose:** a record's canonical constructor is as public as the record, and scope is a safety boundary that must not be
constructible from an id that did not resolve. **Two keys, not one:** `key()` is the typed cache's (sorted ids joined, or
`ALL`); a question about several regions is answered from, and logged under, the whole catalogue's Ready scope
(`readyScope()`, itself for one region, `ALL` otherwise), exactly as `AskService` had routed it. `names()` stays as stored and
sorted because the system prompt prints them (the golden prompt tests would move if they were lower-cased); the single
case-insensitive comparison lives in `contains`. `AskIntentMatcher.match` and `AskReadyService.serve/freshAnswers/suggestions`
take the scope instead of a `(key, names)` pair, and `AskReadyService.ALL`/`CaffeineAskAnswerCache.ALL` are gone. The
normalised form comes from `AskQuestionSanitiser` for every producer (`AskQuestion.of`): the dry-run and the Ready precompute
had each lower-cased the text themselves, but nothing reads `normalised` on either path (it is read only by the Ready intent
matcher, the typed cache and `ask_log`, all typed-only) and `ask_ready_answer` stores the offer's text, never the normalised
form, so no stored key moved. Nothing on the wire moved.

---

## §3 Phases

Every phase: its own branch and worktree off up-to-date `main`; one commit containing the code and
a `changelog.d/` entry; **never push**. Backend gate: `./mvnw clean verify --batch-mode
--no-transfer-progress -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false`, judged on exit
code. Frontend gate: `npm run lint && npm test && npm audit --audit-level=high && npm run build`.
Frontend phases run the adversarial review of CLAUDE.md § *UI Work — Review Cadence* before
committing, reviewers read-only. Migrations are **pending CI**: write the Testcontainers test and
say so in the report. New small records need real tests for JaCoCo's 80% per class.

### P0 — Land the documents — XS
Commit this plan, the prompts file and `docs/design/ask-photocast/` to `main` (owner's
instruction). A fresh worktree cannot see untracked files.

### B1 — Read model, tools, contract, validator — M/L
**Files (new, `service/ask/`):** `AskSnapshot`, `AskSnapshotBuilder`, `AskTools` (+ argument and
result records), the §2.9 records, `AskAnswerValidator`, `AskWindowId` (codec; make
`BriefingRollupBuilder` use it rather than duplicating the format).
No Claude, no controller, no migration.
**Tests** (`AskSnapshotBuilderTest`, `AskToolsTest`, `AskAnswerValidatorTest`):
- the 2026-09-29 shape: three hand-run 4★ slots in an ineligible region of 40 are never returned
- a 5★ wood beside a 3★ headland: the wood is never returned
- a HIGH-state, LOW-wanting coastal slot: `tideState` HIGH, `tideAligned` false, both present
- events 7–8 (they carry a `window()` but are not in `renderedEvents`), passed windows at the minute boundary, travel days: excluded
- `bestBet` location sorts first among equal ratings; `list_windows` exposes the pick
- nothing eligible → empty list with the note
- a null `locationId` slot is skipped; unknown ids are error results; the 6,000-character cap
- every validator rule with a surviving and a dropped case, including the `BEST_*` anchor and URL
  stripping
- agreement: a snapshot's window verdicts and picks equal `BriefingWindow`'s for the same briefing

### B2a — The engine — L
**Files:** `AskEngine`, `ClaudeAskEngine`, `AskToolSchemas` (the SDK `Tool` definitions),
`AskPromptBuilder`, `AskProperties`, `AskJobRunService` (the per-day run, the scoped cost
increment, the spend sum), `RunType.ASK` / `ASK_READY`, `AnthropicApiClient.createAskMessage`, the
`ask` retry / breaker / bulkhead in the four YAMLs, `JobRunRepository` scoped update.
Verify the SDK tool API against the 2.60.0 jar (`javap`), not from memory.
**Tests** (SDK mocked at `AnthropicApiClient`): one-turn and three-turn conversations; turn 4
without `submit_answer` fails; refusal, `max_tokens`, a turn with no tool call fail; a tool error is
fed back as `isError`, not thrown; the deadline stops the loop and shortens the last timeout;
`personal` set only by `maxDriveMinutes`; every turn logged with tokens and the job run's total
incremented; two concurrent first questions create one daily run (second thread); the spend sum
counts `ASK` and ignores `ASK_READY`; the question appears only in the user message; resilience
limits read from the registries; out-of-range properties fail startup.

### B2b — Stub, fixture, dry-run — M
**Files:** `StubAskEngine` (answers from `rank_spots` / `get_hot_topics` with templated text, no
client call; selected by `photocast.ask.stub`), `AskLocalFixtureSeeder` (runs only under the
`local` profile with `seed-local-fixture=true`: writes rated `cached_evaluation` entries for named
local locations across the next two windows — at least two coastal, one canopy, one in a region
left ineligible — then triggers a briefing build; idempotent), `AskAdminController.dryRun`.
**Tests:** the stub makes no client call and its picks pass the validator; the seeder refuses to
run outside `local`; dry-run is ADMIN-only and returns the tool trace.
**Seen, not just tested:** start the local backend with the fixture and confirm `GET /api/briefing`
carries the seeded ratings.

*As built (B2b), where the plan was wrong or silent:*
- **Engine wiring.** `AskEngineSelection` holds two `@Conditional` classes that read `photocast.ask.stub`
  through one conversion and are each other's negation, so exactly one `AskEngine` bean exists for any
  value (two `@ConditionalOnProperty` annotations would leave none for `stub=yes`, which the binder reads
  as true). B2a's `@ConditionalOnProperty` on `ClaudeAskEngine` was replaced. A non-boolean value fails
  startup. **`stub=true` under the `prod` profile also fails startup** (neither templates for readers nor
  a silent fall-back to the engine that bills the key). `StubAskEngine` has no Anthropic client and no job-run
  dependency at all; it enforces the same `AskRunOptions` contract (`requireConsistentWith`, extracted) and
  resolves scope through the shared `AskScopes`; the BEST anchor's lead window is the validator's own
  (`anchoredWindow`, made package-visible), not a second definition.
- **Nothing seeds a local database with regions or locations.** `forecast.locations` in
  `application-local.yml` is bound by `ForecastProperties` and read by nothing, so the "named local
  locations" the plan assumed do not exist. The fixture brings its own: three regions named `Fixture …`
  and 22 locations named `… (fixture)` (the names are how it recognises its own rows), written through the
  repositories.
- **Ratings go in through `BriefingEvaluationService.mergeFromBatch` / `mergeWoodlandFromBatch`**, the
  pipeline's own write path, never `writeFromBatch` (which would replace a region's whole entry) and never the
  table. They survive `enrichSlot` with no `forecast_evaluation` row: the serve-time re-enrichment reads
  `cached_evaluation` alone where no forecast row exists.
- **No BEST BET / ALSO GOOD locally.** A window's `pick` needs the region's Claude gloss headline
  (`PlanWindowProjector#candidate`), and the fixture makes no Claude call, so `BriefingWindow.pick` is null
  on every local window and `list_windows` carries no `bestBet`. The Ready `BEST_*` anchor therefore cannot
  be rehearsed in the browser: B3 tests it with a unit fixture, and §7's "pick 1's card wears BEST BET" check
  needs a real gloss.
- **The briefing build is not free of Claude calls**: it makes the gloss and best-bet calls with whatever
  `ANTHROPIC_API_KEY` is set. That is why `seed-local-fixture` is **off** by default (§9).
- **Tide:** nothing local holds tide data (the WorldTides key is empty), so the fixture writes synthetic
  semi-diurnal extremes for its three coastal locations through `TideExtremeRepository`, continuing an
  existing series' phase and never overwriting, and a coastal fixture slot then carries a real served
  `tideState`/`tideAligned` (one match, one HIGH-water miss, one LOW-water match at the first light).
- **The sample gate** is met by rating every sky location of the two eligible regions (7 of 7, 6 of 6) and
  one of eight in the third, which is refused with its 4★ slot held back. The wood is 5★ and canopy.
- **The H2 enum claim B2a reported was true and is fixed** (§10): `LocalH2EnumWidener`, local profile only.
- `AskQuestionSanitiser` is the one place a question is cleaned (strip control and format characters,
  collapse whitespace, trim, refuse blank or over 200 characters). **B4 must extend it, not clean a second
  way.** The dry-run's `normalised` form is a plain lower-case; B5 owns the real one.

### B3 — Ready answers — L
**Files:** `ReadyQuestion`, `AskReadyService`, `AskReadyAnswerEntity` + repository, migration
(`ask_ready_answer`) + Testcontainers test, the orchestrator dispatch, `AskController.getReady`,
`AskAdminController.precompute`, the ETag allow-list entry.
**Tests:** each availability predicate on both sides of its boundary (Monday vs Friday for
`BEST_WEEKEND`); precompute is idempotent, skips on stale / flag off / simulation / the daily
ceiling; a thrown question does not stop the rest; the pipeline run is COMPLETED before precompute
starts and stays so when precompute throws or overruns its deadline; `maxDriveMinutes` is refused;
a `BEST_*` answer that does not lead with the BEST pick's window is not stored; freshness: a passed
window, a changed rating, a changed verdict, a vanished topic each withhold the **whole** question;
each question's own `runLabel`; role matrix (LITE/PRO/ADMIN + anonymous); flag off → 404.

*As built (B3), where the plan was wrong or silent:*
- **Files:** `ReadyQuestion` (the catalogue, an enum whose constants decide their own availability, text and
  windows), `AskReadyService` (precompute and serve), `AskReadyFreshness` (the serve-time check, pure),
  `AskReadyStore` (upsert and the JSON codec), `AskReadyResponse` (the wire records), `AskReadyAnswerEntity` +
  repository, `V165__ask_ready_answer.sql`, `AskController` (new; B4 adds `POST /api/ask` to it), the dispatch in
  `PipelineOrchestrator`, `POST /api/admin/ask/ready/precompute`, and `/api/ask/ready` in `HttpCachingConfig`
  (which matches `getRequestURI()`, so `?scope=` does not affect it; the body's hash keeps scopes apart).
  `AskSnapshot` gained `briefingStale` (the plan's "skipped when the briefing is `stale()`" had nothing to read:
  the snapshot carried no stale flag; a six-argument constructor keeps every existing caller), and
  `AskSnapshot.Topic.inScope` is now the one topic-in-scope test (`get_hot_topics` and the freshness check).
- **The per-day ceiling is a count of `job_run` rows, not a new table:** scheduled (`triggered_manually = false`)
  `ASK_READY` runs started since UK midnight, against `photocast.ask.ready.max-cycles-per-day`. It is durable
  across a restart and a refused cycle starts no run, so a refusal does not use one up. **An admin's on-demand
  precompute is exempt** (it is a person's decision, as the dry-run is, and counting it would let a local press lock
  the nightly out): it is neither counted nor stopped, though every other refusal still applies to it.
- **One precompute at a time** (`AtomicBoolean`): a second, scheduled or on demand, is refused ("already running"),
  not queued. A whole-precompute refusal is `Result.refusal`; the admin endpoint answers it 409 `{error}` and a run
  that ran 200 `{written, skipped, failed}`. `skipped` counts questions not run: unavailable for the scope, an
  events question that found nothing, or left behind by the deadline or a stop.
- **Last-moment re-checks:** the flag, both simulations and the 5-minute deadline are asked again before every
  question (an overrun stops between questions; the one in flight can finish, up to the engine's own 30 s), and the
  simulation once more after the engine returns, before the write, so an answer built while one switched on is
  dropped. The accounting latch (`AskRun.accountingUnavailable`) stops the whole run: every later call would be
  refused too.
- **What is stored.** Only an `OK` answer the question itself accepts (`ReadyQuestion.violation`): a pick question
  needs a pick (an empty one is a failure: the offer said a candidate existed), an events question needs an event
  (an empty one is `skipped`: "no rare events" is not a card), no pick may sit outside the question's own windows
  (a "this weekend" card on a Friday is a model error the server decides, not the validator, which only anchors
  pick 1), and a coastal-high question's picks must be at high water. `answerable:false` is never stored.
- **Relevance is server-side, from two predicates, and applied at both store and serve** (a Codex review found a
  `SNOW_TOPS` answer could carry any tool-returned event, because the validator only proves an event came from a
  tool). `ReadyQuestion.admitsEvent(type)` and `admitsPick(pick, offer, snapshot, scope)` decide; at store time
  `relevantPart` first removes what the question may not carry (events of other types, and any pick on an events
  question) and `violation` then judges the rest; at serve time `AskReadyFreshness` runs the same `violation` on the
  stored row, so a row written under an older, looser rule is **withheld**, never served. Picks of a pick question are
  never stripped (that would renumber ranks and could remove the BEST BET lead): a pick the question does not admit
  discards the answer. An answer that would lose an event carrying a `safetyNote` is not stored at all (the summary
  may name the event and its card is the only place the warning is shown). A `SNOW_TOPS` answer with no snow event
  left is not stored (a "no snow" summary cannot be verified against the prose); it counts as skipped. Residual,
  stated: a kept answer's summary prose may still mention something that was removed.

  | Question | Event types kept | Picks kept (all: pick-eligible in the question's scope) | Slot must be |
  |---|---|---|---|
  | `BEST_WEEKEND` | none | on a Saturday or Sunday window of the window set; pick 1 on the BEST BET window when there is one | any |
  | `BEST_SOON` | none | on a window that has a pick in scope (offered only when the weekend question is not); BEST BET lead | any |
  | `BEST_NEXT` | none | on the next window alone; BEST BET lead | any |
  | `COASTAL_HIGH` | none | on a window with a coastal slot at high water | coastal, `tideState` HIGH |
  | `AM_OR_PM` | none | on either window of the offered date | any |
  | `RARE_EVENTS` | any type | none | n/a |
  | `SNOW_TOPS` | `SNOW_TOPS`, `SNOW_FRESH`, `SNOW_MIST` (the three snow hot-topic types; no almanac type is about snow, so none is listed) | none | n/a |

- **Serve-time freshness is the plan's four rules plus three** (`AskReadyFreshness`): a question is also withheld
  when it would no longer be offered **under the same text** (a "tomorrow morning" stored on Sunday is not served
  on Monday although its window is still ahead; BEST_SOON once the weekend question has taken over), when the live
  BEST BET window is not the one pick 1 is on (the Plan tab and Ask must not name two bests), and when the
  re-decorated answer would no longer pass `violation`. Names, dates, event labels and safety notes are re-joined
  from the live snapshot; only the stored `why` prose survives from the model.
- **`AM_OR_PM`** is the next date on which both windows are in the window set **and each has a pick-eligible slot in
  scope** (a comparison needs both sides), not merely the next date with both windows.
- **The stub honours the Ready windows**: a `BestAnchor` narrows the whole ranking to its windows, and a day word
  in the question (`today`, `tonight`, `tomorrow`, a weekday, `weekend`) narrows it to that day's windows, so the
  catalogue's day-worded questions answer about the right day locally. Nothing changed for a question without
  either.

### B4 — The typed endpoint and its guards — L
**Files:** `AskController.ask`, `AskService` (steps 1, 2, 4, 7, 8, 9 and the response of step 10;
steps 3, 5, 6 and the log arrive in B5 behind interfaces that are no-ops here), `AskRateLimiter`,
`AskUsageEntity` + repository, migration (`ask_usage`, CASCADE) + Testcontainers test,
`UserSettingsController` `GET /ask`, `AdminAlertService.sendAskSpendCapAlert`.
**Tests:** every row of §2.9's error table; the rate limit fires before the snapshot is built; the
allow-list (accented letters pass, zero-width and control characters are 400); reserve is atomic at
`used = limit − 1` on two threads; the first-question insert race; refund on failure and on
`answerable:false`, on the reserved date across UK midnight under BST, never below zero;
`engine_calls` is never refunded and `DAILY_LIMIT` fires at 3 × limit with `used` still at 0; the
cap blocks, alerts once per UK day, and ignores Ready spend; deleting a user with usage rows
succeeds; the personal path is pinned in `HttpCachingConfigTest`; settings GET is 200 with the flag
off.

*As built (B4), where the plan was wrong or silent:*
- **Files:** `AskService` (the ordered steps), `AskController.ask`, `AskRateLimiter`, `AskUsageStore` +
  `AskUsageEntity`/`AskUsageRepository` + `V166__ask_usage.sql`, `AskSpendGuard`, `AskRequest`/`AskResponse`/
  `AskSettingsResponse`, `AskRefusal` + `AskErrorCode` (the one error shape), the four B5 seams below,
  `UserSettingsController` `GET /ask`, `AdminAlertService.sendAskSpendCapAlert`, and `AskReadyService.suggestions`
  (the `try` choice reuses `serve`'s freshness test through one extracted `freshQuestions`). `AskProperties` already
  held every §2.9 key; B4 added only `limitFor(role)` and `engineCeilingFor(role)`.
- **The rate limit sits in front of the body (Codex round 1, P1).** A limiter inside `AskService.ask` never saw a body
  that failed conversion (the exception handler answered 400 first), so malformed, mistyped or oversized bodies could
  be sent without limit. Step 1 is now `AskService.admit(auth)` (resolve the user, take one slot), called by
  `AskAdmissionInterceptor`, a `HandlerInterceptor` on `POST /api/ask` only (its `preHandle` runs before `@RequestBody`
  conversion). It leaves the admitted user on a request attribute; the controller calls `admit` itself only when that
  attribute is absent, and `AskService.ask(user, request)` counts nothing, so a request is counted exactly once and the
  user is looked up once. Anonymous requests are 401 from Spring Security before any interceptor and are never counted;
  with Ask off nothing is counted (the controller answers 404). **Body size:** nothing bounded it (Tomcat's
  `max-http-form-post-size` and `maxSwallowSize` are 2 MB and cover only form bodies and swallowed bytes; the application
  sets no request-size property; Jackson's string limit is 20 million characters), so `AskBodyLimitFilter` caps a
  `POST /api/ask` body at 8 KiB (a valid body is under 2 KiB), per-endpoint so no other endpoint moves; over it is an
  unreadable body, 400 `INVALID`.
- **Order as built, and where the snapshot is first built:** user lookup (one indexed read; the token carries only
  a username) → 1 rate limit (before the body is converted, above) → 2 sanitise/validate → 3 pre-filter → **4 snapshot (first built here)** → 5 Ready
  match → 6 cache → 7 spend cap + accounting latch → 8 reservation → cap asked again → 9 engine → 10 respond,
  cache, log. Steps 5 and 6 are *before* the cap on purpose: a match or hit costs nothing, so it is served even
  when typed questions are off for the day.
- **No briefing is 503 `TYPED_UNAVAILABLE`** (the admin dry-run's 409 has no row in §2.9's table, and 503 is
  what the client reads as "Ready questions only today").
- **The three 429s and the 503s are told apart by `code` only.** `RATE_LIMITED` is the limiter; `ALLOWANCE_EXHAUSTED`
  is `used >= limit` (named first when both ceilings are reached, since it is the one the reader can see);
  `DAILY_LIMIT` is the never-refunded `engine_calls` ceiling reached with the allowance not. `TYPED_UNAVAILABLE`
  covers the spend cap, the accounting latch (`AskJobRunService.accountingAvailable()`; `AskRun.accountingUnavailable()`
  from the engine, which is also refunded), no briefing, and a database that cannot reserve (fail closed);
  `ENGINE_FAILED` (502) is any other FAILED run, including an engine that throws. Two additions the table lacks:
  a malformed or wrongly typed body is 400 `INVALID` (a controller-local handler, with a fixed sentence that echoes
  nothing; flag off still wins with 404), and a token for a deleted user is 401 `UNAUTHENTICATED`.
- **The cap is a soft ceiling, and the gap is decided, not closed.** The cap check (step 7) and the engine run are
  separate steps and the spend figure is memoised for up to 30 s, so requests that all pass it just before it is
  crossed can together overshoot it. B4 asks the cap **again after the reservation and before the engine runs**
  (a trip refunds `used`; `engine_calls` stays burned, which is harmless because a reached cap stays reached until
  UK midnight). The residual overshoot is bounded by the 5/min rate limit, the daily allowance, the `ask` bulkhead
  (4 concurrent calls) and at most 4 turns a conversation, and the engine's own per-call latch closes the
  unrecorded-cost case. The alert is once per UK day from an in-memory latch (a restart the same day may repeat it).
- **Sanitiser.** `AskQuestionSanitiser` is still the one place: `sanitise` (lenient, the dry-run) and `sanitiseTyped`
  (strict) share one pass. The typed pass composes to NFC first, then *refuses* (never strips) control, format
  (zero-width, bidi, BOM), surrogate, symbol, emoji and combining characters and anything outside letters, digits,
  space and `? ! ' ’ , . - : / & ( )`; whitespace collapses; 200 code points; a raw input over 1,000 UTF-16 units is
  refused before anything else; text with no letter or digit is refused. `normalised` (B5's key) is lower-cased by
  root locale, apostrophes removed, other punctuation a word break, `FILLER_WORDS` dropped (the one list, deliberately
  conservative: no day, place, negation, modal, `my` or `near`), and a question of only filler keeps its words.
- **`try`** is filled only for a `cant` (up to two fresh Ready questions of the question's scope: one region → that
  region, otherwise `ALL`, §6 Q8); an `own` answer carries `[]`. A pre-filter `cant` has no briefing time to name, so
  its `generatedAt`/`runLabel` are null when no snapshot exists.
- **`ask_usage`** is `ON DELETE CASCADE` with a unique `(user_id, usage_date)`; find-or-insert is an `existsBy` + a
  native `INSERT` whose unique violation is read as "the row exists now" (a violation after which the row still does
  not exist is rethrown), then ONE conditional `UPDATE` takes both counters. All reads are projections, never the
  entity (open-session-in-view is on). No pruning yet: at most one row per asking user per day. The usage-row
  cascade is proved on Postgres only by `AskUsageMigrationTest` (pending CI).
- **B5 seams** are `AskPreFilter`, `AskIntentMatcher`, `AskAnswerCache` and `AskLog` (with its `Entry` and
  `Outcome`), each with a no-op class (`NoOpAskPreFilter`, `NoOpAskIntentMatcher`, `NoOpAskAnswerCache`,
  `NoOpAskLog`: `@Component @Fallback`, overriding methods, the interfaces stay abstract) rather than
  `@ConditionalOnMissingBean`, which depends on scan order outside auto-configuration, so B5 adds a plain `@Component`
  and deletes nothing (`AskPhaseB5FallbackTest` proves a user-supplied bean wins). **Not built here:** the in-memory per-user-per-hour count of denied requests (§2.5's INFO line);
  denials log at DEBUG (rate limit) or WARN (cap) for now — built in B5 (`AskDenialCounter`).
- **Shared and fixed on the way:** `AskScopes.validRegionIds` (the admin dry-run now uses it too; it used
  `List.contains(null)`, which throws on an immutable list), and `AdminAlertService.deliver` takes any reference, not
  only a run id.

### B5 — Pre-filter, intent match, cache, log — M/L
**Files:** `AskPreFilter`, `AskIntentMatcher`, `AskAnswerCache`, `AskLogEntity` + repository,
`AskLogCleanupJob`, migration (`ask_log`, SET NULL, the scheduler seed) + Testcontainers test,
`AskAdminController.metrics`.
**Tests:** the phrase list against a should / should-not table ("open horizon", "national park",
"Park Rash"); the pre-filter runs before the matcher ("is the car park busy this weekend" is
`cant`); the matcher refuses qualified questions and unavailable or stale Ready ids; the cache key
separates users for personal answers, region sets, UK dates; a hit that fails freshness is a miss;
a hit is not charged; nothing cached during a simulation; denied requests write no row; the
question is stored only for `CLAUDE_OK` / `CLAUDE_CANT`; deleting a user nulls `user_id`; metrics
never return a raw question.

*As built (B5), where the plan was wrong or silent:*
- **Files:** `PhraseAskPreFilter`, `KeywordAskIntentMatcher` + `ReadyIntentRules` (the pure word rules),
  `CaffeineAskAnswerCache`, `DatabaseAskLog` + `AskLogEntity`/`AskLogRepository` + `V167__ask_log.sql`,
  `AskLogCleanupJob`, `AskMetricsService` + `GET /api/admin/ask/metrics`, `AskDenialCounter`, `AskSimulation`
  (the one "is a simulation on" test, shared by the Ready precompute and the cache). Each implements B4's seam as a
  plain `@Component`; the four no-ops stay (`@Fallback`) and nothing was deleted. `AskService`'s constructor gained
  the denial counter. `AskReadyFreshness.recheck` (the pick and event half of the freshness test, with no Ready
  question behind it) was extracted from `check` so the cache **shares** it rather than copying it, and
  `AskReadyService.freshAnswers` (the fresh, decorated Ready questions for a snapshot the caller holds) was added so
  the matcher serves exactly what a tap on the question would.
- **The pre-filter phrase list** (whole words, split on anything that is not a letter or a digit, lower-cased):
  car park / car parks / car parking / carpark(s) / parking; opening time(s) / opening hour(s); crowd / crowds /
  crowded / crowding; busy / busier / busiest; queue / queues / queued / queuing / queueing; toilet(s); café(s) /
  cafe(s); shop(s); pub(s); restaurant(s). Each topic has one fixed `missing` phrase (priority order: car park
  information, opening times, visitor numbers or crowd information, toilet information, café, pub, restaurant or
  shop information); a question that is about both the car park and the crowds says "visitor numbers or car park
  data". The `summary` is fixed too ("PhotoCast covers sky colour, weather and tides. It has no <missing>."), never
  the model's. **"Busy" is the one judgement call:** it is kept as a whole word, but a "busy" beside a sky word (sky,
  skies, cloud(s), clouded, horizon: before it, after it, or before it with is/are/looks/gets/be between) is a busy
  sky, not a busy place, and is not a match — a wrongly passed question costs one refunded engine call, a wrongly
  refused one costs the reader their answer.
- **The matcher's rules** (`ReadyIntentRules`): every word of the normalised question must be accounted for. The Ready
  question's own subject phrase is taken out, and every word left must be a plain asking word (where, best, good,
  spot, go, for, in, on, at, of, over, worth, should…) or one of a few subject-specific words, and at least one of them
  must ask for a place. An unknown word is a reason not to match: a named place, a drive or distance, a number, another
  day, a time of day the answer ignores, a negation. The common drive and distance words are also refused by name
  (`QUALIFIERS`), and any location or region name in the snapshot, as whole words, stops a match even when it is made of
  ordinary words. Subjects: weekend ("this weekend", "weekend"; and "Saturday"/"Sunday" only while every pick of the
  answer is on that day, since the answer covers both); next few days ("next few days", "next couple of days", "coming
  days", "few days", "this week"); the next window, read from the Ready question's own text (tonight / this evening,
  this morning, tomorrow morning / sunrise, tomorrow evening / night / sunset, "Saturday evening"…); "high tide" / "high
  water" with an optional coastal word; "sunrise or sunset" with the Ready question's own day or none; a rare / special /
  unusual with event(s) / anything / happening / things; snow with tops / hills / fells / mountains. A question with a
  word no rule knows costs no read at all; otherwise one `findScope` read. **The context window is not a reason to
  decline** (a matched question has fixed its own time; one that names none is never matched).
- **The cache key as built:** `(sorted distinct region ids | ALL, UK civil date from the injected clock, the
  snapshot's generatedAt, normalised question, windowId | null, userId | null)`. **`personal` is
  `AskOutcome.personal()`, which `AskTools` sets the moment a conversation calls `rank_spots` with `maxDriveMinutes`
  (before it checks the asker has drive times) — the server's decision, never the model's.** A personal answer is
  stored under the asker's id; a lookup tries the shared key and then only the caller's own key, so another user's
  personal answer cannot be named. Residual: a personal answer is not re-keyed on a change of home within its 30
  minutes. A hit is `AskReadyFreshness.recheck` against the live snapshot with the scope names stored at write time
  (so a hit costs no database read); a failed check evicts that entry and is a miss. **Only an answer with a pick or an
  event is stored**: one with neither ("nothing is worth it today") has nothing the freshness test can hold it to, so
  serving it later could contradict the forecast. Nothing is stored while a simulation is active (lookup is not
  blocked: a hit is re-checked against live data like any other). Maintenance runs on the calling thread, so the
  bound holds at once; the cache's clock is the injected one.
- **`ask_log`** is V167. Outcomes are the six in B4's `AskLog.Outcome` (no more). `outcome` is plain text, not a Java
  enum column (no local H2 enum trap when one is added), and the row is inserted by one native statement with the three
  nullable columns cast (no half-persisted entity in the request's session, and a typed null on Postgres). The
  question is stored only for `CLAUDE_OK`/`CLAUDE_CANT` — by `DatabaseAskLog`, **and by a check constraint** on the
  table — capped at 200 characters; `missing` only for `PREFILTER_CANT`/`CLAUDE_CANT`, capped at 60 (both cut on code
  points). A failed write is logged at ERROR and swallowed. **The cron slot is 03:55 UTC** (every seed across the
  migrations was read: 02:00 Mon, 02:40, 03:00, 03:10, 03:30, 03:45, 03:50, 04:00, 04:40, 05:00, 05:30/17:30, 14:00,
  15:00, 22:00 and :20 past every hour are taken). Retention is on `created_at`: a row exactly the retention old is
  kept, the first deleted is a moment older.
- **Denied requests** are every `AskRefusal` except `UNAUTHENTICATED` and `ENGINE_FAILED` (a failure has its own
  `CLAUDE_FAILED` row): rate limited, a malformed or over-long question, allowance used up, the daily engine ceiling,
  typed questions unavailable before the engine ran. `AskDenialCounter` keeps one open hour per user (bounded: a user is
  reported and forgotten when their next denial arrives in a later hour or a sweep, every 64 denials, finds the hour
  over) and logs one INFO line per user per hour with the counts by reason. It never carries a question.
- **Metrics** (`days` is a whole number, clamped 1..90, default 7; blank is the default, non-numeric is 400): the
  window is the last N UK civil days including today. `cacheHitRate` = hits / (hits + engine runs);
  `readyMatchRate` = Ready matches / every answered request; `cantRate` = (pre-filter + engine can't) / answered, where
  answered excludes `CLAUDE_FAILED`; each is null when its denominator is zero. Spend is
  `sumCostMicroDollarsByRunTypeStartedSince` for `ASK` (it includes an admin's Claude dry-run) and `ASK_READY` over the
  window; cost the engine could not record is not in a multi-day figure. The only free text is the `missing` phrase;
  no query selects the question column.
- **Not done / not feasible:** a JPA-level (H2) proof that deleting a user nulls `user_id` — the entity holds the user as
  a plain column, as every per-user table does, so H2 has no foreign key to test; V167's Testcontainers test proves it on
  Postgres (pending CI).

### F1a — Client core, unmounted — M/L
**Files:** `api/askApi.js`, `hooks/useAskReady.js`, `hooks/useAskAllowance.js`,
`context/AskContext.jsx`, `utils/askModel.js`, `components/ask/AskConversation.jsx`,
`AskPickCard.jsx`, `AskEventCard.jsx`, `AskContextChips.jsx`, their CSS. Nothing is mounted in the
app. Fixtures are the literal JSON of §2.9. `AskEventCard` renders an event's `safetyNote`
visibly whenever it is present, outside every role gate and every narrow-viewport drop.
**Tests:** an event with a `safetyNote` renders it visibly for LITE, PRO and ADMIN and at a narrow
width, and an event without one renders none; every state — empty, busy (both lines), answer, can't answer (from the pre-filter shape
and from `kind: cant`), allowance used up, each error code; a Ready tap makes no POST; a typed
`kind: ready` reply is rendered as Ready with "no question used"; the count comes from the
response, never decremented locally; a pick with no slot is dropped; drive is the **home** map even
when the provider's origin is away; the live region is empty while hidden; nothing is written to
`swrCache` or module scope (a logout must not carry an answer to the next user).

*As built (F1a), where the plan was wrong or silent:*
- **Files:** `api/askApi.js`, `hooks/useAskAllowance.js`, `hooks/useAskReady.js`, `context/AskContext.jsx`,
  `utils/askModel.js`, `components/ask/{AskConversation,AskPickCard,AskEventCard,AskContextChips}.jsx`, the `.wf-ask-*`
  block at the end of `index.css`, and one word in `utils/locationSheet.js` (`slotsOf` is now exported: the pick join
  walks one window's slots through the same function every index in that file uses). Nothing is mounted; `App.jsx` and
  the shell are untouched.
- **The home reach map already exists and needed no change.** `WindowFirstBriefingProvider` publishes `reachById` (the
  reader's `GET /api/user/settings/reach`, home) beside `effectiveReachById` (replaced by the region-base matrix when the
  Plan origin moves), and has since the origin landed. Ask reads `reachById` and never the other. A test moves the
  origin on the real provider and asserts both maps and the card's drive.
- **The context API (what F1b/F2 consume).** `AskProvider` goes inside `WindowFirstBriefingProvider` and above every Ask
  surface — F1b mounts it in `App.jsx` around `WindowFirstShell`, so a map pane rendered as a shell child sees it; F1a
  mounts nothing. `useAsk()` returns:
  `phase` (`empty|busy|answer|cant|error`; `plan` is reserved for F5 and entered by nothing here), `kind`
  (`ready|own|cant`; while busy, the kind being waited for), `question`, `answer`
  (`{id, answerable, kind, summary, picks, events, missing, try, runLabel, generatedAt, charged, allowanceLeft,
  allowanceLimit}`; `id` is monotonic, new for every answer that lands and unchanged when a refusal puts an earlier
  answer back, so F3's camera fits on a changed `answer.id`, never on `pickCards`' identity), `pickCards` (the picks
  joined by `buildPickCards`), `selectedPick` (a rank or null, never a rank without a card), `planPick` (null until F5),
  `contextWindow`/`removedWindow`, `error` (`{status, code, message}` in phase `error`), `inputError`, `restored` (the conversation on screen is an earlier
  one a refusal put back), `busyRunLabel`,
  `allowance` (`useAskAllowance`'s value), `typedDisabled`, `availability`, `isPro`, and the actions
  `askTyped(question, {windowId, regionIds, view})`, `openReady(readyQuestion)`, `selectPick(rank|null)`, `clear()`,
  `retry()`, `removeContextWindow(windowId)` and `restoreContextWindow()`. `askTyped` **resolves** with what became of
  the question (`ignored|answered|refused|failed|superseded`), so a field that clears itself on submit knows whether to put
  the text back. `AskConversation` takes `view`, `scope`, `viewLabel`, `windowLabel`, `windowId` (required with `windowLabel`),
  `hidden` and `pickActions(card)` (a node for each pick card's own row: F1b's "Show on map ›", F5's "Plan this ›"). The field, the
  placeholder (`READY_ONLY_PLACEHOLDER` in `askModel.js` when `typedDisabled`), Escape, scrolling and where Ask opens are
  the surface's. **`AskConversation` renders `inputError` itself** (in its own live region), so a field must not render
  it a second time; F4's collapsed ask row, where the conversation is `hidden`, must show it where the field is. **The
  provider has no in-flight guard**: a second `askTyped` while one is out supersedes the first (its answer, if it was
  charged, is dropped and the allowance re-read), so F1b's field must not submit while `phase === 'busy'`.
- **`availability` has four values because "Ask is off" and "we do not know yet" are different.** `pending` (the first
  settings read has not landed: show nothing, or Ask flashes on a server with the flag off), `off` (settings say
  `enabled: false`, or a POST was 404: hide every surface), `down` (the first read failed and nothing is known: show Ask,
  disabled) and `on`. A LATER failed read leaves `on`. The **default context value — no provider — is `off`**, so a shell
  rendered without one (every shell test that predates Ask) draws no Ask surface and acts on no key. Rewind is the shell's
  call and not here, and F1b must also keep the provider from mounting its settings read under a rewind, because the
  axios interceptor puts the rewound clock on every GET outside `/api/admin/`.
- **`typedDisabled` is true whenever Ask is not known to be on** (`availability` is not `on`: still pending, off, or
  unreachable) **and** from two further sources, because settings alone cannot tell them apart: the settings read
  (`typedAvailable: false`, or `left <= 0`) and a refusal this session that means "none today" — `ALLOWANCE_EXHAUSTED` or
  `DAILY_LIMIT`, remembered for the UK day it came on (`DAILY_LIMIT` is the never-refunded engine ceiling, which
  `GET /api/user/settings/ask` does not carry). `TYPED_UNAVAILABLE` is deliberately NOT remembered: it is transient (a
  briefing rebuild, an accounting latch) and the settings read reports it live. The
  allowance is also re-read when the tab returns on a later UK day (`useAskAllowance`, the shape of `useTodaysLight`'s
  day check), so an installed PWA left open overnight does not stay on "No own questions left today".
- **Refusals restore the conversation.** `INVALID`, `RATE_LIMITED` and the three "no typed questions" codes put back
  what was on screen before the ask, with the server's sentence in `inputError` — nothing was used and a typo must not
  cost the reader their answer. What "Try again" re-asks is part of that snapshot (`conv.retryWith`), so a refused
  question cannot hijack the retry of an earlier failure (a review finding). Everything else that is not a refusal, the
  404 or a superseded response becomes phase `error`: `ENGINE_FAILED`, an unreadable 200, a 401, any other status, and a
  failure with no response. **A lost connection does not say "No question used"** (the request may have reached the
  server); the allowance is re-read instead. A typed question has a 40 s client ceiling (`ASK_TIMEOUT_MS`), a little over
  the server's own 30 s deadline.
- **The allowance is re-read after every POST that could have moved it** (an answer, a failure, the three refusals that
  can, and a superseded response that landed late), and a 200's own `allowanceLeft`/`allowanceLimit` is applied at once.
  Nothing counts down locally, and a stale response's figure is never applied.
- **Late responses and the Ready timer.** One sequence number is bumped by every ask, Ready tap and `clear()`; a typed
  response that finds it moved is dropped, success and failure alike. The Ready answer's 400 ms is a timer, so it is
  cancelled with `clearTimeout` in the four places that must (a new Ready tap, a typed ask, `clear()`, unmount);
  mutation-tested one at a time. (An unmount needs no sequence bump: React ignores an update to a tree that is gone, so
  that line was dead and was removed.)
- **The Ready busy line is not the design's copy verbatim.** "Opening this morning's answer" is written for the 06:00
  run, and there is an 18:00 run: `readyBusyLine(runLabel)` says "this evening's" for a run at or after 12:00 and
  "Opening the answer" for an unreadable label. A lexical map over the served `HH:mm`, in the same licensed class as the
  AM/PM word.
- **The Ready list is fetched only once a briefing exists** (`generatedAt` is the hook's key), and the hook only ever
  hands out a list fetched for the key it is asked about, so a scope change, a rebuilt briefing or a surface shown again
  after being hidden shows an empty list rather than the old one while the new one loads. State is per mount, so a
  surface that unmounts when closed (`BottomSheet` returns `null`) refetches each time it opens, which the browser's
  ETag revalidation makes cheap. The "READY FROM THE HH:MM RUN · FREE" line names the **newest** run among the questions
  offered on that tab (each question carries its own `runLabel`); the suggestions are every served question whose `tabs`
  names the view, in served order, not the design's three. A `cant` reply's `try` suggestions are resolved against the
  list the reader already holds, and one that does not resolve is left out.
- **A pick whose slot is in the client's briefing but has no usable rating keeps its card** (verdict word "Not scored",
  no number), because the Plan tab says exactly that of the slot; only a pick with no slot at all is dropped. Ranks are
  never renumbered, so a card's number is its map marker's number. **The cards are live and the prose is not:** the cards
  are re-joined to the briefing the reader is looking at on every render, so they always agree with the Plan tab, while
  the summary is the answer's own words and its footer names the run it came from. An answer left open across a
  briefing rebuild is therefore dated, not rewritten; if the owner wants it withdrawn instead, `answer.generatedAt` is
  there to compare against the briefing's.
- **Cards carry what F3 and F5 will read raw:** `eventInstant` (the slot's served UTC instant, for `leaveByParts`) and
  `summary` (the slot's served one-line reading, F5's note), beside `shortWindow`, `dayWord` and `targetType` (F3's
  `eventType`). `data-ask-pick`/`data-ask-pick-select` on the `li` and the button are the non-test hooks for scrolling
  and focus.
- **Pick card structure.** The rank circle is a sibling of the card's button, not inside it, because a button's
  children are presentational to a screen reader and the circle must carry "Pick 1, Whitby, Saturday sunrise, 5 stars";
  the button also leads with an sr-only "Pick N" because the circle is not focusable (in browse mode the rank is said
  twice, the lesser cost). The button is stretched over the card with `::after`. **The card's own row is a third
  element: `.wf-ask-pick-act`, below the button in column 2, positioned and raised above that `::after`** (a button
  inside a button is invalid, and an unpositioned sibling would paint beneath the overlay and never be pressed). **The
  selected card is `aria-current`, not `aria-pressed`**: choosing a pick does not toggle, and a pressed button promises
  an undo it does not have. The first draft of this note put "Plan this ›" in a "right-hand cell"; there is none.
- **Accessibility, as built.** Three live regions, each mounted empty: an sr-only `role="status"` carrying the busy line
  (the visible dot and line are `aria-hidden`), one `aria-live` container for the answer / not-in-the-forecast / error,
  and one for the refusal sentence, so a refusal is heard without the previous answer being read out around it; an earlier
  answer that a refusal PUTS BACK is rendered outside the live region (`restored`) for the same reason. The empty
  state's suggestions are outside all three (a `cant` reply's own "Try asking" list is part of that reply and inside). The three controls that unmount when pressed (a suggestion, "Try again", a chip's ✕)
  move focus to the conversation root (`tabIndex -1`) first. The safety note names itself ("Safety warning:") to a
  screen reader and is in no media query (`askCss.test.js` pins it against `index.css`, since jsdom loads no stylesheet).
- **"Pro: 30 a day" is a constant** (`PRO_DAILY_LIMIT`): nothing serves the Pro limit. It is `photocast.ask.limit-pro`'s
  default; if that is ever changed the copy lies, and the fix is a `proLimit` on `GET /api/user/settings/ask`.
- **The window chip is keyed on the window, not a flag.** `removeContextWindow(windowId)` records which window's chip was
  removed; a different window brings the chip back by itself and is sent, and `askTyped` leaves out only the removed one.
  `restoreContextWindow()` is for a tab switch back to the same window.
- **Token mapping** (the design's bare `--bg`/`--ink`/`--go` do not exist; the mapping and the deliberate deviations are
  in the comment heading the `.wf-ask-*` block): `--surface/--panel/--border/--border-light` →
  `--color-plex-surface/-panel/-border/-border-light`; `--ink` → `--color-plex-text`; `--ink-2` (70%) and `--ink-3` (50%)
  both → `--color-plex-text-secondary` (66%), since the muted token (42%) is ~3.5:1 and every line the design sets in
  `--ink-3` here is information; `--home` → `--color-home`; `--go` → `--color-badge-go` (text) with `rgba(138,174,114,.4)`
  for the Ready tag's border; `--marginal`/`#E0735E` → `--color-badge-maybe`/`--color-badge-poor`; `#8FC0C7` →
  `--color-badge-tide`; `#EBD9A8` → `--color-segment-active`; `#F9F1E2` → `--color-plex-gold-light`; `#1B1411` →
  `--color-plex-bg`; `#EE8064` / `--dawn` → `--color-plex-coral-bright` / `--color-plex-dawn`; the event card's three hex
  values → `badgeChannel`'s five channels (`--color-badge-eclipse/-tide/-nlc/-go/-snow`). The chip's ✕ is 24px, not 20.
  The safety note's text is the primary ink, as the Plan tab's own safety lines are, with an amber edge and tint.
- **Not built, by design:** "Plan this ›" (F5), "Add to Coming up" (removed), the input field, Escape, the sheet and the
  dock (F1b/F2), the numbered markers (F3), and anything that carries the Map's window and scope to a surface (F1b/F2/F4
  each read them from `MapView`'s own state; the `windowId`/`regionIds`/`view` arguments are theirs to supply).

### F1b — Phone and tablet-portrait entry — M/L
**Files:** `components/ask/AskBar.jsx`, `AskSheet.jsx`, `hooks/useVisualViewportHeight.js`,
`BottomSheet.jsx` (`size="tall"`, `closeOnEscape`), `WindowFirstShell.jsx` (`askOpen`, the
`selectTab` list, the pane's 58px reserve), the 640–1023 field beside the tab row (shared with F2:
build `AskField.jsx` here in its 260px form). On the phone Map tab there is **no** entry until F4.
"Show on map ›" switches tab and closes the sheet; the numbered markers arrive in F3.
**Tests:** the bar appears on Plan and Coming up below 640 and not on Map or Operations; existing
`BottomSheet` callers are unchanged (60vh, no Escape); tall mode changes both sites; Escape closes
only with the opt-in; the sheet is the single modal and a tab switch closes it; hidden while
rewound and when `enabled` is false; disabled when the backend is down; no shell dialog can be open
under it.
**Seen:** 390 × 844 on the fixture — the bar does not cover the last Plan row; the iOS Simulator
keyboard does not cover the input.

*As built (F1b), where the plan was wrong or silent:*
- **Files:** `components/ask/{AskBar,AskField,AskSheet,AskInputRow}.jsx` (the input row is its own component — F2's
  dock and F4's peek row repeat it), `hooks/useVisualViewportHeight.js`,
  `hooks/useAskSurface.js` (new; the band — `phone` < 640 via `useIsMobile`'s own query, `tablet` 640–1023,
  `desktop` — F2 reads the third value, so it adds no second width test), `BottomSheet.jsx`,
  `WindowFirstShell.jsx`, `App.jsx` (`AskWhenLive`), the F1b block at the end of `index.css`, and the shared test
  helpers `test/askShellHarness.jsx` and `test/askViewport.js` (a width-aware `matchMedia`; the app's own
  `resolveInitialTab` query has a comma in it, which the helper handles).
- **Shell state.** `askOpen` is a `useState` of `WindowFirstShell` (declared with `searchSeed`, above `selectTab`),
  and `selectTab`'s body gained exactly one line, `setAskOpen(false)` — so the settings edge, the cog, the nudge, a
  `tabRequest`, the location-sheet handoff and a tab press all close the sheet through the one list. It is never set
  from `AskContext`. What the shell derives from it: `askEntry` (`'bar'` phone on Plan/Coming up; `'field'` tablet on
  Plan/Coming up/Map; else null — **nothing at ≥ 1024px until F2** — F2 gave those widths `askDockEntry`, a separate flag, below), `askSheetOpen = askOpen && askEntry !== null`,
  and `askDisabled` (Ask `down`, `contentDisabled`, a window popup, a layer over it, search or settings).
- **`AskProvider` is in `App.jsx`, inside `WindowFirstBriefingProvider`, around `WindowFirstShell`, and is not
  mounted while `useRewind()` is set** (`AskWhenLive`). Without a provider `useAsk()` is the F1a default —
  `availability: 'off'` — so a rewound page has no Ask surface and makes no `GET /api/user/settings/ask`; the test
  asserts the mocked call was never made. `RewindGate` remounts the tree on every change, so the choice never flips
  in place.
- **`BottomSheet` opt-ins, and one more.** `size="tall"` and `closeOnEscape` as briefed, plus `footer`, a node
  held below the scroller and outside it — the input row cannot be a sticky child of the scroller (a sticky row sits
  after short content, not at the sheet's foot, and the scroller's own padding scrolls). `tall` is the **visual
  viewport** minus 24px as a `height` AND a `maxHeight` (an input at the foot has to BE at the foot), the scroller's
  budget follows it (`calc(<tall>px − 40|64px − var(--safe-b))`), and the sheet is lifted by `bottomInset` (layout
  viewport − visual height − offsetTop, clamped to 0) so a keyboard cannot cover it. `closeOnEscape` answers only
  when the sheet is the LAST `role="dialog"` in DOM order (sheets portal to the end of `<body>` at one z-index, so DOM
  order is paint order) and ignores an IME's composing Escape. **Defaults proven unchanged** by value in
  `BottomSheetTall.test.jsx` (60vh, the exact class strings, no `data-size`, no `bottom`, no footer, no Escape, no
  viewport subscription) with the pre-existing `BottomSheet.test.jsx` untouched and passing. Two seams you will
  trip on: the tall flex-column layout is `.app-safe-sheet[data-size="tall"]` in `index.css`, **not** conditional
  utility classes, because the sheet's class string is an exempt dialog root that `formFieldFocusRules.test.js`
  reads as a plain literal; and the scroller gained `data-testid="bottom-sheet-scroller"` and the strip
  `data-testid="bottom-sheet-strip"` (attributes only — the CSS and the tests address them by those). The calc
  literal is also scanned by `safeAreas.test.jsx` for a safe-area term, so no comment spells it. Found in review and
  fixed: the close-button strip is `shrink-0` (an empty flex item has no minimum size, and a long answer squeezed it
  by ~12px so the scroller slid under the ✕), and the sheet gives back `max(0px, calc(var(--safe-b) − <lift>px))` of
  its home-indicator padding as the keyboard lifts it, continuously rather than at "lift > 0" (iOS's toolbar
  animation reports an inset of a pixel or two with no keyboard). The scrim is the app's one shared `.app-scrim`
  (74%), not the mock's 50%.
- **`useVisualViewportHeight(enabled)` returns `{height, bottomInset}`** (a number would not carry the keyboard's
  lift). A `useSyncExternalStore` over a string of three rounded integers; with `enabled` false it subscribes to
  nothing, which is how every existing `BottomSheet` pays nothing for it. Absent `visualViewport` (jsdom) it is
  `innerHeight` with no inset.
- **The page behind the sheet is `inert` — the whole app container, not the shell root.** Not in the plan:
  `useDialogFocus` is not a focus trap, so a keyboard reader could Tab out of the sheet onto a window card behind the
  scrim and open a popup UNDER it (two `aria-modal`s). The first cut put `inert` on the shell root; review found four
  lenses agreeing that the banners (`AuroraBanner`'s "view on map" opens the z-200 `MapOverlay` under the sheet), the
  session banners and the footer sit OUTSIDE it. It is now set on the shell's ancestor that is a direct child of
  `<body>` (`#root`), by a **layout effect** with a hand-set attribute — on purpose: `useDialogFocus` restores focus in
  a passive cleanup, a node inside an `inert` subtree cannot take focus, and a layout-effect cleanup runs earlier in
  the same commit, so `inert` is gone by then (state in an ancestor would come off a commit later and the restore
  would fail). The sheet portals to a different child of `<body>`, so it is not covered. The opener is blurred by
  `inert` before `useDialogFocus` reads it (and a tap on iOS never focused it), so the sheet takes `restoreFallback`
  (the trigger if still connected and enabled, else the tab in force, read from the DOM). jsdom has no `inert`: tests
  assert the attribute and its removal on close and on unmount, never a browser's refusal. The attribute is neither set
  nor cleared if something already holds it. Cost, stated: while the sheet is open the session-expiry banner's
  "stay signed in" is as dead as the rest.
- **Opening Ask never closes a dialog.** The entry is a real `disabled` button/field while any shell dialog,
  a layer over it, search or settings is open (a refusal, nothing taken down); `openAsk` additionally refuses when a
  `role="dialog"` outside the shell root stands (the map overlay, the settings modal are siblings the shell cannot see
  as state), the way `/` does. The `/` handler was untouched in F1b (F2 rewrote it: it now belongs to Ask, and below 1024px it does nothing).
- **The entry controls are buttons, not inputs** (the mock's `<button class="askbar">` / `.askf`): the question is
  typed in the sheet, where the answer is. The bar stays MOUNTED while the sheet is open (the mock hides it) because it
  is the sheet's return address. `AskField` takes `width` (260|340), `showKeyHint` (draws "/" and sets
  `aria-keyshortcuts`; F1b never sets it) and `prompt` — the props F2 needs. Below 720px with four tabs
  (`data-tab-count="4"` on the always-rendered `.wf-tabrow`) CSS collapses the field to 34px by CLIPPING its label,
  never `display: none`, so its name survives. The tab row wrapper is **always** rendered, with or without a field:
  wrapping conditionally would remount the tab buttons when Ask's availability resolved. On the tablet's Operations
  tab (no field) an `aria-hidden` ghost box (`.wf-askf-ghost`, `visibility: hidden`) holds the field's width, because
  Operations is pinned to the tab list's right edge by `margin-left: auto` and would otherwise slide ~282px under
  the pointer the moment it was pressed.
- **The 58px reserve is at the end of the document, not on the pane** (plan §2.6 said the pane). The footer (two
  social links) follows the pane, so padding on the pane would have left the links under the bar at the end of the
  scroll. `.app-safe:has(.wf-ask-bar-on) { padding-bottom: calc(var(--safe-b) + 58px) }`, with `.wf-ask-bar-on` on the
  shell root only while the bar is drawn; where `:has()` is unsupported the bar overlaps the footer. The same class
  gives the viewport `scroll-padding-bottom: calc(var(--safe-b) + 58px + 8px)` (`html:has(.wf-ask-bar-on)`), the
  bottom twin of the lens bar's `scroll-margin-top`: end-of-document padding only helps the LAST row, and a control
  tabbed to near the bottom of the viewport otherwise scrolls to just under the opaque bar (WCAG 2.4.11). The bar's
  z-index is 40 (a test reads `Modal`'s z-50 and the sheet's 9999 out of source and pins the order).
- **What the sheet does and does not send.** `view` is the tab (`plan`/`coming-up`/`map`), `regionIds: []`, no
  `windowId`, and the chip says what is sent: "Plan · all regions", "Coming up · all regions", and — on the tablet
  Map — "**Map · all regions**", because the Map's scope segment and window are `MapView` state the shell cannot
  see. Carrying them to a surface is F3/F4's.
- **Closing keeps the conversation; "Clear answer" ends it.** The ✕, the scrim and Escape only close (`dismissAsk`
  is `setAskOpen(false)`); the answer is there when the sheet reopens. The first cut cleared on close (the mock's ✕
  does, and a first cut spared only a question still out); review found it contradicted this plan — §2.8's highlight
  is "applied when the sheet closes", §2.7's "clearing the answer" is an explicit act — and was a trap: a mis-tap on a
  full-viewport scrim binned an answer the reader was charged a question for, and on the tablet Map (where "Show on
  map ›" is not offered) closing was the only way to see the map. So the sheet carries a **"Clear answer"** text
  button after the conversation (answer, not-in-the-forecast and error phases; never while busy, since clearing then
  drops a charged answer), which moves focus to the question field first (it unmounts when pressed) and is the only
  way back to the Ready suggestions, which the conversation shows only in its empty phase. The phone-Map ask row's ✕
  (F4) is the F4 table's own "clear answer". Navigating away keeps it too.
- **`askOpen` cannot outlive its surface.** `askSheetOpen = askOpen && askEntry !== null`, so with the window crossing
  1024px, a phone Map, or Ask switched off, the sheet unmounted but `askOpen` stayed true and the sheet **came back by
  itself** (focus and all) when an entry next existed. The shell now lets `askOpen` go in the render the entry goes
  (`if (askOpen && askEntry === null) setAskOpen(false)`, the settings edge's shape — own setter, during render, no
  loop). The conversation is untouched.
- **The entry is disabled by what is ON SCREEN, not by a stale key:** `askDialogOpen` reads `openCard`, not
  `openWindowKey` — the key of a window whose event has passed is deliberately never released, and would leave the bar
  dimmed with no dialog showing.
- **"Show on map ›"** is a `pickActions` button on each pick: it selects that pick (`ask.selectPick(rank)` — F3
  starts from it), `selectTab('map')` (which closes the sheet), then focuses the Map tab button a frame later — the
  bar that opened the sheet unmounts on that press and focus would otherwise fall to `<body>`. `pickActions` is
  always a function (returning null when there is nothing to offer) so F5's "Plan this ›" joins it. Offered only when
  the shell was handed a Map pane and the reader is **not already on the Map** (nowhere to go). Its accessible name
  is "Show on map — Whitby" (the visible words lead the name). Until F3 the Map shows nothing extra for the answer;
  the conversation survives for the reader to reopen (tablet: the field; phone: F4's peek row, not built here).
- **The question field is `readOnly` + `aria-disabled`, not `disabled`, when typed questions are off**
  (`typedDisabled`, with `READY_ONLY_PLACEHOLDER`, which is also a hidden sentence the field is `aria-describedby`,
  because a placeholder is not reliably announced on a read-only field; the locked state dims nothing — the first cut's
  `opacity: .6` took the only explanation to 3.3:1). It can flip while the reader is typing (a refusal that means "none
  today"), and a `disabled` control that held focus drops it to `<body>` — jsdom keeps it, so the test asserts
  `not.toBeDisabled()`. It never submits while `phase === 'busy'` (the provider has no guard), clears itself on submit
  and puts the text back for `refused`/`ignored`; 16px, pinned against `index.css` as text; its edge is the bone ink at
  .4 (3.3:1 on the sheet), since `--color-plex-border-light` measures 1.6:1. Opening focuses it (the sheet's parent
  effect runs after `useDialogFocus` has captured the opener) **unless typed questions are off**, when focus stays on
  the dialog, which says what it is. Recorded for the owner's browser check: focusing the field at once raises the iOS
  keyboard and the dialog's name may not be announced first (the brief mandates the focus; the locked case is the only
  carve-out).
- **Static import, no `lazy`:** a lazy sheet would leave a window with no dialog and the page already `inert`.
- **Tested, not seen** (nothing past the login page could be reached — the session cannot sign in; the login page
  rendered at 375 × 812 with no console errors, which is all that was SEEN): the bar's position over the last row and
  the footer reserve; the field's alignment and width beside 3 and 4 tabs at 640/720/834/1023; the scrim and the
  sheet's slide-up and height; the sheet's height and lift under the iOS keyboard (including that focusing the input
  on open raises it at once, and the lift's dead gap on a notched iPhone); focus returning to the bar, and landing on
  the Map tab; `inert` actually blocking Tab and pointer and the focus-restore ordering it relies on; `:has()` support
  for the footer reserve and the focus scroll-padding; the field's focus ring in forced colours; the Map tab on a
  tablet with the sheet over it; the 720px four-tab collapse (a hand estimate left under 10px of slack at 720 with
  the Coming-up badge showing — check 720 and 726 as an admin, and raise the breakpoint to 740 if it scrolls).
- **For F2:** (1) the four-tab collapse is a **viewport** media query, but the dock narrows the *column* (a 360px
  dock leaves ~664px of column at a 1024px viewport, where no viewport query fires) — move it to a container query on
  `.wf-tabrow`. (2) `askSheetOpen` drives the app-container `inert`; the dock is not modal, so give the dock its own
  openness (do not add a `'dock'` value to `askEntry` and let `askSheetOpen` carry it, or the dock inerts itself),
  and make the dock `inert` — not the page — while a shell dialog is open. (3) The "any `role="dialog"` outside the
  shell root" test now exists in three places (the `/` handler, `openAsk`, and a test); extract it before the `/`
  handler's rewrite adds a fourth. (4) `AskField`'s `width`/`showKeyHint` are built; "260 below 1180" needs a CSS
  override or a fourth band from `useAskSurface`. (5) Closing keeps the conversation (above): a docked, non-modal
  surface wants exactly that.
- **For F3:** (1) **`askWindow` cannot be App state written from the conversation** — `AppInner` is *above* `AskProvider`
  and cannot call `useAsk()`; the pane and `MapView` are provider descendants and can. Keep the channel in the pane
  (or lift the provider above `AppInner`'s state). (2) The Map's scope segment, focused region and window are `MapView`
  state: the shell sends `Map · all regions`, no region, no window for the tablet-on-Map sheet. F3/F4 need a small
  registration channel (a context or ref the pane writes `{regionIds, windowId, windowLabel, viewLabel}` into) for the
  sheet to read. (3) The tablet-on-Map sheet is a modal over an `inert` map, so a pick cannot be tapped on the map
  while the sheet is open; with closing now keeping the answer, the reader closes the sheet to see the markers — decide
  whether a pick-numbering arrival should also close the sheet there, and fit the camera when it closes, not when the
  answer lands (the sheet covers the map then). `"Show on map ›"` leaves `selectedPick` set to the pressed pick.
- **For F4:** `'peek:ask'` lives in `MapView`'s `openMapMenu`, which `selectTab` cannot reach; the pane is mounted but
  hidden after the first visit and gets no `active` prop, so "tab switch away and back: closed" needs one.
- **For F5:** `askPickActions` is the per-card hook ("Plan this ›" joins "Show on map ›"). The sheet's footer is the
  input row whatever the phase; `phase === 'plan'` needs its own footer ("‹ Back to the answer") or a question typed
  there silently supersedes the plan view. "Open in Plan ›" on the sheet surface closes Ask first: the shell's layers
  are all inside the `inert` container, so nothing can stack over the Ask sheet.

### F2 — Desktop — L
**Files:** `AskField.jsx` (340px, `/` hint), `components/ask/AskDock.jsx`,
`hooks/useAskSurface.js`, `WindowFirstShell.jsx` (the root row, the field beside the tablist,
`inert` while a dialog is open, the `/` handler per §6 Q1), `useOutsideDismiss.js`
(`[data-ask-surface]`), `index.css`. Re-point the tests that use `/` as the way into search at the
⌕ button.
**Tests:** the bands at 639/640, 1023/1024, 1179/1180; `/` focuses the field on Plan, Coming up and
Map and keeps every refusal (fields, modifiers, any dialog, backend down) — asserted on
`defaultPrevented` and against a rendered control; `/` over an open window popup is refused and the
popup stays; Escape inside the dock closes it and returns focus, Escape elsewhere is unchanged; the
dock and field are `inert` under each shell dialog and under settings; a press on a pick card does
not dismiss an open drilldown; the dock is `complementary`; switching to Operations closes Ask.
**Seen:** at 1280 and 1600 the masthead and panel stay aligned; on Plan the dock's input stays in
view while the page scrolls; the tab row at 1024 with four tabs.

*As built (F2), where the plan was wrong or silent:*
- **Files:** `components/ask/{AskDock,AskClearAnswer}.jsx`, `AskField.jsx` (+`controls`), `AskSheet.jsx` (now uses
  `AskClearAnswer`), `hooks/useAskSurface.js` (four bands), `hooks/useOutsideDismiss.js`, `utils/shellForeignDialog.js`,
  `WindowFirstShell.jsx`, `MastheadTickLine.jsx` (a key cap came off), the F2 block at the end of `index.css`, and the
  re-pointed tests (below). `MapView`, the peek sheet, `MapCallout` and `App.jsx` are untouched.
- **Four bands, not three.** `useAskSurface` returns `phone` (<640) / `tablet` (640–1023) / `desktop` (1024–1179) /
  `wide` (≥1180). The dock is 360px and its field 260px (no key cap) in `desktop`; 380px and 340px with the `/` cap
  in `wide`. **`aria-keyshortcuts="/"` is set in BOTH docked bands, not only `wide`** (a deviation from the brief, on
  an accessibility review's finding): the key works from 1024px and the attribute needs no room, so `AskField` gained
  `keyShortcut` beside `showKeyHint` (the cap implies the attribute). Never below 1024px. With no `matchMedia` match (every shell suite that predates Ask) the answer is `desktop`, not `wide`.
- **The dock has its own openness, and `askEntry` is still the sheet's alone.** `askDockOpen` is a shell `useState`;
  `askDockEntry` (Ask `on`/`down`, a docked band, not Operations) is "a dock can be drawn", and `askDockShown` is both.
  `selectTab` is untouched: the dock is not a layer and survives Plan ↔ Coming up ↔ Map. It is released **during
  render** when `askDockEntry` goes (Operations, a window narrowed below 1024px, Ask off), the shape `askOpen` uses, so
  it cannot come back by itself — and the conversation is untouched. The app container is never made `inert` by it.
  The dock itself is `inert` exactly when a shell DIALOG is up (`askDialogOpen`: a window popup, a layer over it,
  search, settings) — and **not** under a dead backend or Ask `down`: there the field is disabled and `/` refused (the
  `askDisabled` flag), but a dock already open must stay closable, and review found an inert ✕ would pin it open,
  dimmed, for as long as the backend was down. (The `opacity: .5` the first cut put on `[inert]` went with that: the
  dialogs' scrims already cover it.) Opening never closes a dialog and the dock never does either. **Focus is kept
  alive across both ways the dock can stop being focusable while it holds focus** — turning `inert` under a
  programmatically opened dialog, and being released (the window narrowing past 1024px under 200% zoom, an iPad
  rotating, Ask off): `AskDock`'s layout effect hands focus to the tab in force (`fallbackFocus`), in the commit that
  sets `inert` and in the unmount cleanup, never to the field (disabled in one case, about to be replaced in the
  other). It does nothing when focus is elsewhere, so the ✕/Escape path, which moves focus first, is untouched.
- **Placement: a `.wf-shell-col` wrapper, not just "the root becomes a row".** The masthead + tab row + panel region are
  wrapped in one always-rendered `div` (`window-first-shell-col`; not re-indented, for the tab row wrapper's reason) and
  the dock is the root's next child. Undocked the wrapper is a plain block — and on the Map `flex-1 min-h-0 flex
  flex-col`, taking the root's old place in that chain, so every existing class pin held. Docked, the root gets
  `wf-shell--docked` (a row, `justify-content: center`) and, on the Map, drops `flex-col` (a utility and a rule of one
  specificity would be a source-order bet); the column takes `flex: 1 1 0%` with an INLINE `maxWidth: WRAP_MAX_WIDTH`,
  so the 1080 is the shell's one constant and the CSS writes none. The masthead and the panel inside share the column,
  which is what makes O-17 hold at any width. Measured in a browser on the real shell and stylesheet (a scratch
  harness with the briefing stubbed — see "Seen"): at 1280 the masthead and panel are both 16→884 (868 wide) with the
  dock 884→1264; at 1600 both are 70→1150 (1080) with the dock 1150→1530, the pair centred.
- **The dock mounts on open; it is not kept at width 0.** The mock keeps `.ap` mounted and animates its width. A
  sticky 100dvh box mounted shut would still make the root 100dvh tall, so it mounts on open and animates in with a
  keyframe (`wf-ask-dock-in`, 0.28s; none under `prefers-reduced-motion`). **Correction to this note's first draft:**
  the pane's `ResizeObserver` does fire every frame, but `MapSizeSync` RESTARTS its 60ms interval on every nonce
  change, so it does not `invalidateSize` until ~60ms after the LAST frame and then polls for 340ms. Leaflet therefore
  holds its old size for the 0.28s (the mock does too, until `transitionend`) and catches up afterwards — nothing to add
  for F2, but see F3's note below. A stub pane observing itself reported 632px with the dock open at 1024px.
- **`top: 0; height: 100dvh` was not enough, and the plan's own "Seen" line would have failed it.** At scroll 0 the
  dock's top is the banners plus `<main>`'s padding down the page, so a 100dvh box hung that far below the fold with its
  input row. `AskDock` publishes `--wf-dock-top` (its own `getBoundingClientRect().top`, floored at 0; it does not
  depend on the dock's height, so there is no loop) from a scroll/resize/body-resize listener and the stylesheet
  subtracts it: measured, the input row's bottom is 800 of 800 at scroll 0, 20, 40, 100 and 300. The dock sticks at
  `--safe-t` and gives `--safe-b` back, because `safeAreas.test.jsx` (rightly) refuses a bare 100dvh: an iPad at
  1024px is a docked width and the page is `viewport-fit=cover`. On the Map the dock is the frame's own height (the row's
  stretch) and publishes nothing. The dock is bounded by the shell root, so at the very end of a short page it ends
  above the footer rather than overlapping it.
- **`/`.** The search handler is gone; the Ask handler is registered only while `askDockEntry` and acts only when
  `askDisabled` is false, no `role="dialog"` stands outside the shell root (`foreignDialogOpen`, the one helper the
  `/` handler, `openAsk` and the dock's opener now share), the target is not an INPUT/TEXTAREA/SELECT/contenteditable
  and Meta/Ctrl/Alt are not held (Shift is). It opens the dock, or — already open — puts the cursor back in its field;
  the field's own press does the same, so a second press never closes what was just asked. **The window popup now
  refuses it**: search was allowed over the popup because it is anchored to the masthead the popup is drawn over; the
  dock is not, and a live control under an `aria-modal` dialog is the thing this arm exists to prevent. Below 1024px,
  on Operations, with Ask off, pending, `down` or rewound, `/` does nothing and is left alone (`defaultPrevented`
  false). **Observation for the owner: with `photocast.ask.enabled` false (the shipped default) `/` is now dead on Plan,
  where it used to open search — Q1 as decided has no fallback.**
- **Tests re-pointed, and why.** Every `/`-opens-search test in `planOriginShell`, `WindowFirstShell` (the
  search→sheet→popup walk, the arrows-under-search test, "opens search over an open popup", "refused over a stacked
  layer"), `locationSheetShell` (`openSheetFor`) and `AskShellDialogs` now press the masthead's ⌕ (`window-first-search`)
  — the same dialogs, the same Escape ladder, the same guards, a different way in. `planOriginShell`'s whole
  `the / shortcut` block (the four field kinds, three modifiers, Shift, search-open, foreign dialog, dead backend,
  "another tab") became `search is reached by its buttons`: the ⌕ opens it, `/` does not (with the lazy-boundary
  positive control kept, and `defaultPrevented` false), and each refusal moved to `AskShellKey.test.jsx` against the new
  key, every one paired with a control (the same press, refusal lifted, acts). "Ignored on another tab" became "acts on
  Coming up and Map, nothing on Operations". `AskShellEntry`'s "at 1024px neither is drawn" and "focus lands on the tab
  when the entry has gone" changed truth (a field IS drawn at 1024px now, and it is the next address).
- **The ⌕ lost its `/` key cap** (`wf-tick-kbd`, deleted with its CSS). Found in the browser, not by a test: with
  `/` moved, the masthead's search button still advertised it, and at 1180px+ the page carried two `/` caps. The Ask
  field draws the one.
- **`AskField` no longer claims a dialog from 1024px.** `aria-haspopup="dialog"` is dropped when `controls` is given
  (the dock is a complementary landmark), and `aria-controls` names the dock only while it is open.
- **Escape is a native listener on the dock's node**, not a React prop (the a11y lint refuses key handlers on an
  `<aside>`, and a native one stops propagation at the dock, so no `document`-level Escape rule — a popup's, a map
  panel's — sees a press the dock took). Only while focus is inside, an IME's composing Escape is left alone, and
  closing focuses `askRestoreFallback()` (the field, else the tab in force) BEFORE the dock unmounts. The dock is NOT a
  dialog, so `foreignModalOver` and every map Escape ladder behave as before: pinned by an Escape walk through a popup
  and its Plan drill-down with the dock open (the MAP panels' ladders are pinned only at the hook —
  `useOutsideDismiss`'s own test and the dock's `data-ask-surface` matching its selector — never with a real panel and
  the real dock together; the Map pane is a stub in every shell test).
- **`useOutsideDismiss` treats `[data-ask-surface]` like the map frame** (`isInsideAskSurface`). That is the only
  consumer change; the Map's ground-click controller is a Leaflet event and a press in the dock never reaches it.
- **The four-tab collapse is a CONTAINER query** (`.wf-tabrow { container: wf-tabrow / inline-size }`,
  `@container wf-tabrow (max-width: 719px)`), as F1b's note asked, and it names `[data-width]` so it outranks a new
  step: with four tabs the 340px field steps down to 260px under an ESTIMATED 800px of column. Measured natural widths
  of four tabs plus field and gutters (admin): 752px with the 340 field, 672px with 260 — so 800 and 720 are both
  conservative by ~48px, deliberately (a Coming-up badge adds ~25px). At 1024px with the dock open the admin's field is
  the 34px "Ask" button (container 632); three tabs need no collapse anywhere. The key cap is hidden in the 34px state.
  `askCss.test.js` reads `@container` blocks now too (the safety note may not hide in one).
- **Operations holds the field's place at ≥1024px as well** (the ghost, at the band's width), for the reason F1b gave
  for the tablet: Operations is pinned to the tab list's right edge.
- **Context sent from the dock** is the sheet's: `view` plan / coming-up / map, `regionIds: []`, no `windowId`, chips
  "Plan · all regions" / "Coming up · all regions" / "**Map · all regions**" (the Map's scope and window are `MapView`
  state; F3 builds the channel), the header "· on Plan" / "· on Coming up" / "· on Map".
- **"Show on map ›" works from the dock unchanged** (`selectTab('map')` leaves the dock open; focus goes to the Map tab,
  because the pressed button is not offered on the Map and unmounts). `AskClearAnswer` is the sheet's "Clear answer",
  extracted so the two surfaces share it.
- **Seen vs tested.** SEEN, in a browser, on the real `WindowFirstShell` + `index.css` + `AskProvider` with the briefing
  context stubbed through a scratch Vite alias and axios answering from the Ask fixtures (a scratch harness, deleted;
  nothing past it was reachable without a sign-in, and no backend was started): the masthead/panel/dock edges at
  1024, 1180, 1280 and 1600; the input row pinned at the foot of the viewport at scroll 0–300; the admin four-tab row at
  1024 (34px "Ask") and 1180 (step-down to 260, cap shown); a stub map pane's own `ResizeObserver` reporting 632px with
  the dock open at 1024; `/` opening the dock with the field focused and no slash typed; a typed answer rendering;
  Escape closing it onto the field and the answer surviving a reopen — all BEFORE the review's fixes, so the dock as a flex column (its inner box no longer `height: 100%`), the `--safe-t`/`--safe-b` terms, the focus rescue and the un-dimmed dock were tested but not re-seen. TESTED, NOT SEEN: the real `MapView` refit and
  `MapSizeSync` under the dock's animation; the dock beside the real banners and the real footer; real pick cards (the
  stub briefing's slots had passed against the live clock); the width animation; reduced motion; the dock over a window
  popup's scrim; forced colours; Safari (container queries, `dvh`, `inert`); an iPad at 1024px with real insets; the
  focus return across `inert` in a real browser.
- **Residuals, all accepted and named.** (1) The dock is not `inert` under a dialog this shell does not own (the map
  overlay), only the press-time refusal `openAsk` also relies on: it is not reactive (`WindowFirstMapPane`'s
  `MutationObserver` store would make it so). The consequence is narrow but real: Tab out of the non-trapping overlay
  into the open dock and an Escape there closes the dock and is swallowed (the overlay's `window` listener never sees
  it). (2) The pair is centred, so opening the dock at 1600px moves the column 190px left; that is the design (the
  column "narrows"). (3) CLAUDE.md still says `/` opens Plan search — Z's sweep. (4) **A short column makes the page
  scroll where it did not:** the sticky dock is a 100dvh-high row item, so on a Plan/Coming up page shorter than the
  viewport (loading, empty, error) the root is as tall as the dock and the footer lands ~170px below the fold. That is
  the plan's own `sticky; height: 100dvh` shape, and a size-contained cell or an overflow clip was worse (it hides the
  input row). (5) The dock is the root's LAST child, after the whole panel in DOM order and inside `<main>` (axe's
  `landmark-complementary-is-top-level` is a best-practice miss); there is no skip link. (6) Closing discards an unsent
  draft (the input row mounts per opening, the sheet's rule). (7) Holding `/` with the dock closed types slashes once
  the dock has opened and focus moved (auto-repeat targets the input). (8) `container-type: inline-size` on the tab
  row implies layout containment (a stacking context and a containing block for fixed descendants); the tab row has
  no positioned descendants and the health panel sits above it in the DOM and in z-index, which is reading, not
  seeing. (9) The tablet band is NOT unchanged for an admin: the collapse is the column (viewport − 32px) now, so four
  tabs collapse at a viewport under ~751px where 720px used to be the line — measured need is 672px with the 260
  field (+~25px for a Coming-up badge), so 720 is conservative, and an iPad mini at 744px gets the 34px button.
  (10) **Q1's consequence the owner should know:** `/` was the only way to open search OVER an open window popup
  without leaving the keyboard-free hand (the popup's scrim covers the masthead's ⌕ for a pointer); that rung of the
  Escape ladder is now reachable only by Tabbing out of the dialog. And with `photocast.ask.enabled` false (the
  shipped default) `/` does nothing on Plan.
- **For F3:** the dock is the surface and the pane is not a provider ancestor of it, so `AskContext` is the channel; the
  Map's scope, window and label must reach the dock the way F1b's note says (a registration channel the pane writes).
  **Where the arguments live is more than "two lines in the shell"** (a review correction to this note's first draft):
  `AskInputRow` hardcodes `askTyped(question, { regionIds: [], view })` and takes no `regionIds`/`windowId`;
  `AskDock` and `AskSheet` hardcode `scope="all"` and pass no `windowLabel`/`windowId` to `AskConversation`; the shell
  holds only the chip label (`askViewSpec`). F3 changes all four. (§3's F3 file list still names `App.jsx (askWindow)`,
  which F1b's note already showed cannot be the channel.) **The dock outlives a tab switch and the conversation does
  not record the context it was asked in:** the chip follows the CURRENT tab (as §2.6 says), so an answer asked on Plan
  reads "Map · all regions" above it after a switch, and Retry re-sends the original view. Harmless while every context
  is "all regions"; with F3's region and window chips F3 must store the asked context on the answer. **Camera:** the
  dock narrows the map and covers nothing, so the measured-rect padding of §2.7 is ZERO on desktop (do not measure
  `[data-ask-surface]`, it is not an overlay). Fit AFTER an explicit `map.invalidateSize()` — not on `resizeNonce`,
  which is a counter with no "settled" signal and also bumps on window resizes — and refit when the dock opens or
  closes with an answer present (`invalidateSize` keeps the centre, so picks near the right edge are clipped); closing
  keeps the answer, so that path is routine. Numbering a pick does not need the dock closed (it is non-modal) — the
  F1b "close the sheet to see the markers" problem does not exist here.
- **For F4:** nothing in F2 touches the phone; the dock's `AskInputRow` and `AskClearAnswer` are the pieces F4's ask
  row repeats. `askDockOpen` never exists below 1024px. Note the semantics DIVERGE across surfaces: §2.7's phone table
  closes the peek's ask section on a tab switch away and back; the dock survives one.
- **For F5:** `askPickActions` is the one hook ("Plan this ›" joins "Show on map ›"), and it only injects a button into
  a card's row. `AskDock` (like `AskSheet`) hardwires `AskConversation` → `AskClearAnswer` → `AskInputRow`, and
  `AskClearAnswer`'s settled-phase set would draw nothing in a `plan` phase, so "‹ Back to the answer", "Open in Plan ›"
  and the shell callbacks need a second seam on BOTH surfaces (a prop or a context), not just the footer. "Open in Plan
  ›" from the dock does NOT close Ask (it is non-modal and the answer should stay beside the plan), but the four-day
  sheet it opens counts in `stackedOverPopup`, so the dock goes `inert` while that sheet is up and focus must come back
  to a node that was inert (the `fallbackFocus` route covers the way in; the way out is for F5 to test). The highlight
  is "live on the dock" (§2.8), so F5 can apply it as soon as the answer lands.

### F3 — Map linkage — L
**Files:** `WindowFirstMapPane.jsx` (the window channel and the context channel — not `App.jsx`: see
"As built (F3)"), `MapView.jsx`, `MapLabels.jsx` + `utils/mapLabels.js`, `PinsLayer.jsx`,
`MapHeatLayer.jsx`, a new `AskCameraController`, `AskContext`/`AskInputRow`/`AskDock`/`AskSheet`/
`AskConversation`, `index.css`.
**Tests:** `askWindow` sets the window without touching the lens, the scope, `minStars` or the
breadcrumb, and a live Plan-door handoff survives it; a pick chip shows its own window's rating; a
pick filtered out by the reader's floor is still labelled; an unplaceable pick falls back to its
rank circle; fade beats tide dimming (cascade test); the heat dim leaves the coastline alone;
padding is clamped on a 400px-tall frame (every pick inside it); selecting a card clears the selection and
opens no callout; selecting a map pick selects the card; clearing restores everything; reduced
motion.
**Seen:** dock at 1280 — the map narrows, refits, all picks inside the viewport, each chip's rating
equal to its card's.

*As built (F3), where the plan was wrong or silent:*
- **Files:** `components/map/AskCameraController.jsx`, `utils/askCamera.js` (the padding clamp, the select offset, the
  heat-dim constant, `prefersReducedMotion`), `utils/askMapContext.js` (the Map's facts to the question's context),
  `hooks/useAskRequestContext.js`; `AskContext.jsx` (`asked`, `selectionNonce`, `mapContext` + `registerMapContext`),
  `AskInputRow`/`AskDock`/`AskSheet`/`AskConversation`/`AskContextChips`, `WindowFirstMapPane.jsx`, `MapView.jsx` (six `ask*`
  props), `MapLabels.jsx` + `utils/mapLabels.js`, `PinsLayer.jsx`, `MapHeatLayer.jsx` (`dim`), `WindowFirstShell.jsx` (one
  branch), the F3 blocks of `index.css`.
- **The window channel is in the pane, not App (the plan was wrong, F1b's note was right).** `AppInner` is above
  `AskProvider`; `WindowFirstMapPane` is a provider descendant. It reads `pickCards`/`selectedPick`/`selectionNonce`, derives
  `askWindow = {date, eventType, nonce}` for the chosen card and hands it to `MapView`, whose own effect sets `setEventType` +
  `setUserHasOverriddenEvent(true)` and reports the date through the existing `onSelectDate`, through the same
  `isForwardableRow` rule `selectEvRow` applies (so the map and `App` cannot name two days). It touches no lens, scope, rating
  floor or breadcrumb — **including the floor reset `selectEvRow` makes on a change of event kind**, deliberately not made.
  `selectionNonce` is new on EVERY choice (the same pick chosen twice is two) from a provider-wide counter that never
  repeats. Three guards, each from review: it applies **once per nonce** (a refused follow-up empties the conversation and
  puts the earlier answer back with the same nonce — keyed on the nonce alone that was a second choice and snapped the map
  back to a window the reader had left); it **waits for `paneVisible`** (a card chosen in the dock on Plan must not move
  the app's date behind the reader, and one chosen in the tablet's sheet applies when the sheet closes, with the camera);
  and a choice made while the pane was never mounted applies at its first mount, which is what "Show on map ›" from Plan
  needs.
- **The context channel.** The pane publishes `{regionIds, regionNames, windowId, windowLabel, viewLabel}` into
  `AskContext.mapContext` (`registerMapContext`, de-duplicated by value, `null` on unmount). `MapView` computes the facts
  (`jumpFitOverride?.regionName` = the focused region; `heat.hasHome && heatArea` = "My area"; the scope pool's region names;
  `askWindowOf(activeMapEvent)`, solar and served rows only — a night row sends none) and the pane turns names into ids with
  `regions` and writes the chip words; it republishes when `regions` arrive. **One rule, "the chip names what is SENT":** a
  region the list cannot place sends — and says — "Map · Everywhere". "My area" sends the WHOLE regions of the scope pool, so
  it can be wider than the area (an area drawn round locations, a question scoped by region); the module says so.
  `useAskRequestContext(view, viewLabel)` is the one reader (dock, sheet, input row): on the Map it is the published context,
  elsewhere "all regions"; `scope` for the Ready list is a single region's id, else `all` (Q8). **F4's peek row must call it
  the same way and pass all four fields to `AskConversation`** (`scope`, `viewLabel`, `windowLabel`/`windowId`, `regionIds`).
- **The answer stores the context it was asked in** (`conv.asked = {view, viewLabel, regionIds, windowId, windowLabel}`). The
  chips over an answer say what was SENT and are plain labels (the window chip loses its ✕ — removing it from a finished
  answer would change nothing); with nothing asked yet they are the live chips and the ✕ works. "Try again" re-sends the
  stored context through `send`, bypassing `removedWindow`. A Ready answer records its scope and **no window**, and says
  "<tab> · all regions" when its list is the whole catalogue's. **Review finding, fixed:** the NEXT typed question is sent with
  the surface's context of the moment, and choosing a pick moves the Map's window — so after an answer the next question
  could carry a window nothing on screen named. `AskInputRow` now draws a "Next question" row (view + window with a ✕, focus
  to the field first) whenever the next question differs from what was asked or a window can be removed; it is quiet on Plan.
- **Padding is clamped, but the failure is not a NaN zoom (the plan was wrong).** Measured on a real Leaflet 1.9 map: padding
  over the frame makes the scale negative, `getScaleZoom` turns the NaN into `Infinity`, `maxZoom` caps it, and the fit lands
  at zoom 10 with the picks **outside the frame**, silently. A `Number.isFinite` check passes on the broken version; "every
  pick is inside the viewport afterwards" (400px-tall frame, 490px inset) is what fails — written first, watched fail on
  unclamped padding, then the clamp (`clampPadding`: each axis scaled until its pair is ≤ 60% of the frame). Padding = base
  (80 top, 60 elsewhere, for the map's own chrome) + the inset.
- **The camera (`AskCameraController`)** fits **once per `answer.id`** (a refusal that puts an earlier answer back changes
  nothing), no closer than zoom 10; a chosen pick flies to 10.5, offset by the inset; reduced motion jumps. **It holds while
  `active` (`MapView`'s `paneVisible`) is false** — the tab hidden, the page not focused, or a foreign `aria-modal` dialog over
  the pane, which is how the tablet's Ask sheet holds the fit until it closes (SEEN: an unfocused page held the fit and it ran
  the moment the page took focus). A selection that arrives while held is covered by the fit that follows. It re-measures
  (`invalidateSize`) before every move and **re-applies its last move without animation** when the frame has resized and
  settled (250ms after the last Leaflet `resize`, which covers the dock opening and closing — F2's note) **and when the inset
  changes** (the seam F4's peek sheet uses: its height changes while the frame does not). **A reader's own move ends the
  re-applying** (`dragstart`, or a `zoomstart` outside the camera's own flight), or closing the dock would drag the camera back
  over a pan. `inset` is passed by nothing in F3 (it is tested; F4 is its caller).
- **Chips and pins.** `labelSpots` appends each pick (id first, name second; lowest rank wins a duplicate; a pick not in the
  catalogue is skipped) with `askPick = {rank, shortWindow, rating, verdict, label, selected}` — the card's facts for the pick's
  own window; the spot's own `rating` stays the active window's. `chipCandidates` puts picks first (chosen first, then rank),
  ahead of the selected location, and offers all of them whatever the budget; `MapLabels` places them before the region
  names. **The fallback ladder:** full chip → the same button drawn as the bare 25px rank circle (`data-compact`, placed against
  everything committed) → the circle on its own point regardless of clear air → only a point outside the frame is left
  unmarked. The chip has the card's accessible name ("Pick 1, Whitby, Saturday sunrise, 5 stars"), no tide glyph or
  `data-tide` and **no tooltip**; the one tide gate (`tideTier` in `labelSpots`) is untouched and pick chips never read it.
  **DOM order is tab order**: picks first in rank order (choosing one does not reorder them); paint order is CSS (`z-index`, 1
  and 2 for the chosen). In Pins mode picks are first in the DOM and on top by an inline z-index (best-ranked highest), show
  the rank in place of the star in the pick window's colour with a readable ink.
- **Heat** takes `dim` (0.36, `ASK_HEAT_DIM`) as a factor on the fill inside the draw; the coastline stroke is asserted
  unchanged; the reach rings are drawn by code that does not read `dim` (not asserted by a test).
- **CSS cascade.** The fade is `.wf-maplab-layer .wf-maplab-chip[data-ask='fade']` (0,3,0) over the tide-miss `.72` (0,2,0), so
  it wins on specificity, not on source order — `askMapCascade.test.jsx` injects the real rules in BOTH orders; a mutant that
  dropped the prefix survived every other test and died to the reordered one. Hover, keyboard focus and "the selected
  location" restore a faded mark to full strength (0,4,0). Review findings fixed: the chosen pin's halo (an outline) had
  replaced its focus ring; a faded chip never restored on keyboard focus; faded chips painted over picks (`z-index`); an
  unrated pick pin printed its rank in its own fill colour; a pick lost the chip's hover ring; no forced-colours marker; tab
  order did not follow rank. Not fixed (named): the fade-in transitions and the fade-out snaps (the transition is on the faded
  rule only); the 4px selected halo is not reserved by the placer.
- **"Show on map ›"** is now also offered on the **tablet's Ask sheet over the Map** (the brief's "closes the sheet and fits"):
  it selects the pick and closes the sheet; the camera and the window follow on the release of `paneVisible`. The dock, which
  covers nothing, is not offered it. Focus returns through the sheet's own restore, to the field.
- **Changes to the existing suite (deliberate):** `AskShellDock`'s "sends the view it is open on" no longer expects the chip
  to follow the tab for an answer already asked (the chip keeps what was SENT; the empty state's chips follow the tab);
  `AskShellEntry`'s "not offered on the Map tab itself (tablet)" became "offered on the sheet, and it uncovers the map".
- **Known limits, accepted.** (1) **An aurora window has no chips**: `MapLabels`/`PinsLayer` mount only while the field is
  offered (`heatOffered`, false in aurora mode), so picks are unmarked there and the dim cannot apply until a card is chosen
  (which moves the window to a solar one); the camera still fits. (2) **A typed question in flight empties the conversation**,
  so the numbering, the dim and the fade go for the duration and return with a refused question's restored answer. (3) **A
  stale selection applies at the pane's first mount** and, being later in effect order than the Plan door's handoff, would
  win over it for a reader who chose a pick and then opened the Map for the first time through a door. (4) **A pick appended
  past the reader's filters joins `labelSpots`, which also feeds the tide strip's in-view coastal counts** — the
  selected-location append already did. (5) **On a phone** the picks are numbered and fitted too (F1b's "Show on map ›"
  reaches the Map), with only the base 60px bottom padding against the 74px peek sheet; the sheet and its inset are F4's.
  (6) `paneVisible` includes the page's focus, so an answer landing while the window is blurred is held until it is back.
  (7) The first fit ignores a chosen pick (it fits every pick, the chosen one ringed); only a LATER choice flies to it. (8) Two
  picks at one location (the server cannot send this) leave the second unmarked. (9) Under a docked column the camera's
  re-fit after a close is not asserted against a real `ResizeObserver`/`MapSizeSync` chain.
- **For F4:** the phone table's "pick chip press while minimised → expand and select" meets the existing map-touch collapse (a
  pick chip is a map touch): order them in `MapView`, not in the controller. `askWindow`/`selectionNonce`/`asked` are
  surface-independent; `inset` re-applies on change, so a sheet height change needs only a new inset object, not a new
  answer. `useAskRequestContext` has no dock/sheet assumption. The camera's `active` gate is a modal test, which the peek sheet
  is not (correct for it). Every 74px literal is still F4's.
- **For F5:** `selectionNonce` > 0 and `selectedPick` outlive a `phase === 'plan'`; `askPickActions` is shared, and the
  `askCanShowOnMap` condition now includes `askSheetOpen`, so "Plan this ›"/"Open in Plan ›" added to it must not render a dead
  "Show on map ›" over Plan (the condition is the Map's alone, and Plan is not the Map).
- **For Z (CLAUDE.md):** `utils/askMapContext.js` (names to ids, the window id, the labels), `useAskRequestContext`'s
  `scope = one region's id, else all`, and `labelSpots`' pick append are filter/map/select over served facts — the
  already-licensed class, no new member; `askMapContext` re-states `jumpResetArea`'s "a segment narrows only with a home"
  rule. New architecture to record: the Map pane publishes into `AskContext` (`registerMapContext`), and the window follow is
  its own channel beside `planHandoff`. The "Backend-heavy" bullet needs no new class.
- **Review (six read-only lenses — runtime, CSS, test quality, accessibility, conventions, what it makes harder for F4/F5 —
  then adjudicated against the code):** the findings fixed are named above; the accepted ones are the "Known limits". 25
  mutants over the camera, the window channel, the label placer, the cascade and the context channel were all killed.
- **Seen vs tested** (a scratch Vite harness mounting the real pane, `MapView`, `MapLabels`, `PinsLayer`, `AskProvider` and the
  dock, the briefing and auth stubbed and the network answered by an axios adapter, at 1280px; deleted): SEEN — the dock's
  chips "Tonight sunset" and "Map · Everywhere"; the question sent with `windowId` = tonight's id and no regions; three picks
  numbered with their own window's short window and rating ("Whitby Tue PM 5★", "Saltburn Wed AM 5★"); every other chip at
  `.25`, four of them coastal tide matches; the heat visibly dimmer; the camera fitting the picks (zoom 10) from a map zoomed
  out by hand, and HELD while the page had no focus and run the moment it took it; choosing card 2 moving the pill to "Tomorrow
  sunrise", ringing chip 2 and its card, flying to Saltburn at about zoom 10.5 and opening no callout; the "Next question" row
  following the window; picks first in the DOM in rank order with z-indexes 1/2/1 (chips) and 99/98/97 (pins); Pins mode with the
  numbered dots and the faded rest and a visible focus ring on a focused pick pin. **Tested, not seen:** the bare rank circle
  under a label-budget squeeze and the forced dot, the camera under `prefers-reduced-motion`, the settle re-fit when a real dock
  opens and closes, the tablet sheet holding the fit and releasing it, the heat dim next to a real coastline stroke, forced
  colours, the compact circle's 25px box in a real layout, and the chosen pick chip's focus ring.

### F4 — Phone Map — L
Per §2.7's phone table: **one test per cell**. Updates every 74 literal and the tests that pin them
(`mapPhoneChromeCascade`, `MapViewMobilePeekSheet`, `MapPeekSheet`, `mapCalloutClampCascade`,
`MapViewTideStripCalloutWiring`). Desktop and tablet provably unchanged on the same fixture.
**Also tested:** the callout's band reads 112 under a minimised answer and repaints on `bandKey`;
focus never falls to `<body>` when the ask row replaces the buttons (asserted on
`document.activeElement`).
**Seen:** 390 × 844 and 375 × 667 — the open sheet never covers the pill; a minimised answer and a
callout do not overlap (measured rects).

*As built (F4), where the plan was wrong or silent:*
- **Files:** `utils/askPeek.js` (the heights, `askPeekMode`, `peekTargetHeight`, `peekRestingHeight`,
  `PEEK_SETTLED_PHASES`), `components/map/MapPeekAsk.jsx` (the Ask row, the Ask section's body, the minimised line),
  `MapPeekSheet.jsx` (`askMode`/`ask`/`fallbackFocusRef`, the button row's focus rescue), `MapView.jsx`,
  `WindowFirstMapPane.jsx` (three new props), `MapCallout.jsx` (`bandKey`), `MapLabels.jsx` (`bottomInset`),
  `AskCameraController.jsx` (`onOwnMove`), the F4 blocks of `index.css`. `AskInputRow` and `AskConversation` are reused
  unchanged. **`AskClearAnswer` is not** (the brief said the row reuses it): its text button has no place in a 44px
  row, and the ✕ implements the same rule itself — clear what is settled, never what is still being fetched.
- **The sheet's state is derived, not stored — that is how "one test per cell" stays honest.** Three facts decide it:
  whether the Ask section is **expanded** (`openMapMenu === 'peek:ask'`, D-1's one gate, and nothing else), whether
  another section is open, and whether the conversation has something **settled** (`AskContext.phase` in
  `PEEK_SETTLED_PHASES`: answer, plan, cant, error). **"Minimised" is "a settled answer and no section open"**, so every
  table cell that ends on the 112px line is the *same write* (`openMapMenu → null`) as the cell that ends on the 126px
  row; the conversation decides which the reader sees. A tab switch needs only to close the expanded section: the
  pane's own `panelShown` (the ResizeObserver's 0×0 box) falling nulls `'peek:ask'`, which `selectTab` cannot reach (the
  pane is mounted but hidden, and gets no `active` prop). Another section (`peek:win`) is deliberately left alone, as it
  always was.
- **The two dismiss paths, as built.** `SheetDismissOnMapTouch` gained two things, not the one the brief expected: the
  **busy exemption** (the table is silent; the mock leaves the section open, and collapsing would hide the only sign
  the question is out) and an **own-move gate on `zoomstart`**: Leaflet fires one for *any* zoom, and Ask's camera
  flies to a new answer's picks and to a chosen card, which minimised the very answer that had just landed. The
  camera stamps the end of each move of its own (`onOwnMove`, before the move) into a ref the listener reads; a
  press, a touch and a drag are never gated. **The selection→collapse effect gained nothing**: with the state derived
  it is already "minimise". Pick chips stop their own click propagation, so a pick press is never a map touch; it
  clears the selection itself (not left to the window-follow effect, which runs only while the pane is on screen)
  and expands only from null or a peek value, so an open drilldown, Regions or Filters stays open — **which means a
  pick pressed while the drilldown is open selects the card but the sheet stays on the 112px line** (the table's
  "minimised: expand, select card" holds when nothing else is open; panels persist under a press on the map).
- **`--psh` is three-valued, not "every 74 becomes 126" (the plan was wrong).** With Ask not offered (the flag, an
  unreachable server, a rewind) the sheet must be exactly what it was — a 52px empty band would be a defect — so
  `MapView` writes `--psh` inline on the pane (an inline style in a "Tailwind only" codebase: `--tsh` is written the same
  way, and the value is state): **74** with no Ask row, **126** with one, **112** under a minimised
  answer, and every consumer (the Leaflet corner's padding, `.wf-map-chrome-bl`'s `calc(var(--psh, 74px) + 27px + 8px)`,
  `.wf-map-empty-low`, `MapCallout`) reads the property; the `74px` left in the stylesheet is its default and each
  `var()` fallback. jsdom resolves no `var()`, so `mapPhoneChromeCascade.test.jsx` was rewritten to substitute the
  three resting heights (`atPsh`) — a stronger test, it checks the whole lifted stack at each. `MapViewMobilePeekSheet`,
  `mapCalloutClampCascade` and `MapViewTideStripCalloutWiring` needed **no change** (their 74 is the Ask-off truth).
  Sheet heights (`.wf-map-peek[data-ask=…]`): 126 collapsed, 112 minimised, 408 another section, 470 Ask, all under the
  base rule's `max-height: calc(100% - 64px)`. The transition is the README's `.28s cubic-bezier(.3,.7,.2,1)` (the
  mock's own CSS says `.26s`, which the sheet had before). The height literals live in `utils/askPeek.js` *and* the
  stylesheet; `askPeekCss.test.js` reads one against the other.
- **D-7 is broken in exactly one state, on purpose, and `map-mobile-sheet-plan.md` §5 D-7 now says so:** a callout may
  stand over the **112px minimised line**. It is safe because that height is fixed (the band is read from the target,
  never mid-transition), `--psh` is live, and `MapCallout`'s new `bandKey` prop (the resting height) repaints the
  band when it changes — tested at 74, 126 and 112, and with the key withheld to show the defect it prevents. An
  *open* sheet (356 / 408 / 470) and a callout still never coexist, in either order.
- **The answer replaces the three buttons, in the expanded state and the minimised one** (the mock's
  `.sh.askmin .pk{display:none}`; there is no room in 112px). So with an answer minimised **Tide and Layers are one
  step further**: the pill opens the Windows section, which draws the buttons. The table's "a peek button" cell for the
  minimised column is therefore reachable through the pill only. The minimised row is the **entry button** plus the
  line, not the mock's field: the field mounts when the section opens, so a draft does not survive a collapse (the
  sheet's and the dock's rule too).
- **Focus.** Every control that is replaced by another is handled, asserted on `document.activeElement`: opening
  puts the cursor in the field when there is nothing to read and on the Ask node (`tabIndex -1`) when there is, and only
  when focus has actually been lost; ✕ and Escape park focus on that node first (Escape through a native listener,
  which runs before the pane's own handler, and stands down for a dialog the pane stands down for) and an effect hands
  it to the entry button; and **the three-button row reports from a layout-effect cleanup that it held focus when it
  unmounted** (an answer lands, a section closes onto the minimised line — the ordinary route, found in review) and
  `MapPeekSheet` focuses the entry button. `restoreFallback` for Regions and Filters falls back to the entry.
- **A refusal's sentence is shown in the collapsed row only while unseen.** The conversation, while open, renders and
  announces `inputError`; the provider keeps it until the next question, so a row that mirrored it would keep a
  read sentence where the prompt belongs and announce it again on every collapse. "Seen" is what the open
  conversation last showed (state adjusted during render; forgotten when the sentence goes, so the same refusal twice
  is two refusals). It wraps to two lines in the 44px row; the full sentence is the button's accessible name.
- **The camera.** `inset.bottom` is the sheet's TARGET height (470 expanded, 408 under another section, 112 minimised)
  while picks are on the map; the controller's 60% clamp is untouched, and a change re-applies the last move without
  animation, which is how the picks re-fit when the answer is minimised. **Two camera changes F3 did not have:**
  `dragstart` now ends the re-applying *whenever* it comes (it was gated by the own-move window like `zoomstart`, so a
  drag begun within 900ms of a fit left the camera free to snap the map back when the touch minimised the sheet), and
  `startOwnMove` is called only where a move is made, not before the branches. **And a re-apply for a changed inset
  waits for the pointers to lift**: the sheet minimises on `touchstart`, before a drag's first move, and a camera
  move in that gap leaves Leaflet's Draggable holding the map pane's old position (found by the second review pass,
  from Leaflet's source — not seen in a browser); a drag that begins meanwhile has cleared the move, so nothing is
  re-applied, and a tap re-applies on release (pointer events, a set of ids, so two fingers wait for the last).
- **Label obstacles — decided: yes, but only while an answer's picks are numbered.** `MapLabels` takes `bottomInset`
  and seeds a synthetic box of the sheet's target height (clamped to frame − 64, like the CSS) as one more obstacle; a
  pick a 112px line would hide is worse than a shifted one. Without picks the sheet is not an obstacle, as it never was
  (changing that would alter every phone's placement); `PinsLayer` (dots, no placement pass but the home label) is
  unchanged. The box is built from `containerRect.top + height`, not `.bottom`: the first draft read `.bottom` and a
  test's rect without one made the obstacle `NaN`.
- **Seen vs tested.** SEEN, in a browser (a scratch Vite page mounting the real `index.css`, `MapPeekSheet`,
  `MapPeekAsk`, `AskInputRow` and `AskPickCard` with the conversation context stubbed, a fake map and pill, at 390×844
  and 320×568, deleted): the sheet at exactly **126 / 470 / 112** px; at a 440px frame the expanded sheet clamped to
  **376** and left the pill a 10px gap; the minimised line ("1 Saltburn Tue PM 17:41 · 3 picks ▴"); the expanded
  field, ✕, chips and cards; the attribution flush above the sheet at 112 (`--psh`) and a stand-in callout 8px above
  it; focus on the entry after ✕; an unseen refusal in the row; at 320px the 16px placeholder measured 183px against
  176px available, so the field's side padding in the row is 8px. **TESTED, NOT SEEN:** the real `MapView` with real
  Leaflet and a real callout over the 112px line (the band is tested in jsdom against `--psh`); the iOS keyboard with
  the field at the top of a sheet that is not `visualViewport`-aware; the 667px phone's actual map frame (the clamp is
  asserted as text); the transition and reduced motion; focus rings in forced colours; whether a map touch that
  minimises the answer makes the camera's re-fit read as a jump (the plan asks for it; the mock does not refit); a
  screen reader on the parked Ask node and on the minimised line's name; Safari.
- **Review (six read-only lenses — runtime, CSS, test quality, accessibility, conventions, F5 — 60+ charges, then a
  refutation pass over what was deferred and one over the fixes) and 20 mutants** over the state table, both dismiss
  paths, the camera stamp, the selection clearing, the focus handoffs, `bandKey`, the inline `--psh`, the inset and the
  obstacle, all killed. **Fixed from review:** focus fell to `<body>` when a section closed back onto the minimised
  line (the buttons unmounted holding it); a pick press under a callout relied on another effect to clear it; a drag
  within the camera's own window could snap the map back; a read refusal stuck in the row and was announced twice; the
  transition's attribution; a misplaced doc block, stale 74px prose and an unfocusable-ring clip on the line.
  **Accepted, with the reason, and refuted as defects by the second pass:** the expanded 470px sheet covers the picks the
  camera fits on frames under ~590px (nothing the camera can do shows them; minimising re-fits); the parked node has no
  role; an answer landing while the section was closed on purpose is not announced; Tide and Layers are two steps
  away while an answer is minimised; `--psh` steps instantly while the sheet animates (a 14px step).
- **For F5:** (1) `'plan'` is already in `PEEK_SETTLED_PHASES` and `MinimisedLine` treats it like an answer; F5 must
  give the line a plan text and decide that choosing a pick during `plan` returns the conversation to `answer`
  (`handleSelectAskPick` expands whatever the phase). `AskClearAnswer`'s own list is untouched and must stay so until
  F5 gives it its seam. (2) `MapView.handleOpenLocationSheet(inPlan, spot)` reads the date and event from
  `activeMapEvent`, not the pick: "Plan this ›" / "Open in Plan ›" on a card the Map has not followed yet need an
  explicit window argument. (3) The peek sheet passes no `pickActions` and the shell's `askPickActions` is out of the
  pane's reach: F5 needs a seam from the shell through `WindowFirstMapPane` into `MapPeekAsk`. (4) `AskConversation` is
  mounted only while the section is expanded, so scroll position and card focus do not survive a collapse; keep it
  mounted `hidden` if F5 needs them. (5) "Open in Plan ›" moves the tab: `panelShown` goes false and the section closes,
  so the Map comes back minimised — test that route. (6) Opening a non-`inPlan` four-day sheet over the Map leaves
  `'peek:ask'` open and not `inert` (O-20); `openAskSection` refuses over a dialog, `handleSelectAskPick` does not.
- **For Z (CLAUDE.md, *Map tab on a phone — the peek sheet*):** these sentences are now false and the sweep must change
  them — "74px tall showing three summary buttons … opening to 356px" (126 with the Ask row; a section 356, or 408 with
  it; Ask 470; an answer minimised 112); "`MapCallout`'s phone placement band reads a fixed `--psh: 74px` … every map
  tap collapses the sheet first" (`--psh` is state-driven inline, and a callout may stand over the 112px line); "the
  whole phone lifted stack was rewritten off the sheet's 74px floor"; "the open section is a value of … `'peek:win' |
  'peek:tide' | 'peek:lay'`" (add `'peek:ask'`, meaning expanded only); "any installed map touch or selection
  collapses it" (an expanded answer minimises, busy is exempt, the camera's own zoom is not a touch); "three summary
  buttons" (an answer replaces them). Add: `MapView` takes `askOffered`/`askPhase`/`panelShown` from the pane; the Ask
  row is phone-only inside `MapView`, and the shell's `askEntry` stays null on the phone Map.

### F5 — Plan this — M
**Files:** `components/ask/AskPlanThis.jsx`, `utils/locationSheet.js` (export `lightWindows`),
`WindowFirstHeatStrip.jsx` (`highlightKeys`), the shell wiring for Open in Plan.
**Tests:** each of the four figures equals the location sheet's own output for the same fixture;
the no-postcode state; "‹ Back to the answer" restores the selected pick and focus; Open in Plan
closes the sheet first and opens the location sheet at the pick's window; the highlight is distinct
from `data-open`, does not open the popup, and a pick with no card highlights nothing.

*As built (F5), where the plan was wrong or silent:*
- **Files:** `components/ask/AskPlanThis.jsx`, `utils/askPlan.js` (the four figures), `utils/postcodeNudge.js` (the tick line's
  "set a postcode" words, now one home for both), `context/AskContext.jsx` (`openPlan`, `backToAnswer`, the derived `phase`/`planPick`),
  `components/ask/{AskConversation,AskDock,AskSheet,AskClearAnswer}.jsx`, `components/map/MapPeekAsk.jsx`, `components/MapView.jsx`
  (`askPlanActions`), `components/WindowFirstShell.jsx`, `components/WindowFirstHeatStrip.jsx` (`highlightKeys`),
  `components/LocationFourDaySheet.jsx` (`restoreFocusFallback`), `components/MastheadTickLine.jsx` (reads the constants),
  `utils/locationSheet.js` (`lightWindows` exported; `departureWithDay` extracted and exported), `utils/shellForeignDialog.js`
  (`ignore`), `utils/askPeek.js` (a comment), the F5 block at the end of `index.css`. Nothing on the backend, the map's markers or
  camera, the peek heights or the `/` key moved.
- **"Plan this ›" is drawn by `AskConversation`, not injected through `pickActions` (the plan, F1a/F1b and the brief all said the
  latter).** It is a move of the CONVERSATION (`ask.openPlan(rank)`), so it needs nothing from a host, and the phone peek —
  which has no `pickActions` — gets it with no seam. It is drawn after whatever `pickActions` adds ("Show on map ›"), on all four
  hosts, with the accessible name "Plan this — <place>". The brief's token mapping: border `rgba(201,162,75,.5)` is `--color-home` at
  50% (no alpha token exists; the F1a block writes the same literal for the selected card) and text `#EBD9A8` is exactly
  `--color-segment-active`.
- **The second seam is one prop, `planActions = {openInPlan(card, pressedControl), setPostcode()}`**, threaded
  shell → `AskDock`/`AskSheet` → `AskConversation` → `AskPlanThis`, and `MapView.askPlanActions` → `MapPeekAsk` →
  `AskConversation` for the phone. A door the host did not hand over draws no control (never a button that does nothing).
  "‹ Back to the answer" needs no seam: it is the conversation's. The F4 note's "a seam from the shell through
  `WindowFirstMapPane`" was not needed: `MapView` already holds `onOpenLocationSheet` and `onOpenSettings`, and the pane forwards them.
- **The plan phase.** `phase` and `planPick` are DERIVED, like `selectedPick`: a plan whose card has gone (a briefing rebuilt without
  that slot) reads as the answer again, and focus the plan view held is parked on the conversation root (a rebuild with no press
  would otherwise leave it on `<body>`). `openPlan` selects the pick as well (nonce bumped, so the Map follows it and the Plan card
  is highlighted); `selectPick` of any rank during `plan` returns to `answer` (F4's open question: a chip on the map is a choice,
  and the reader wants the list); `clear()`, a typed question and a Ready tap supersede it; a refused question puts a plan view
  back with the rest of the conversation and takes no focus. `answer.id` is untouched by entering and leaving (tested: F3's camera
  must not refit). **Known limit, accepted:** the STORED phase stays `plan` while the derived one reads `answer`, so if that card
  returns on a later briefing the plan view reappears unasked.
- **`AskClearAnswer`'s settled set now includes `plan`** (F4's note asked it stay untouched "until F5 gives it its seam"; the seam is
  not what it needed — a reader in the plan view should be able to end the conversation as the peek's ✕ can). `PEEK_SETTLED_PHASES`
  and that set now agree but remain two lists for two questions.
- **Returning from the plan puts the answer back QUIETLY.** The answer is re-inserted into the live region otherwise, and a screen
  reader reads the whole of it again around the focus that lands on the card (an accessibility review, P2): `backToAnswer` and a
  choice made in the plan view set `restored`, the flag a refusal already uses to render outside the live region. Focus returns to the
  "Plan this ›" of the card that opened the plan — **without `preventScroll`**, since the answer regrows near the top of its scroller
  and a later card is likely below the fold. The hand-off is set only by the two presses and spent once (a stale intent stole focus
  from a reader after a later answer: found by mutation, the first test of it passed vacuously because a mock that resolves at once
  lets React batch `busy` and `answer` into one render — it now holds the question in flight).
- **The four figures are the location sheet's own functions** (`utils/askPlan.js`): Leave home is `departureWithDay` over the card's
  served `eventInstant` and the HOME drive — extracted from `buildLocationSheet`'s row (`leaveByParts` plus the day word) and used by
  both; Best light is the exported `lightWindows` over `buildScoreIndex(scoreRows)`; Drive is the card's `driveLabel`
  (`formatDriveDuration` already); Tide is the card's own tide fact (already the tide-alignment index's), drawn with the map chip's
  `TideWave` (the letter for a match, the arrow for a miss) and the `STATE_WORD`. `askPlan.test.js` builds both sides from one
  briefing through the real `buildLocationSheet` and compares them value for value (including the midnight wrap, the id-less name
  lookup, a zero-minute drive and every missing input). **A dash is spoken as "Not known" — except Tide, "No tide data"**: an
  inland spot has no tide, which is not something unknown about it.
- **The no-postcode state.** Dashes for Leave home and Drive, and the tick line's own button ("Set a postcode for light and drive
  times", the long form as its accessible name at every width, the short form drawn on a phone) **only when the reader is KNOWN to have
  no postcode (`homePlace === null`, the tick line's positive answer, never an absent one)** and a door to settings exists. A reader
  with a postcode and no measured drive time to that spot sees dashes and no reason — **an open owner call**, not decided here
  (§6 Q7 promises an explanatory empty result for typed drive questions; this view has none). Pressed from the sheet, the nudge closes
  Ask first (`selectTab(effectiveTab)`) and hands settings `askRestoreFallback` as its return address.
- **The note is `card.summary` or nothing**, and no placeholder sentence stands in for it (§1 #7).
- **"Open in Plan ›" takes the primary style** (gold wash and edge, 44px): "Add to Coming up" carried it in the mocks, and a lone
  secondary button reads as disabled.
- **"Open in Plan ›", route by route.** Dock: `askOpenInPlan` = `selectTab('plan')`, `setSheetSpot`, `setSheetWindowKey(date:targetType)` in
  ONE handler (the batch the handoff effect's note explains); Ask stays open and goes `inert` (`askDialogOpen` already reads
  `sheetSpot`). Tablet and phone sheets: the same handler closes the Ask sheet through `selectTab`, so the location sheet is the
  single modal. Phone peek: `MapView.askPlanActions` calls `onOpenLocationSheet({…, inPlan: true, date, targetType})` with **the
  pick's own window — not `handleOpenLocationSheet`, which reads the date and event off the active map window and the place off the
  selection** (tested with a parent that does not follow the pick's date, so the map is on another window); `App`'s handoff does the
  rest, the pane goes hidden, `panelShown` falls and the Ask section closes (tested as one chain through the real shell and pane:
  `AskPeekPlanChain.test.jsx`). A dialog this shell does not own refuses the press — **but Ask's own sheet is itself a dialog outside the
  shell root**, so `foreignDialogOpen` refused every press from the sheet hosts until it gained `ignore` (the one dialog the press is
  about to close, matched by `ASK_SHEET_LABEL`); every other foreign dialog still refuses (tested with a positive control that also
  warms the lazy sheet — without one a sheet that DID open is only the Suspense fallback and the negative passes vacuously).
- **The way OUT of the location sheet — three defects found, all fixed.** (1) On the sheet hosts the Ask sheet's own close runs in the
  same commit as the location sheet's mount, with the Ask trigger already `disabled` and a modal standing, so its restore declines,
  nothing holds focus, the location sheet captures `<body>` as its opener and closing it left the reader there. `LocationFourDaySheet`
  gained `restoreFocusFallback` (`Modal`'s own option); the shell passes `askSheetFallback`, live only for a sheet opened from Ask's
  sheet. Closing now lands on the Ask bar or field. (2) From the dock the pressed button goes `inert` and `AskDock`'s rescue hands
  focus to the Plan tab, which is where the sheet's restore returns; the shell then returns the reader to the pressed control **only if
  focus was left on the PLAN tab (or nowhere)** — the first cut accepted any tab and yanked a reader who had arrowed along the tab bar
  (which closes the sheet too). (3) Safari and Firefox on macOS do not focus a button on a mouse press, so `activeElement` was
  `<body>` and there was no opener: the view passes the pressed control (`event.currentTarget`) to `openInPlan`. **Accepted:** the phone
  peek's route inherits the Map callout's — no return address of its own — and its postcode nudge passes none.
- **The highlight (`highlightKeys` is a `Map` of `date:targetType` → rank, not a `Set`):** the rank has to be drawn on the card, and
  only the selected pick highlights, so there is at most one entry. Gated in the shell on **the Plan tab being the one in force, Ask's
  SHEET not covering it, and Ask being available** (a 404 hides every surface but keeps the conversation, and a ring with nothing
  to clear it would stick). It is therefore also live on a dock that was closed with its ✕ (the conversation is kept; §2.8's "live on
  the dock"), and returning to the Plan tab scrolls to it again. The mark is the spec's border (.7) and wash (.08) **plus a second
  inset pixel of the same gold plus the rank circle**, because the open card's own look (border .62, wash .11) is almost the same
  gold; it keeps its own `:hover` arm and a doubled class so it beats `button.wf-hc.best.on:hover` by order. **The rank circle is in
  the card's top row beside the sun word, not hung over the corner** (measured in a browser: an overhanging circle sat 1px on the word's
  top edge, and the 2px ring bit 3px into it; 12px of overhang would have met the rail label above the first column). It costs
  the card its verdict tint and, on a BEST BET / ALSO GOOD card, the green border and ring (as `.wf-hc.on` already does); the legend
  keeps its ink. Forced colours: an inset outline, with the focus ring's own offset restored so focusing the card changes something.
  `scrollIntoView({block: 'nearest', behavior: 'auto' | 'smooth'})`, guarded for jsdom, on arrival or move only (the effect keys on the
  highlight's signature, never the cards), "Ask pick N" last in the card's accessible name. An away cell highlights nothing.
- **Known limits, accepted, for the owner:** (1) a pick whose event has PASSED keeps "Plan this ›" (a past "leave" time) and its Plan card
  is gone, and "Open in Plan ›" opens the sheet's own best window because the key names a window it does not hold (`buildLocationSheet`
  drops it) — only an answer left open past its window reaches this; (2) a typed answer is not re-checked against the clock the way
  Ready answers are; (3) under an away Plan origin the card says ⌂ and the sheet it opens measures from the base (§1 #24 decided
  both); (4) `Escape` in the plan view closes the dock or sheet, not one level; (5) a refused "Open in Plan ›" (a foreign dialog) says
  nothing; (6) the phone peek's `Open in Plan ›` and postcode nudge have no return address (above).
- **Tests (every file's claims mutation-tested; 41 one-line mutants, all killed; two were dropped as equivalent or stale):**
  `askPlan.test.js`, `AskPlanThis.test.jsx`, `AskShellPlan.test.jsx` (dock, tablet and phone sheets), `AskPeekPlanChain.test.jsx`,
  the F5 block of `AskPeekChain.test.jsx`, `WindowFirstHeatStripAskHighlight.test.jsx`, `askPlanCss.test.js` (the mock's measurements
  and tokens, the cascade facts, forced colours), `shellForeignDialog.test.js`; `AskConversation.test.jsx`'s "no Plan this" test
  became "Plan this on every pick, never Add to Coming up". The suite went from 325 files / 8,262 tests to 331 / 8,398 (the gate
  also ran lint, `npm audit --audit-level=high`, 0 vulnerabilities, and the build).
- **Review (six read-only lenses — runtime, CSS/tokens, test quality, accessibility, conventions, what it leaves for Z — then
  adjudicated):** fixed from review — the answer re-read by a screen reader after Back; off-screen focus after Back; the forced-colours
  focus ring and the grid's lost rules; the circle's collision; a vacuous foreign-dialog test and an untested postcode route; an
  untested ignore predicate; the dead guard and the misplaced doc block; `windowKey` reused; the highlight outliving Ask; the
  arrow-key yank; the macOS mouse press; focus lost when a rebuild drops the plan. The rest is the list above.
- **Seen vs tested** (a scratch Vite page mounting the real `index.css`, `AskDock`, `AskConversation`, `AskPlanThis`, `AskPickCard` and
  `WindowFirstHeatStrip` with the briefing and the Ask API stubbed through aliases, deleted; Chromium in the Browser pane): SEEN — the
  pick cards with "Plan this ›" in the dock (380px) and in a 320px column; the plan view in both (spot name `600 22px / 24.2px
  Newsreader`, the 2×2 at 130–152px cells, Leave home `16:46` for an 18:41 sunset with a 1h 35 drive, Drive `⌂ 1h 35min`, golden and
  blue ranges unbroken and wrapping label-over-range at 320px, the tide cell, the served note, the primary "Open in Plan ›"); and the
  matrix with a highlighted, an open and a highlighted-and-open card, a BEST BET and an ALSO GOOD card highlighted, the circle beside
  the sun word. **TESTED, NOT SEEN:** the real shell with a real dock or sheet beside the real matrix; the location sheet opening at the
  pick's row and the focus returns (jsdom, with a real `inert`-less DOM); `scrollIntoView` against the sticky lens bar; the phone Map's
  plan view in a real peek sheet (470px) and its minimised line; the iOS keyboard with the plan view's input row; forced colours;
  reduced motion; Safari's mouse-press focus rule; a screen reader on the plan view, the quiet return and the highlight's name.
- **For Z (everything this series has made false or incomplete in CLAUDE.md, and the rest of the sweep):**
  - *Plan tab paragraph:* "Search is the Plan tab's alone: `/` refuses elsewhere" is false from F2 (`/` is Ask's from 1024px;
    search keeps ⌕ and the origin button). Each matrix card can now wear Ask's pick (`highlightKeys` → `data-ask-highlight`, the rank
    circle in the top row, "Ask pick N" in the accessible name, a scroll into view), a different mark from `data-open`; live only on the
    Plan tab, with Ask's sheet not covering it and Ask available. The two-deep paragraph gains a route: Ask's sheet is a dialog outside
    the shell root and "Open in Plan ›" closes it through `selectTab('plan')` (`foreignDialogOpen(root, ignore)` excuses only it); the
    dock stays open and goes `inert`; focus returns through `askOpenerRef` (dock) and `restoreFocusFallback` (sheet). "THREE routes into
    that dialog" (settings) becomes five: Ask's nudge (`askSetPostcode`, through `selectTab(effectiveTab)`) and the peek's (no return
    address). The location sheet's hosts: a third opening route (Ask's Open in Plan, seeded with the pick's `date:targetType`) and the
    `restoreFocusFallback` prop.
  - *Map tab paragraphs:* "three sheet routes … one handoff" — the peek's Open in Plan calls `onOpenLocationSheet` directly with the
    pick's window, not `handleOpenLocationSheet`. The whole of F4's For-Z list (the phone peek's 74 → 126/112/470, `peek:ask`, derived
    minimised, three-valued `--psh`, the touch rules) plus: the peek carries the plan view and its minimised line reads "Plan this".
  - *Backend-heavy bullet:* `askModel.js` (the pick join), `askPlan.js` (`planFigures`), `departureWithDay`, the exported `lightWindows`,
    the shell's `askHighlight`, `AskContext`'s derived `phase`/`planPick`, `utils/askMapContext.js` and `useAskRequestContext` are all the
    already-licensed filter/map/select class; none is a new member and none re-derives a served verdict.
  - *Elsewhere in CLAUDE.md (the series as a whole is undocumented there):* What's Built (Ask PhotoCast: the Claude tool-loop engine,
    the stub engine, Ready answers precomputed after the pipeline run), API Endpoints (`POST /api/ask`, `GET /api/ask/ready`,
    `GET /api/user/settings/ask`, `/api/admin/ask/{dry-run,ready/precompute,metrics}`), the migrations table (the Ask migrations),
    `RunType` (`ASK`, `ASK_READY`), Resilience (`ask` retry, breaker, bulkhead), Roles (open to every role), User Settings (the new
    path under `HttpCachingConfigTest.personalDataPathsAreNeverFiltered`), the Rewind bullet (Ask is not mounted under a rewind), the
    Configuration keys (`photocast.ask.*`), local-dev (the fixture seeder), the suite sizes (re-measure; this phase alone is +5 files and
    +100 tests), and `AskPromptRegressionTest` with PIT's `excludedTestClasses`.
  - *This plan:* mark the F4/F3/F2/F1b "For F5" bullets and the "reserved for F5 / null until F5" lines as discharged; §2.8's "`lightWindows`
    … F5 exports it" and "Leave home = `leaveByParts`" (it is `departureWithDay`, which is `leaveByParts` plus the day word) are now
    history; §3's F5 file list and "pickActions joins" are superseded by this note; §4 gains: the plan view hides the chips and the
    question bubble, "Open in Plan ›" takes the primary style, the minimised line says "Plan this", the highlight is window-only and
    carries the rank in the card's top row.
  - *Production-enable checklist (known from this series):* `photocast.ask.enabled` (default false; the prod host's config is hand-edited,
    so read the running file); `stub` must NOT be true; `daily-spend-cap-usd` (0.50) and what happens when it trips (503
    `TYPED_UNAVAILABLE` for everyone until the next UK day, with only an admin email as the signal) — so `notifications.admin-alerts.enabled`
    and working mail; the `ask` resilience blocks in the production profile; the Ask migrations proven by CI's Backend job; `POST
    /api/admin/ask/ready/precompute` once after enabling, then the metrics endpoint; §7's measurements (about 1p a typed question over 20
    dry-runs, the Monday and BEST BET checks, the iOS Simulator keyboard, 390 × 844 and 375 × 667); `AskPromptRegressionTest` with the
    owner's approval of its assertions; the frontend hides Ask on a 404 or `enabled: false`, so rollback is the flag; and the open owner
    calls above (the postcode-with-no-drive-time empty state; the passed-window pick).

### Z — Sweep — S/M
CLAUDE.md (What's Built, API Endpoints, the migrations table, the Backend-heavy bullet's note on
`askModel.js`, the `/` key); `application-example.yml`; `AskPromptRegressionTest`
(`@Tag("prompt-regression")`, added to PIT's `excludedTestClasses`) — three cases, structural
invariants only, **its assertions shown to the owner for approval before the commit**, because no
later session may change them; §7 measured; the production enable checklist (flag, cap value,
`notifications.admin-alerts.enabled`).

*As built (Z):*
- **CLAUDE.md** — corrected: the Plan-tab paragraph's "search is the Plan tab's alone: `/` refuses elsewhere" (search is the ⌕ and the origin button only; `/` is Ask's from 1024px), the dialog-stack paragraph (Ask's sheet and dock are layers that are not stack members), "THREE routes into settings" (five), the location sheet's routes (a third, from Ask), the Map v2 bullet's "one handoff carries all three" (the phone peek's Open in Plan is the exception), the whole of the peek-sheet bullet's 74px/`--psh`/D-7/`'peek:*'`/"every map touch collapses it" wording (now the Ask-off truth plus one ⚠️ saying what F4 changed), `RunType`, *Resilience*, *Rewind*, *Roles*, the suite sizes and the integration-class count (6 → 13). Added: the *Ask PhotoCast* bullets (the two doors, the horizon-aware catalogue, the engine, the guards, the client), the *Backend-heavy* bullet's note that Ask adds no class, an *Ask PhotoCast* section in *API Endpoints* with the error table, `GET /api/user/settings/ask`, the V165–V167 rows, the local-dev recipe, the `photocast.ask.*` keys, *Role gating*. `backend/AGENTS.md` gained deliberate-decision 11 and the prompt-regression rule.
- **`application-example.yml`** — every `photocast.ask.*` key with default and range (the `ask` resilience instances were already there from B2a).
- **`AskPromptRegressionTest`** (`service/ask/`, `@Tag("prompt-regression")`, in PIT's `excludedTestClasses`; `PitExclusionDriftTest` passes) — three cases through the REAL `ClaudeAskEngine` against `src/test/resources/prompt-regression/ask/ask-fixture-snapshot.json` (a compact description the test maps through `AskFixtures` and the real `AskSnapshotBuilder`): a where question, a rare-events question, and an unanswerable one. Structural invariants only. **It skips (the class is aborted in `@BeforeAll` by an assumption) when `ANTHROPIC_API_KEY` is unset** — the two older regression classes throw instead; a skip is the brief's wording and is visible in the surefire report. It was compiled and skip-tested only: no real call was made by the session that wrote it. **Its assertions were shown to the owner before the commit; once approved they are frozen and no later session may change them.**
- **§7, §11, §12** below.

---

## §4 Disagreements with the spec, on purpose

1. Ready questions are a horizon-aware catalogue (§1 #1).
2. Ready answers never mention home (§1 #2).
3. Picks carry ids; every card fact is served (§1 #5).
4. Picks exclude ineligible regions and woods, and a generic "best" leads with BEST BET (§1 #6).
5. No scripted fallback; an error state (§1 #19).
6. Plan this's note is the served summary (§1 #7).
7. No floating card in v1; a sheet below 1024px, with "Show on map ›" (§1 #11).
8. The dock runs the full height beside the masthead, not below the tab row (§2.6).
9. `GET /api/ask/ready` takes `scope`; allowance has its own endpoint (§2.4, §1 #17).
10. Events are `{type, label, date, why}`, not `{topic, why}` (§2.9).
11. Event colours come from the badge channels (§1 #10).
12. Heat dims inside the draw (§1 #14).
13. The can't-answer filter is whole-phrase and runs first (§1 #15).
14. Questions are restricted to a character allow-list (§2.5 step 2).
15. Personal typed answers are cached per user; hits are re-checked for freshness (§2.5 step 6).
16. Ready matching is a keyword classifier, not embeddings.
17. Claude writes no `try` suggestions; the server chooses them.
18. A never-refunded daily ceiling on engine calls (§1 #20).
19. `max_tokens` is 600, not 400 (a 400 ceiling truncates the reply into a paid failure).
20. "Pro: 30 a day" is text, not a link.
21. "Add to Coming up" is removed outright (§1 #9, owner decision).
22. Selecting a pick clears the map selection rather than selecting the location (§2.7).
23. Scope follows the Map's scope segment, not the viewport; Plan sends no window chip (§2.6).
24. Drive on a card is always from home (§1 #24).
25. No Ask on the Operations tab.
26. Ask attaches the solar-eclipse lens-filter warning to almanac entries itself (§2.3). An
    observation for the owner, not something this series changes: the Coming up tab itself carries
    no solar-eclipse safety warning today (`ComingUpEntry` has no such field and
    `ComingUpAssembler.enrichEclipse` sets none), so the warning exists on the Plan card's hot topic
    and, through Ask, nowhere on the feed.

## §5 Decisions taken in this plan (challenge in review, not in code)

- **D-1** The reply is a `submit_answer` tool call, not JSON in prose.
- **D-2** Tools are in-process over one memoised `AskSnapshot` built from the Plan tab's assembly.
- **D-3** Pick-eligible = non-canopy, rated ≥3, eligible region, window with a card. No exemptions.
- **D-4** The dock is a sibling of the whole shell column.
- **D-5** Freshness is all-or-nothing against live data, for Ready answers and cache hits alike.
- **D-6** A typed-cache hit is not charged.
- **D-7** ADMIN has the PRO allowance; the spend cap is the real ceiling.
- **D-8** The cache and the rate limiter are in-memory; a restart empties them.
- **D-9** One `ASK` job run per UK day with a scoped cost increment; `ASK_READY` per precompute.
- **D-10** The model is a property limited to Haiku and Sonnet 4.6, not a Models-screen row.
- **D-11** Ask has its own retry, breaker and bulkhead.
- **D-12** Precompute runs after the pipeline run is finished, never inside it.
- **D-13** `ask_log` stores a question only for answered-by-Claude outcomes, for 90 days.

## §6 Owner decisions (binding; Q1–Q4 and Q7 decided by the owner on 2026-10-05)

- **Q1 — the `/` key. DECIDED:** `/` focuses Ask at ≥1024px on Plan, Coming up and Map; Plan
  search keeps ⌕ only.
- **Q2 — "Add to Coming up". DECIDED: removed.** Coming up is for weather-type events, not a
  schedule. No button, no table, no phase.
- **Q3 — the floating card. DECIDED:** deferred; sheet below 1024px. Building it later needs the
  top-right map controls to move and `MapCallout` to learn a right-hand obstacle.
- **Q4 — the spend cap. DECIDED:** $0.50 a day, typed questions only (roughly 40–50 questions at
  the estimated cost); precompute capped separately at 6 cycles a day.
- **Q5 — Ready hit rate.** *Default:* measured for typed questions only (a tap makes no request).
- **Q6 — charging.** *Default:* cache hits free; `answerable:false` always refunded; the engine
  ceiling is 3 × the allowance.
- **Q7 — LITE and drive questions. DECIDED:** `maxDriveMinutes` works for anyone with stored
  drive times; nothing is added to give LITE drive times.
- **Q8 — "My area" across several regions.** *Default:* the Ready set shown is the whole
  catalogue's, and the chip says so; typed questions send the area's regions.

## §7 Verify by measurement

Measured 2026-10-06 on `29382830` plus Phase Z, by the Z session, with **no real Claude call**: the backend gate (`./mvnw clean verify
-Dtest='!**/integration/**'`, 11,146 tests, exit 0), the frontend gate (lint, 8,398 tests in 331 files, `npm audit` 0 vulnerabilities, build, exit 0),
and a local backend on the stub engine against a fresh H2 file with the §9 fixture (`--photocast.ask.seed-local-fixture=true`, a dummy
`ANTHROPIC_API_KEY` and a dead HTTPS proxy so nothing could leave the machine), driven with `curl` as the local `admin` user. The session could
not sign in to a browser, so every browser row is the owner's (§11).

| Check | How | Result |
|---|---|---|
| A Ready answer opens with no request | network panel: zero requests between tap and answer | **Asserted from code and test, not from a network panel.** `AskContext.openReady` makes no request: the answer is already in the list `useAskReady` fetched (the 400 ms is a timer), pinned by `AskConversation.test.jsx` "makes no request to open it: the answer is already in the list". The list itself is one `GET /api/ask/ready` per surface open, ETag-revalidated: measured `200` with `ETag` then **`304`** on `If-None-Match`. Network-panel check: §11 |
| A typed question costs about 1p | mean **and p95** of `api_call_log` cost over 20 dry-run questions | **Needs the real engine — owner's run.** Start the backend on the real key with `stub` off (this bills it), sign in as an admin, then for each of 20 varied questions: read `total_cost_micro_dollars` of today's `ASK` `job_run` (`SELECT id, total_cost_micro_dollars FROM job_run WHERE run_type = 'ASK' ORDER BY id DESC LIMIT 1`), `POST /api/admin/ask/dry-run {"question": …, "regionIds": []}`, read it again; the difference is that question's cost (the increment is display-only but is the same figure the cap sums). Mean and p95 of the 20 differences; the answer to "about 1p" is those two numbers. Also record `turns` and the `trace` of each (a 4-turn conversation is the tail) |
| Monday offers no weekend question | B3's predicate test + the browser on a Monday fixture | **Tested, and seen on the API, on a Tuesday.** `ReadyQuestionTest` ("`BEST_WEEKEND` is not offered on a Monday … but is offered on a Friday") passes in the gate. Live: `POST /api/admin/ask/ready/precompute` on the stub on Tuesday 2026-10-06 wrote **5 of 7** questions for scope `all` — `BEST_SOON`, `BEST_NEXT`, `COASTAL_HIGH`, `AM_OR_PM`, `RARE_EVENTS` — and no `BEST_WEEKEND` (Saturday is outside the window set) and no `SNOW_TOPS` (no snow topic); `{written: 15, skipped: 13, failed: 0}` over 4 scopes (3 fixture regions + `all`). The browser on a Monday: §11 |
| Ask's "best" agrees with BEST BET | B3's anchor test + the browser: pick 1's card wears BEST BET | **Tested; the browser check needs a real gloss — owner's run.** The B3/validator anchor tests pass (`AskReadyServiceTest` "only a BEST_* question carries an anchor…", `AskAnswerValidatorTest`). The local fixture has **no BEST BET** (a window's pick needs the region's Claude gloss headline and the fixture makes none), so it cannot be rehearsed locally. With the real engine, `AskPromptRegressionTest`'s first case asserts pick 1 is on the fixture's BEST BET window |
| Picks on the map match the cards | each chip's rating equals its card's, on the stub | **Tested; seen in F3's stub harness, not on the real shell.** `MapLabelsAsk`, `MapViewAsk`, `PinsLayerAsk` and `AskMapLinkage` assert a pick chip carries its own window's rating, the one its card shows; F3 saw "Whitby Tue PM 5★" and the other two chips against their cards at 1280 on a scratch page. §11 |
| Nothing personal is ETag-cached | response headers on `/api/user/settings/ask` and `POST /api/ask` | **Measured.** `GET /api/user/settings/ask` → `200`, `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, `Pragma: no-cache`, `Expires: 0`, **no `ETag`**. `POST /api/ask` → the same, no `ETag`. `GET /api/ask/ready` (user-independent) → `Cache-Control: private, no-cache` and an `ETag`, as intended. `HttpCachingConfigTest.personalDataPathsAreNeverFiltered` pins the personal path |
| An abuser cannot spend freely | B4's `DAILY_LIMIT` test; 10 unanswerable questions as LITE locally | **Tested; the 10-question local run is not possible on the stub.** `AskServiceTest` "an unanswerable question every time hits DAILY_LIMIT at 3 x the allowance with used still 0" passes. The stub never answers `answerable:false` (so nothing is ever refunded) and a LITE account would have to be created, so the row is the owner's with the real engine: as a LITE user ask 10 questions the forecast cannot answer that pass the pre-filter ("What's the pollen count?"), expect the 10th to be 429 `DAILY_LIMIT` with `GET /api/user/settings/ask` still showing `used: 0`. **Measured instead, live:** five rapid `POST /api/ask` — four `200` then **`429 RATE_LIMITED`**, and a **malformed body is `429` too** (the limiter runs before the body is read); the typed cache: the same non-Ready question twice → `kind: own, charged: true, allowanceLeft: 29` then `charged: false, allowanceLeft: 29`; `GET /api/admin/ask/metrics` counted the outcomes (`PREFILTER_CANT` 4, `READY_MATCH` 1, `CLAUDE_OK` 1, `CACHE_HIT` 1) and returned no question text |
| Phone: answer minimises, callout clears it | 390 × 844 and 375 × 667, measured rects | **Owner's run (§11).** F4 saw the sheet at exactly 126 / 470 / 112 px at 390 × 844 and 320 × 568 on a scratch page and a 10px gap under the pill at a 440px frame; the real `MapView` with a real callout over the 112px line was tested in jsdom only |
| iOS keyboard does not cover the input | Simulator, the sheet on Plan | **Owner's run (§11).** Tested only (`visualViewport` arithmetic); the Simulator needs the signed-in app |
| Desktop: columns stay aligned, map refits | 1280 and 1600: masthead and panel left edges equal | **Owner's run on the real shell (§11).** F2 measured both edges at 16 → 884 (1280) and 70 → 1150 (1600) on a scratch harness with a stubbed briefing; the real `MapView` refit under the dock was tested, not seen |

## §8 How the chain is run

The orchestrating session starts each phase's Sonnet session in its own worktree with the prompt
from `ask-photocast-prompts.md`. The implementing session never pushes. **Pushing, opening the PR
and merging happen only on the owner's explicit instruction for that phase** (CLAUDE.md, Git
Conventions). After the push the orchestrator reads CI — the Backend job is the only proof of a
migration — and the Codex review, and updates §0 at merge.

## §9 Local verification recipe

1. `application-local.yml` carries `photocast.ask.enabled: true` and `stub: true` (spend-free, safe as
   committed defaults). `seed-local-fixture` is **false** there and is turned on for one run on the command
   line (step 2): the seeder writes into the developer's own H2 file and triggers a briefing build, which
   makes the gloss and best-bet Claude calls with whatever `ANTHROPIC_API_KEY` is set.
2. Backend, against a fresh H2 file so a real `backend/data` is never touched:
   `./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local
   -Dspring-boot.run.arguments="--photocast.ask.seed-local-fixture=true
   --spring.datasource.url=jdbc:h2:file:./data/ask-fixture;AUTO_SERVER=TRUE"` (port 8083; add
   `--server.port=8093` if a parallel session holds it). A session that must be certain no Claude call
   leaves the machine also sets a dummy `ANTHROPIC_API_KEY` and
   `-Dspring-boot.run.jvmArguments="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9
   -Dhttp.nonProxyHosts=*.open-meteo.com|localhost|127.0.0.1"` (everything but Open-Meteo then goes to a
   dead proxy). The seeder writes the rated fixture and builds a briefing.
3. Ready answers: `POST /api/admin/ask/ready/precompute` (works on the stub, costs nothing).
4. Frontend: `npm run dev`. **The owner signs in** — the session cannot; without that, report
   "tested, not seen".
5. ⚠️ Turning the stub off bills the real Anthropic key (about 1p a question). `POST
   /api/forecast/run` is never part of this recipe.

## §10 What the adversarial review changed (2026-10-05)

Four read-only reviewers, 52 findings; none refuted outright. The substantive changes:

- **Honesty:** the "≥4★ exempts an ineligible region" rule deleted (it rebuilt the 2026-09-29
  failure); woods excluded from ranking; BEST BET exposed and anchored; tide state and tide
  preference returned as separate fields; freshness made all-or-nothing so stored prose cannot
  contradict live cards; drive pinned to home.
- **Money and abuse:** a never-refunded engine ceiling; a spend query that can actually be built;
  typed and Ready spend separated; Ask's own breaker and retry; the snapshot built after the rate
  limit; a character allow-list against cache poisoning; cache key widened and hits re-validated;
  denied requests write no rows; delete-user behaviour specified; `max_tokens` raised and tool
  results capped.
- **Backend facts:** the per-day job run would have shown £0 forever; the timeouts needed a new
  client overload; the window set limited to events that have a card; precompute moved out of the
  pipeline run; flag-off behaviour made consistent.
- **Frontend:** the floating card deferred; the dock moved out of the panel wrapper; open/closed
  state moved to the shell; the window-follow given its own channel; an Escape and outside-press
  ladder written down; a phone state table; camera padding clamped; pick chips built from their own
  window with a rank-circle fallback; the heat dim moved inside the draw.
- **Delegability:** literal wire contracts (§2.9); B2 and F1 split; a local fixture seeder and an
  admin precompute endpoint so the browser can show a rich state; binding defaults for every owner
  question; P0; the prompt-regression class moved to Z with owner approval.

*Examined later, B2a, and settled in B2b:* production's `job_run.run_type` is a plain `VARCHAR(20)` with
no check constraint (V29; no later migration touches it), so `ASK` and `ASK_READY` need **no migration**. The
local H2 schema is different: Hibernate maps every `@Enumerated(STRING)` column to a native H2 `enum (...)`
type fixed when the table is created, and `ddl-auto: update` does not alter it — so a developer's **existing**
`backend/data/goldenhour.mv.db` refuses an `ASK` row. B2a read this from the DDL; **B2b reproduced it**
(`LocalH2EnumOldSchemaReproductionTest`: a file database with the fifteen pre-Ask `RunType` values, the real
Hibernate with `update`, and `saveAndFlush(ASK)` fails with *Value not permitted for column … "ASK"*) **and
fixed it**: `LocalH2EnumWidener` (local profile, H2 only, a `SmartInitializingSingleton`) widens each
registered column to its current values plus the Java enum's with one `ALTER TABLE … SET DATA TYPE ENUM(…)`
(H2 keeps stored values and `NOT NULL`; checked), idempotent and never failing startup. Its registry
(`TARGETS`) has one line, `job_run.run_type`; **add a line when a change adds a value to an enum whose column
an existing local database must accept.** A fresh file and every test were never affected.

**Not examined by any reviewer:** `PinsLayer` and `MapLegendPanel` internals; the tests that pin
74px beyond two files; `o20-shell-inert-plan.md`; the real token size of production briefing and
almanac payloads (the 1p figure is an estimate until §7 measures it); whether Anthropic bills a
generation abandoned at the client timeout; how a travel day is marked on the served briefing (B1 found
it is not, and uses `TravelDayService`); whether `RunType.ASK` trips an H2 enum constraint under local
`ddl-auto: update` (it does, and B2b fixed it, above). When the plan was written nothing had been built or
run.

## §11 Owner's browser check

Everything below was **tested but not seen**, or is an accepted limit the owner should look at once with the app signed in (the implementing
sessions cannot sign in). Run the backend per §9 (stub, fixture) with `npm run dev`; the checks that need real numbers are marked.
Tick each; a defect found here is a new fix, not an edit to the sessions' notes.

**F1b — phone and tablet-portrait entry**
- [ ] 390 × 844: the 48px ask bar does not cover the last Plan row or the footer links; tabbing near the bottom scrolls clear of it.
- [ ] Tablet field beside 3 and 4 tabs at 640 / 720 / 834 / 1023 — as an admin with the Coming-up badge showing, check **720 and 726** (under 10px of slack; raise the collapse breakpoint to 740 if the tab row scrolls).
- [ ] Sheet slide-up and height; the input stays above the iOS keyboard (open and close with the keyboard up; the dead gap on a notched iPhone); focusing the input on open raises the keyboard at once and the dialog's name may not be announced first — decide whether that is acceptable.
- [ ] `inert` really blocks Tab and pointer behind the sheet; focus returns to the bar, and to the Map tab after "Show on map ›".
- [ ] Forced-colours focus rings; the Map tab on a tablet with the sheet over it.
- [ ] Accepted: a held answer does not announce itself while the sheet is closed; a disabled entry gives no reason.

**F2 — desktop dock**
- [ ] 1024 / 1280 / 1600: masthead and panel left edges equal with the dock open; on Plan the dock's input stays in view while the page scrolls.
- [ ] The REAL map refits when the dock opens and closes (picks near the right edge are not clipped); the width animation; reduced motion.
- [ ] The dock beside the real banners and the real footer; the dock under a window popup's scrim; Safari (container queries, `dvh`, `inert`); an iPad at 1024 with real insets.
- [ ] **Owner question, decide now:** with `photocast.ask.enabled` false `/` does nothing on Plan (search lost its key; `/` was also the only keyboard route to search over an open popup). Keep, or give `/` a fallback to search while Ask is off?
- [ ] Accepted: a short Plan column scrolls ~170px under the 100dvh dock; the dock is not `inert` under the map overlay; no skip link past the dock; Escape discards an unsent draft; four tabs collapse under ~751px viewport on a tablet for an admin.

**F3 — map linkage**
- [ ] Numbered picks with their own window and rating, everything else at `.25`, the heat dimmer next to the REAL coastline stroke; the camera fits on a real dock open/close; choosing a pick flies to about zoom 10.5 and opens no callout.
- [ ] The bare rank circle under a label squeeze and the forced dot; the 25px compact circle; the chosen chip's focus ring; reduced motion; forced colours; the tablet sheet holding and releasing the fit.
- [ ] Accepted: an aurora window draws no chips (the camera still fits); a typed question in flight clears the numbering for its duration; a stale selection at the pane's first mount can win over a Plan door.

**F4 — phone Map peek**
- [ ] 390 × 844 and 375 × 667 with measured rects: the open sheet never covers the pill; a minimised answer and a callout do not overlap; the real `MapView` + Leaflet + real callout over the 112px line.
- [ ] The iOS keyboard with the field at the top of a sheet that is not `visualViewport`-aware; the transition and reduced motion; whether the re-fit when a touch minimises the answer reads as a jump; a screen reader on the parked Ask node and the minimised line; Safari; the deferred inset re-apply during a drag.
- [ ] Accepted: on frames under ~590px the expanded sheet covers the fitted picks (minimising re-fits); the parked focus node has no role; Tide and Layers are a step further while an answer is minimised; `--psh` steps instantly while the sheet animates.

**F5 — Plan this**
- [ ] The real shell with a real dock or sheet beside the real matrix: the highlight scrolls clear of the sticky lens bar; "Open in Plan ›" opens the location sheet at the right row and focus comes back (dock, tablet sheet, phone peek); Safari's mouse-press focus rule.
- [ ] The phone plan view in a real 470px peek and its minimised line; the iOS keyboard with the plan view's input row; forced colours; reduced motion; a screen reader on the plan view, the quiet return to the answer and the highlight's name.
- [ ] Accepted, decide whether each is acceptable: a pick whose event has passed keeps "Plan this ›" and its Plan card is gone; **a postcode with no measured drive time shows dashes and no reason** (§6 Q7 promised an explanation for typed drive questions, this view has none); Escape closes the dock or sheet rather than stepping back from the plan view; a refused "Open in Plan ›" says nothing; the phone peek's route has no return address; a plan whose card disappears and returns reappears unasked.

**Z — the rest**
- [ ] §7's rows marked "owner's run": the 20-question cost figure, the Monday browser check, pick 1 wearing BEST BET, the 10-question LITE run, the Network panel for a Ready tap, the phone rects, the iOS Simulator keyboard, the desktop edges.
- [ ] `AskPromptRegressionTest` once with a real key (§12 step 8).
- [ ] The Coming up tab itself shows no solar-eclipse safety warning (the warning exists on the Plan card's hot topic and, through Ask, on Ask's cards): look at it and decide.

## §12 Production enable checklist

Ask ships **off**. Nothing below is done by the implementing sessions; each step is the owner's, in order.

1. **CI is green on the Ask PRs, and the migrations ran.** V165–V167 are proven before merge only by CI's Backend job. After the deploy, confirm production applied them: `SELECT version, description, success FROM flyway_schema_history WHERE version::int >= 165 ORDER BY installed_rank;` (three rows, `success = t`), the tables `ask_ready_answer`, `ask_usage` and `ask_log` exist, and `SELECT job_key, cron_expression, status FROM scheduler_job_config WHERE job_key = 'ask_log_cleanup';` returns `0 55 3 * * *`, `ACTIVE` (the prod DB recipe is in the owner's notes: the container is `goldenhour-db`).
2. **Read the RUNNING config, not this repo's.** The production host's checkout is stale and its config hand-edited. Confirm: `photocast.ask.enabled` is what you intend (default false); **`photocast.ask.stub` is absent or false** (startup refuses `stub=true` under `prod`, so a wrongly set flag is a failed boot, not a silent template); the `ask` blocks exist under `resilience4j.retry`, `circuitbreaker` and `bulkhead` (`application-prod.yml` has them; a host file that replaces it wholesale without them would run the `ask` instances on Resilience4j defaults and the 4-concurrent bulkhead would not exist).
3. **The spend cap.** `photocast.ask.daily-spend-cap-usd` defaults to 0.50 a UK day, typed questions only (roughly 40–50 questions at the estimated 1p — replace that with §7's measured figure). Reaching it answers 503 `TYPED_UNAVAILABLE` to **everyone** until the next UK day, and the only signal is an email to the admins.
4. **That email can arrive.** `notifications.admin-alerts.enabled: true` (committed true in `application-prod.yml`; confirm on the host) and working mail (the health widget's mail probe). Without either, the cap trips silently.
5. **Turn it on:** set `photocast.ask.enabled=true`, restart, and confirm `GET /api/user/settings/ask` returns `enabled: true` for a signed-in user and the client now shows Ask. With the flag off the client shows nothing and every endpoint is 404.
6. **First Ready answers.** `POST /api/admin/ask/ready/precompute` once (it spends real Claude money: at most (regions + 1) × 7 Haiku conversations, billed to an `ASK_READY` run), or wait for the next pipeline cycle (at most `ready.max-cycles-per-day`, 6, scheduled runs a day). A manual briefing rebuild does NOT trigger it. Then `GET /api/ask/ready?scope=all` and `GET /api/admin/ask/metrics`.
7. **The first real dry-run is the owner's paid call.** `POST /api/admin/ask/dry-run` with a handful of questions; read the `trace` and `turns`, look at the day's `ASK` run in Operations, and do §7's 20-question cost measurement before announcing it to anyone.
8. **Run the regression class once with a real key:** `cd backend && ANTHROPIC_API_KEY=… ./mvnw test -Pprompt-regression -Dtest=AskPromptRegressionTest` (a few pence). Its assertions are the owner's (shown to them at the Phase Z stop point, frozen once approved); a failure is a prompt or tool regression, never a reason to loosen them.
9. **Rollback is the flag.** Set `photocast.ask.enabled=false` and restart: every Ask endpoint is 404 and the client hides every surface. There is no data to unwind; `ask_ready_answer`, `ask_usage` and `ask_log` may stay (the log prunes itself at 90 days, the Ready table is at most (regions + 1) × 7 rows).
10. **Open owner questions that outlive Z:** (a) `/` does nothing on Plan while the flag is off (§11, F2); (b) "Pro: 30 a day" is a constant on the client — if `limit-pro` ever changes, add a `proLimit` to `GET /api/user/settings/ask`; (c) the Coming up tab shows no solar-eclipse warning; (d) a postcode with no measured drive time to a pick shows dashes with no reason; (e) §6 Q5 and Q8 are still on their defaults; (f) the floating card (Q3) and an Ask-aware aurora window (no chips there) are not built.
