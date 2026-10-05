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
| B1 | Read model, tool functions, answer contract, validator (no Claude, no endpoint) | M/L | not started |
| B2a | The engine: Claude tool loop, properties, run types, cost logging | L | not started |
| B2b | Stub engine, local fixture seeder, admin dry-run | M | not started |
| B3 | Ready answers: catalogue, precompute after the pipeline, `GET /api/ask/ready` | L | not started |
| B4 | `POST /api/ask`: allowance, limits, spend cap, `GET /api/user/settings/ask` | L | not started |
| B5 | Pre-filter, Ready intent match, typed cache, `ask_log`, metrics endpoint | M/L | not started |
| F1a | Client core, unmounted: API, hooks, provider, pick model, conversation and cards | M/L | not started |
| F1b | Phone and tablet-portrait entry: ask bar, tall sheet, shell wiring | M/L | not started |
| F2 | Desktop: tab-row field, the `/` key, the docked column | L | not started |
| F3 | Map linkage: numbered picks, dimming, camera, window follow | L | not started |
| F4 | Phone Map: the ask row in the peek sheet | L | not started |
| F5 | "Plan this", "Open in Plan", the Plan-card highlight | M | not started |
| S1 | "Add to Coming up" (**owner-gated, §6 Q2**) | L | not started |
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
scored horizon: Gate 4 never scores T+4+, and only the first six upcoming events carry a served
`BriefingWindow` (`PlanWindowProjector`, `PlanRenderLimits.MAX_VISIBLE_EVENTS`). **Ready questions
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

### 9. "Add to Coming up" has nothing to add to
Coming up is the ETag-shared almanac. There is no per-user saved item anywhere. Phase S1, gated on
§6 Q2. **Until S1 ships the button is not rendered.**

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
a rewind is active**.

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
- **The window set** is the event summaries of the served briefing whose `window()` is non-null,
  that `PlanWindowProjector.hasPassed` has not retired, and that are not travel days (B1 verifies
  how a travel day is marked and excludes it). Solar only. Every tool and every Ready predicate
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

The snapshot is memoised for 30 seconds (the assembly is not cheap; CLAUDE.md's digest note).

**Pick-eligible slot:** non-null `locationId`; not `canopy`; non-null `claudeRating` ≥ 3; its
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

- **Model:** `photocast.ask.model`, default `HAIKU`, validated to `HAIKU | SONNET` at startup. No
  `model_selection` row and no entry in `ModelSelectionService.CONFIGURABLE_RUN_TYPES` — Sonnet 5.5
  must not be selectable (it cannot disable thinking, which breaks the token and time budgets).
- **Loop:** at most 4 model turns, `tool_choice` auto, `max_tokens` 600. The reply is
  `submit_answer`'s input. A turn that ends without a tool call, a refusal, `max_tokens`, or turn 4
  without `submit_answer` is a **failure**.
- **Time:** `AnthropicApiClient` gains `createAskMessage(params, RequestOptions)` on its own
  Resilience4j instances — retry `ask` (2 attempts, 5xx/529 only, **no** content-filter retry) and
  circuit breaker `ask` — so a user cannot open the shared `anthropic` breaker against the forecast
  pipeline. Per-call timeout 20s; a 30s deadline is checked before each turn and the remaining time
  is that call's timeout. Bulkhead `ask`: 4 concurrent, 2s wait. The instances are declared in
  `application-local.yml`, `application-example.yml`, `application-prod.yml` and
  `src/test/resources/application.yml`; a test reads the limits back from the registries.
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
    argument carries `ask` / `ask-ready` for the reader of the log; nothing queries it).
  - **Today's typed spend** = `SUM(cost_micro_dollars)` over `api_call_log` rows whose
    `job_run_id` is an `ASK` run started since UK midnight (uses `idx_api_call_log_job_run`),
    memoised 30s. Precompute spend does **not** count toward the typed cap.
- **Flag:** `photocast.ask.enabled`, default false (§2.9 says what each endpoint does when off).

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
7. **Spend cap** — today's typed spend ≥ `photocast.ask.daily-spend-cap-usd` (default 2.00) →
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
`engine-ceiling-multiplier` (3), `daily-spend-cap-usd` (2.00), `ready.max-cycles-per-day` (6),
`cache.max-entries` (2000), `cache.ttl-minutes` (30), `log.retention-days` (90),
`seed-local-fixture` (false).

