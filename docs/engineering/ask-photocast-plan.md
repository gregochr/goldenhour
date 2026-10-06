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
| F1a | Client core, unmounted: API, hooks, provider, pick model, conversation and cards | M/L | not started |
| F1b | Phone and tablet-portrait entry: ask bar, tall sheet, shell wiring | M/L | not started |
| F2 | Desktop: tab-row field, the `/` key, the docked column | L | not started |
| F3 | Map linkage: numbered picks, dimming, camera, window follow | L | not started |
| F4 | Phone Map: the ask row in the peek sheet | L | not started |
| F5 | "Plan this", "Open in Plan", the Plan-card highlight | M | not started |
| Z | Sweep: CLAUDE.md, prompt-regression class, measured Verify list, production enable | S/M | not started |

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
  The 48px ask bar is `position: fixed`, offset by `--safe-b`, below `Modal`'s z-50, and the pane
  reserves 58px so the last row is not covered. A tab switch closes the sheet (`selectTab`'s list).
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

- **Window follow is its own channel.** `askWindow` is App state with its own nonce and its own
  effect in `MapView` (`setEventType`, `setUserHasOverriddenEvent(true)`); `App.selectDate` carries
  the date. It does **not** ride `mapTabHandoff`: that effect applies the Plan lens to every source
  that is not `'map'`, mounts `MapBreadcrumb`, and is a single slot a second writer would clobber.
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
  `map.getSize()` on either axis (Leaflet returns a NaN zoom when padding exceeds the frame, and
  the mock's 490px is taller than the real phone frame). Selecting a pick: `flyTo` at zoom 10.5,
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
(its own doc records this stale-band defect for the tide strip).

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

### F3 — Map linkage — L
**Files:** `App.jsx` (`askWindow`), `WindowFirstMapPane.jsx`, `MapView.jsx`, `MapLabels.jsx` +
`utils/mapLabels.js`, `PinsLayer.jsx`, `MapHeatLayer.jsx`, a new `AskCameraController`, `index.css`.
**Tests:** `askWindow` sets the window without touching the lens, the scope, `minStars` or the
breadcrumb, and a live Plan-door handoff survives it; a pick chip shows its own window's rating; a
pick filtered out by the reader's floor is still labelled; an unplaceable pick falls back to its
rank circle; fade beats tide dimming (cascade test); the heat dim leaves the coastline alone;
padding is clamped on a 400px-tall frame (no NaN zoom); selecting a card clears the selection and
opens no callout; selecting a map pick selects the card; clearing restores everything; reduced
motion.
**Seen:** dock at 1280 — the map narrows, refits, all picks inside the viewport, each chip's rating
equal to its card's.

### F4 — Phone Map — L
Per §2.7's phone table: **one test per cell**. Updates every 74 literal and the tests that pin them
(`mapPhoneChromeCascade`, `MapViewMobilePeekSheet`, `MapPeekSheet`, `mapCalloutClampCascade`,
`MapViewTideStripCalloutWiring`). Desktop and tablet provably unchanged on the same fixture.
**Also tested:** the callout's band reads 112 under a minimised answer and repaints on `bandKey`;
focus never falls to `<body>` when the ask row replaces the buttons (asserted on
`document.activeElement`).
**Seen:** 390 × 844 and 375 × 667 — the open sheet never covers the pill; a minimised answer and a
callout do not overlap (measured rects).

### F5 — Plan this — M
**Files:** `components/ask/AskPlanThis.jsx`, `utils/locationSheet.js` (export `lightWindows`),
`WindowFirstHeatStrip.jsx` (`highlightKeys`), the shell wiring for Open in Plan.
**Tests:** each of the four figures equals the location sheet's own output for the same fixture;
the no-postcode state; "‹ Back to the answer" restores the selected pick and focus; Open in Plan
closes the sheet first and opens the location sheet at the pick's window; the highlight is distinct
from `data-open`, does not open the popup, and a pick with no card highlights nothing.

### Z — Sweep — S/M
CLAUDE.md (What's Built, API Endpoints, the migrations table, the Backend-heavy bullet's note on
`askModel.js`, the `/` key); `application-example.yml`; `AskPromptRegressionTest`
(`@Tag("prompt-regression")`, added to PIT's `excludedTestClasses`) — three cases, structural
invariants only, **its assertions shown to the owner for approval before the commit**, because no
later session may change them; §7 measured; the production enable checklist (flag, cap value,
`notifications.admin-alerts.enabled`).

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

| Check | How |
|---|---|
| A Ready answer opens with no request | network panel: zero requests between tap and answer |
| A typed question costs about 1p | mean **and p95** of `api_call_log` cost over 20 dry-run questions |
| Monday offers no weekend question | B3's predicate test + the browser on a Monday fixture |
| Ask's "best" agrees with BEST BET | B3's anchor test + the browser: pick 1's card wears BEST BET |
| Picks on the map match the cards | each chip's rating equals its card's, on the stub |
| Nothing personal is ETag-cached | response headers on `/api/user/settings/ask` and `POST /api/ask` |
| An abuser cannot spend freely | B4's `DAILY_LIMIT` test; 10 unanswerable questions as LITE locally |
| Phone: answer minimises, callout clears it | 390 × 844 and 375 × 667, measured rects |
| iOS keyboard does not cover the input | Simulator, the sheet on Plan |
| Desktop: columns stay aligned, map refits | 1280 and 1600: masthead and panel left edges equal |

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