**Java records** (`service/ask/`):
```java
record AskQuestion(String sanitised, String normalised, String windowId, List<Long> regionIds, String view) {}
record AskUserContext(Long userId, UserRole role, boolean hasDriveTimes) {}   // null userId = user-less
record AskPick(int rank, long locationId, String locationName, String regionName, LocalDate date,
               TargetType targetType, String windowId, String why, Integer ratingAtAnswer, String verdictAtAnswer) {}
record AskEvent(String type, String label, LocalDate date, String why) {}
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
    "events":[{"type":"AURORA","label":"Aurora","date":"2026-10-10","why":"…"}],
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

**`GET /api/user/settings/ask`** → always 200:
`{"enabled":true,"used":1,"limit":3,"left":2,"typedAvailable":true}` (flag off: `enabled:false`,
zeros).

**Admin:** `POST /api/admin/ask/dry-run` `{question, regionIds, windowId?}` → the outcome plus the
tool trace (counts toward the typed spend). `POST /api/admin/ask/ready/precompute` → `{written,
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
- events 7–8 (no `window()`), passed windows at the minute boundary, travel days: excluded
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

### F1a — Client core, unmounted — M/L
**Files:** `api/askApi.js`, `hooks/useAskReady.js`, `hooks/useAskAllowance.js`,
`context/AskContext.jsx`, `utils/askModel.js`, `components/ask/AskConversation.jsx`,
`AskPickCard.jsx`, `AskEventCard.jsx`, `AskContextChips.jsx`, their CSS. Nothing is mounted in the
app. Fixtures are the literal JSON of §2.9.
**Tests:** every state — empty, busy (both lines), answer, can't answer (from the pre-filter shape
and from `kind: cant`), allowance used up, each error code; a Ready tap makes no POST; a typed
`kind: ready` reply is rendered as Ready with "no question used"; the count comes from the
response, never decremented locally; a pick with no slot is dropped; drive is the **home** map even
when the provider's origin is away; the live region is empty while hidden; nothing is written to
`swrCache` or module scope (a logout must not carry an answer to the next user).

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

### S1 — Add to Coming up — L — owner-gated (§6 Q2)
`saved_pick(user_id CASCADE, location_id, target_date, target_type, created_at)`, unique per user
and slot; `GET|POST|DELETE /api/user/settings/saved-picks` (pinned in the caching test); a "Your
plans" block at the top of Coming up, each row joined to the **current** briefing rating and gone
once its window has passed; the button in Plan this. A prompt for this phase is written only after
Q2 is answered.

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
21. "Add to Coming up" is withheld until S1 (§1 #9).
22. Selecting a pick clears the map selection rather than selecting the location (§2.7).
23. Scope follows the Map's scope segment, not the viewport; Plan sends no window chip (§2.6).
24. Drive on a card is always from home (§1 #24).
25. No Ask on the Operations tab.

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

## §6 Owner decisions (the default is binding until changed)

- **Q1 — the `/` key.** *Default:* `/` focuses Ask at ≥1024px on Plan, Coming up and Map; Plan
  search keeps ⌕ only. Alternatives: `/` stays search on Plan; or Ask gets no key in v1.
- **Q2 — "Add to Coming up".** *Default:* S1 as written, last; no button before it. Alternatives:
  drop it; or a different meaning (calendar export, a notification).
- **Q3 — the floating card.** *Default:* deferred; sheet below 1024px. Building it needs the
  top-right map controls to move and `MapCallout` to learn a right-hand obstacle.
- **Q4 — the spend cap.** *Default:* $2.00 a day, typed questions only; precompute capped
  separately at 6 cycles a day.
- **Q5 — Ready hit rate.** *Default:* measured for typed questions only (a tap makes no request).
- **Q6 — charging.** *Default:* cache hits free; `answerable:false` always refunded; the engine
  ceiling is 3 × the allowance.
- **Q7 — LITE and drive questions.** *Default:* `maxDriveMinutes` works for anyone with stored
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

1. `application-local.yml`: `photocast.ask.enabled: true`, `stub: true`,
   `seed-local-fixture: true`.
2. Backend: `./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local` (port 8083; use
   8093 if a parallel session holds it). The seeder writes the rated fixture and builds a briefing.
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

**Not examined by any reviewer:** `PinsLayer` and `MapLegendPanel` internals; the tests that pin
74px beyond two files; `o20-shell-inert-plan.md`; the real token size of production briefing and
almanac payloads (the 1p figure is an estimate until §7 measures it); whether Anthropic bills a
generation abandoned at the client timeout; whether `RunType.ASK` trips an H2 enum constraint under
local `ddl-auto: update`; how a travel day is marked on the served briefing. Nothing was built or
run.
